package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val channelViews = ArrayList<TextView>()

    @Volatile
    private var running = false

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

        return data + byteArrayOf(
            crc.toByte(),
            (crc shr 8).toByte()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(22, 20, 22, 20)
            setBackgroundColor(0xFFF2F5F8.toInt())
        }

        fun addText(
            value: String,
            size: Float,
            bold: Boolean = false
        ): TextView {
            val view = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(0xFF17212B.toInt())
                setPadding(0, 7, 0, 7)

                if (bold) {
                    setTypeface(typeface, Typeface.BOLD)
                }
            }

            root.addView(view)
            return view
        }

        addText("C12 Gimbal Control", 24f, true)
        addText("Версія 0.8.0", 16f)
        addText("КАНАЛИ ПУЛЬТА SIYI", 19f, true)

        status = addText(
            "Підключення до UART...",
            15f,
            true
        )

        addText("CH1 — CH16", 17f, true)

        for (i in 1..16) {
            val view = addText(
                "CH$i: ---",
                17f,
                i == 6 || i == 7 || i == 12 || i == 13
            )

            channelViews.add(view)
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        startUart()
    }

    private fun startUart() {
        running = true

        Thread {
            try {
                val config = ProcessBuilder(
                    "/system/bin/stty",
                    "-F",
                    "/dev/ttyHS3",
                    "115200",
                    "raw",
                    "-echo"
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

                                    if (b >= 0) {
                                        buffer.add(b.toByte())
                                    }
                                }
                            } else {
                                Thread.sleep(10)
                            }

                            while (buffer.size >= 8) {

                                var header = -1

                                for (i in 0 until buffer.size - 1) {
                                    if (
                                        (buffer[i].toInt() and 0xFF) == 0x55 &&
                                        (buffer[i + 1].toInt() and 0xFF) == 0x66
                                    ) {
                                        header = i
                                        break
                                    }
                                }

                                if (header < 0) {
                                    val keepLast =
                                        (buffer.last().toInt() and 0xFF) == 0x55

                                    buffer.clear()

                                    if (keepLast) {
                                        buffer.add(0x55.toByte())
                                    }

                                    break
                                }

                                if (header > 0) {
                                    repeat(header) {
                                        buffer.removeAt(0)
                                    }
                                }

                                if (buffer.size < 8) break

                                val dataLength =
                                    (buffer[3].toInt() and 0xFF) or
                                    ((buffer[4].toInt() and 0xFF) shl 8)

                                if (dataLength > 512) {
                                    buffer.removeAt(0)
                                    continue
                                }

                                val totalLength = 10 + dataLength

                                if (buffer.size < totalLength) break

                                val packet = ByteArray(totalLength)

                                for (i in 0 until totalLength) {
                                    packet[i] = buffer[i]
                                }

                                val receivedCrc =
                                    (packet[totalLength - 2].toInt() and 0xFF) or
                                    ((packet[totalLength - 1].toInt() and 0xFF) shl 8)

                                val calculatedCrc =
                                    crc16(packet, totalLength - 2)

                                if (receivedCrc != calculatedCrc) {
                                    buffer.removeAt(0)
                                    continue
                                }

                                val command =
                                    packet[7].toInt() and 0xFF

                                if (command == 0x42 && dataLength >= 32) {

                                    val channels = IntArray(16)

                                    for (i in 0 until 16) {
                                        val position = 8 + i * 2

                                        channels[i] =
                                            (packet[position].toInt() and 0xFF) or
                                            ((packet[position + 1].toInt() and 0xFF) shl 8)
                                    }

                                    validPackets++

                                    val now = System.currentTimeMillis()

                                    if (now - lastUpdate >= 100) {
                                        lastUpdate = now

                                        runOnUiThread {
                                            status.text =
                                                "ЗВ'ЯЗОК Є | CRC OK\n" +
                                                "Коректних пакетів: $validPackets"

                                            for (i in 0 until 16) {
                                                channelViews[i].text =
                                                    "CH${i + 1}: ${channels[i]}"
                                            }
                                        }
                                    }
                                }

                                repeat(totalLength) {
                                    buffer.removeAt(0)
                                }
                            }

                            if (buffer.size > 2048) {
                                buffer.clear()
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    status.text =
                        "ПОМИЛКА UART:\n${e.javaClass.simpleName}\n${e.message}"
                }
            }

        }.start()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
