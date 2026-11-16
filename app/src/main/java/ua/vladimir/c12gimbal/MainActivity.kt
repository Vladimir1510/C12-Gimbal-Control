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
        addText("UART TEST 0.4.0", 18f, true)

        addText("ПЕРЕВІРКА СИСТЕМИ", 19f, true)

        val sttyPaths = listOf(
            "/system/bin/stty",
            "/vendor/bin/stty",
            "/bin/stty"
        )

        var found = false

        for (path in sttyPaths) {
            val file = File(path)
            addText("$path : ${file.exists()}")
            if (file.exists()) found = true
        }

        addText("РЕЗУЛЬТАТ", 19f, true)

        addText(
            if (found) {
                "Знайдено stty. Потрібно перевірити права доступу."
            } else {
                "stty у стандартних місцях не знайдено."
            },
            17f,
            true
        )

        addText(
            "UART не відкривається і не змінюється. " +
            "Команди не передаються."
        )

        setContentView(root)
    }
}
