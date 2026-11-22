package ua.vladimir.c12gimbal

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var uartStatus: TextView
    private lateinit var networkStatus: TextView
    private lateinit var udpStatus: TextView
    private lateinit var rootLayout: LinearLayout

    private val channelViews = ArrayList<TextView>()
    private val importantViews = HashMap<Int, TextView>()

    @Volatile
    private var running = false

    @Volatile
    private var latestChannels = IntArray(16)

    @Volatile
    private var lastRcPacketMs = 0L

    private val cameraIp = "192.168.144.108"
    private val cameraPort = 5000

    private val pageColor = Color.rgb(12, 19, 32)
    private val cardColor = Color.rgb(24, 34, 51)
    private val primaryColor = Color.rgb(239, 245, 252)
    private val secondaryColor = Color.rgb(151, 169, 191)
    private val accentColor = Color.rgb(46, 213, 180)
    private val warningColor = Color.rgb(255, 190, 92)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(
        color: Int,
        radiusDp: Int = 18,
        strokeColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun makeText(
        value: String,
        size: Float,
        color: Int = primaryColor,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun addCard(title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = roundedBackground(cardColor)
        }

        val heading = makeText(title, 14f, accentColor, true)
        heading.letterSpacing = 0.06f
        card.addView(heading)

        val params = LinearLayout.LayoutParams(
