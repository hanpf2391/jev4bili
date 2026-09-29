package com.biligate.app

/**
 * 提取引擎（纯函数，无 Android 依赖，可 JVM 单测直接验证）。
 * 规则全部按 2026-09-28 红米K90max 真机样本校准，回归样本见 ExtractionTest.kt。
 */
object Extractor {

    /** 界面按钮/占位/免责等噪声词——命中即不可作为标题/UP主候选。
     *  注意：不含单字"万"——含"万"的合法标题太多（万万没想到/42万养老金），纯数字串由数字正则把守 */
    val UI_WORDS = listOf(
        "点赞", "投币", "收藏", "分享", "弹幕", "评论", "关注", "粉丝", "播放",
        "不感兴趣", "更多", "举报", "刷新", "推荐", "搜索", "首页", "动态", "热门", "频道",
        "直播", "我的", "会员", "消息", "通知", "下载", "缓存", "稍再看", "已关注",
        "次播放", "人点赞", "昨天", "前天", "小时前", "分钟前",
        "返回", "头像", "详情页", "沉浸", "正在看", "继续看", "合集", "选集", "自动连播",
        "竖屏", "小窗", "仅供参考", "玩命加载", "历史记录", "个人主页", "简介", "全屏", "分集", "含AI生成", "含虚构演绎",
    )

    /** 剥离标题尾部的"#话题"标签（真机样本："结账趣事 #短视频变现"、"…#炫益"） */
    private fun stripTopicTag(t: String): String = t.replace(Regex("\\s*#\\S+$"), "").trim()

    /**
     * 标题提取：
     * 强模式：竖屏流标题与播放数粘连 —— "失败再多次，也要有一颗敢于旋转的心‎ 11万播放"
     *   （‎ 为隐形字符U+200E；剥掉尾部 "NN[万亿]?播放" 即标题）
     *   "分集/第N集"选集条也粘连播放数，命中时跳过继续找（真机样本：疑似CSTG年会彩排案）
     * 兜底：非UI词最长文本，先排除UP主候选（相邻重复/粉丝数前缀）——防UP主名当选标题
     */
    fun pickTitle(texts: List<String>): String? {
        val glued = Regex("^(.{4,80}?)\\s*‎?\\s*[0-9.]+[万亿]?播放$")
        for (t in texts) {
            val m = glued.find(t.trim())
            if (m != null) {
                val title = m.groupValues[1].trim()
                val isEpisode = title.contains("分集") ||
                    Regex("^第[0-9一二三四五六七八九十]+集").containsMatchIn(title)
                if (!isEpisode && UI_WORDS.none { title.contains(it) }) return stripTopicTag(title).take(80)
            }
        }
        // 兜底前先收集UP主候选（相邻重复 / 粉丝数前缀），标题候选里排除——防UP主名被当标题
        val uploaderCands = uploaderCandidates(texts)
        return texts.filter { it.length in 6..60 }
            .filter { t -> UI_WORDS.none { t.contains(it) } }
            .filter { it !in uploaderCands }
            .filter { !it.matches(Regex("^[0-9.,:·%w亿万/\\-— ]+$")) }
            .maxByOrNull { it.length }
            ?.let { stripTopicTag(it).take(80) }
    }

    /**
     * UP主提取（真机校准）：
     * 强模式①：同一短文本相邻出现两次（样本：UP主名渲染两遍）
     * 强模式②：紧邻 "7.3万粉丝" 样式文本之前的一个短文本
     * 兜底：短文本、非UI词、非标题子串
     *
     * v0.4.4 邻页隔离：卡片信息栏的树顺序固定为 [头像│UP主名│粉丝数│关注│标题+播放数│互动按钮]，
     * 所以**本条的UP主必然排在标题之前**；而预加载的下一条视频整块排在标题之后。
     * 真机实证（2026-09-28 用户报告）：邻页UP主抢走当前页署名——《劳斯莱斯》被记成"贪吃蛇小丫头"。
     * 因此只在"标题之前"的文本里找候选；标题前确实找不到时才退回全表（保底不丢记录）。
     */
    fun pickUploader(texts: List<String>, title: String): String {
        fun okName(t: String) = t.length in 2..20 &&
            UI_WORDS.none { t.contains(it) } &&
            !t.matches(Regex("^[0-9.,:·%w亿万/\\-— ：:#‎]+$")) &&
            t != title && !title.contains(t)
        val cut = texts.indexOfFirst { it.contains(title) }
        val head = if (cut > 0) texts.subList(0, cut) else texts
        uploaderCandidates(head).forEach { if (okName(it)) return it }
        head.firstOrNull { okName(it) }?.let { return it }
        if (cut > 0) {          // 标题前没找到 → 退回全表
            uploaderCandidates(texts).forEach { if (okName(it)) return it }
            texts.firstOrNull { okName(it) }?.let { return it }
        }
        return ""
    }

    /** UP主候选集：相邻重复 + 粉丝数前缀邻文本 */
    private fun uploaderCandidates(texts: List<String>): Set<String> {
        val cands = mutableSetOf<String>()
        for (i in 0 until texts.size - 1) {
            val t = texts[i]
            if (t.length in 2..20 && t == texts[i + 1]) cands.add(t)
        }
        val fanIdx = texts.indexOfFirst { it.matches(Regex("^[0-9.]+[万亿]?粉丝$")) }
        if (fanIdx > 0) texts[fanIdx - 1].let { if (it.length in 2..20) cands.add(it) }
        return cands
    }

    /** 标题净化：剥掉界面截断的尾部省略号（模拟器实测样本："…有菜有肉还有饭，..."） */
    private fun cleanTitle(t: String): String = t.trimEnd('.', '…', '。', ' ')

    /** 播放量提取：从粘连文本剥 "39.3万播放" 的数字部分 */
    fun pickViews(texts: List<String>): String =
        texts.firstNotNullOfOrNull { t -> Regex("([0-9.]+[万亿]?)播放").find(t)?.groupValues?.get(1) } ?: ""

    /** 在线人数提取（v0.4.4）：真机样本 "53人正在看" / "2000+人正在看"。
     *  用途：这是**当前在播视频独有**的信号（预加载的邻页只显示历史播放数，无在线人数），
     *  用于区分"文本混入的两张卡片里谁在播"——先落档观测，结构性修复依赖它。 */
    fun pickOnline(texts: List<String>): String =
        texts.firstNotNullOfOrNull { t ->
            Regex("([0-9.]+[万亿]?\\+?)人正在看").find(t)?.groupValues?.get(1)
        } ?: ""

    /** 竖屏流场景白名单：专属特征（"详情页/沉浸"按钮、"人正在看"）三者见一才提取 */
    fun inFeed(texts: List<String>): Boolean =
        texts.any { it == "详情页" || it == "沉浸" || it.contains("人正在看") }

    // ===== v0.5.0 视图ID直取 =====

    /** ID直取的输入节点（服务侧把无障碍节点拍平后传入） */
    data class Node(val text: String, val y: Int, val id: String)

    /** 一张卡片的关键字段 */
    data class Card(val title: String, val name: String, val fans: String, val online: String, val views: String,
                    val signals: String = "")

    /**
     * 视图ID直取（竖屏流卡片专用）。
     * 锚点ID（2026-09-28 小米13活体树 dump 实测——反编译 resources.arsc 里的是卡片库ID，
     * 流内信息栏实际挂的是这几个，两套名字不同！）：
     *   title              标题（粘连"41.4万播放"，左下角 y≈0.86H；合集视频带"合集 · 第17集"前缀）
     *   name               UP主名（title 上方）
     *   fans               粉丝数
     *   story_ctrl_online  在线人数（屏顶）
     * 配对规则：先按 y 落在屏内 [0.35H, 0.97H] 挑"当前卡片"的 title（离标题稳定位 0.85H 最近者），
     * 其余字段各自取"离该 title 最近"的同 ID 节点——邻页字段即使混入，y 距离天然隔开，杂交记录结构性杜绝。
     * 找不到任何 id=title 节点 → 返回 null，调用方回退旧启发式。
     */
    fun pickCardById(nodes: List<Node>, screenH: Int): Card? {
        // v0.8.7 过滤"(0/0)"占位标题（真机实测：未渲染完成的title节点内容是"(0/0)"进度占位）
        val titles = nodes.filter { it.id == "title" && it.text.isNotBlank() }
            .filter { !it.text.trim().matches(Regex("^\\(\\d+/\\d+\\)$")) }
        if (titles.isEmpty()) return null
        val inBand = titles.filter { it.y in (screenH * 0.35).toInt()..(screenH * 0.97).toInt() }
        val anchor = (inBand.minByOrNull { kotlin.math.abs(it.y - (screenH * 0.85).toInt()) }
            ?: titles.maxByOrNull { it.y }) ?: return null
        return cardFromAnchor(nodes, anchor)
    }

    /** v0.5.2 相邻卡预读：B站把下一条（和上一条）渲染在屏幕外等着（ViewPager 预加载），
     *  滑动瞬间就能读到"正在来的"那张卡——不用等渲染稳定，快刷不漏的关键。
     *  只认贴屏预加载页（距屏≤1.3H），更远的回收页不碰；取离屏幕最近的那张。 */
    fun pickAdjacentCardById(nodes: List<Node>, screenH: Int): Card? {
        val titles = nodes.filter { it.id == "title" && it.text.isNotBlank() }
            .filter { it.y < 0 || it.y > screenH }                          // 屏外
            .filter { it.y >= -screenH * 1.3 && it.y <= screenH * 2.3 }     // 且贴着屏的预加载页
        if (titles.isEmpty()) return null
        val anchor = titles.minByOrNull {
            kotlin.math.min(kotlin.math.abs(it.y), kotlin.math.abs(it.y - screenH))
        } ?: return null
        return cardFromAnchor(nodes, anchor)
    }

    /** 以一张 title 节点为锚，把同卡片的 name/fans/online/views 按 y 邻近配对出来 */
    private fun cardFromAnchor(nodes: List<Node>, anchor: Node): Card? {
        fun near(id: String): Node? =
            nodes.filter { it.id == id && it.text.isNotBlank() }
                .minByOrNull { kotlin.math.abs(it.y - anchor.y) }
        var raw = anchor.text.trim()
        // 合集/分集前缀（活体样本："合集 · 第17集     【雍正王朝17】… 101.1万播放"）
        raw = raw.replace(Regex("^(合集|分集) · 第[0-9一二三四五六七八九十]+集\\s*"), "")
        val glued = Regex("^(.{4,80}?)\\s*‎?\\s*[0-9.]+[万亿]?播放$").find(raw)
        val title = if (glued != null) glued.groupValues[1].trim() else raw
        if (title.isBlank()) return null
        val views = glued?.let { Regex("([0-9.]+[万亿]?)播放").find(it.value)?.groupValues?.get(1) } ?: ""
        // v0.6.2 互动数据富化：这些数字人肉判断也在看（高播放低互动=刷量嫌疑/时长与内容不符=引流嫌疑），
        // 全部被动可得（同一棵树的 id 节点），不需要用户做任何额外动作
        fun nearText(id: String): String =
            nodes.filter { it.id == id && it.text.isNotBlank() }
                .minByOrNull { kotlin.math.abs(it.y - anchor.y) }?.text ?: ""
        val duration = nodes.filter { it.id == "story_ctrl_pause_seek" && it.text.contains("/") }
            .minByOrNull { kotlin.math.abs(it.y - anchor.y) }?.text?.trim()?.substringAfterLast("/")?.trim() ?: ""
        val tags = nodes.filter {
            kotlin.math.abs(it.y - anchor.y) < 700 &&
                (it.text.contains("含AI生成") || it.text.contains("含虚构演绎") || it.text.contains("作者声明"))
        }.map { it.text.trim().take(12) }.distinct().joinToString("/")
        val parts = mutableListOf<String>()
        val like = nearText("like_text"); if (like.isNotBlank()) parts.add("赞$like")
        val comment = nearText("comment_text"); if (comment.isNotBlank()) parts.add("评$comment")
        val coin = nearText("coin_text"); if (coin.isNotBlank()) parts.add("投$coin")
        val fav = nearText("favorite_text"); if (fav.isNotBlank()) parts.add("藏$fav")
        val share = nearText("share_text"); if (share.isNotBlank()) parts.add("享$share")
        if (duration.isNotBlank()) parts.add("时长$duration")
        if (tags.isNotBlank()) parts.add("标注:$tags")
        return Card(
            title = stripTopicTag(title).take(80),
            name = near("name")?.text ?: "",
            fans = near("fans")?.text ?: "",
            online = near("story_ctrl_online")?.text?.let { pickOnline(listOf(it)) } ?: "",
            views = views,
            signals = parts.joinToString(" "),
        )
    }

    /** 场景判定ID版：竖屏流必有 story_ctrl_online / story_view_pager 节点（story_ 命名空间，其他页面无） */
    fun idInFeed(nodes: List<Node>): Boolean =
        nodes.any { it.id == "story_ctrl_online" || it.id == "story_view_pager" }
}
