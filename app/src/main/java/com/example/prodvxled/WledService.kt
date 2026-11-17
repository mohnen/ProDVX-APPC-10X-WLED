package com.example.prodvxled

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service that turns the panel into a WLED device:
 * render engine -> /dev/ttyS1, HTTP API (:8080, redirected to :80 when root
 * allows), UDP realtime (:21324) and mDNS announcements.
 */
class WledService : Service() {

    companion object {
        const val HTTP_PORT = 8080
        const val UDP_PORT = 21324
        @Volatile var running = false; private set
        @Volatile var httpPort = HTTP_PORT; private set
        @Volatile var localIp = ""; private set

        fun start(c: Context) = c.startForegroundService(Intent(c, WledService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, WledService::class.java))
    }

    private val pipe = LedController.Pipe()
    private var http: WledHttp? = null
    private var engine: Thread? = null
    private var udp: Thread? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val nsdRegs = mutableListOf<NsdManager.RegistrationListener>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(1, buildNotification())
        localIp = findIp()

        engine = Thread({ engineLoop() }, "led-engine").apply { isDaemon = true; start() }
        udp = Thread({ udpLoop() }, "wled-udp").apply { isDaemon = true; start() }

        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("wled").apply {
                setReferenceCounted(true); acquire()
            }
        } catch (_: Exception) { }

        // WLED clients verify discovered devices via HTTP on port 80.
        // Do all setup off the main thread: su/iptables can block.
        // 1) try binding :80 directly (works if the kernel allows unprivileged low ports)
        // 2) otherwise run on :8080 and ask root to redirect :80 -> :8080
        WledState.statusText = "API: starting"
        Thread({
            if (tryBind(80)) {
                port80Method = "direct"
            } else if (tryBind(HTTP_PORT)) {
                // kernel-level redirect first; if the kernel lacks NAT/REDIRECT
                // fall back to a root TCP proxy via app_process
                port80Method = when {
                    redirectPort80() -> "iptables"
                    startRootProxy() -> "rootproxy"
                    else -> "none"
                }
            }
            if (http == null) {
                WledState.statusText = "HTTP failed to start"
                return@Thread
            }
            updateStatus()
            verifyPort80()
            registerNsd("_wled._tcp")
            registerNsd("_http._tcp")
        }, "wled-setup").start()
    }

    private fun tryBind(port: Int): Boolean {
        val srv = WledHttp(port)
        return try {
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            http = srv
            httpPort = port
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- status ----------

    private var port80Probe: Boolean? = null   // null = not tested yet
    private var port80Method = "none"          // direct | iptables | rootproxy | none
    private var iptablesErr: String? = null
    private var nsdOk = 0
    private var nsdFail: String? = null

    private fun updateStatus() {
        val parts = mutableListOf("API: http://$localIp${if (httpPort != 80) ":$httpPort" else ""}")
        if (httpPort == 80) parts += "port 80 direct"
        else when (port80Probe) {
            true -> parts += ":80 via $port80Method"
            false -> parts += ":80 unreachable${iptablesErr?.let { " ($it)" } ?: ""}"
            null -> parts += ":80 probing ($port80Method)"
        }
        parts += when {
            nsdFail != null -> "mDNS fail: $nsdFail"
            nsdOk > 0 -> "mDNS ok"
            else -> "mDNS?"
        }
        WledState.statusText = parts.joinToString(" | ")
    }

    /** Probe http://<ip>:80 and report what discovery clients will see. */
    private fun verifyPort80() {
        Thread.sleep(500)
        port80Probe = try {
            java.net.URL("http://$localIp:80/json/info").openConnection().apply {
                connectTimeout = 1500; readTimeout = 1500
            }.getInputStream().use { it.readBytes() }.isNotEmpty()
        } catch (e: Exception) { false }
        updateStatus()
    }

    override fun onDestroy() {
        running = false
        engine?.interrupt(); udp?.interrupt()
        try { udpSocket?.close() } catch (_: Exception) { }
        try { http?.stop() } catch (_: Exception) { }
        try { rootProxy?.destroy() } catch (_: Exception) { }
        pipe.close()
        nsdRegs.forEach { try { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } catch (_: Exception) { } }
        try { multicastLock?.release() } catch (_: Exception) { }
        WledState.statusText = "server stopped"
        super.onDestroy()
    }

    // ---------- render engine ----------

    private var lastFrame = IntArray(0)
    private var prevFrame = IntArray(0)
    private var transStart = 0L
    private var transDur = 0L
    private var lastErr: String? = null

    private fun engineLoop() {
        val buf = IntArray(WledState.LED_COUNT)
        while (running) {
            val now = System.currentTimeMillis()
            if (WledState.dirty) {
                WledState.dirty = false
                prevFrame = lastFrame.copyOf()
                transStart = now
                transDur = WledState.transition * 100L
            }

            val live = WledState.liveLeds
            val custom = WledState.customLeds
            val target = IntArray(WledState.LED_COUNT)
            when {
                !WledState.on -> { /* stays black */ }
                WledState.isLive && live != null -> live.copyInto(target)
                custom != null -> custom.copyInto(target)
                WledState.seg.on -> {
                    buf.fill(0)
                    Effects.render(WledState.seg, buf, now)
                    buf.copyInto(target)
                }
            }

            // crossfade from prevFrame during transition window
            val t = if (transDur > 0) (now - transStart).toFloat() / transDur else 1f
            val out = if (t < 1f && prevFrame.size == target.size) {
                IntArray(target.size) { Effects.mix(prevFrame[it], target[it], t) }
            } else target

            if (!out.contentEquals(lastFrame)) {
                pipe.useRoot = WledState.useRoot
                val err = pipe.write(LedController.buildFrame(out, WledState.level(), WledState.dim()))
                if (err == null) {
                    lastFrame = out
                    if (lastErr != null) { lastErr = null; updateStatus() }
                } else if (err != lastErr) {
                    lastErr = err
                    WledState.statusText = "LED write: $err"
                }
            }
            Thread.sleep(if (WledState.isAnimated() || transDur > 0 && t < 1f) 33 else 80)
        }
    }

    // ---------- UDP realtime (WARLS / DRGB / DNRGB on :21324) ----------

    private fun udpLoop() {
        try {
            udpSocket = DatagramSocket(UDP_PORT).apply { broadcast = true }
        } catch (e: Exception) {
            return   // port busy etc.; HTTP API still works
        }
        val buf = ByteArray(2048)
        val live = IntArray(WledState.LED_COUNT)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                udpSocket!!.receive(p)
                val d = p.data; val n = p.length
                if (n < 2) continue
                val timeout = (d[1].toInt() and 0xFF).let { if (it == 0) 2 else it }
                var ok = false
                when (d[0].toInt() and 0xFF) {
                    1 -> {   // WARLS: [1,timeout, idx,r,g,b, idx,r,g,b, ...]
                        var i = 2
                        while (i + 3 < n) {
                            val idx = d[i].toInt() and 0xFF
                            if (idx < live.size) live[idx] = rgb(d[i + 1], d[i + 2], d[i + 3])
                            i += 4
                        }
                        ok = true
                    }
                    2 -> {   // DRGB: [2,timeout, r,g,b, r,g,b, ...] from index 0
                        var i = 2; var idx = 0
                        while (i + 2 < n && idx < live.size) { live[idx++] = rgb(d[i], d[i + 1], d[i + 2]); i += 3 }
                        ok = true
                    }
                    4 -> {   // DNRGB: [4,timeout, startHi,startLo, lenHi,lenLo, r,g,b, ...]
                        if (n >= 6) {
                            val start = ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
                            val len = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
                            var i = 6; var idx = start
                            while (i + 2 < n && idx < live.size && idx < start + len) {
                                live[idx++] = rgb(d[i], d[i + 1], d[i + 2]); i += 3
                            }
                            ok = true
                        }
                    }
                    // 0/5: WLED sync notifier packets - ignored
                }
                if (ok) {
                    WledState.liveLeds = live.copyOf()
                    WledState.liveUntil = System.currentTimeMillis() + timeout * 1000L
                }
            } catch (e: Exception) {
                if (running) Thread.sleep(50)
            }
        }
    }

    private fun rgb(r: Byte, g: Byte, b: Byte) =
        ((r.toInt() and 0xFF) shl 16) or ((g.toInt() and 0xFF) shl 8) or (b.toInt() and 0xFF)

    // ---------- mDNS ----------

    private fun registerNsd(type: String) {
        try {
            val nsd = getSystemService(NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "wled-prodvx"
                serviceType = type
                // advertise :80 once the redirect probe passed
                port = if (httpPort == 80 || port80Probe == true) 80 else httpPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) { nsdOk++; updateStatus() }
                override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
                    nsdFail = "$type err$err"; updateStatus()
                }
                override fun onServiceUnregistered(si: NsdServiceInfo) { }
                override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) { }
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdRegs += listener
        } catch (e: Exception) {
            nsdFail = "$type: ${e.message}"; updateStatus()
        }
    }

    // ---------- helpers ----------

    private fun runRoot(cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return p.waitFor() to out
    }

    private fun redirectPort80(): Boolean {
        if (!WledState.useRoot) { iptablesErr = "root off"; return false }
        return try {
            // PREROUTING is what LAN clients need; OUTPUT is only for our self-test
            val (c1, o1) = runRoot(
                "iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports $HTTP_PORT")
            runRoot(
                "iptables -t nat -A OUTPUT -o lo -p tcp --dport 80 -j REDIRECT --to-ports $HTTP_PORT")
            if (c1 != 0) iptablesErr = (o1.ifEmpty { "exit $c1" }).take(80)
            c1 == 0
        } catch (e: Exception) { iptablesErr = e.message; false }
    }

    private var rootProxy: Process? = null

    /**
     * Fallback when iptables/NAT is unavailable: run a TCP :80 -> :8080 relay
     * as root via app_process (loads a class straight from our APK - the same
     * trick Shizuku uses).
     */
    private fun startRootProxy(): Boolean {
        if (!WledState.useRoot) return false
        return try {
            val apk = applicationInfo.sourceDir
            val p = ProcessBuilder("su", "-c",
                "CLASSPATH='$apk' exec app_process /system/bin com.example.prodvxled.RootProxy 80 $HTTP_PORT")
                .redirectErrorStream(true).start()
            rootProxy = p
            // drain output so a chatty JVM can't deadlock on a full pipe
            Thread { try { p.inputStream.bufferedReader().forEachLine { } } catch (_: Exception) { } }
                .apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            iptablesErr = e.message
            false
        }
    }

    private fun findIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress ?: "127.0.0.1"
    } catch (e: Exception) { "127.0.0.1" }

    private fun buildNotification(): Notification {
        val ch = "wled"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(ch, "WLED server", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, ch)
            .setContentTitle("WLED API active")
            .setContentText("Controlling LED frame")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .build()
    }
}

/** Autostart the WLED API after boot so the panel behaves like an appliance. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("led", Context.MODE_PRIVATE)
            if (prefs.getBoolean("server", true)) WledService.start(context)
        }
    }
}
