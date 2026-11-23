package ua.vladimir.c12gimbal

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
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
class DiagnosticsActivity : Activity(), InputManager.InputDeviceListener {
    private lateinit var output: TextView
    private lateinit var inputManager: InputManager
    private val log = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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
            setOnClickListener { log.setLength(0); append("Журнал очищено") }
        }
        root.addView(clear)

        val copy = Button(this).apply {
            text = "Скопіювати звіт"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("UniRC diagnostics", log.toString()))
                append("Звіт скопійовано в буфер обміну")
            }
        }
        root.addView(copy)

        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        inputManager.registerInputDeviceListener(this, null)
        runFullScan()
        append("\nТЕСТ ПОДІЙ: залиш цей екран відкритим і рухай CH6/CH7, натискай CH12/CH13.")
        append("Події, які Android доставить саме цьому застосунку, з'являться нижче.")
    }

    override fun onDestroy() {
        try { inputManager.unregisterInputDeviceListener(this) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun runFullScan() {
        append("\n========== СКАНУВАННЯ ${timeFormat.format(Date())} ==========")
        append("Android=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT} виробник=${Build.MANUFACTURER} модель=${Build.MODEL}")
        scanInputDevices()
        scanSystemServiceNames()
        scanInstalledPackagesAndDeclaredServices()
        append("========== КІНЕЦЬ СКАНУВАННЯ ==========")
    }

    private fun scanInputDevices() {
        append("\n[ANDROID INPUT DEVICES]")
        val ids = inputManager.inputDeviceIds
        append("Кількість пристроїв: ${ids.size}")
        if (ids.isEmpty()) append("Android не показує жодного зовнішнього/віртуального input device.")
        for (id in ids) {
            val d = inputManager.getInputDevice(id) ?: continue
            append("Device id=${d.id}; name=${d.name}; descriptor=${d.descriptor}")
            append("  vendorId=${d.vendorId}; productId=${d.productId}; sources=0x${d.sources.toString(16)}; virtual=${d.isVirtual}")
            append("  keyboardType=${d.keyboardType}; supportsSource(JOYSTICK)=${d.supportsSource(InputDevice.SOURCE_JOYSTICK)}")
            for (r in d.motionRanges) {
                append("  AXIS ${MotionEvent.axisToString(r.axis)}: min=${r.min}, max=${r.max}, flat=${r.flat}, fuzz=${r.fuzz}, source=0x${r.source.toString(16)}")
            }
        }
    }

    private fun scanSystemServiceNames() {
        append("\n[ANDROID SYSTEM SERVICES]")
    
        val services = listOf(
            "INPUT_SERVICE" to Context.INPUT_SERVICE,
            "WINDOW_SERVICE" to Context.WINDOW_SERVICE,
            "USB_SERVICE" to Context.USB_SERVICE,
            "AUDIO_SERVICE" to Context.AUDIO_SERVICE,
            "BLUETOOTH_SERVICE" to Context.BLUETOOTH_SERVICE,
            "WIFI_SERVICE" to Context.WIFI_SERVICE,
            "SENSOR_SERVICE" to Context.SENSOR_SERVICE,
            "ACTIVITY_SERVICE" to Context.ACTIVITY_SERVICE,
            "PACKAGE_SERVICE" to Context.POWER_SERVICE
        )
    
        for ((name, serviceName) in services) {
            try {
                val service = getSystemService(serviceName)
                append(
                    "$name: " +
                    if (service != null) service.javaClass.name
                    else "недоступний"
                )
            } catch (e: Exception) {
                append("$name: помилка ${e.message}")
            }
        }
    
        append(
            "Примітка: перевірені доступні Android API. " +
            "Це не повний список внутрішніх системних служб UniRC."
        )
    }

    @Suppress("DEPRECATION")
    private fun scanInstalledPackagesAndDeclaredServices() {
        append("\n[ВСТАНОВЛЕНІ ПАКЕТИ ТА ОГОЛОШЕНІ SERVICES]")
        val pm = packageManager
        val packages = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong()))
            else pm.getInstalledPackages(PackageManager.GET_SERVICES)
        } catch (e: Exception) {
            append("Помилка читання пакетів: ${e.message}")
            return
        }
        append("Видимих пакетів: ${packages.size}. Android може приховувати частину пакетів без QUERY_ALL_PACKAGES.")
        val hits = packages.filter { p ->
            val s = (p.packageName + " " + (p.applicationInfo?.loadLabel(pm)?.toString() ?: ""))
            s.contains("siyi", true) || s.contains("skydroid", true) || s.contains("unirc", true) || s.contains("sky", true) || s.contains("rc", true)
        }
        if (hits.isEmpty()) append("Пакетів із назвами SIYI/SKYDROID/UniRC/RC серед видимих не знайдено.")
        for (p in hits) {
            val label = try { p.applicationInfo?.loadLabel(pm)?.toString() ?: "" } catch (_: Exception) { "" }
            append("PACKAGE: $label | ${p.packageName}")
            val services = p.services ?: emptyArray()
            if (services.isEmpty()) append("  Оголошених services не знайдено/не видно.")
            services.forEach { s ->
                append("  SERVICE: ${s.name}; exported=${s.exported}; permission=${s.permission ?: "(none)"}; process=${s.processName}")
            }
        }
        append("Інші пакети не виводяться, щоб звіт залишався компактним.")
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_MOVE || ev.action == MotionEvent.ACTION_HOVER_MOVE) {
            val axes = StringBuilder()
            for (i in 0 until ev.pointerCount) {
                val p = ev.getPointerId(i)
                val names = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
                names.forEach { axis ->
                    val v = ev.getAxisValue(axis, i)
                    if (kotlin.math.abs(v) > 0.02f) axes.append(" ${MotionEvent.axisToString(axis)}=$v")
                }
            }
            if (axes.isNotEmpty()) append("INPUT MOTION device=${ev.device?.name} id=${ev.deviceId} source=0x${ev.source.toString(16)}:$axes")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            append("INPUT KEY ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}: device=${event.device?.name} id=${event.deviceId} keyCode=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)}) scanCode=${event.scanCode}")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onInputDeviceAdded(deviceId: Int) { append("INPUT DEVICE ADDED id=$deviceId"); scanInputDevices() }
    override fun onInputDeviceRemoved(deviceId: Int) { append("INPUT DEVICE REMOVED id=$deviceId"); scanInputDevices() }
    override fun onInputDeviceChanged(deviceId: Int) { append("INPUT DEVICE CHANGED id=$deviceId"); scanInputDevices() }

    private fun append(line: String) {
        if (!::output.isInitialized) {
            log.append(line).append('\n')
            return
        }
        log.append(line).append('\n')
        output.text = log.toString()
    }
}
