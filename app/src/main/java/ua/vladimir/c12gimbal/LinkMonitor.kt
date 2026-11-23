package ua.vladimir.c12gimbal

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Checks the video-link chain RC -> ground unit -> air unit (radio) -> C12 every few seconds.
 * The camera never answers UDP commands, so each node is pinged; the camera is also probed over TCP
 * (a refused connection still proves it is on the network).
 */
class LinkMonitor(private val onResult: (State) -> Unit) {

    /** Round-trip time in ms, or null when the node did not answer. */
    data class State(val ground: Int?, val air: Int?, val camera: Int?)

    private val executor = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        executor.scheduleWithFixedDelay({ onResult(probeAll()) }, 0, INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() = executor.shutdownNow()

    private fun probeAll() = State(
        ground = ping(GROUND_IP),
        air = ping(AIR_IP),
        camera = ping(C12Client.CAMERA_IP) ?: tcpProbe(C12Client.CAMERA_IP),
    )

    private fun ping(ip: String): Int? = try {
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val ok = process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
        process.destroy()
        // "time=23.5 ms" in the reply line; spawning ping would distort a wall-clock measurement.
        if (ok) Regex("time=([0-9.]+)").find(output)?.groupValues?.get(1)?.toDouble()?.let { kotlin.math.ceil(it).toInt() } ?: 1 else null
    } catch (_: Exception) {
        null
    }

    private fun tcpProbe(ip: String): Int? {
        for (port in CAMERA_TCP_PORTS) {
            val started = System.nanoTime()
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), TCP_TIMEOUT_MS) }
            } catch (e: ConnectException) {
                // Refused: the host answered, so it is reachable. Unreachable hosts also surface as ConnectException.
                if (e.message?.contains("ECONNREFUSED") != true && e.message?.contains("refused") != true) continue
            } catch (_: Exception) {
                continue
            }
            return ((System.nanoTime() - started) / 1_000_000).toInt()
        }
        return null
    }

    companion object {
        const val GROUND_IP = "192.168.144.12"
        const val AIR_IP = "192.168.144.11"
        private val CAMERA_TCP_PORTS = intArrayOf(554, 80)
        private const val TCP_TIMEOUT_MS = 700
        private const val INTERVAL_MS = 2000L
    }
}
