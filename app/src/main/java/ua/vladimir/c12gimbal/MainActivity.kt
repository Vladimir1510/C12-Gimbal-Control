
package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var cameraIp: EditText
    private lateinit var speedLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var logView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val shuttingDown = AtomicBoolean(false)

    private val timeFormat =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val log = StringBuilder()
    private val maxLogChars = 30000

    private val cameraPort = 5000

    // 20 = 2.0 degrees/second according to the protocol.
    private var movementSpeed = 20

    private var heldCommand: String? = null
    private var heldSpeed = 0

    private val repeatIntervalMs = 200L

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val command = heldCommand ?: return

            sendSpeedCommand(command, heldSpeed)

            if (heldCommand != null) {
                mainHandler.postDelayed(this, repeatIntervalMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildInterface()

        appendLog("Застосунок запущено.")
        appendLog("UDP: порт камери $cameraPort")
        appendLog("UART не використовується.")
        appendLog("Камера: ${cameraIp.text}")

        updateStatus("Готовий до керування")
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
        }

        val title = TextView(this).apply {
            text = "SKYDROID C12 — UDP CONTROL"
            textSize = 21f
            setPadding(0, 0, 0, 12)
        }
        root.addView(title)

        val description = TextView(this).apply {
            text = "Ручне керування підвісом через UDP. " +
                "Команди надсилаються без використання UART."
            textSize = 13f
        }
        root.addView(description)

        addSpace(root, 12)

        root.addView(TextView(this).apply {
            text = "IP-адреса камери:"
            textSize = 15f
        })

        cameraIp = EditText(this).apply {
            setSingleLine(true)
            setText("192.168.144.108")
            textSize = 17f
        }
        root.addView(cameraIp)

        root.addView(TextView(this).apply {
            text = "UDP порт: $cameraPort"
            textSize = 14f
        })

        addSpace(root, 10)

        statusLabel = TextView(this).apply {
            textSize = 15f
            text = "Статус: очікування"
        }
        root.addView(statusLabel)

        addSpace(root, 12)

        root.addView(TextView(this).apply {
            text = "Швидкість руху:"
            textSize = 16f
        })

        speedLabel = TextView(this).apply {
            textSize = 15f
        }
        root.addView(speedLabel)

        val speedBar = SeekBar(this).apply {
            max = 60
            progress = movementSpeed
        }

        speedBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    movementSpeed = progress.coerceAtLeast(1)
                    updateSpeedLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        root.addView(speedBar)
        updateSpeedLabel()

        addSpace(root, 12)

        root.addView(TextView(this).apply {
            text = "КЕРУВАННЯ ПІДВІСОМ"
            textSize = 17f
        })

        addSpace(root, 8)

        val upRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val upButton = createButton("▲ ВГОРУ")
        upRow.addView(
            upButton,
            LinearLayout.LayoutParams(-1, 64)
        )
        root.addView(upRow)

        val middleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val leftButton = createButton("◀ ЛІВОРУЧ")
        val rightButton = createButton("ПРАВОРУЧ ▶")

        middleRow.addView(
            leftButton,
            LinearLayout.LayoutParams(0, 64, 1f)
        )

        middleRow.addView(
            rightButton,
            LinearLayout.LayoutParams(0, 64, 1f)
        )

        root.addView(middleRow)

        val downRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val downButton = createButton("▼ ВНИЗ")
        downRow.addView(
            downButton,
            LinearLayout.LayoutParams(-1, 64)
        )
        root.addView(downRow)

        bindHoldButton(upButton, "GSP", movementDirection = 1)
        bindHoldButton(downButton, "GSP", movementDirection = -1)
        bindHoldButton(rightButton, "GSY", movementDirection = 1)
        bindHoldButton(leftButton, "GSY", movementDirection = -1)

        addSpace(root, 10)

        val centerButton = createButton("ЦЕНТРУВАТИ ПІДВІС")
        centerButton.setOnClickListener {
            stopMovement()
            sendCommand("G", "PTZ", "05")
            updateStatus("Надіслано команду центрування")
        }
        root.addView(centerButton)

        addSpace(root, 8)

        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val zoomIn = createButton("ZOOM +")
        val zoomOut = createButton("ZOOM −")

        zoomIn.setOnClickListener {
            sendCommand("D", "DZM", "0A")
            updateStatus("Надіслано Zoom +")
        }

        zoomOut.setOnClickListener {
            sendCommand("D", "DZM", "0B")
            updateStatus("Надіслано Zoom −")
        }

        zoomRow.addView(
            zoomIn,
            LinearLayout.LayoutParams(0, 58, 1f)
        )

        zoomRow.addView(
            zoomOut,
            LinearLayout.LayoutParams(0, 58, 1f)
        )

        root.addView(zoomRow)

        addSpace(root, 8)

        val stopButton = createButton("STOP — ЗУПИНИТИ ВСЕ")
        stopButton.setOnClickListener {
            stopMovement()
            sendAllStopCommands()
            updateStatus("Надіслано загальну зупинку")
        }
        root.addView(stopButton)

        addSpace(root, 14)

        root.addView(TextView(this).apply {
            text = "ЖУРНАЛ UDP"
            textSize = 17f
        })

        val clearButton = createButton("Очистити журнал")
        clearButton.setOnClickListener {
            log.setLength(0)
            updateLog()
            appendLog("Журнал очищено")
        }
        root.addView(clearButton)

        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }

    private fun createButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
        }
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int
    ) {
        parent.addView(
            View(this),
            LinearLayout.LayoutParams(1, height)
        )
    }

    private fun updateSpeedLabel() {
        if (!::speedLabel.isInitialized) return

        speedLabel.text =
            "Значення: $movementSpeed  |  " +
            "приблизно ${movementSpeed / 10.0}°/с"
    }

    private fun bindHoldButton(
        button: Button,
        command: String,
        movementDirection: Int
    ) {
        button.setOnTouchListener { _, event ->

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startMovement(
                        command,
                        movementSpeed * movementDirection
                    )
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    stopMovement()
                    true
                }

                else -> true
            }
        }
    }

    private fun startMovement(
        command: String,
        speed: Int
    ) {
        stopMovement()

        heldCommand = command
        heldSpeed = speed

        sendSpeedCommand(command, speed)

        mainHandler.removeCallbacks(repeatRunnable)
        mainHandler.postDelayed(
            repeatRunnable,
            repeatIntervalMs
        )

        updateStatus(
            "Рух: $command, швидкість $speed"
        )
    }

    private fun stopMovement() {
        mainHandler.removeCallbacks(repeatRunnable)

        val previousCommand = heldCommand

        heldCommand = null
        heldSpeed = 0

        if (previousCommand != null) {
            sendSpeedCommand(previousCommand, 0)
            updateStatus("Зупинка осі $previousCommand")
        }
    }

    private fun sendSpeedCommand(
        command: String,
        speed: Int
    ) {
        val safeSpeed = speed.coerceIn(-127, 127)

        val byteValue =
            if (safeSpeed < 0) {
                256 + safeSpeed
            } else {
                safeSpeed
            }

        val payload =
            String.format(Locale.US, "%02X", byteValue)

        sendCommand("G", command, payload)
    }

    private fun sendAllStopCommands() {
        sendSpeedCommand("GSP", 0)
        sendSpeedCommand("GSY", 0)
        sendCommand("G", "PTZ", "00")
    }

    /**
     * Builds a fixed-length #TP command.
     *
     * Example:
     * #TPUG2wPTZ056F
     *
     * Checksum = ASCII byte sum modulo 256.
     */
    private fun buildFrame(
        destination: String,
        command: String,
        payload: String
    ): String {
        require(destination.length == 1)
        require(command.length == 3)
        require(payload.length == 2)

        val base =
            "#TPU${destination}2w${command}${payload}"

        val checksum = base
            .toByteArray(StandardCharsets.US_ASCII)
            .sumOf { it.toInt() and 0xFF } and 0xFF

        return base + String.format(
            Locale.US,
            "%02X",
            checksum
        )
    }

    private fun sendCommand(
        destination: String,
        command: String,
        payload: String
    ) {
        if (shuttingDown.get()) return

        val ip = cameraIp.text.toString().trim()

        val frame = try {
            buildFrame(destination, command, payload)
        } catch (e: Exception) {
            appendLog("ПОМИЛКА формування кадру: ${e.message}")
            return
        }

        networkExecutor.execute {
            try {
                val address = InetAddress.getByName(ip)
                val data = frame.toByteArray(
                    StandardCharsets.US_ASCII
                )

                DatagramSocket().use { socket ->
                    val packet = DatagramPacket(
                        data,
                        data.size,
                        address,
                        cameraPort
                    )

                    socket.send(packet)
                }

                appendLog("UDP TX → $ip:$cameraPort | $frame")

            } catch (e: Exception) {
                appendLog(
                    "UDP ПОМИЛКА: ${e.javaClass.simpleName}: " +
                        "${e.message}"
                )

                updateStatus(
                    "Помилка UDP: ${e.message}"
                )
            }
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            log.append(timeFormat.format(Date()))
                .append("  ")
                .append(message)
                .append('\n')

            if (log.length > maxLogChars) {
                log.delete(
                    0,
                    log.length - maxLogChars
                )
            }

            updateLog()
        }
    }

    private fun updateLog() {
        if (!::logView.isInitialized) return

        logView.text = log.toString()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            if (::statusLabel.isInitialized) {
                statusLabel.text = "Статус: $message"
            }
        }
    }

    override fun onPause() {
        stopMovement()
        sendAllStopCommands()
        super.onPause()
    }

    override fun onDestroy() {
        stopMovement()
        sendAllStopCommands()

        shuttingDown.set(true)
        networkExecutor.shutdown()

        mainHandler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }
}
