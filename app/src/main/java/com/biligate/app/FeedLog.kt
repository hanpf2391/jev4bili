package com.biligate.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据获取层观测日志（v0.1.8 纯观测版核心）：
 * 每次成功/失败的视频提取都记一条，供设置页"获取记录"列表展示，
 * 用户可直接判断：覆盖率（刷10条记了几条）/ 延迟（多快拿到）/ 失败率。
 * 内存 ring buffer，进程内有效，不落盘。
 */
object FeedLog {

    data class Record(
        val time: String,        // HH:mm:ss
        val ok: Boolean,
        val title: String,       // 失败时空
        val uploader: String,
        val views: String,       // 如 "39.3万"，可空
        val captureMs: Long,     // 本轮首事件 → 数据到手 耗时
        val source: String,      // active/windows/eventSrc
        val online: String = "", // v0.4.4 在线人数：当前在播视频独有，用于判定"谁在播"
        var judgment: String = "", // v0.6.1 Jev判断结果回填（"预判✓ 放行·干货 味0.7"）
    )

    private val records = ArrayDeque<Record>()
    private const val CAP = 300   // v0.9.2 100→300：历史要经得起回看
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)   // v0.9.5 加日期前缀：7天柱图分桶用

    @Synchronized
    fun add(ok: Boolean, title: String, uploader: String, views: String, captureMs: Long, source: String, online: String = "") {
        if (records.size >= CAP) records.removeFirst()
        records.addLast(Record(fmt.format(Date()), ok, title, uploader, views, captureMs, source, online))
    }

    /** v0.9.2 历史持久化：服务启动时从上次快照恢复（重启/被杀不再清零） */
    @Synchronized
    fun restore(r: Record) {
        if (records.size >= CAP) records.removeFirst()
        records.addLast(r)
    }

    @Synchronized
    fun latest(n: Int = 30): List<Record> = records.toList().takeLast(n).reversed()

    /** v0.5.4：供 DiagStore 跨进程持久化读取 */
    @Synchronized
    fun raw(): List<Record> = records.toList()

    /** v0.6.1：Jev判断完成后回填到最近一条同标题记录（倒序找，命中最近那条） */
    @Synchronized
    fun setJudgment(title: String, text: String) {
        records.asReversed().firstOrNull { it.ok && it.title == title }?.judgment = text
    }

    @Synchronized
    fun stats(): String {
        if (records.isEmpty()) return "暂无记录"
        val ok = records.count { it.ok }
        val avg = records.filter { it.ok }.map { it.captureMs }.average().toLong()
        return "最近${records.size}条：成功 $ok / 失败 ${records.size - ok}，成功平均到手 ${avg}ms"
    }

    @Synchronized
    fun clear() = records.clear()

    /** 列表文本（倒序，最新在上） */
    fun renderText(n: Int = 30): String = buildString {
        append(stats()).append("\n\n")
        latest(n).forEachIndexed { i, r ->
            if (r.ok) {
                val v = if (r.views.isNotBlank()) " ▶${r.views}" else ""
                val o = if (r.online.isNotBlank()) " ◉${r.online}在线" else ""
                append("${r.time} ✓ ${r.uploader.ifBlank { "?" }}《${r.title.take(22)}》$v$o ${r.captureMs}ms·${r.source}\n")
            } else {
                append("${r.time} ✗ 提取失败（${r.captureMs}ms·${r.source}）\n")
            }
        }
    }
}
