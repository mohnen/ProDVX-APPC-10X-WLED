package com.example.prodvxled

import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WLED-compatible HTTP + WebSocket API.
 *   GET/POST /json, /json/state   - state
 *   GET      /json/info           - device info
 *   GET      /json/eff|effects    - effect names (index = fx id)
 *   GET      /json/pal|palettes   - palette names
 *   GET/POST /win                 - legacy XML API
 *   WS       /ws                  - live state push + JSON commands
 *   GET      /                    - minimal web UI
 */
class WledHttp(port: Int) : NanoWSD(port) {

    private val sockets = CopyOnWriteArrayList<WebSocket>()

    /** Latest-state queue drained by a single sender thread so a dead
     *  /ws client can never block an HTTP worker. */
    private val wsQueue = java.util.concurrent.ArrayBlockingQueue<String>(1)
    private val wsSender = Thread({
        while (true) {
            try {
                val msg = wsQueue.take()
                for (s in sockets) try { s.send(msg) } catch (_: Exception) { sockets.remove(s) }
            } catch (_: InterruptedException) { return@Thread }
        }
    }, "ws-broadcast").apply { isDaemon = true; start() }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/ws") {
            // NanoWSD validates the Upgrade header and runs the handshake;
            // plain GETs get its built-in error response.
            return super.serve(session)
        }
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(""))

        var body: JSONObject? = null
        if (session.method == Method.POST || session.method == Method.PUT) {
            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
                files["postData"]?.takeIf { it.isNotBlank() }?.let { body = JSONObject(it) }
            } catch (e: Exception) { /* non-JSON or empty body is fine for /win */ }
        }
        val uri = session.uri.removeSuffix("/").ifEmpty { "/" }
        val params = session.parms + legacyParams(uri)

        val t0 = System.nanoTime()
        val resp = when {
            uri == "/" || uri == "/index.htm" || uri == "/index.html" ->
                fixed(Response.Status.OK, "text/html", INDEX_HTML)
            uri == "/favicon.ico" -> newFixedLengthResponse(Response.Status.NO_CONTENT, "", "")
            uri == "/log" -> fixed(Response.Status.OK, "text/plain",
                reqLog.joinToString("\n"))
            uri == "/json" || uri == "/json/state" || uri == "/json/info" ||
                uri == "/json/eff" || uri == "/json/effects" ||
                uri == "/json/pal" || uri == "/json/palettes" || uri == "/json/nodes" ||
                uri == "/json/si" || uri == "/json/cfg" || uri == "/json/fxdata" ||
                uri == "/presets.json" || uri == "/playlists.json" ->
                serveJson(uri, body, params)
            // legacy API: GET /win&T=2&A=255 (params inside the path, no '?')
            uri == "/win" || uri.startsWith("/win&") ->
                serveWin(params, body)
            uri == "/settings" || uri == "/settings/leds" ->
                fixed(Response.Status.OK, "text/html", "<html><body>No settings on this device.</body></html>")
            else -> fixed(Response.Status.NOT_FOUND, "text/plain", "Not found: ${session.uri}")
        }
        val bodyStr = body?.toString()?.take(160)?.let { " $it" } ?: ""
        logReq("${session.method} ${session.uri}$bodyStr -> ${resp.status.description} ${(System.nanoTime() - t0) / 1_000_000}ms")
        return cors(resp)
    }

    private val reqLog = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun logReq(line: String) {
        reqLog.addLast("${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} $line")
        while (reqLog.size > 60) reqLog.pollFirst()
    }

    /** WLED's /win API puts params in the path: "/win&T=2&A=255". */
    private fun legacyParams(uri: String): Map<String, String> {
        val q = uri.substringAfter('&', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else try {
                it.substring(0, i).uppercase() to
                    java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            } catch (e: Exception) { null }
        }.toMap()
    }

    private fun serveJson(uri: String, body: JSONObject?, params: Map<String, String>): Response {
        if (body != null && (uri == "/json" || uri == "/json/state")) {
            WledState.applyJson(body)
            broadcast()
            if (!body.optBoolean("v", false)) return json(JSONObject().put("success", true))
        }
        val out = when (uri) {
            "/json/state" -> WledState.stateJson()
            "/json/info" -> WledState.infoJson()
            // compact state+info polled by Home Assistant's wled client
            "/json/si" -> JSONObject()
                .put("state", WledState.stateJson())
                .put("info", WledState.infoJson())
            "/json/eff", "/json/effects" -> Effects.effectsJson()
            "/json/nodes" -> JSONObject().put("nodes", JSONArray().put(
                JSONObject().put("name", "WLED-ProDVX").put("ip", WledService.localIp)
                    .put("age", 0).put("type", 0).put("vid", 2412190)))
            // per-effect slider metadata; "0" = no extra controls
            "/json/fxdata" -> JSONArray().apply { repeat(Effects.FX_COUNT) { put("0") } }
            "/presets.json" -> Presets.json()
            // no config or playlists on this device - empty objects are valid
            "/json/cfg", "/playlists.json" -> JSONObject()
            else -> Effects.palettesJson()
        }
        // bare /json returns the combined state+info+effects+palettes object,
        // like real WLED (Home Assistant's client requests GET /json/)
        if (uri == "/json") {
            return json(JSONObject()
                .put("state", WledState.stateJson())
                .put("info", WledState.infoJson())
                .put("effects", Effects.effectsJson())
                .put("palettes", Effects.palettesJson()))
        }
        return json(out)
    }

    /** Legacy WLED XML API used by older integrations. */
    private fun serveWin(params: Map<String, String>, body: JSONObject?): Response {
        body?.let { WledState.applyJson(it); broadcast() }
        var changed = false
        fun p(k: String) = params[k]?.toIntOrNull()
        p("T")?.let { t ->   // 0=off 1=on 2=toggle
            WledState.on = if (t == 2) !WledState.on else t != 0; changed = true
        }
        p("A")?.let { WledState.bri = it.coerceIn(0, 255); changed = true }
        val r = p("R"); val g = p("G"); val b = p("B")
        if (r != null && g != null && b != null) {
            WledState.customLeds = null
            WledState.seg.col[0] = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            changed = true
        }
        p("FX")?.let { WledState.customLeds = null; WledState.seg.fx = it.coerceIn(0, Effects.FX_COUNT - 1); changed = true }
        p("SX")?.let { WledState.seg.sx = it.coerceIn(0, 255); changed = true }
        p("IX")?.let { WledState.seg.ix = it.coerceIn(0, 255); changed = true }
        p("FP")?.let { WledState.seg.pal = it.coerceIn(0, Effects.PAL_COUNT - 1); changed = true }
        if (changed) { WledState.touch(); broadcast() }

        val s = WledState.seg
        // WLED clients derive "on" from brightness > 0, so report 0 when off
        val ac = if (WledState.on) WledState.bri else 0
        val sb = StringBuilder("<vs><ac>$ac</ac>")
        for (i in 0..2) {
            sb.append("<cl>${(s.col[i] shr 16) and 0xFF}</cl><cl>${(s.col[i] shr 8) and 0xFF}</cl><cl>${s.col[i] and 0xFF}</cl>")
        }
        sb.append("<ns>0</ns><nr>1</nr><nl>${if (WledState.isLive) 1 else 0}</nl>")
            .append("<nf>1</nf><nd>60</nd><nt>0</nt>")
            .append("<fx>${s.fx}</fx><sx>${s.sx}</sx><ix>${s.ix}</ix><fp>${s.pal}</fp>")
            .append("<wv>-1</wv><ws>0</ws><cy>0</cy><ds>WLED-ProDVX</ds><ss>0</ss></vs>")
        return fixed(Response.Status.OK, "text/xml", sb.toString())
    }

    // ---------- websocket ----------

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Ws(handshake)

    private inner class Ws(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            sockets.add(this)
            try { send(JSONObject().put("state", WledState.stateJson()).put("info", WledState.infoJson()).toString()) }
            catch (_: IOException) { }
        }
        override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            sockets.remove(this)
        }
        override fun onMessage(message: WebSocketFrame) {
            try {
                val o = JSONObject(message.textPayload)
                if (WledState.applyJson(o)) broadcast()
            } catch (_: Exception) { }
        }
        override fun onPong(pong: WebSocketFrame) { }
        override fun onException(exception: IOException) { sockets.remove(this) }
    }

    /** Queue a state push to all connected /ws clients (non-blocking). */
    fun broadcast() {
        if (sockets.isEmpty()) return
        wsQueue.clear()   // only the latest state matters
        wsQueue.offer(JSONObject().put("state", WledState.stateJson()).put("info", WledState.infoJson()).toString())
    }

    // ---------- helpers ----------

    private fun json(o: Any): Response =
        fixed(Response.Status.OK, "application/json",
            if (o is String) o else if (o is JSONArray) o.toString() else (o as JSONObject).toString())

    private fun fixed(status: Response.Status, mime: String, text: String): Response =
        newFixedLengthResponse(status, mime, text)

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
        // real WLED closes every connection; avoids keep-alive weirdness
        // through the root proxy relay
        r.addHeader("Connection", "close")
        return r
    }

    companion object {
        /** Minimal control page; real WLED clients use the JSON API. */
        private val INDEX_HTML = """
<!doctype html><html><head><meta name=viewport content="width=device-width">
<title>WLED ProDVX</title><style>
body{background:#222;color:#eee;font-family:sans-serif;max-width:420px;margin:2em auto;padding:0 1em}
button,input,select{font-size:1.1em;padding:.5em;margin:.3em 0;width:100%;box-sizing:border-box}
.row{display:flex;gap:.5em}
</style></head><body>
<h2>WLED ProDVX</h2>
<div class=row><button onclick="set('on',true)">On</button><button onclick="set('on',false)">Off</button></div>
<label>Brightness <input id=bri type=range min=1 max=255 onchange="set('bri',+this.value)"></label>
<label>Color <input id=col type=color value="#ffa000" onchange="col_(this.value)"></label>
<label>Effect <select id=fx onchange="seg({fx:+this.value})"></select></label>
<label>Palette <select id=pal onchange="seg({pal:+this.value})"></select></label>
<label>Speed <input id=sx type=range max=255 onchange="seg({sx:+this.value})"></label>
<pre id=st></pre>
<script>
async function api(o){await fetch('/json/state',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o)})}
function set(k,v){let o={};o[k]=v;api(o)}
function seg(o){api({seg:[o]})}
function col_(h){let r=parseInt(h.substr(1,2),16),g=parseInt(h.substr(3,2),16),b=parseInt(h.substr(5,2),16);seg({col:[[r,g,b],[0,0,0],[0,0,0]]})}
async function poll(){try{
 let j=await(await fetch('/json/state')).json();
 let e=await(await fetch('/json/effects')).json();
 let p=await(await fetch('/json/palettes')).json();
 let fx=document.getElementById('fx'),pal=document.getElementById('pal');
 if(!fx.options.length){e.forEach((n,i)=>fx.add(new Option(n,i)))}
 if(!pal.options.length){p.forEach((n,i)=>pal.add(new Option(n,i)))}
 fx.value=j.seg[0].fx;pal.value=j.seg[0].pal;document.getElementById('bri').value=j.bri;
 document.getElementById('st').textContent=JSON.stringify(j.seg[0],null,1);
}catch(x){}}
poll();setInterval(poll,3000);
</script></body></html>""".trimIndent()
    }
}
