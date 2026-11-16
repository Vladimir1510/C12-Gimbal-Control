package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(24, 28, 24, 20)
            setBackgroundColor(0xFFF2F5F8.toInt())
        }

        fun addText(value: String, size: Float, bold: Boolean = false) {
            val item = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(0xFF17212B.toInt())
                setPadding(0, 8, 0, 8)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            root.addView(item)
        }

        addText("C12 Gimbal Control", 25f, true)
        addText("Версія 0.5.2", 16f)
        addText("ПЕРЕВІРКА UART", 19f, true)

        addText("Пристрій: /dev/ttyHS3", 16f)
        addText("Існує: ${File("/dev/ttyHS3").exists()}", 16f)
        addText("Читання доступне: ${File("/dev/ttyHS3").canRead()}", 16f)

        status = TextView(this).apply {
            text = "Інтерфейс працює.\nUART не відкривається.\nКоманди не передаються."
            textSize = 17f
            setTextColor(0xFF176B3A.toInt())
            setPadding(0, 20, 0, 20)
        }

        root.addView(status)

        setContentView(root)
    }
}
