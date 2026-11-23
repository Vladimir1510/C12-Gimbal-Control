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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Controller screen and diagnostics for UniRC 7 -> C12.
 * The controller runs in GimbalService; this Activity only shows its state and the diagnostics log.
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

    // The controller itself lives in GimbalService so it keeps running behind other apps.
    private var gimbal: GimbalService? = null

    private val gimbalListener = object : GimbalService.Listener {
        override fun onLog(line: String) = logEvent(line)
        override fun onChannels(channels: Map<String, Int>) = updateLive(channels)
        override fun onStateChanged() = refreshStatus()
        override fun onCommand(what: String) {
            if (::lastCommand.isInitialized) lastCommand.text = "Остання команда: $what  ·  ${timeFormat.format(Date())}"
        }
    }

    private val gimbalConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            gimbal = (binder as GimbalService.LocalBinder).service.also { it.listener = gimbalListener }
            logEvent("Сервіс керування підключено")
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gimbal = null
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        inputManager.registerInputDeviceListener(this, null)
        runFullScan()
        // The service subscribes to RC channels right away; gimbal commands start only with the toggle.
        val serviceIntent = Intent(this, GimbalService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, gimbalConnection, Context.BIND_AUTO_CREATE)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        handler.post(statusTask)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the overlay permission screen: show the floating button if it was just allowed.
        if (android.provider.Settings.canDrawOverlays(this)) gimbal?.showOverlay()
        refreshStatus()
    }

    // ---------- UI ----------

    private lateinit var rcStatus: TextView
    private lateinit var camStatus: TextView
    private lateinit var toggleButton: Button
    private lateinit var centerButton: Button
    private lateinit var downButton: Button
    private lateinit var pitchBar: android.widget.ProgressBar
    private lateinit var pitchLabel: TextView
    private lateinit var zoomBar: android.widget.ProgressBar
    private lateinit var zoomLabel: TextView
    private lateinit var lastCommand: TextView
    private lateinit var hint: TextView
    private lateinit var diagnostics: LinearLayout
    private lateinit var overlayButton: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun rounded(color: Int, radius: Int = 14) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_CARD)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        children.forEach { addView(it) }
    }

    private fun bigButton(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(COLOR_TEXT)
        isAllCaps = false
        background = rounded(color)
        setOnClickListener { onClick() }
    }

    private fun bar() = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = WHEEL_SPAN * 2
        progress = WHEEL_SPAN
        progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
    }

    private fun spaced(view: View, top: Int = 10, weight: Float = 0f, width: Int = -1, height: Int = -2) =
        view.apply {
            layoutParams = LinearLayout.LayoutParams(if (weight > 0f) 0 else width, height, weight).apply { topMargin = dp(top) }
        }

    private fun buildUi(): View {
        rcStatus = label("", 15f, COLOR_TEXT, bold = true).apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        camStatus = label("", 15f, COLOR_TEXT, bold = true).apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val statusRow = LinearLayout(this).apply {
            addView(spaced(rcStatus, 0, 1f))
            addView(spaced(camStatus, 0, 1f).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10) })
        }

        toggleButton = bigButton("", COLOR_OK) {
            gimbal?.let { it.setControlActive(!it.controlActive) }
        }
        centerButton = bigButton("⊙  Центр", COLOR_BUTTON) { gimbal?.manualCommand(C12Protocol.center(), "центр (екран)") }
        downButton = bigButton("↓  Вниз 90°", COLOR_BUTTON) {
            gimbal?.manualCommand(C12Protocol.pitchAbsolute(-90, GimbalService.DOWN90_SPEED), "вниз 90° (екран)")
        }
        overlayButton = bigButton("Дозволити кнопку поверх усіх додатків", COLOR_BUTTON) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
        }.apply { textSize = 15f }
        val quickRow = LinearLayout(this).apply {
            addView(spaced(centerButton, 0, 1f, height = dp(64)))
            addView(spaced(downButton, 0, 1f, height = dp(64)).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10) })
        }

        pitchBar = bar()
        pitchLabel = label("Нахил: —", 16f, COLOR_TEXT)
        zoomBar = bar()
        zoomLabel = label("Зум: —", 16f, COLOR_TEXT)
        lastCommand = label("Остання команда: —", 13f, COLOR_MUTED)
        val live = card(pitchLabel, spaced(pitchBar, 4), spaced(zoomLabel, 12), spaced(zoomBar, 4), spaced(lastCommand, 12))

        val legend = card(
            label("Керування з пульта", 15f, COLOR_TEXT, bold = true),
            spaced(label("Колесо CH6 — нахил камери\nКолесо CH11 — зум\nКнопка CH12 — центр\nКнопка CH13 — вниз 90°", 14f, COLOR_MUTED), 6),
            spaced(label("Плаваюча кнопка C12", 15f, COLOR_TEXT, bold = true), 14),
            spaced(label("Дотик — увімкнути/вимкнути керування\nПеретягування — перемістити\nУтримання — відкрити застосунок\nЗакрити повністю — «Закрити» у сповіщенні", 14f, COLOR_MUTED), 6),
        )

        hint = label("", 14f, COLOR_WARN).apply { visibility = View.GONE }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("C12 Gimbal", 26f, COLOR_TEXT, bold = true))
            addView(spaced(statusRow, 12))
            addView(spaced(hint, 8))
            addView(spaced(toggleButton, 14, height = dp(76)))
            addView(spaced(quickRow, 12))
            addView(spaced(live, 12))
            addView(spaced(overlayButton, 12, height = dp(56)))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(legend)
        }
        val main = LinearLayout(this).apply {
            addView(spaced(left, 0, 1f))
            addView(spaced(right, 0, 1f).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(16) })
        }

        diagnostics = buildDiagnostics().apply { visibility = View.GONE }
        val diagToggle = label("▸  Діагностика", 15f, COLOR_MUTED).apply {
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener {
                val show = diagnostics.visibility != View.VISIBLE
                diagnostics.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "▾  Діагностика" else "▸  Діагностика"
                if (show) scheduleRender()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            addView(main)
            addView(diagToggle)
            addView(diagnostics)
        }
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
            addView(content)
        }.also { refreshStatus() }
    }

    private fun buildDiagnostics(): LinearLayout {
        fun small(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(COLOR_TEXT)
            background = rounded(COLOR_BUTTON, 10)
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            addView(spaced(small("Оновити сканування") { runFullScan() }, 0, 1f))
            addView(spaced(small("Очистити журнал") {
                events.clear()
                lastAxisValues.clear()
                logEvent("Журнал очищено")
            }, 0, 1f).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8) })
            addView(spaced(small("Скопіювати звіт") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("UniRC diagnostics", buildReport()))
                logEvent("Звіт скопійовано в буфер обміну")
            }, 0, 1f).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8) })
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(COLOR_MUTED)
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            background = rounded(COLOR_CARD, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(output)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(spaced(scroll, 8, height = dp(360)))
        }
    }

    private fun refreshStatus() {
        if (!::toggleButton.isInitialized) return
        val service = gimbal
        val rcOk = service != null && service.rcConnected && service.channelsReceived
        rcStatus.text = if (rcOk) "●  Пульт: підключено" else "●  Пульт: очікування…"
        rcStatus.background = rounded(if (rcOk) COLOR_OK_DIM else COLOR_WARN_DIM, 20)
        rcStatus.setTextColor(if (rcOk) COLOR_OK_TEXT else COLOR_WARN)

        val camNet = cameraNetworkUp()
        camStatus.text = if (camNet) "●  Мережа камери: є" else "●  Мережа камери: немає"
        camStatus.background = rounded(if (camNet) COLOR_OK_DIM else COLOR_WARN_DIM, 20)
        camStatus.setTextColor(if (camNet) COLOR_OK_TEXT else COLOR_WARN)

        val hintText = when {
            !camNet -> "Немає мережі камери (usb0). Перевірте, що дрон увімкнено, і від'єднайте USB-кабель пульта від комп'ютера."
            !rcOk -> "Немає даних з пульта. Переконайтеся, що застосунок UniGCS встановлено."
            else -> ""
        }
        hint.text = hintText
        hint.visibility = if (hintText.isEmpty()) View.GONE else View.VISIBLE

        overlayButton.visibility = if (android.provider.Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE

        val active = service?.controlActive == true
        toggleButton.text = if (active) "■  Вимкнути керування" else "▶  Увімкнути керування"
        toggleButton.background = rounded(if (active) COLOR_STOP else COLOR_OK)
        listOf(centerButton, downButton).forEach {
            it.isEnabled = active
            it.alpha = if (active) 1f else 0.4f
        }
    }

    private fun cameraNetworkUp(): Boolean = try {
        java.net.NetworkInterface.getByName(CAMERA_INTERFACE)?.isUp == true
    } catch (_: Exception) {
        false
    }

    private val statusTask = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    private fun updateLive(ch: Map<String, Int>) {
        if (!::pitchBar.isInitialized) return
        ch[CH_PITCH]?.let { v ->
            pitchBar.progress = (v - WHEEL_CENTER + WHEEL_SPAN).coerceIn(0, WHEEL_SPAN * 2)
            val speed = GimbalService.wheelToSpeed(v, GimbalService.MAX_PITCH_SPEED)
            pitchLabel.text = "Нахил: " + when {
                speed > 0 -> "вгору, швидкість $speed"
                speed < 0 -> "вниз, швидкість ${-speed}"
                else -> "стоїть"
            }
        }
        ch[CH_ZOOM]?.let { v ->
            zoomBar.progress = (v - WHEEL_CENTER + WHEEL_SPAN).coerceIn(0, WHEEL_SPAN * 2)
            zoomLabel.text = "Зум: " + when (GimbalService.wheelToSpeed(v, 1)) {
                1 -> "збільшення"
                -1 -> "зменшення"
                else -> "без змін"
            }
        }
    }

    override fun onDestroy() {
        try { inputManager.unregisterInputDeviceListener(this) } catch (_: Exception) {}
        gimbal?.listener = null
        try { unbindService(gimbalConnection) } catch (_: Exception) {}
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

    private fun report(line: String) {
        scanLog.append(line).append('\n')
    }

    private fun logEvent(line: String) {
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
        const val WHEEL_CENTER = GimbalService.WHEEL_CENTER
        const val WHEEL_SPAN = GimbalService.WHEEL_SPAN
        const val CH_PITCH = GimbalService.CH_PITCH
        const val CH_ZOOM = GimbalService.CH_ZOOM
        const val STATUS_INTERVAL_MS = 1000L
        const val CAMERA_INTERFACE = "usb0"

        val COLOR_BG = android.graphics.Color.parseColor("#12161C")
        val COLOR_CARD = android.graphics.Color.parseColor("#1E242D")
        val COLOR_BUTTON = android.graphics.Color.parseColor("#2C3440")
        val COLOR_TEXT = android.graphics.Color.parseColor("#F2F4F7")
        val COLOR_MUTED = android.graphics.Color.parseColor("#9AA4B2")
        val COLOR_ACCENT = android.graphics.Color.parseColor("#4DA3FF")
        val COLOR_OK = android.graphics.Color.parseColor("#1F8A4C")
        val COLOR_OK_TEXT = android.graphics.Color.parseColor("#6EE7A0")
        val COLOR_OK_DIM = android.graphics.Color.parseColor("#1B3A2A")
        val COLOR_WARN = android.graphics.Color.parseColor("#F5B841")
        val COLOR_WARN_DIM = android.graphics.Color.parseColor("#3D3220")
        val COLOR_STOP = android.graphics.Color.parseColor("#B3261E")
    }
}
