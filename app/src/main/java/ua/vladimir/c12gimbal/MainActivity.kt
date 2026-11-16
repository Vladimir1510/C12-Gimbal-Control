package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(28, 36, 28, 24)
        }

        fun addText(value: String, size: Float = 16f, bold: Boolean = false) {
            val item = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(0xFF17212B.toInt())
                setPadding(0, 12, 0, 12)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            root.addView(item)
        }

        addText("C12 Gimbal Control", 26f, true)
        addText("UART TEST 0.5.0", 18f, true)
        addText("ПЕРЕВІРКА STTY", 19f, true)

        val paths = listOf(
            "/system/bin/stty",
            "/vendor/bin/stty",
            "/bin/stty"
        )

        for (path in paths) {
            val file = File(path)
            addText("$path")
            addText("Існує: ${file.exists()}")
            addText("Можна запускати: ${file.canExecute()}")
        }

        addText("ТЕСТ ЗАПУСКУ", 19f, true)

        try {
            val process = ProcessBuilder(
                "/system/bin/stty",
                "--help"
            ).redirectErrorStream(true).start()

            val output = process.inputStream
                .bufferedReader()
                .use { it.readText() }

            val exitCode = process.waitFor()

            addText("Код завершення: $exitCode")
            addText(output.take(1200))

        } catch (e: Exception) {
            addText("ПОМИЛКА ЗАПУСКУ", 19f, true)
            addText("${e.javaClass.simpleName}: ${e.message}")
        }

        addText(
            "UART не відкривається. " +
            "Жодних команд пульту або камері не передано."
        )

        setContentView(root)
    }
}
