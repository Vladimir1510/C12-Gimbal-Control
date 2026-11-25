package ua.vladimir.c12gimbal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Foreground service that owns the gimbal controller, so it keeps working while other apps are on screen:
 * reads RC channels from UniGCS (Binder), sends C12 commands (UDP) and shows a floating on/off button.
 */
class GimbalService : Service() {

    interface Listener {
        fun onLog(line: String)
        fun onChannels(channels: Map<String, Int>)
        fun onStateChanged()
        fun onCommand(what: String)
    }

    inner class LocalBinder : Binder() {
        val service: GimbalService get() = this@GimbalService
    }

    private val handler = Handler(Looper.getMainLooper())
    private val localBinder = LocalBinder()
    var listener: Listener? = null

    // UniGCS RemoteControlService: binder of the service, our callback and per-channel last values.
    private var rcService: IBinder? = null
    private var rcBound = false
    private val rcChannelValues = HashMap<String, Int>()
    private val rcCallbackCounts = HashMap<Int, Int>()
    var channelsReceived = false
        private set
    val rcConnected: Boolean get() = rcService != null

    // Gimbal controller: RC channels -> C12 UDP commands.
    private var c12: C12Client? = null
    val controlActive: Boolean get() = c12 != null
    private var lastPitchSpeed = 0
    private var zoomDirection = 0
    private var paletteIndex = 0
    private val buttonPressed = HashMap<String, Boolean>()

    private var overlay: TextView? = null

    // Video-link chain ground unit -> air unit -> camera; null until the first check finishes.
    private val linkMonitor = LinkMonitor { state -> handler.post { onLinkState(state) } }
    var link: LinkMonitor.State? = null
        private set

    /**
     * Raw implementation of biz.siyi.pilot.rcuservice.callback.IRemoteCallback.
     * Logs every incoming transaction code, decodes codes 7/8 as List<ChannelValue>.
     * Runs on a binder thread, so results are posted to the main thread.
     */
    private val rcCallback = object : Binder() {
        init { attachInterface(null, RC_CALLBACK_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val decoded = try {
                data.enforceInterface(RC_CALLBACK_DESCRIPTOR)
                if (code == RC_CB_CHANNELS_A || code == RC_CB_CHANNELS_B) readChannelValues(data) else null
            } catch (e: Exception) {
                handler.post { log("RC CALLBACK code=$code: помилка розбору ${e.message}") }
                null
            }
            reply?.writeNoException()
            handler.post { onRcCallback(code, decoded) }
            return true
        }
    }

    private val rcConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            rcService = service
            log("RC SERVICE підключено: ${name.flattenToShortString()}; descriptor=${service.interfaceDescriptor}")
            rcTransact(RC_TX_REGISTER_CALLBACK, "реєстрація колбека", withCallback = true)
            rcTransact(RC_TX_START_CHANNELS, "запит потоку каналів (tx 40)", withCallback = false)
            stateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            rcService = null
            channelsReceived = false
            log("RC SERVICE відключився (процес UniGCS зупинено?)")
            if (c12 != null && lastPitchSpeed != 0) {
                lastPitchSpeed = 0
                sendC12(C12Protocol.stop(), "стоп (сервіс каналів відключився)")
            }
            stateChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NOTIF_CHANNEL, "C12 Gimbal", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, buildNotification())
        startRcProbe()
        showOverlay()
        linkMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> setControlActive(!controlActive)
            ACTION_EXIT -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_OVERLAY -> showOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = localBinder

    override fun onDestroy() {
        stopController()
        stopRcProbe()
        linkMonitor.stop()
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- public API for the activity ----------

    fun setControlActive(active: Boolean) {
        if (active) startController() else stopController()
        stateChanged()
    }

    fun manualCommand(command: String, what: String) {
        if (c12 == null) return
        lastPitchSpeed = 0
        sendC12(command, what)
    }

    /** Switch the thermal palette to the next one in the cycle (CH14 button or the on-screen button). */
    fun cycleFilter() {
        if (c12 == null) return
        val (code, name) = C12Protocol.PALETTES[paletteIndex]
        paletteIndex = (paletteIndex + 1) % C12Protocol.PALETTES.size
        sendC12(C12Protocol.palette(code), "фільтр: $name")
    }

    fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        val size = (OVERLAY_SIZE_DP * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * resources.displayMetrics.density).toInt()
            y = (120 * resources.displayMetrics.density).toInt()
        }
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        attachDragAndTap(view, params, wm)
        try {
            wm.addView(view, params)
            overlay = view
            updateOverlay()
        } catch (e: Exception) {
            log("OVERLAY: не вдалося показати кнопку ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // Drag moves the button; a short tap toggles control; a long press opens the app.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(view: TextView, params: WindowManager.LayoutParams, wm: WindowManager) {
        val slop = 10 * resources.displayMetrics.density
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        val openApp = Runnable {
            dragging = true
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    originX = params.x; originY = params.y
                    dragging = false
                    handler.postDelayed(openApp, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(openApp)
                    }
                    if (dragging) {
                        params.x = originX + dx.toInt()
                        params.y = originY + dy.toInt()
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(openApp)
                    if (!dragging) setControlActive(!controlActive)
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(openApp)
            }
            true
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        try { getSystemService(WindowManager::class.java).removeView(view) } catch (_: Exception) {}
        overlay = null
    }

    private fun updateOverlay() {
        val view = overlay ?: return
        val ready = rcConnected && channelsReceived
        view.text = if (controlActive) "■\nC12" else "▶\nC12"
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (controlActive) COLOR_OK else if (ready) COLOR_STOP else COLOR_IDLE)
            // Yellow ring: the camera does not answer, so commands go nowhere.
            val cameraLost = link?.let { it.camera == null } == true
            setStroke(((if (cameraLost) 4 else 2) * resources.displayMetrics.density).toInt(), if (cameraLost) COLOR_LINK_WARN else Color.WHITE)
        }
        view.alpha = 0.9f
    }

    private fun onLinkState(state: LinkMonitor.State) {
        val old = link
        link = state
        fun up(v: Int?) = v != null
        applyBroadcastMode()
        if (old == null || up(old.ground) != up(state.ground) || up(old.air) != up(state.air) || up(old.camera) != up(state.camera)) {
            fun mark(v: Int?) = v?.let { "OK ${it} мс" } ?: "немає"
            log("ЗВ'ЯЗОК: наземний ${mark(state.ground)}, ейр-юніт ${mark(state.air)}, камера ${mark(state.camera)}")
            stateChanged()
        } else {
            listener?.onStateChanged()
        }
    }

    /** True while commands go to the broadcast address because the camera does not answer unicast. */
    val broadcastMode: Boolean get() = link?.let { it.air != null && it.camera == null } == true

    private fun applyBroadcastMode() {
        val client = c12 ?: return
        val mode = broadcastMode
        if (client.broadcast != mode) {
            client.broadcast = mode
            log(if (mode) "C12: камера не відповідає на адресні пакети — команди йдуть широкомовно (${C12Client.BROADCAST_IP})" else "C12: камера знову на зв'язку — команди йдуть напряму")
        }
    }

    private fun stateChanged() {
        updateOverlay()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        listener?.onStateChanged()
    }

    private fun buildNotification(): Notification {
        fun pending(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, GimbalService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("C12 Gimbal")
            .setContentText((if (controlActive) "Керування увімкнено" else "Керування вимкнено") + linkSummary())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (controlActive) "Вимкнути" else "Увімкнути", pending(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Закрити", pending(ACTION_EXIT, 2)).build())
            .build()
    }

    private fun linkSummary(): String {
        val state = link ?: return ""
        return when {
            state.camera != null -> " · камера на зв'язку"
            state.air != null -> " · камера НЕ відповідає, команди широкомовно"
            else -> " · немає зв'язку з дроном"
        }
    }

    // ---------- RC channels (UniGCS) ----------

    private fun startRcProbe() {
        rcChannelValues.clear()
        rcCallbackCounts.clear()
        val intent = Intent().setComponent(ComponentName(RC_PACKAGE, RC_SERVICE_CLASS))
        rcBound = try {
            bindService(intent, rcConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            log("RC PROBE: bindService помилка ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        log(if (rcBound) "RC PROBE: bindService OK, чекаю підключення…" else "RC PROBE: bindService повернув false (сервіс недоступний)")
        if (rcBound) handler.postDelayed(rcStatsTask, RC_STATS_INTERVAL_MS)
    }

    private fun stopRcProbe() {
        if (!rcBound) return
        handler.removeCallbacks(rcStatsTask)
        rcTransact(RC_TX_STOP_CHANNELS, "зупинка потоку каналів (tx 41)", withCallback = false)
        rcTransact(RC_TX_UNREGISTER_CALLBACK, "зняття колбека", withCallback = true)
        try { unbindService(rcConnection) } catch (_: Exception) {}
        rcBound = false
        rcService = null
        log("RC PROBE зупинено")
    }

    // Transactions 1/2 of IRemoteControl take only our callback binder (register/unregister, nothing reaches the RC).
    // 40/41 take no arguments: the service tells the RC MCU to start/stop reporting channels (module 16, cmd 1, byte 1/0).
    private fun rcTransact(code: Int, what: String, withCallback: Boolean) {
        val service = rcService ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RC_SERVICE_DESCRIPTOR)
            if (withCallback) data.writeStrongBinder(rcCallback)
            service.transact(code, data, reply, 0)
            reply.readException()
            log("RC PROBE: $what OK")
        } catch (e: Exception) {
            log("RC PROBE: $what помилка ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // Parcel layout of List<biz.siyi.protocol.bu.rcu.ChannelValue>: size, then per item non-null flag, enum name, value.
    private fun readChannelValues(data: Parcel): List<Pair<String, Int>> {
        val size = data.readInt()
        val result = ArrayList<Pair<String, Int>>()
        for (i in 0 until size) {
            if (data.readInt() == 0) continue
            val channel = data.readString() ?: "?"
            result.add(channel to data.readInt())
        }
        return result
    }

    private fun onRcCallback(code: Int, channels: List<Pair<String, Int>>?) {
        val count = (rcCallbackCounts[code] ?: 0) + 1
        rcCallbackCounts[code] = count
        if (channels == null) {
            if (count == 1) log("RC CALLBACK code=$code (перший раз)")
            return
        }
        val changed = StringBuilder()
        for ((channel, value) in channels) {
            val key = "$code/$channel"
            val last = rcChannelValues[key]
            if (last == null || kotlin.math.abs(value - last) > RC_CHANNEL_EPS) {
                rcChannelValues[key] = value
                changed.append(" ${channel.removePrefix("CHANNEL_").let { "CH$it" }}=$value")
            }
        }
        if (changed.isNotEmpty()) log("RC CHANNELS code=$code n=${channels.size}:$changed")
        if (code == RC_CB_CHANNELS_B) {
            val map = channels.toMap()
            if (!channelsReceived) {
                channelsReceived = true
                stateChanged()
            }
            listener?.onChannels(map)
            if (c12 != null) controlGimbal(map)
        }
    }

    // ---------- controller ----------

    private fun startController() {
        if (c12 != null) return
        c12 = C12Client(onError = { msg -> handler.post { log(msg) } })
        lastPitchSpeed = 0
        buttonPressed.clear()
        zoomDirection = 0
        if (!rcBound) startRcProbe()
        applyBroadcastMode()
        handler.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS)
        log("CONTROLLER увімкнено: $CH_PITCH=нахил, $CH_CENTER=центр, $CH_DOWN90=вниз 90°, $CH_ZOOM=зум, $CH_FILTER=фільтр")
    }

    private fun stopController() {
        val client = c12 ?: return
        handler.removeCallbacks(watchdogTask)
        handler.removeCallbacks(zoomTask)
        zoomDirection = 0
        lastPitchSpeed = 0
        client.send(C12Protocol.stop())
        client.close()
        c12 = null
        log("CONTROLLER вимкнено, надіслано STOP")
    }

    private fun sendC12(command: String, what: String) {
        c12?.send(command)
        log("C12 → $what: $command")
        listener?.onCommand(what)
    }

    private fun controlGimbal(ch: Map<String, Int>) {
        ch[CH_PITCH]?.let { value ->
            val speed = wheelToSpeed(value, MAX_PITCH_SPEED)
            if (speed != lastPitchSpeed) {
                lastPitchSpeed = speed
                sendC12(C12Protocol.pitchSpeed(speed), if (speed == 0) "стоп" else "нахил $speed")
            }
        }
        if (risingEdge(ch, CH_CENTER)) {
            lastPitchSpeed = 0
            sendC12(C12Protocol.center(), "центр")
        }
        if (risingEdge(ch, CH_DOWN90)) {
            lastPitchSpeed = 0
            sendC12(C12Protocol.pitchAbsolute(-90, DOWN90_SPEED), "вниз 90°")
        }
        if (risingEdge(ch, CH_FILTER)) cycleFilter()
        ch[CH_ZOOM]?.let { value ->
            val direction = wheelToSpeed(value, 1)
            if (direction != zoomDirection) {
                zoomDirection = direction
                log("ZOOM ${when (direction) { 1 -> "збільшення"; -1 -> "зменшення"; else -> "стоп" }}")
                handler.removeCallbacks(zoomTask)
                if (direction != 0) handler.post(zoomTask)
            }
        }
    }

    private fun risingEdge(ch: Map<String, Int>, channel: String): Boolean {
        val value = ch[channel] ?: return false
        val pressed = value > WHEEL_CENTER
        val was = buttonPressed.put(channel, pressed) ?: pressed
        return pressed && !was
    }

    // Zoom is step-based: one step every ZOOM_STEP_MS while the wheel is off-centre.
    private val zoomTask = object : Runnable {
        override fun run() {
            val client = c12 ?: return
            if (zoomDirection == 0 || rcService == null) return
            client.send(if (zoomDirection > 0) C12Protocol.zoomIn() else C12Protocol.zoomOut())
            handler.postDelayed(this, ZOOM_STEP_MS)
        }
    }

    // The service sends channel frames only on change, so a held wheel produces no frames:
    // repeat the current speed while moving, and stop if the channel source is gone.
    private val watchdogTask = object : Runnable {
        override fun run() {
            if (lastPitchSpeed != 0) {
                if (rcService == null) {
                    lastPitchSpeed = 0
                    sendC12(C12Protocol.stop(), "стоп (сервіс каналів недоступний)")
                } else {
                    c12?.send(C12Protocol.pitchSpeed(lastPitchSpeed))
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val rcStatsTask = object : Runnable {
        override fun run() {
            val stats = rcCallbackCounts.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${it.value}" }
            log("RC STATS (code:кількість) ${stats.ifEmpty { "колбеків ще не було" }}")
            handler.postDelayed(this, RC_STATS_INTERVAL_MS)
        }
    }

    private fun log(line: String) {
        Log.i(LOG_TAG, line)
        listener?.onLog(line)
    }

    companion object {
        const val ACTION_TOGGLE = "ua.vladimir.c12gimbal.TOGGLE"
        const val ACTION_EXIT = "ua.vladimir.c12gimbal.EXIT"
        const val ACTION_SHOW_OVERLAY = "ua.vladimir.c12gimbal.SHOW_OVERLAY"
        const val LOG_TAG = "UniRcDiag"

        private const val NOTIF_CHANNEL = "c12-gimbal"
        private const val NOTIF_ID = 12
        private const val OVERLAY_SIZE_DP = 64
        private const val LONG_PRESS_MS = 700L

        // Taken from decompiled UniGCS (biz.siyi.remotecontrol): IRemoteControl / IRemoteCallback binder contract.
        private const val RC_PACKAGE = "biz.siyi.remotecontrol"
        private const val RC_SERVICE_CLASS = "biz.siyi.pilot.rcuservice.RemoteControlService"
        private const val RC_SERVICE_DESCRIPTOR = "biz.siyi.pilot.rcuservice.IRemoteControl"
        private const val RC_CALLBACK_DESCRIPTOR = "biz.siyi.pilot.rcuservice.callback.IRemoteCallback"
        private const val RC_TX_REGISTER_CALLBACK = 1
        private const val RC_TX_UNREGISTER_CALLBACK = 2
        private const val RC_TX_START_CHANNELS = 40
        private const val RC_TX_STOP_CHANNELS = 41
        private const val RC_CB_CHANNELS_A = 7
        private const val RC_CB_CHANNELS_B = 8
        private const val RC_CHANNEL_EPS = 3
        private const val RC_STATS_INTERVAL_MS = 5000L

        // Controller mapping (values seen on hardware: 1050..1950, centre 1500).
        const val CH_PITCH = "CHANNEL_6"
        const val CH_ZOOM = "CHANNEL_7"
        const val CH_CENTER = "CHANNEL_12"
        const val CH_DOWN90 = "CHANNEL_13"
        const val CH_FILTER = "CHANNEL_14"
        const val WHEEL_CENTER = 1500
        const val WHEEL_SPAN = 450
        const val WHEEL_DEADZONE = 45
        const val MAX_PITCH_SPEED = 50
        const val DOWN90_SPEED = 30
        private const val WATCHDOG_INTERVAL_MS = 200L
        private const val ZOOM_STEP_MS = 90L

        private val COLOR_OK = Color.parseColor("#1F8A4C")
        private val COLOR_STOP = Color.parseColor("#B3261E")
        private val COLOR_IDLE = Color.parseColor("#5A6473")
        private val COLOR_LINK_WARN = Color.parseColor("#FFC107")

        /** Maps a 1050..1950 wheel to -max..max with a dead zone around 1500. */
        fun wheelToSpeed(value: Int, max: Int): Int {
            val d = value - WHEEL_CENTER
            val mag = kotlin.math.abs(d) - WHEEL_DEADZONE
            if (mag <= 0) return 0
            val scaled = (mag * max + WHEEL_SPAN - WHEEL_DEADZONE - 1) / (WHEEL_SPAN - WHEEL_DEADZONE)
            return Integer.signum(d) * scaled.coerceIn(1, max)
        }
    }
}
