package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(22, 24, 22, 20)
            setBackgroundColor(0xFFF2F5F8.toInt())
        }

        fun addText(value: String, size: Float, bold: Boolean = false): TextView {
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
        addText("UART TEST 0.6.0", 17f, true)

        val port = File("/dev/ttyHS3")

        addText("ПРИСТРІЙ: /dev/ttyHS3", 16f, true)
        addText("Існує: ${port.exists()}", 16f)
        addText("Читання: ${port.canRead()}", 16f)
        addText("Запис: ${port.canWrite()}", 16f)

        val result = addText(
            "Перевірка параметрів UART...\nЗачекай кілька секунд.",
            15f
        )

        setContentView(root)

        Thread {
            val message = try {
                val process = ProcessBuilder(
                    "/system/bin/stty",
                    "-F",
                    "/dev/ttyHS3",
                    "-a"
                ).redirectErrorStream(true).start()

                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "ТАЙМ-АУТ: команда не завершилася за 3 секунди."
                } else {
                    val output = process.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    "Код завершення: ${process.exitValue()}\n" +
                    output.take(500).replace("\n", " ")
                }
            } catch (e: Exception) {
                "ПОМИЛКА: ${e.javaClass.simpleName}: ${e.message}"
            }

            runOnUiThread {
                result.text = message
            }
        }.start()
    }
}
