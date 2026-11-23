package ua.vladimir.c12gimbal

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors

/**
 * C12 gimbal commands over UDP (ASCII "#TPUG..." + 2-hex checksum = sum of bytes mod 256).
 * Verified on hardware: pitchAbsolute(-90) and center() move the gimbal.
 */
object C12Protocol {
    fun encode(body: String): String {
        val sum = body.toByteArray(Charsets.US_ASCII).sumOf { it.toInt() and 0xFF } and 0xFF
        return body + String.format(Locale.US, "%02X", sum)
    }

    fun stop() = encode("#TPUG2wPTZ00")
    fun center() = encode("#TPUG2wPTZ05")

    /** Pitch speed -99..99; 0 means stop. */
    fun pitchSpeed(speed: Int): String {
        require(speed in -99..99) { "speed outside -99..99" }
        return if (speed == 0) stop() else encode("#TPUG2wGSP" + String.format(Locale.US, "%02X", speed and 0xFF))
    }

    /** One zoom step; repeat while the zoom control is held (taken from the earlier app version, 429419c). */
    fun zoomIn() = encode("#TPUD2wDZM0A")
    fun zoomOut() = encode("#TPUD2wDZM0B")

    /** Absolute pitch -90..10 degrees, speed 1..30. */
    fun pitchAbsolute(degrees: Int, speed: Int): String {
        require(degrees in -90..10) { "pitch outside -90..10 degrees" }
        require(speed in 1..30) { "absolute pitch speed outside 1..30" }
        return encode("#TPUG6wGAP" + String.format(Locale.US, "%04X%02X", (degrees * 100) and 0xFFFF, speed))
    }
}

/** Sends commands from a single background thread; network I/O is not allowed on the main thread. */
class C12Client(
    private val host: String = CAMERA_IP,
    private val port: Int = CAMERA_PORT,
    private val onError: (String) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    /**
     * Send to the subnet broadcast instead of [host]. On some C12 boots (new random MAC each boot) unicast to the
     * camera is lost on the radio link while broadcast still reaches it (verified on hardware).
     */
    @Volatile var broadcast = false

    fun send(command: String) {
        executor.execute {
            try {
                val s = socket ?: DatagramSocket().also { it.broadcast = true; socket = it }
                val bytes = command.toByteArray(Charsets.US_ASCII)
                s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(if (broadcast) BROADCAST_IP else host), port))
            } catch (e: Exception) {
                onError("C12 send '$command' помилка ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun close() {
        executor.execute { socket?.close(); socket = null }
        executor.shutdown()
    }

    companion object {
        const val CAMERA_IP = "192.168.144.108"
        const val CAMERA_PORT = 5000
        const val BROADCAST_IP = "192.168.144.255"
    }
}
