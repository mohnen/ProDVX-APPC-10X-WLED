package net.mohnen.prodvxled

import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny TCP relay, intended to be launched as root via:
 *   su -c "CLASSPATH=<apk> app_process /system/bin net.mohnen.prodvxled.RootProxy 80 8080"
 *
 * Runs under app_process (not as an Android app component), so it must only use
 * plain java.* APIs. Binds the privileged :80 and forwards each connection to
 * 127.0.0.1:<target>, making the app's WLED HTTP API reachable on the port that
 * WLED discovery clients hardcode.
 */
object RootProxy {
    @JvmStatic
    fun main(args: Array<String>) {
        val listenPort = args.getOrNull(0)?.toIntOrNull() ?: 80
        val targetPort = args.getOrNull(1)?.toIntOrNull() ?: 8080
        val server = ServerSocket(listenPort)
        while (true) {
            val src = server.accept()
            Thread {
                try {
                    val dst = Socket("127.0.0.1", targetPort)
                    src.tcpNoDelay = true
                    dst.tcpNoDelay = true
                    val up = Thread {
                        try { src.inputStream.copyTo(dst.outputStream, 8192) } catch (_: Exception) { }
                        try { dst.shutdownOutput() } catch (_: Exception) { }
                    }
                    up.start()
                    try { dst.inputStream.copyTo(src.outputStream, 8192) } catch (_: Exception) { }
                    try { src.shutdownOutput() } catch (_: Exception) { }
                    up.join(30_000)
                    try { src.close() } catch (_: Exception) { }
                    try { dst.close() } catch (_: Exception) { }
                } catch (_: Exception) {
                    try { src.close() } catch (_: Exception) { }
                }
            }.start()
        }
    }
}
