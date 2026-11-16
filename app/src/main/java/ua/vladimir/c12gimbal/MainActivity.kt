package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(22, 24, 22, 20)
            setBackgroundColor(0xFFF2F5F8.toInt())
        }

        fun addText(
            value: String,
            size: Float,
            bold: Boolean = false
        ): TextView {
            val item = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(0xFF17212B.toInt())
                setPadding(0, 8, 0, 8)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            root.addView(item)
            return item
        }

        addText("C12 Gimbal Control", 24f, true)
        addText("UART TEST 0.7.0", 18f, true)
        addText("ПЕРЕВІРКА ПЕРЕДАВАННЯ ДАНИХ", 16f, true)

        val port = File("/dev/ttyHS3")

        addText("Пристрій: /dev/ttyHS3", 15f)
        addText("Існує: ${port.exists()}", 15f)
        addText("Читання: ${port.canRead()}", 15f)
        addText("Запис: ${port.canWrite()}", 15f)

        result = addText(
            "Запуск перевірки UART...\nЗачекай приблизно 10 секунд.",
            16f
        )

        setContentView(root)

        Thread {
            val message = try {
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

                val configOutput = config.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                if (config.exitValue() != 0) {
                    throw Exception("Помилка stty: $configOutput")
                }

                val command = byteArrayOf(
                    0x55, 0x66, 0x01, 0x01,
                    0x00, 0x00, 0x00, 0x42,
                    0x06, 0x31, 0x80.toByte()
                )

                val received = mutableListOf<Byte>()
                var sent = 0

                FileInputStream(port).use { input ->
                    FileOutputStream(port).use { output ->

                        repeat(3) {
                            output.write(command)
                            output.flush()
                            sent++
                            Thread.sleep(50)
                        }

                        val start = System.currentTimeMillis()

                        while (
                            System.currentTimeMillis() - start < 8000
                        ) {
                            val available = input.available()

                            if (available > 0) {
                                repeat(minOf(available, 256)) {
                                    val value = input.read()
                                    if (value >= 0) {
                                        received.add(value.toByte())
                                    }
                                }
                            } else {
                                Thread.sleep(20)
                            }
                        }
                    }
                }

                val hex = received.joinToString(" ") {
                    "%02X".format(it.toInt() and 0xFF)
                }

                if (received.isEmpty()) {
                    "НАЛАШТУВАННЯ UART: OK\n" +
                    "Команд надіслано: $sent\n" +
                    "Отримано байтів: 0\n\n" +
                    "Відповідь від пристрою не надійшла."
                } else {
                    "НАЛАШТУВАННЯ UART: OK\n" +
                    "Команд надіслано: $sent\n" +
                    "Отримано байтів: ${received.size}\n\n" +
                    "ОТРИМАНІ ДАНІ:\n$hex"
                }

            } catch (e: Exception) {
                "ПОМИЛКА:\n${e.javaClass.simpleName}\n" +
                "${e.message ?: "Невідома помилка"}"
            }

            runOnUiThread {
                result.text = message
            }

        }.start()
    }
}
