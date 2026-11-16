
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
        addText("Версія 0.1.0", 15f)
        addText("КАНАЛИ КЕРУВАННЯ", 19f, true)

        addText("CH10 — ZOOM", 18f, true)
        addText("CH11 — PITCH", 18f, true)
        addText("CH12 — CENTER", 18f, true)
        addText("CH13 — DOWN 90°", 18f, true)

        addText("СТАН СИСТЕМИ", 19f, true)
        addText("Камера: очікує перевірки")
        addText("Пульт: очікує підключення")

        addText(
            "Безпечний тестовий режим. " +
            "Команди руху поки не передаються."
        )

        setContentView(root)
    }
}
