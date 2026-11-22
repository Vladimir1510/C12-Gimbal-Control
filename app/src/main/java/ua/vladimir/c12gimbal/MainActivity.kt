package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var uartStatus: TextView
    private lateinit var networkStatus: TextView
    private lateinit var udpStatus: TextView
    private lateinit var rootLayout: LinearLayout

    private val channelViews = ArrayList<TextView>()
    private val importantViews = HashMap<Int, TextView>()

    @Volatile
    private var running = false

    @Volatile
    private var latestChannels = IntArray(16)

    @Volatile
    private var lastRcPacketMs = 0L

    private val cameraIp = "192.168.144.108"
    private val cameraPort = 5000

    private val pageColor = Color.rgb(12, 19, 32)
    private val cardColor = Color.rgb(24, 34, 51)
    private val primaryColor = Color.rgb(239, 245, 252)
    private val secondaryColor = Color.rgb(151, 169, 191)
    private val accentColor = Color.rgb(46, 213, 180)
    private val warningColor = Color.rgb(255, 190, 92)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(
        color: Int,
        radiusDp: Int = 18,
        strokeColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun makeText(
        value: String,
        size: Float,
        color: Int = primaryColor,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun addCard(title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = roundedBackground(cardColor)
        }

        val heading = makeText(title, 14f, accentColor, true)
        heading.letterSpacing = 0.06f
        card.addView(heading)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        }
        rootLayout.addView(card, params)
        return card
    }

    private fun addStatusLine(parent: LinearLayout, text: String): TextView {
        val view = makeText(text, 14f, secondaryColor)
        view.setPadding(0, dp(9), 0, dp(3))
        parent.addView(view)
        return view
    }

    private fun addChannelValue(parent: LinearLayout, text: String): TextView {
        val view = makeText(text, 17f, primaryColor, true)
        view.setPadding(0, dp(12), 0, dp(12))
        parent.addView(view)
        return view
    }

    private fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    private fun makeRequest(): ByteArray {
        val data = byteArrayOf(
            0x55, 0x66, 0x01, 0x01,
            0x00, 0x00, 0x00, 0x42, 0x06
        )
        val crc = crc16(data, data.size)
        return data + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }

    private fun makeCameraFrame(
        address: Char,
        command: String,
        data: String
    ): ByteArray {
        val body = "#TPU${address}${data.length.toString(16).uppercase()}w${command}${data}"
        val checksum = body.toByteArray(Charsets.US_ASCII)
            .sumOf { it.toInt() and 0xFF } and 0xFF
        return (body + "%02X".format(checksum)).toByteArray(Charsets.US_ASCII)
    }

    private fun sendCameraCommand(
        socket: DatagramSocket,
        address: Char,
        command: String,
        data: String
    ) {
        val bytes = makeCameraFrame(address, command, data)
        val packet = DatagramPacket(
            bytes,
            bytes.size,
            InetAddress.getByName(cameraIp),
            cameraPort
        )
        socket.send(packet)
    }

    private fun sendPitchSpeed(socket: DatagramSocket, speed: Int) {
        val limited = speed.coerceIn(-99, 99)
        val encoded = if (limited < 0) 256 + limited else limited
        sendCameraCommand(socket, 'G', "GSP", "%02X".format(encoded))
    }

    private fun sendCenter(socket: DatagramSocket) {
        sendCameraCommand(socket, 'G', "PTZ", "05")
    }

    private fun sendDown(socket: DatagramSocket) {
        sendCameraCommand(socket, 'G', "PTZ", "02")
    }

    private fun sendZoomIn(socket: DatagramSocket) {
        sendCameraCommand(socket, 'D', "DZM", "0A")
    }

    private fun sendZoomOut(socket: DatagramSocket) {
        sendCameraCommand(socket, 'D', "DZM", "0B")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
            setBackgroundColor(pageColor)
        }

        val scroll = ScrollView(this).apply {
            fillViewport = true
            setBackgroundColor(pageColor)
            addView(rootLayout)
        }
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(5), dp(3), dp(8))
        }
        val title = makeText("C12 Gimbal Control", 27f, primaryColor, true)
        val subtitle = makeText("Керування камерою та підвісом", 14f, secondaryColor)
        subtitle.setPadding(0, dp(7), 0, 0)
        header.addView(title)
        header.addView(subtitle)
        rootLayout.addView(header)

        val statusCard = addCard("СТАН СИСТЕМИ")
        uartStatus = addStatusLine(statusCard, "●  SIYI UART: підключення...")
        networkStatus = addStatusLine(statusCard, "●  Мережа C12: перевірка...")
        udpStatus = addStatusLine(statusCard, "●  UDP C12: очікування...")

        val controlsCard = addCard("ОСНОВНІ КАНАЛИ")
        val controls = listOf(
            6 to "CH6   Нахил камери / PITCH",
            7 to "CH7   Збільшення / ZOOM",
            12 to "CH12  Центрування камери",
            13 to "CH13  Нахил вниз на 90°"
        )
        for ((channel, label) in controls) {
            val value = addChannelValue(controlsCard, "$label     ---")
            importantViews[channel] = value
            if (channel != 13) {
                val divider = View(this).apply {
                    setBackgroundColor(0x334B6078)
                }
                controlsCard.addView(divider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ))
            }
        }

        val allChannelsCard = addCard("УСІ КАНАЛИ ПУЛЬТА")
        for (row in 0 until 8) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (column in 0..1) {
                val index = row * 2 + column
                val channel = index + 1
                val view = makeText("CH$channel: ---", 14f, primaryColor, channel == 6 || channel == 7 || channel == 12 || channel == 13)
                view.setPadding(0, dp(9), 0, dp(9))
                rowLayout.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                channelViews.add(view)
            }
            allChannelsCard.addView(rowLayout)
        }

        val footer = makeText("Версія 1.1.0  •  UART 115200  •  C12 UDP 5000", 12f, secondaryColor)
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, dp(18), 0, dp(5))
        rootLayout.addView(footer)

        running = true
        startNetworkCheck()
        startUart()
        startCameraControl()
    }

    private fun startNetworkCheck() {
        Thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cameraIp, 554), 2500)
                }
                runOnUiThread {
                    networkStatus.text = "●  Мережа C12: TCP 554 доступний ($cameraIp)"
                    networkStatus.setTextColor(accentColor)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    networkStatus.text = "●  Мережа C12: немає TCP-з'єднання (${e.javaClass.simpleName})"
                    networkStatus.setTextColor(warningColor)
                }
            }
        }.start()
    }

    private fun startUart() {
        Thread {
            try {
                val config = ProcessBuilder(
                    "/system/bin/stty", "-F", "/dev/ttyHS3",
                    "115200", "raw", "-echo"
                ).redirectErrorStream(true).start()

                if (!config.waitFor(3, TimeUnit.SECONDS)) {
                    config.destroyForcibly()
                    throw Exception("Тайм-аут налаштування UART")
                }
                if (config.exitValue() != 0) {
                    throw Exception("Помилка налаштування UART")
                }

                val port = File("/dev/ttyHS3")
                val request = makeRequest()

                FileInputStream(port).use { input ->
                    FileOutputStream(port).use { output ->
                        repeat(3) {
                            output.write(request)
                            output.flush()
                            Thread.sleep(50)
                        }

                        val buffer = ArrayList<Byte>()
                        var validPackets = 0
                        var lastUpdate = 0L

                        while (running) {
                            val available = input.available()
                            if (available > 0) {
                                repeat(minOf(available, 256)) {
                                    val b = input.read()
                                    if (b >= 0) buffer.add(b.toByte())
                                }
                            } else {
                                Thread.sleep(5)
                            }

                            while (buffer.size >= 8) {
                                var header = -1
                                for (i in 0 until buffer.size - 1) {
                                    if ((buffer[i].toInt() and 0xFF) == 0x55 &&
                                        (buffer[i + 1].toInt() and 0xFF) == 0x66) {
                                        header = i
                                        break
                                    }
                                }

                                if (header < 0) {
                                    val keepLast = (buffer.last().toInt() and 0xFF) == 0x55
                                    buffer.clear()
                                    if (keepLast) buffer.add(0x55.toByte())
                                    break
                                }
                                if (header > 0) repeat(header) { buffer.removeAt(0) }
                                if (buffer.size < 8) break

                                val dataLength = (buffer[3].toInt() and 0xFF) or
                                    ((buffer[4].toInt() and 0xFF) shl 8)
                                if (dataLength > 512) {
                                    buffer.removeAt(0)
                                    continue
                                }

                                val totalLength = 10 + dataLength
                                if (buffer.size < totalLength) break
                                val packet = ByteArray(totalLength)
                                for (i in 0 until totalLength) packet[i] = buffer[i]

                                val receivedCrc = (packet[totalLength - 2].toInt() and 0xFF) or
                                    ((packet[totalLength - 1].toInt() and 0xFF) shl 8)
                                if (receivedCrc != crc16(packet, totalLength - 2)) {
                                    buffer.removeAt(0)
                                    continue
                                }

                                val command = packet[7].toInt() and 0xFF
                                if (command == 0x42 && dataLength >= 32) {
                                    val channels = IntArray(16)
                                    for (i in 0 until 16) {
                                        val position = 8 + i * 2
                                        channels[i] = (packet[position].toInt() and 0xFF) or
                                            ((packet[position + 1].toInt() and 0xFF) shl 8)
                                    }

                                    latestChannels = channels
                                    lastRcPacketMs = System.currentTimeMillis()
                                    validPackets++
                                    val now = System.currentTimeMillis()

                                    if (now - lastUpdate >= 100) {
                                        lastUpdate = now
                                        runOnUiThread {
                                            if (!running) return@runOnUiThread
                                            uartStatus.text = "●  SIYI UART: ЗВ'ЯЗОК Є  •  CRC OK  •  $validPackets пакетів"
                                            uartStatus.setTextColor(accentColor)

                                            for (i in 0 until 16) {
                                                channelViews[i].text = "CH${i + 1}: ${channels[i]}"
                                            }
                                            importantViews[6]?.text = "CH6   Нахил камери / PITCH     ${channels[5]}"
                                            importantViews[7]?.text = "CH7   Збільшення / ZOOM          ${channels[6]}"
                                            importantViews[12]?.text = "CH12  Центрування камери          ${channels[11]}"
                                            importantViews[13]?.text = "CH13  Нахил вниз на 90°           ${channels[12]}"
                                        }
                                    }
                                }
                                repeat(totalLength) { buffer.removeAt(0) }
                            }
                            if (buffer.size > 2048) buffer.clear()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    runOnUiThread {
                        uartStatus.text = "●  ПОМИЛКА UART: ${e.javaClass.simpleName} — ${e.message}"
                        uartStatus.setTextColor(warningColor)
                    }
                }
            }
        }.start()
    }

    private fun startCameraControl() {
        Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(cameraPort)
                socket.soTimeout = 100

                runOnUiThread {
                    udpStatus.text = "●  UDP C12: локальний порт 5000 відкритий; камера $cameraIp:5000"
                    udpStatus.setTextColor(accentColor)
                }

                var lastPitchSpeed = 0
                var lastZoomTime = 0L
                var centerPressed = false
                var downPressed = false
                var lastStaleStop = false

                while (running) {
                    val now = System.currentTimeMillis()
                    val channels = latestChannels
                    val rcFresh = now - lastRcPacketMs < 500 && lastRcPacketMs > 0

                    if (!rcFresh) {
                        if (lastPitchSpeed != 0) {
                            sendPitchSpeed(socket, 0)
                            lastPitchSpeed = 0
                        }
                        if (!lastStaleStop) {
                            runOnUiThread {
                                udpStatus.text = "●  UDP C12: очікування сигналу пульта — рух зупинено"
                                udpStatus.setTextColor(warningColor)
                            }
                            lastStaleStop = true
                        }
                        Thread.sleep(100)
                        continue
                    }
                    lastStaleStop = false

                    // CH6 PITCH: швидкість збільшена вдвічі.
                    // Було ±20, стало ±40; за шкалою GSP це приблизно до 4°/с.
                    val pitchDifference = channels[5] - 1500
                    val pitchSpeed = if (abs(pitchDifference) <= 30) {
                        0
                    } else {
                        (pitchDifference * 40 / 450).coerceIn(-40, 40)
                    }

                    if (pitchSpeed != 0 || lastPitchSpeed != 0) {
                        sendPitchSpeed(socket, pitchSpeed)
                        lastPitchSpeed = pitchSpeed
                    }

                    // CH7 ZOOM: інтервал між кроками зменшений удвічі: 350 -> 175 мс.
                    val zoomPwm = channels[6]
                    if (now - lastZoomTime >= 175) {
                        when {
                            zoomPwm > 1600 -> {
                                sendZoomIn(socket)
                                lastZoomTime = now
                            }
                            zoomPwm < 1400 -> {
                                sendZoomOut(socket)
                                lastZoomTime = now
                            }
                        }
                    }

                    val centerNow = channels[11] > 1700
                    if (centerNow && !centerPressed) {
                        sendPitchSpeed(socket, 0)
                        lastPitchSpeed = 0
                        sendCenter(socket)
                        runOnUiThread {
                            udpStatus.text = "●  UDP C12: команда центрування CH12 надіслана"
                            udpStatus.setTextColor(accentColor)
                        }
                    }
                    centerPressed = centerNow

                    val downNow = channels[12] > 1700
                    if (downNow && !downPressed) {
                        sendPitchSpeed(socket, 0)
                        lastPitchSpeed = 0
                        sendDown(socket)
                        runOnUiThread {
                            udpStatus.text = "●  UDP C12: команда нахилу вниз CH13 надіслана"
                            udpStatus.setTextColor(accentColor)
                        }
                    }
                    downPressed = downNow
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                if (running) {
                    runOnUiThread {
                        udpStatus.text = "●  ПОМИЛКА UDP: ${e.javaClass.simpleName} — ${e.message}"
                        udpStatus.setTextColor(warningColor)
                    }
                }
            } finally {
                try {
                    socket?.let {
                        if (!it.isClosed) {
                            sendPitchSpeed(it, 0)
                            it.close()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
