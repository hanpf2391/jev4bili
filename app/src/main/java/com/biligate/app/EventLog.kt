package com.biligate.app

/**
 * 事件流观测（v0.2.1）：记录每个B站无障碍事件的指纹（类型/控件ID/类名/文本），
 * 用于从真机数据中找出"翻页/标题渲染"的专属指纹，替代时间采样触发。
 * 内存 ring buffer，随"复制诊断"导出。
 */
object EventLog {
    private val lines = ArrayDeque<String>()
    private const val CAP = 150

    @Synchronized
    fun add(type: String, viewId: String, cls: String, text: String) {
        if (lines.size >= CAP) lines.removeFirst()
        lines.addLast("[$type] id=$viewId cls=$cls ${text.take(18)}")
    }

    @Synchronized
    fun renderText(n: Int = 80): String = lines.toList().takeLast(n).joinToString("\n")

    /** v0.5.4：供 DiagStore 跨进程持久化读取 */
    @Synchronized
    fun raw(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}
