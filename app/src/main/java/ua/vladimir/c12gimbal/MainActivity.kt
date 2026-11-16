package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(20, 20, 20, 20)
        }

        fun addText(value: String, size: Float = 15f, bold: Boolean = false) {
            val item = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(0xFF17212B.toInt())
                setPadding(0, 5, 0, 5)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            root.addView(item)
        }

        addText("C12 Gimbal Control", 23f, true)
        addText("UART TEST 0.5.1", 17f, true)

        var result = ""
        var exitCode = "не отримано"

        try {
            val process = ProcessBuilder(
                "/system/bin/stty",
                "--help"
            ).redirectErrorStream(true).start()

            val output = process.inputStream
                .bufferedReader()
                .use { it.readText() }

            exitCode = process.waitFor().toString()

            result = if (output.isNotBlank()) {
                output.take(350).replace("\n", " ")
            } else {
                "Команда не повернула текст."
            }

        } catch (e: Exception) {
            result = "${e.javaClass.simpleName}: ${e.message}"
        }

        addText("РЕЗУЛЬТАТ ЗАПУСКУ", 17f, true)
        addText("Код завершення: $exitCode", 16f, true)
        addText(result, 13f)

        addText("UART: НЕ ЗМІНЮВАВСЯ", 16f, true)
        addText("Швидкість не змінювали.")
        addText("Дані не читали.")
        addText("Команди не передавали.")
    }
}
