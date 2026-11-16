package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.os.Process
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

        val port = File("/dev/ttyHS3")

        addText("C12 Gimbal Control", 26f, true)
        addText("UART TEST 0.2.0", 18f, true)

        addText("ПАРАМЕТРИ ПУЛЬТА", 19f, true)
        addText("Android: 13")
        addText("SDK: UART")
        addText("Очікувана швидкість: 115200 бод")

        addText("ПЕРЕВІРКА UART", 19f, true)
        addText("Порт: /dev/ttyHS3")
        addText("Існує: ${port.exists()}")
        addText("Це файл: ${port.isFile}")
        addText("Доступний для читання: ${port.canRead()}")
        addText("Доступний для запису: ${port.canWrite()}")
        addText("UID програми: ${Process.myUid()}")

        addText(
            "УВАГА", 19f, true
        )
        addText(
            "Це попередня перевірка прав доступу. " +
            "UART не відкривається, дані не читаються " +
            "і команди не передаються."
        )

        setContentView(root)
    }
}
