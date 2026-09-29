package com.biligate.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.5.4 跨进程诊断存储：无障碍服务跑在 :a11y 独立进程，UI 跑主进程——
 * 内存对象（FeedLog/EventLog/计数器）不跨进程共享，全部状态经此文件中转：
 * filesDir/biligate_diag.json。服务进程写（脏标记+500ms节流+临时文件原子替换），UI 进程读。
 * 独立进程的意义（用户实测 bug）：MIUI 把 App 划掉=砍进程，服务随之而死且系统不重绑，
 * 重进 App 后"显示运行中但一条记录都没有"。拆分后划掉只杀主进程，
 * 服务进程由系统的无障碍绑定保活，继续记录（GKD/李跳跳同款结构）。
 */
object DiagStore {

    private val diag = LinkedHashMap<String, String>()
    private var aliveAt = 0L
    private var connectedAt = ""
    private var dirty = false
    private var lastFlush = 0L

    fun put(ctx: Context, key: String, value: String) {
        diag[key] = value
        noteDirty(ctx)
    }

    fun heartbeat(ctx: Context) { aliveAt = System.currentTimeMillis(); noteDirty(ctx) }
    fun markDead(ctx: Context) { aliveAt = 0L; noteDirty(ctx) }
    fun setConnectedAt(ctx: Context, s: String) { connectedAt = s; noteDirty(ctx) }

    fun noteDirty(ctx: Context) {
        dirty = true
        val now = System.currentTimeMillis()
        if (now - lastFlush < 500) return
        lastFlush = now
        flush(ctx)
    }

    private fun flush(ctx: Context) {
        if (!dirty) return
        dirty = false
        try {
            val o = JSONObject()
            o.put("aliveAt", aliveAt)
            o.put("connectedAt", connectedAt)
            o.put("totalEvents", GateKeeperService.totalEvents)
            o.put("eventCount", GateKeeperService.eventCount)
            o.put("lastEventAt", GateKeeperService.lastEventAt)
            o.put("totalLastAt", GateKeeperService.totalLastAt)
            val d = JSONObject(); diag.forEach { (k, v) -> d.put(k, v) }
            o.put("diag", d)
            val recs = JSONArray()
            FeedLog.raw().forEach { r ->
                recs.put(JSONObject().apply {
                    put("time", r.time); put("ok", r.ok); put("title", r.title)
                    put("uploader", r.uploader); put("views", r.views)
                    put("captureMs", r.captureMs); put("source", r.source); put("online", r.online)
                    put("judgment", r.judgment)
                })
            }
            o.put("records", recs)
            o.put("events", EventLog.raw().joinToString("\n"))
            val f = java.io.File(ctx.filesDir, "biligate_diag.json")
            val tmp = java.io.File(ctx.filesDir, "biligate_diag.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    // ================= 以下供 UI（主进程）读取 =================

    data class Record(val time: String, val ok: Boolean, val title: String, val uploader: String,
                      val views: String, val captureMs: Long, val source: String, val online: String,
                      val judgment: String)

    data class Snap(
        val aliveAt: Long, val connectedAt: String,
        val totalEvents: Long, val eventCount: Long, val lastEventAt: Long, val totalLastAt: Long,
        val diag: Map<String, String>, val records: List<Record>, val events: String,
    )

    fun load(ctx: Context): Snap {
        try {
            val f = java.io.File(ctx.filesDir, "biligate_diag.json")
            if (!f.exists()) return Snap(0, "", 0, 0, 0, 0, emptyMap(), emptyList(), "")
            val o = JSONObject(f.readText())
            val recs = mutableListOf<Record>()
            val arr = o.optJSONArray("records") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                recs.add(Record(
                    r.optString("time"), r.optBoolean("ok"), r.optString("title"),
                    r.optString("uploader"), r.optString("views"), r.optLong("captureMs"),
                    r.optString("source"), r.optString("online"), r.optString("judgment"),
                ))
            }
            val d = o.optJSONObject("diag") ?: JSONObject()
            val m = mutableMapOf<String, String>()
            d.keys().forEach { m[it] = d.optString(it, "") }
            return Snap(
                o.optLong("aliveAt"), o.optString("connectedAt"),
                o.optLong("totalEvents"), o.optLong("eventCount"),
                o.optLong("lastEventAt"), o.optLong("totalLastAt"),
                m, recs, o.optString("events"),
            )
        } catch (_: Exception) {
            return Snap(0, "", 0, 0, 0, 0, emptyMap(), emptyList(), "")
        }
    }

    /** 与旧 FeedLog.renderText 同格式的记录文本 */
    fun renderRecords(s: Snap, n: Int = 30): String = buildString {
        if (s.records.isEmpty()) { append("暂无记录"); return@buildString }
        val ok = s.records.count { it.ok }
        val avg = s.records.filter { it.ok }.map { it.captureMs }.average().toLong()
        append("最近${s.records.size}条：成功 $ok / 失败 ${s.records.size - ok}，成功平均到手 ${avg}ms\n\n")
        s.records.takeLast(n).reversed().forEach { r ->
            if (r.ok) {
                val v = if (r.views.isNotBlank()) " ▶${r.views}" else ""
                val on = if (r.online.isNotBlank()) " ◉${r.online}在线" else ""
                val jd = if (r.judgment.isNotBlank()) " 判:${r.judgment}" else ""
                append("${r.time} ✓ ${r.uploader.ifBlank { "?" }}《${r.title.take(22)}》$v$on ${r.captureMs}ms·${r.source}$jd\n")
            } else {
                append("${r.time} ✗ 提取失败（${r.captureMs}ms·${r.source}）\n")
            }
        }
    }

    fun renderEvents(s: Snap, n: Int = 80): String =
        s.events.lineSequence().toList().takeLast(n).joinToString("\n")
}
