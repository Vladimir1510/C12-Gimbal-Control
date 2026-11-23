package ua.vladimir.c12gimbal

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }
        val title = TextView(this).apply {
            text = "UniRC 7 — діагностика (БЕЗ КОМАНД)\nUART не відкривається. UDP не використовується."
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
    }
}
