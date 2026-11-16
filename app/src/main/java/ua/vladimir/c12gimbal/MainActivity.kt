package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileInputStream

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
        addText("UART TEST 0.3.0", 18f, true)

        val port = File("/dev/ttyHS3")

        addText("ПЕРЕВІРКА ПОРТУ", 19f, true)
        addText("Пристрій існує: ${port.exists()}")
        addText("Доступ для читання: ${port.canRead()}")

        var input: FileInputStream? = null
        var result: String

        try {
            input = FileInputStream(port)
            result = "ПОРТ ВІДКРИТО ДЛЯ ЧИТАННЯ"
        } catch (e: Exception) {
            result = "ПОМИЛКА ВІДКРИТТЯ:\n${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                input?.close()
            } catch (_: Exception) {
            }
        }

        addText("РЕЗУЛЬТАТ", 19f, true)
        addText(result, 17f, true)

        addText(
            "Дані не читаються і не передаються. " +
            "Порт відкривається лише для короткої перевірки."
        )

        setContentView(root)
    }
}
