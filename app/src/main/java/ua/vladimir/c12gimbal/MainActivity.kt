package ua.vladimir.c12gimbal

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only diagnostics for UniRC 7.
 * IMPORTANT: This Activity does not open UART, create UDP sockets, or send commands.
 */
class MainActivity : Activity(), InputManager.InputDeviceListener {
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var inputManager: InputManager
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Scan report is rebuilt on every scan; events are a bounded ring buffer so stick movement can't grow the log forever.
    private val scanLog = StringBuilder()
    private val events = ArrayDeque<String>()
    private var renderPending = false

    // Last logged value per (deviceId, axis), so only changes are logged.
    private val lastAxisValues = HashMap<Long, Float>()

    // UniGCS RemoteControlService probe: binder of the service, our callback and per-channel last values.
    private var rcService: IBinder? = null
    private var rcBound = false
    private val rcChannelValues = HashMap<String, Int>()
    private val rcCallbackCounts = HashMap<Int, Int>()

    // Gimbal controller: RC channels (via the probe above) -> C12 UDP commands.
    private var c12: C12Client? = null
    private var lastPitchSpeed = 0
    private var zoomWarned = false
    private val buttonPressed = HashMap<String, Boolean>()

    /**
     * Raw implementation of biz.siyi.pilot.rcuservice.callback.IRemoteCallback.
     * Only listens: logs every incoming transaction code, decodes codes 7/8 as List<ChannelValue>.
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
                handler.post { logEvent("RC CALLBACK code=$code: помилка розбору ${e.message}") }
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
            logEvent("RC SERVICE підключено: ${name.flattenToShortString()}; descriptor=${service.interfaceDescriptor}")
            rcTransact(RC_TX_REGISTER_CALLBACK, "реєстрація колбека", withCallback = true)
            rcTransact(RC_TX_START_CHANNELS, "запит потоку каналів (tx 40)", withCallback = false)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            rcService = null
            logEvent("RC SERVICE відключився (процес UniGCS зупинено?)")
            if (c12 != null && lastPitchSpeed != 0) {
                lastPitchSpeed = 0
                sendC12(C12Protocol.stop(), "стоп (сервіс каналів відключився)")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }
        val title = TextView(this).apply {
            text = "UniRC 7 → C12 контролер\nUART не відкривається. Команди C12 — лише коли контролер увімкнено."
            textSize = 18f
        }
        root.addView(title)

        val refresh = Button(this).apply {
            text = "Оновити список пристроїв і служб"
            setOnClickListener { runFullScan() }
        }
        root.addView(refresh)

        val clear = Button(this).apply {
            text = "Очистити журнал подій"
            setOnClickListener {
                events.clear()
                lastAxisValues.clear()
                logEvent("Журнал очищено")
            }
        }
        root.addView(clear)

        val copy = Button(this).apply {
            text = "Скопіювати звіт"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("UniRC diagnostics", buildReport()))
                logEvent("Звіт скопійовано в буфер обміну")
            }
        }
        root.addView(copy)

        val rcProbe = Button(this).apply {
            text = "Проба UniGCS: підписатися на канали"
            setOnClickListener { if (rcBound) stopRcProbe() else startRcProbe() }
        }
        root.addView(rcProbe)

        val controller = Button(this).apply {
            text = "Контролер C12: увімкнути"
            setOnClickListener {
                if (c12 == null) startController() else stopController()
                text = if (c12 == null) "Контролер C12: увімкнути" else "Контролер C12: ВИМКНУТИ"
            }
        }
        root.addView(controller)

        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        inputManager.registerInputDeviceListener(this, null)
        runFullScan()
        logEvent("ТЕСТ ПОДІЙ: залиш цей екран відкритим і рухай CH6/CH7, натискай CH12/CH13.")
        logEvent("Події, які Android доставить саме цьому застосунку, з'являться нижче.")
    }

    override fun onDestroy() {
        try { inputManager.unregisterInputDeviceListener(this) } catch (_: Exception) {}
        stopController()
        stopRcProbe()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun runFullScan() {
        scanLog.setLength(0)
        report("========== СКАНУВАННЯ ${timeFormat.format(Date())} ==========")
        report("Android=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT} виробник=${Build.MANUFACTURER} модель=${Build.MODEL}")
        scanInputDevices()
        scanSystemServiceNames()
        scanInstalledPackagesAndDeclaredServices()
        report("========== КІНЕЦЬ СКАНУВАННЯ ==========")
        scheduleRender()
    }

    private fun scanInputDevices() {
        report("\n[ANDROID INPUT DEVICES]")
        val ids = inputManager.inputDeviceIds
        report("Кількість пристроїв: ${ids.size}")
        if (ids.isEmpty()) report("Android не показує жодного зовнішнього/віртуального input device.")
        for (id in ids) {
            val d = inputManager.getInputDevice(id) ?: continue
            report("Device id=${d.id}; name=${d.name}; descriptor=${d.descriptor}")
            report("  vendorId=${d.vendorId}; productId=${d.productId}; sources=0x${d.sources.toString(16)}; virtual=${d.isVirtual}")
            report("  keyboardType=${d.keyboardType}; supportsSource(JOYSTICK)=${d.supportsSource(InputDevice.SOURCE_JOYSTICK)}")
            for (r in d.motionRanges) {
                report("  AXIS ${MotionEvent.axisToString(r.axis)}: min=${r.min}, max=${r.max}, flat=${r.flat}, fuzz=${r.fuzz}, source=0x${r.source.toString(16)}")
            }
        }
    }

    private fun scanSystemServiceNames() {
        report("\n[ANDROID SYSTEM SERVICES]")

        val services = listOf(
            "INPUT_SERVICE" to Context.INPUT_SERVICE,
            "WINDOW_SERVICE" to Context.WINDOW_SERVICE,
            "USB_SERVICE" to Context.USB_SERVICE,
            "AUDIO_SERVICE" to Context.AUDIO_SERVICE,
            "BLUETOOTH_SERVICE" to Context.BLUETOOTH_SERVICE,
            "WIFI_SERVICE" to Context.WIFI_SERVICE,
            "SENSOR_SERVICE" to Context.SENSOR_SERVICE,
            "ACTIVITY_SERVICE" to Context.ACTIVITY_SERVICE,
            "POWER_SERVICE" to Context.POWER_SERVICE
        )

        for ((name, serviceName) in services) {
            try {
                val service = getSystemService(serviceName)
                report(
                    "$name: " +
                    if (service != null) service.javaClass.name
                    else "недоступний"
                )
            } catch (e: Exception) {
                report("$name: помилка ${e.message}")
            }
        }

        report(
            "Примітка: перевірені доступні Android API. " +
            "Це не повний список внутрішніх системних служб UniRC."
        )
    }

    @Suppress("DEPRECATION")
    private fun scanInstalledPackagesAndDeclaredServices() {
        report("\n[ВСТАНОВЛЕНІ ПАКЕТИ ТА ОГОЛОШЕНІ SERVICES]")
        val pm = packageManager
        val packages = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong()))
            else pm.getInstalledPackages(PackageManager.GET_SERVICES)
        } catch (e: Exception) {
            report("Помилка читання пакетів: ${e.message}")
            return
        }
        report("Видимих пакетів: ${packages.size} (QUERY_ALL_PACKAGES запитано в маніфесті).")

        val keywords = listOf("siyi", "skydroid", "unirc")
        val hits = packages.filter { p ->
            val s = p.packageName + " " + labelOf(pm, p)
            keywords.any { s.contains(it, true) }
        }
        report("\n-- Пакети з назвами SIYI/SKYDROID/UniRC --")
        if (hits.isEmpty()) report("Не знайдено.")
        hits.forEach { reportPackage(pm, it) }

        val userApps = packages.filter { p ->
            val flags = p.applicationInfo?.flags ?: 0
            p !in hits && p.packageName != packageName && flags and ApplicationInfo.FLAG_SYSTEM == 0
        }
        report("\n-- Інші не системні пакети --")
        if (userApps.isEmpty()) report("Не знайдено.")
        userApps.forEach { reportPackage(pm, it) }
    }

    private fun labelOf(pm: PackageManager, p: PackageInfo): String =
        try { p.applicationInfo?.loadLabel(pm)?.toString() ?: "" } catch (_: Exception) { "" }

    private fun reportPackage(pm: PackageManager, p: PackageInfo) {
        report("PACKAGE: ${labelOf(pm, p)} | ${p.packageName}")
        val services = p.services ?: emptyArray()
        if (services.isEmpty()) report("  Оголошених services не знайдено/не видно.")
        services.forEach { s ->
            report("  SERVICE: ${s.name}; exported=${s.exported}; permission=${s.permission ?: "(none)"}; process=${s.processName}")
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_MOVE || ev.action == MotionEvent.ACTION_HOVER_MOVE) {
            // Use the axes the device actually declares; fall back to every axis if the device is unknown.
            val ranges = ev.device?.motionRanges?.filter { it.source and ev.source == it.source }
            val axes = ranges?.map { it.axis }?.distinct()?.takeIf { it.isNotEmpty() }
                ?: (0..MotionEvent.AXIS_GENERIC_16).toList()
            val changed = StringBuilder()
            for (axis in axes) {
                val range = ranges?.firstOrNull { it.axis == axis }
                val eps = if (range != null && range.range > 0f) range.range * 0.01f else 0.01f
                val v = ev.getAxisValue(axis)
                val key = (ev.deviceId.toLong() shl 32) or axis.toLong()
                val last = lastAxisValues[key]
                if (last == null || kotlin.math.abs(v - last) > eps) {
                    lastAxisValues[key] = v
                    changed.append(" ${MotionEvent.axisToString(axis)}=$v")
                }
            }
            if (changed.isNotEmpty()) logEvent("INPUT MOTION device=${ev.device?.name} id=${ev.deviceId} source=0x${ev.source.toString(16)}:$changed")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if ((isDown && event.repeatCount == 0) || event.action == KeyEvent.ACTION_UP) {
            logEvent("INPUT KEY ${if (isDown) "DOWN" else "UP"}: device=${event.device?.name} id=${event.deviceId} keyCode=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)}) scanCode=${event.scanCode}")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onInputDeviceAdded(deviceId: Int) { logEvent("INPUT DEVICE ADDED id=$deviceId"); runFullScan() }
    override fun onInputDeviceRemoved(deviceId: Int) { logEvent("INPUT DEVICE REMOVED id=$deviceId"); runFullScan() }
    override fun onInputDeviceChanged(deviceId: Int) { logEvent("INPUT DEVICE CHANGED id=$deviceId"); runFullScan() }

    private fun startRcProbe() {
        rcChannelValues.clear()
        rcCallbackCounts.clear()
        val intent = Intent().setComponent(ComponentName(RC_PACKAGE, RC_SERVICE_CLASS))
        rcBound = try {
            bindService(intent, rcConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            logEvent("RC PROBE: bindService помилка ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        logEvent(if (rcBound) "RC PROBE: bindService OK, чекаю підключення… Рухай стіки/колеса." else "RC PROBE: bindService повернув false (сервіс недоступний)")
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
        logEvent("RC PROBE зупинено")
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
            logEvent("RC PROBE: $what OK")
        } catch (e: Exception) {
            logEvent("RC PROBE: $what помилка ${e.javaClass.simpleName}: ${e.message}")
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
            if (count == 1) logEvent("RC CALLBACK code=$code (перший раз)")
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
        if (changed.isNotEmpty()) logEvent("RC CHANNELS code=$code n=${channels.size}:$changed")
        if (code == RC_CB_CHANNELS_B && c12 != null) controlGimbal(channels.toMap())
    }

    private fun startController() {
        c12 = C12Client(onError = { msg -> handler.post { logEvent(msg) } })
        lastPitchSpeed = 0
        buttonPressed.clear()
        zoomWarned = false
        if (!rcBound) startRcProbe()
        handler.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS)
        logEvent("CONTROLLER увімкнено: $CH_PITCH=нахил, $CH_CENTER=центр, $CH_DOWN90=вниз 90°, $CH_ZOOM=зум (не підтримується)")
    }

    private fun stopController() {
        val client = c12 ?: return
        handler.removeCallbacks(watchdogTask)
        client.send(C12Protocol.stop())
        client.close()
        c12 = null
        logEvent("CONTROLLER вимкнено, надіслано STOP")
    }

    private fun sendC12(command: String, what: String) {
        c12?.send(command)
        logEvent("C12 → $what: $command")
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
        // Zoom command for C12 is not known yet (needs camera manual/SDK), so only warn once.
        val zoom = ch[CH_ZOOM]
        if (zoom != null && wheelToSpeed(zoom, 1) != 0 && !zoomWarned) {
            zoomWarned = true
            logEvent("ZOOM: $CH_ZOOM рухається, але команда зуму C12 ще не відома — нічого не надіслано")
        }
    }

    /** Maps a 1050..1950 wheel to -max..max with a dead zone around 1500. */
    private fun wheelToSpeed(value: Int, max: Int): Int {
        val d = value - WHEEL_CENTER
        val mag = kotlin.math.abs(d) - WHEEL_DEADZONE
        if (mag <= 0) return 0
        val scaled = (mag * max + WHEEL_SPAN - WHEEL_DEADZONE - 1) / (WHEEL_SPAN - WHEEL_DEADZONE)
        return Integer.signum(d) * scaled.coerceIn(1, max)
    }

    private fun risingEdge(ch: Map<String, Int>, channel: String): Boolean {
        val value = ch[channel] ?: return false
        val pressed = value > WHEEL_CENTER
        val was = buttonPressed.put(channel, pressed) ?: pressed
        return pressed && !was
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
            logEvent("RC STATS (code:кількість) ${stats.ifEmpty { "колбеків ще не було" }}")
            handler.postDelayed(this, RC_STATS_INTERVAL_MS)
        }
    }

    private fun report(line: String) {
        scanLog.append(line).append('\n')
    }

    private fun logEvent(line: String) {
        Log.i(LOG_TAG, line)
        events.addLast("${timeFormat.format(Date())} $line")
        while (events.size > MAX_EVENT_LINES) events.removeFirst()
        scheduleRender()
    }

    private fun buildReport(): String =
        scanLog.toString() + "\n[ПОДІЇ]\n" + events.joinToString("\n")

    // Coalesces UI updates: joystick events arrive at 50–200 Hz, the TextView is redrawn at most every RENDER_INTERVAL_MS.
    private fun scheduleRender() {
        if (renderPending || !::output.isInitialized) return
        renderPending = true
        handler.postDelayed({
            renderPending = false
            output.text = buildReport()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }, RENDER_INTERVAL_MS)
    }

    private companion object {
        const val MAX_EVENT_LINES = 500
        const val RENDER_INTERVAL_MS = 100L
        const val LOG_TAG = "UniRcDiag"

        // Taken from decompiled UniGCS (biz.siyi.remotecontrol): IRemoteControl / IRemoteCallback binder contract.
        const val RC_PACKAGE = "biz.siyi.remotecontrol"
        const val RC_SERVICE_CLASS = "biz.siyi.pilot.rcuservice.RemoteControlService"
        const val RC_SERVICE_DESCRIPTOR = "biz.siyi.pilot.rcuservice.IRemoteControl"
        const val RC_CALLBACK_DESCRIPTOR = "biz.siyi.pilot.rcuservice.callback.IRemoteCallback"
        const val RC_TX_REGISTER_CALLBACK = 1
        const val RC_TX_UNREGISTER_CALLBACK = 2
        const val RC_TX_START_CHANNELS = 40
        const val RC_TX_STOP_CHANNELS = 41
        const val RC_CB_CHANNELS_A = 7
        const val RC_CB_CHANNELS_B = 8
        const val RC_CHANNEL_EPS = 3
        const val RC_STATS_INTERVAL_MS = 5000L

        // Controller mapping (values seen on hardware: 1050..1950, centre 1500).
        const val CH_PITCH = "CHANNEL_6"
        const val CH_ZOOM = "CHANNEL_11"
        const val CH_CENTER = "CHANNEL_12"
        const val CH_DOWN90 = "CHANNEL_13"
        const val WHEEL_CENTER = 1500
        const val WHEEL_SPAN = 450
        const val WHEEL_DEADZONE = 45
        const val MAX_PITCH_SPEED = 25
        const val DOWN90_SPEED = 20
        const val WATCHDOG_INTERVAL_MS = 200L
    }
}
