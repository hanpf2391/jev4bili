package com.biligate.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 守门员服务：监听B站（tv.danmaku.bili）界面变化 ->
 * 提取当前视频标题/UP主 -> 黑名单秒跳 / Jev判断 -> 提示+拟人上滑。
 *
 * MVP提取策略：遍历节点树收集文本，启发式识别标题（长文本、非UI词）与UP主（短文本、非互动词）。
 * 真机校准：打开 dump 模式（设置页）把界面文本打进 logcat（tag=BiliGate），按实际结构再精调。
 */
class GateKeeperService : AccessibilityService() {

    companion object {
        const val TAG = "BiliGate"
        val BILI_PKGS = setOf("tv.danmaku.bili", "tv.danmaku.bili.blue")   // 正式版+概念版
        @Volatile var eventCount = 0L        // 自检：B站事件计数
        @Volatile var lastEventAt = 0L       // 自检：最近B站事件时间戳
        @Volatile var totalEvents = 0L       // 自检：所有应用事件计数（区分服务死活）
        @Volatile var totalLastAt = 0L
        const val DEDUP_WINDOW_MS = 30_000L  // v0.4.4 同视频去重窗口
        const val CLEAR_ACTION = "com.biligate.app.CLEAR_LOGS"   // v0.5.4 主进程清空按钮广播
        const val KEY_ACTION = "com.biligate.app.KEY_CHANGED"    // v0.7.0 BYOK密钥更新广播
    }

    /** 采集到的一条界面文本：内容 / 屏幕y坐标 / 视图ID。
     *  v0.4.4 诊断：坐标与ID是判定"这条文本属于哪张卡片"的唯一客观依据（BFS顺序会被预加载页打乱）。 */
    data class Txt(val text: String, val y: Int, val id: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    /** v0.4.4 重复抑制表：标题 → 最近记录时刻。
     *  真机实证（2026-09-28）：预加载邻页文本混入使同屏内容在 A/B 之间翻转，
     *  单值 lastTitle 被反复击穿——《劳斯莱斯》46秒记4次、《高楼》记3次。 */
    private val recentTitles = LinkedHashMap<String, Long>()
    private var debounceRunnable: Runnable? = null
    @Volatile private var inFlight = 0   // 并发判断限流
    private var cachedSourceRoot: AccessibilityNodeInfo? = null   // 事件源节点兜底（不依赖flag）
    private var pendingFirstEventAt = 0L   // 本轮首个事件时间戳（测量获取耗时）
    private var failStreak = 0             // 连续提取失败计数（节流失败记录）
    private var lastProcessAt = 0L         // 节流：上次处理时刻
    private var throttleRunnable: Runnable? = null
    private var feedSnapshotRunnable: Runnable? = null   // 翻页指纹触发的快照

    /** 判定结果缓存（LRU 50条）：信息流重复推同一视频时不再调API */
    private val judgeCache = object : LinkedHashMap<String, JevClient.Judgment>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JevClient.Judgment>) = size > 50
    }
    /** v0.6.0 当前正在屏上展示的视频标题（横幅只对当前视频弹；预判结果迟到时补弹也以此判定） */
    private var lastPresented = ""
    /** v0.6.0 在途判断去重（预读+当前可能对同一视频重复触发判断） */
    private val judgingTitles = mutableSetOf<String>()
    /** v0.8.7 判断排队：快刷时在途超限不再静默丢（"未判定"记录+横幅被吞的根因），排队稍后补判 */
    private data class PendingJudge(val title: String, val uploader: String, val signals: String)
    private val pendingJudges = ArrayDeque<PendingJudge>()
    /** v0.8.6 每视频的上屏时刻——迟到补弹闸门从"还在看这条"放宽为"4秒内离开也弹" */
    private val presentedAt = LinkedHashMap<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        runCatching {   // v0.2.2 保险：回调内任何异常都不能杀死服务
            if (!Prefs.enabled(this)) return
            totalEvents++
            totalLastAt = System.currentTimeMillis()
            if (event.packageName?.toString() !in BILI_PKGS) return
        eventCount++
        lastEventAt = System.currentTimeMillis()

        // ===== v0.3.0 指纹分层（真机指纹 2026-09-28）：噪声零成本丢弃 / 翻页精确触发 / 节流兜底 =====
        val cls = event.className?.toString() ?: ""
        if (cls.contains("SeekBar")) return            // 进度条噪声（大头），类名字段自带零成本
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return
        // 观测采样（1/8，SeekBar已滤，样本干净）
        if (eventCount % 8L == 0L) {
            runCatching {
                val typeName = when (event.eventType) {
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT"
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "STATE"
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLL"
                    else -> "0x${event.eventType.toString(16)}"
                }
                EventLog.add(
                    typeName,
                    event.source?.viewIdResourceName?.substringAfterLast("/") ?: "-",
                    cls.substringAfterLast("."), "",
                )
                DiagStore.noteDirty(this)
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {   // 滑动=翻页信号
            scheduleFeedSnapshot(); return
        }
        val viewId = runCatching { event.source?.viewIdResourceName ?: "" }.getOrDefault("")
        if (viewId.contains("danmaku")) return                              // 弹幕噪声
        // v0.4.0 翻页指纹家族扩认：recycler / pager / story（真机实证 story_view_pager 是竖屏流第二翻页控件）
        if (viewId.contains("recycler") || viewId.contains("pager") || viewId.contains("story")) {
            scheduleFeedSnapshot(); return
        }
        event.source?.let { cachedSourceRoot = it }
        if (pendingFirstEventAt == 0L) pendingFirstEventAt = System.currentTimeMillis()

        // 节流兜底（600ms）；v0.4.4：快照循环在跑时不再另起一轮——
        // 两轮并行各拍各的，是"同一秒记录两条不同视频"（22:38:18 修车大全+欧大发）的来源之一
        val nowTs = System.currentTimeMillis()
        if (feedSnapshotRunnable != null) return
        if (nowTs - lastProcessAt >= 600) {
            processCurrentScreen()
        } else if (throttleRunnable == null) {
            val r = Runnable { throttleRunnable = null; processCurrentScreen() }
            throttleRunnable = r
            handler.postDelayed(r, 600 - (nowTs - lastProcessAt))
        }
        }   // runCatching
    }

    /**
     * 翻页指纹触发：确认循环快照（拍到新标题/离场即停）。
     * v0.5.1 快刷断调修复：旧逻辑每来一个事件就 removeCallbacks+重排450ms——
     * 快刷时事件每隔<450ms来一发，循环永远等不到安静期，整轮快刷只记最后一条（用户实测"速度快一点就断调"）。
     * 新逻辑：①滚动瞬间立即补拍一张（旧卡还在屏上，最值钱，200ms限频）；
     * ②链已在跑就不重排（事件潮不影响连拍节奏）；
     * ③新链首拍0延迟、先密后疏共8拍≈2600ms（渲染一完成的第一拍立刻记录）。
     */
    private var feedStartAt = 0L
    private var shotCount = 0

    private fun scheduleFeedSnapshot() {
        if (pendingFirstEventAt == 0L) pendingFirstEventAt = System.currentTimeMillis()
        val nowMs = System.currentTimeMillis()
        if (feedSnapshotRunnable != null) {
            if (shotCount < 2) {
                // 早期（前2拍）重排到"最新事件+250ms"：等渲染稳定（v0.4.x实证行为，450→250提速）；
                // 拍了2+后不再重排——v0.5.1/5.2教训：绝不重排=固定节奏与滑屏相位永久错开（②文本=0×10只记2条），
                // 永远重排=快刷事件潮把链饿死。两害相权取中间。
                handler.removeCallbacks(feedSnapshotRunnable!!)
                handler.postDelayed(feedSnapshotRunnable!!, 250)
            }
            return
        }
        feedStartAt = nowMs
        shotCount = 0
        pendingFirstEventAt = nowMs   // v0.6.0 计时锚到本轮滑动（旧口径按"首事件"算，静止10秒再滑会出10000ms假值）
        var chain: Runnable? = null
        chain = Runnable {
            shotCount++
            val stop = processCurrentScreen()
            if (!stop && System.currentTimeMillis() - feedStartAt < 3000) {
                handler.postDelayed(chain!!, 300)
                feedSnapshotRunnable = chain
            } else {
                feedSnapshotRunnable = null
            }
        }
        feedSnapshotRunnable = chain
        handler.post(chain!!)   // 首拍立即：滑动瞬间抓屏外相邻卡（00:10:06·相邻 实证有效）
    }

    /** 快照脚印（环形10步 + logcat双写：环形会被覆盖，logcat 保留完整历史） */
    private fun step(s: String) {
        Log.d(TAG, "步|$s")
        val cur = Prefs.diag(this, "step_hist")
        val parts = if (cur.isBlank()) mutableListOf() else cur.split("⏐").toMutableList()
        parts.add(s.take(45))
        while (parts.size > 10) parts.removeAt(0)
        DiagStore.put(this, "step_hist", parts.joinToString("⏐"))
    }

    /** 处理当前屏；返回 true=快照循环应停（离场/非feed/已记录新视频），false=继续拍（渲染中/同标题） */
    private fun processCurrentScreen(): Boolean {
        lastProcessAt = System.currentTimeMillis()
        // 多窗口合并收集：竖屏流的标题可能不在 rootInActiveWindow，而在层叠的其他B站窗口
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val activePkg = rootInActiveWindow?.packageName?.toString() ?: "null"
        rootInActiveWindow?.let { if (it.packageName?.toString() in BILI_PKGS) roots.add(it) }
        for (w in windows) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: continue
            // v0.4.4：原按 packageName(CharSequence)== 比对不可靠 → 同一窗口可能被加两次、其文本整份重复。改按 windowId
            if (pkg in BILI_PKGS && roots.none { it.windowId == r.windowId }) roots.add(r)
        }
        // 兜底③：事件源节点爬到根（不依赖任何flag，最稳）
        if (roots.isEmpty()) {
            cachedSourceRoot?.let { src ->
                runCatching {
                    src.refresh()
                    var top = src; var d = 0
                    while (d < 40) { val p = top.parent ?: break; top = p; d++ }
                    if (top.packageName?.toString() in BILI_PKGS) roots.add(top)
                }
            }
        }
        DiagStore.put(this, "active_pkg", activePkg)
        nodesVisited = 0
        val pairs = roots.flatMap { r ->
            runCatching { r.refresh() }
            collectTexts(r, maxDepth = 25, budget = 1200)   // v0.5.2 预加载邻页并入，预算放大
        }
        val texts = pairs.map { it.text }

        // 诊断前置：root层/文本层分开记录（空也记录，供定位断点）；v0.4.0 离场检测——焦点已离B站且无B站窗口=残留快照，不记脚印
        DiagStore.put(this, "root_info",
            "root=${roots.size}个/win=${windows.size}/节点=${nodesVisited}/焦点窗=$activePkg")
        DiagStore.put(this, "texts_count", "${texts.size}")
        if (texts.isEmpty()) {
            // v0.5.3：焦点已不在B站（如用户回来看守门员App）就静默停——
            // 即便后台还挂着B站窗口空壳（root=1个/节点=1），也不空转刷脚印
            if (activePkg !in BILI_PKGS) return true
            step("②文本=0")
            return false   // 可能在渲染，继续拍
        }
        DiagStore.put(this, "texts_sample",
            pairs.take(18).joinToString(" | ") { "${it.text.take(14)}@y${it.y}#${it.id.take(10)}" })
        if (Prefs.dumpDebug(this)) {   // v0.4.4：坐标+视图ID全量落 logcat（Prefs 有截断，logcat 不截断）
            Log.d(TAG, "== DUMP ${texts.size} 条文本 ==")
            pairs.take(80).forEachIndexed { i, p -> Log.d(TAG, "[$i] y=${p.y} id=${p.id} :: ${p.text}") }
        }

        // ===== v0.1.8 纯观测版：数据获取层验证。判断层/提示层停用，只记录 =====
        // v0.1.9 场景白名单：竖屏流专属特征（"详情页/沉浸"按钮、"人正在看"）三者见一才提取，
        // 其他页面（历史记录/详情页/个人主页/首页推荐）不在功能边界内，静默忽略
        val idNodes = pairs.map { Extractor.Node(it.text, it.y, it.id) }
        val screenH = resources.displayMetrics.heightPixels
        val inFeed = Extractor.idInFeed(idNodes) || Extractor.inFeed(texts)
        if (!inFeed) {
            step("③非竖屏流(跳过)")
            pendingFirstEventAt = 0L
            return true
        }
        val now = System.currentTimeMillis()
        // 计时截断：>10s 判定为跨页面污染，按10s计
        val captureMs = minOf(now - (if (pendingFirstEventAt > 0) pendingFirstEventAt else now), 10000L)

        // ===== v0.5.2 相邻卡预读：滑动瞬间"下一条"已渲染在屏外，趁它没动先记下来——
        // 即便本轮快刷错过稳定窗口，这条也已经入账 =====
        val adj = Extractor.pickAdjacentCardById(idNodes, screenH)
        if (adj != null && recentTitles[adj.title] == null) {
            recentTitles[adj.title] = now
            Log.d(TAG, "相邻预读 ${captureMs}ms: ${adj.title} @ ${adj.name}")
            FeedLog.add(true, adj.title, adj.name, adj.views, captureMs, "相邻", adj.online)
            DiagStore.noteDirty(this)
            step("◎相邻预读: ${adj.title.take(12)}")
            // v0.6.0 预判：下一条还没上屏就送去Jev——用户看当前视频的整段时间就是判断的富余时间，
            // 结果进缓存；等它上屏时横幅0等待（时间能不能赶上？能，除非连续快刷快过Jev的0.5~2秒）
            ensureJudged(adj.title, adj.name, present = false, signals = adj.signals)
        }

        // ===== v0.5.0 ID直取优先：锚点ID+坐标配对，一次BFS即得完整字段，不再依赖启发式 =====
        val card = Extractor.pickCardById(idNodes, screenH)
        if (card != null) {
            pendingFirstEventAt = 0L
            // v0.6.0 判断层回归：视频上屏先"出场"（黑名单/白名单/Jev缓存），
            // 即便这条是预读过的旧相识（下面去重跳过），横幅也要照常弹
            if (card.title != lastPresented) {
                lastPresented = card.title
                ensureJudged(card.title, card.name, present = true, signals = card.signals)
            }
            val prevAt = recentTitles[card.title]
            if (prevAt != null && now - prevAt < DEDUP_WINDOW_MS) {
                step("⑤重复跳过(距上次${now - prevAt}ms): ${card.title.take(12)}")
                return false
            }
            if (recentTitles.size >= 40) recentTitles.remove(recentTitles.keys.first())
            recentTitles[card.title] = now
            failStreak = 0
            DiagStore.put(this, "last_title", card.title)
            DiagStore.put(this, "last_uploader", card.name.ifBlank { "（未识别）" })
            Log.d(TAG, "ID直取 ${captureMs}ms: ${card.title} @ ${card.name} 在线${card.online.ifBlank { "-" }}")
            FeedLog.add(true, card.title, card.name, card.views, captureMs, "id", card.online)
            DiagStore.noteDirty(this)
            step("✓ID直取: ${card.title.take(14)}")
            return true
        }

        // ===== 旧启发式兜底（锚点ID缺失/未渲染时） =====
        // v0.5.2：启发式只看屏内文本（预加载邻页文本会搅局，ID路径有带位过滤无所谓，启发式没有）
        val visTexts = pairs.filter { it.y in -100..(screenH + 100) }.map { it.text }
        val title = Extractor.pickTitle(visTexts)
        if (title == null) {
            failStreak++
            if (failStreak == 1) {   // 连续失败只记一条，避免刷屏
                FeedLog.add(false, "", "", "", captureMs, if (visTexts.isEmpty()) "无文本" else "无标题")
                DiagStore.noteDirty(this)
            }
            DiagStore.put(this, "last_title", "（未识别出标题）")
            step("④无标题(记失败)")
            pendingFirstEventAt = 0L
            return false   // 标题可能未渲染，继续拍
        }
        pendingFirstEventAt = 0L
        val uploader = Extractor.pickUploader(visTexts, title)
        val views = Extractor.pickViews(visTexts)
        val online = Extractor.pickOnline(visTexts)
        if (title != lastPresented) {
            lastPresented = title
            ensureJudged(title, uploader, present = true, signals = "播放$views")
        }
        val prevAt = recentTitles[title]
        if (prevAt != null && now - prevAt < DEDUP_WINDOW_MS) {
            step("⑤重复跳过(距上次${now - prevAt}ms): ${title.take(12)}")
            return false
        }
        if (recentTitles.size >= 40) recentTitles.remove(recentTitles.keys.first())
        recentTitles[title] = now
        failStreak = 0
        DiagStore.put(this, "last_title", title)
        DiagStore.put(this, "last_uploader", uploader.ifBlank { "（未识别）" })
        Log.d(TAG, "启发式 ${captureMs}ms: $title @ $uploader 在线${online.ifBlank { "-" }}")
        FeedLog.add(true, title, uploader, views, captureMs, "ok", online)
        DiagStore.noteDirty(this)
        step("✓启发式: ${title.take(14)}")
        return true   // 已捕获，快照循环停止
    }

    /**
     * v0.6.0 判断层（回归+预判）：
     * present=true  → 视频正在屏上：黑名单灰横幅→白名单静默→缓存命中即时弹→否则异步Jev（迟到补弹）
     * present=false → 相邻卡预判：只发Jev进缓存不弹横幅，等它上屏时缓存命中=0等待
     */
    private fun ensureJudged(title: String, uploader: String, present: Boolean, signals: String) {
        if (present) {
            presentedAt[title] = System.currentTimeMillis()
            if (presentedAt.size > 40) presentedAt.remove(presentedAt.keys.first())
        }
        if (uploader.isNotBlank() && UpBlacklist.isBlack(this, uploader)) {
            if (present) OverlayBanner.showBlack(this, "⛔ 已拉黑UP主：$uploader", 1500)
            return
        }
        if (uploader.isNotBlank() && uploader in UpBlacklist.whitelist(this)) return
        val key = "$uploader|$title"
        synchronized(judgeCache) { judgeCache[key] }?.let { j ->
            if (present && withinBannerWindow(title)) showJudgment(j, uploader)
            // v0.6.1 预判命中：判断记录回填（"预判✓"标记=滑进来时结果早就备好，0等待）
            FeedLog.setJudgment(title, "预判✓ " + verdictText(j))
            DiagStore.noteDirty(this)
            return
        }
        synchronized(judgingTitles) {
            if (judgingTitles.contains(key)) return
            if (inFlight >= 6) {   // v0.8.7 限流3→6；超出排队补判不再静默丢——评测包"未判定"与快刷横幅被吞同根同源
                synchronized(pendingJudges) {
                    if (pendingJudges.size < 20) pendingJudges.addLast(PendingJudge(title, uploader, signals))
                }
                return
            }
            judgingTitles.add(key)
        }
        inFlight++
        Prefs.incJudged(this)
        // v0.7.0 UP主画像：历史判定并入输入——同UP主反复标题党/营销，下次直接作为特征告诉Jev
        val rich = listOf(signals, upSignal(uploader), matrixSignal(uploader)).filter { it.isNotBlank() }.joinToString(" ")
        scope.launch {
            try {
                val j = JevClient.judge(this@GateKeeperService, title, uploader, rich)
                if (j != null) {
                    synchronized(judgeCache) { judgeCache[key] = j }
                    recordUp(uploader, j)
                    // v0.8.6 迟到补弹闸门放宽：结果回来时只要这条视频4秒内上过屏就弹（快刷时下一条已滑入也补弹上一条）
                    if (present && withinBannerWindow(title)) showJudgment(j, uploader)
                    // v0.6.1 判断记录回填到该视频的记录行（现场判断无"预判✓"标记）
                    FeedLog.setJudgment(title, verdictText(j))
                    DiagStore.noteDirty(this@GateKeeperService)
                } else {
                    DiagStore.put(this@GateKeeperService, "last_judge", "Jev超时/失败→放行")
                }
            } finally {
                inFlight--
                synchronized(judgingTitles) { judgingTitles.remove(key) }
                drainPendingJudges()
            }
        }
    }

    // v0.9.0 宽口径分类词典（旧键并存防升级瞬间缓存不匹配）
    private val TYPE_ZH = mapOf(
        "original_content" to "原创", "farm_repost" to "搬运农场", "engagement_bait" to "互动钓鱼",
        "sales_funnel" to "卖货导流", "normal_news" to "资讯",
        "genuine_content" to "干货", "soft_ad" to "软广", "hard_ad" to "硬广",
        "clickbait" to "标题党", "course_funnel" to "卖课引流")

    /** v0.8.6 补弹窗口：该视频上过屏且未超时即弹（快刷时迟到结果不再被静默丢弃；4s→8s适配Jev最慢返回） */
    private fun withinBannerWindow(title: String): Boolean {
        val t = presentedAt[title] ?: return false
        return System.currentTimeMillis() - t < 8000
    }

    /** v0.8.7 排空补判队列（每单判断结束调一次） */
    private fun drainPendingJudges() {
        while (inFlight < 6) {
            val p = synchronized(pendingJudges) { pendingJudges.removeFirstOrNull() } ?: break
            ensureJudged(p.title, p.uploader, present = false, signals = p.signals)
        }
    }

    /** 判定结果短文本（回填到获取记录行；v0.7.2三态；v0.8.9加置信度；v0.9.1加"存疑"边缘态） */
    private fun verdictText(j: JevClient.Judgment): String {
        val zh = TYPE_ZH[j.contentType] ?: j.contentType
        val conf = " 信${"%.2f".format(j.confidence)}"
        return when {
            j.isMarketing -> "营销号·$zh 味${"%.1f".format(j.mktgLevel)}$conf"
            j.isBorderline -> "存疑·$zh 味${"%.1f".format(j.mktgLevel)}$conf（置信不足放行）"
            j.isClickbait -> "正常·标题党 味${"%.1f".format(j.mktgLevel)}$conf"
            else -> "正常·$zh 味${"%.1f".format(j.mktgLevel)}$conf"
        }
    }

    /** v0.8.9 矩阵号指纹：账号名带内容农场关键词+数字后缀（真机实证"七七小剧场6"=短剧推广号4809视频）——
     *  该信号竖屏流可见（UP名就在卡片上），作为线索交给Jev自行权衡，不硬性标记。
     *  v0.8.10 追加：名字直接带"求关注/侵删"（实证"人生百态求关注"=666视频求关注搬运号）——无需数字后缀 */
    private fun matrixSignal(uploader: String): String {
        if (uploader.isBlank()) return ""
        if (uploader.contains("求关注") || uploader.contains("侵删")) return "账号名疑似搬运求关注号"
        val kw = listOf("剧场", "小剧", "影院", "影视", "短剧", "小说", "文案", "音乐库", "精选", "搬运")
        val endsNum = Regex("\\d+$").containsMatchIn(uploader)
        return if (endsNum && kw.any { uploader.contains(it) }) "账号名疑似矩阵号" else ""
    }

    // ===== v0.7.0 UP主画像：用自己的历史判定喂Jev（零灰升级路径，替代放弃的M2.1正文路线）=====
    private data class UpStats(var total: Int = 0, var marketing: Int = 0, var sumMktg: Double = 0.0,
                               val types: MutableList<String> = mutableListOf())
    private val upHistory = HashMap<String, UpStats>()

    private fun upSignal(uploader: String): String {
        if (uploader.isBlank()) return ""
        val s = upHistory[uploader] ?: return ""
        val zhTypes = s.types.take(3).joinToString("/") { TYPE_ZH[it] ?: it }
        return "UP主历史:${s.total}条(${s.marketing}营销,均味${"%.1f".format(s.sumMktg / s.total)})$zhTypes"
    }

    private fun recordUp(uploader: String, j: JevClient.Judgment) {
        if (uploader.isBlank()) return
        val s = upHistory.getOrPut(uploader) { UpStats() }
        s.total++
        if (j.isMarketing) s.marketing++
        s.sumMktg += j.mktgLevel
        if (s.types.size < 3) s.types.add(j.contentType)
    }

    private fun showJudgment(j: JevClient.Judgment, uploader: String) {
        DiagStore.put(this, "last_judge",
            "${j.contentType} 置信${"%.2f".format(j.confidence)} 营销味${"%.1f".format(j.mktgLevel)} → ${if (j.isMarketing) "营销号" else if (j.isBorderline) "存疑" else "放行"}")
        val zh = TYPE_ZH[j.contentType] ?: j.contentType
        when {
            j.isMarketing -> {
                Prefs.incBlocked(this)
                strikeUploader(uploader)
                OverlayBanner.showMarketing(this,
                    "营销号 · $zh · 味${"%.1f".format(j.mktgLevel)} · 信${"%.2f".format(j.confidence)} · 建议划走", 3000)
            }
            // v0.9.9 存疑独立黄档：农场度超线但置信不足——此前这种混在绿"正常"里，用户刷时看不出存疑
            j.isBorderline -> OverlayBanner.showSuspect(this,
                "存疑 · $zh · 味${"%.1f".format(j.mktgLevel)} · 信${"%.2f".format(j.confidence)} · 证据不足仅提示", 2400)
            else -> {
                // v0.10.2 正常视频横幅受"每条都弹"门槛（测试期默认开；关闭后正常视频不打扰，判定仍进记录）
                if (Prefs.tipGenuine(this))
                    OverlayBanner.showGenuine(this, "正常 营销味${"%.1f".format(j.mktgLevel)} 信${"%.2f".format(j.confidence)}", 1800)
            }
        }
    }

    private fun strikeUploader(uploader: String) {
        if (uploader.isBlank()) return
        if (UpBlacklist.recordStrike(this, uploader)) {
            Log.d(TAG, "UP主 [$uploader] 累计${UpBlacklist.STRIKE_LIMIT}次 -> 拉黑")
            Toast.makeText(this, "已拉黑UP主：$uploader", Toast.LENGTH_SHORT).show()
        }
    }

    /** BFS 收集节点文本（限深限宽防爆）；返回 (文本, y坐标, 视图ID)。
     *  v0.4.1 可见性过滤 + v0.4.4 坐标/ID采集：真机实证预加载邻页的 bounds 可能落在屏内，
     *  需靠 y 分布 + 视图ID 区分"当前页/预加载页"——BFS 顺序会被预加载页打乱，不可作依据。 */
    private var nodesVisited = 0

    private fun collectTexts(node: AccessibilityNodeInfo, maxDepth: Int, budget: Int): List<Txt> {
        val out = mutableListOf<Txt>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(node to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < budget) {
            val (n, d) = queue.removeFirst()
            visited++
            nodesVisited++
            // v0.5.2：不再做可见性过滤——屏外的预加载邻页正是"下一条视频"的信息来源，
            // 故意全收（BFS顺序会被预加载页打乱，谁当前谁相邻一律靠 y 坐标+视图ID判定）
            val top = runCatching {
                val b = android.graphics.Rect()
                n.getBoundsInScreen(b); b.top
            }.getOrDefault(0)
            val vid = runCatching { n.viewIdResourceName?.substringAfterLast("/") ?: "-" }.getOrDefault("-")
            n.text?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(Txt(it, top, vid)) }
            n.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(Txt(it, top, vid)) }
            if (d < maxDepth) {
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to d + 1) }
            }
        }
        return out
    }

    /** rootInActiveWindow 拿不到时，从窗口列表找B站窗口（全屏沉浸容错） */
    private fun findBiliRoot(): AccessibilityNodeInfo? {
        for (w in windows) {
            val r = w.root ?: continue
            if (r.packageName?.toString() in BILI_PKGS) return r
        }
        return null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.setServiceAlive(this, true)
        // v0.9.2 历史持久化：先恢复上次快照的记录再干活——否则内存从零开始，
        // 下一次DiagStore写盘会用空记录把旧历史盖掉（用户：后台退了历史就被清了）
        DiagStore.load(this).records.takeLast(300).forEach { dr ->
            FeedLog.restore(FeedLog.Record(dr.time, dr.ok, dr.title, dr.uploader,
                dr.views, dr.captureMs, dr.source, dr.online, dr.judgment))
        }
        DiagStore.setConnectedAt(this,
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date()))
        // v0.8.8 上岗瞬间立即写一次心跳——旧逻辑首跳要等3秒，用户刚开开关回App会看到假"未连接"
        DiagStore.heartbeat(this)
        Log.d(TAG, "服务已连接（上岗）")
        Toast.makeText(this, "B站守门员已上岗 ✓", Toast.LENGTH_SHORT).show()
        handler.postDelayed(heartbeatR, 3000)
        androidx.core.content.ContextCompat.registerReceiver(
            this, clearReceiver,
            android.content.IntentFilter().apply { addAction(CLEAR_ACTION); addAction(KEY_ACTION) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // v0.8.4 预热Jev管道：首次调用要付DNS/TLS握手+模型冷启动（实测前3-4个视频无提示的真凶），
        // 上岗时就把这笔冷启动花掉，用户开刷时管道已热（Jarvis上岗预热OCR同款思路）
        scope.launch {
            runCatching { JevClient.judgeWithError(this@GateKeeperService, "预热", "预热", "") }
            Log.d(TAG, "Jev管道预热完成")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Prefs.setServiceAlive(this, false)
        DiagStore.markDead(this)
        handler.removeCallbacks(heartbeatR)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private val heartbeatR = object : Runnable {
        override fun run() {
            DiagStore.heartbeat(this@GateKeeperService)
            handler.postDelayed(this, 3000)
        }
    }

    /** v0.5.4：主进程清空按钮 → 广播 → 服务进程清内存环；v0.7.0：同通道更新BYOK密钥 */
    private val clearReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                KEY_ACTION -> {
                    val k = intent.getStringExtra("key")
                    if (!k.isNullOrBlank()) { JevClient.keyOverride = k; Log.d(TAG, "BYOK密钥已更新") }
                }
                CLEAR_ACTION -> {
                    FeedLog.clear(); EventLog.clear()
                    DiagStore.noteDirty(this@GateKeeperService)
                }
            }
        }
    }

    override fun onDestroy() {
        Prefs.setServiceAlive(this, false)
        DiagStore.markDead(this)
        handler.removeCallbacks(heartbeatR)
        runCatching { unregisterReceiver(clearReceiver) }
        scope.cancel(); super.onDestroy()
    }
}
