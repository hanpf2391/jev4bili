package com.biligate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提取引擎回归测试——样本全部来自 2026-09-28 红米K90max 真机诊断的界面文本。
 * 每次修改 Extractor 前先跑本测试，杜绝"改A崩B"。
 */
class ExtractionTest {

    // ===== 真机样本1：外卖大爷（v0.3.2，含隐形字符‎的标准竖屏流）=====
    private val sampleDelivery = listOf(
        "返回", "2000+人正在看", "搜索", "更多", "(0/0)", "UP主头像",
        "xwtx的日记本", "xwtx的日记本", "3333粉丝", "关注",
        "一天跑80单的南京外卖大爷，怒怼平台，炮轰无良外卖博主  ‎2.8万播放",
        "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像",
    )

    @Test fun `样本1_外卖大爷_标题粘连剥离`() {
        assertEquals("一天跑80单的南京外卖大爷，怒怼平台，炮轰无良外卖博主", Extractor.pickTitle(sampleDelivery))
    }

    @Test fun `样本1_外卖大爷_UP主相邻重复`() {
        val title = Extractor.pickTitle(sampleDelivery)!!
        assertEquals("xwtx的日记本", Extractor.pickUploader(sampleDelivery, title))
    }

    @Test fun `样本1_外卖大爷_播放量与场景`() {
        assertEquals("2.8万", Extractor.pickViews(sampleDelivery))
        assertTrue(Extractor.inFeed(sampleDelivery))
    }

    // ===== 真机样本2：万万没想到（v0.1.9，带话题词"萬州曼哈頓"干扰）=====
    private val sampleWanwan = listOf(
        "返回", "5人正在看", "搜索", "更多", "(0/0)", "UP主头像",
        "按尼康快门", "按尼康快门", "199粉丝", "关注",
        "万万没想到  ‎35.5万播放", "含虚构演绎内容", "萬州曼哈頓",
        "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像", "香蕉猫日记",
    )

    @Test fun `样本2_万万没想到_粘连优先于话题词`() {
        assertEquals("万万没想到", Extractor.pickTitle(sampleWanwan))
        assertEquals("按尼康快门", Extractor.pickUploader(sampleWanwan, "万万没想到"))
    }

    // ===== 真机样本3：分集条干扰（v0.3.0，海报视频合集）=====
    private val sampleEpisode = listOf(
        "返回", "7人正在看", "搜索", "更多", "(0/0)", "UP主头像",
        "海报视频", "海报视频", "2.3万粉丝", "关注",
        "分集 · 第1集     疑似CSTG年会彩排流出！！！！  ‎2.8万播放",   // 分集条（应跳过）
        "老人拾荒21年惊获42万养老金  ‎2.8万播放",                     // 真标题
        "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像", "香蕉猫日记",
    )

    @Test fun `样本3_分集条_跳过选集条取真标题`() {
        assertEquals("老人拾荒21年惊获42万养老金", Extractor.pickTitle(sampleEpisode))
        assertEquals("海报视频", Extractor.pickUploader(sampleEpisode, "老人拾荒21年惊获42万养老金"))
    }

    // ===== 真机样本4：我就是纯爽（v0.3.0，"全屏观看"按钮当UP主的错位案）=====
    private val samplePureCool = listOf(
        "返回", "xx人正在看", "搜索", "更多", "(0/0)", "UP主头像", "全屏观看",
        "我有一张绝版的海报", "我有一张绝版的海报", "12.5万粉丝", "关注",
        "我就是纯爽  ‎17.5万播放",
        "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸",
    )

    @Test fun `样本4_纯爽_全屏观看不得当选UP主`() {
        val title = Extractor.pickTitle(samplePureCool)!!
        assertEquals("我就是纯爽", title)
        assertEquals("我有一张绝版的海报", Extractor.pickUploader(samplePureCool, title))
    }

    // ===== 真机样本5：特厨隋坡（v0.1.9，317.6万粉大涨粉样本）=====
    private val sampleChef = listOf(
        "返回", "124人正在看", "搜索", "更多", "(0/0)", "UP主头像",
        "特厨隋坡", "特厨隋坡", "317.6万粉丝", "关注",
        "特厨探店|在内蒙吃肉还能吃到爽？！—四子王焖把炖‎166.1万播放",
        "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像", "至上无垠科普",
    )

    @Test fun `样本5_特厨隋坡_长标题完整保留`() {
        assertEquals("特厨探店|在内蒙吃肉还能吃到爽？！—四子王焖把炖", Extractor.pickTitle(sampleChef))
        assertEquals("特厨隋坡", Extractor.pickUploader(sampleChef, "特厨探店|在内蒙吃肉还能吃到爽？！—四子王焖把炖"))
    }

    // ===== 真机样本6：富二代（v0.3.2，"关注"夹在UP主与粉丝数之间）=====
    private val sampleRich2 = listOf(
        "返回", "401人正在看", "搜索", "更多", "(0/0)", "UP主头像",
        "贤宝宝Baby", "贤宝宝Baby", "关注", "601.7万粉丝",
        "我找了一群人把我包装成富二代.....  155.2万播放",
        "点赞", "评论", "投币", "收藏", "分享", "沉浸", "UP主头像", "王者知安", "王者知安", "还是我自己来吧",
    )

    @Test fun `样本6_富二代_关注按钮挡粉丝前缀时相邻重复兜底`() {
        val title = Extractor.pickTitle(sampleRich2)!!
        assertEquals("我找了一群人把我包装成富二代.....", title)
        assertEquals("贤宝宝Baby", Extractor.pickUploader(sampleRich2, title))
    }

    // ===== 真机样本7：话题标签尾巴（v0.4.2 模拟器实测："结账趣事 #短视频变现"）=====
    @Test fun `样本7_话题标签_剥离尾部hashtag`() {
        val texts = listOf("返回", "88人正在看", "月下王", "月下王", "3.2万粉丝", "关注",
            "结账趣事 #短视频变现  ‎8.8万播放", "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸")
        assertEquals("结账趣事", Extractor.pickTitle(texts))
    }

    // ===== 真机样本8：AI标识角标粘连播放数（v0.4.2 实测：《含AI生成内容》案）=====
    @Test fun `样本8_AI标识_不得当选标题`() {
        val texts = listOf("返回", "66人正在看", "橙某", "橙某", "1.8万粉丝", "关注",
            "含AI生成内容  ‎4.2万播放", "真实的标题在这里  ‎4.2万播放", "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸")
        assertEquals("真实的标题在这里", Extractor.pickTitle(texts))
    }

    // ===== 真机样本9：预加载邻页UP主抢署名（v0.4.4，用户 2026-09-28 报告原文样本）=====
    // "22:38:32 ✓ 贪吃蛇小丫头《你们眼中有钱人的劳斯莱斯会不会是这样的呢》"——标题对、UP主错
    private val samplePreload = listOf(
        "返回", "53人正在看", "搜索", "更多", "UP主头像",
        "修车大全", "修车大全", "53万粉丝", "关注",
        "你们眼中有钱人的劳斯莱斯会不会是这样的呢  ‎41.4万播放",
        "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像",
        "贪吃蛇小丫头", "贪吃蛇小丫头", "12.3万粉丝", "关注",          // ← 预加载的下一条
    )

    @Test fun `样本9_预加载邻页_不得抢当前页UP主`() {
        val title = Extractor.pickTitle(samplePreload)!!
        assertEquals("你们眼中有钱人的劳斯莱斯会不会是这样的呢", title)
        assertEquals("修车大全", Extractor.pickUploader(samplePreload, title))
    }

    @Test fun `样本9_单卡片_邻页不存在时行为不变`() {
        val alone = samplePreload.take(17)   // 砍掉预加载卡
        val title = Extractor.pickTitle(alone)!!
        assertEquals("修车大全", Extractor.pickUploader(alone, title))
    }

    // ===== v0.4.4：在线人数（当前在播视频独有的信号，用于区分"混入的两张卡片谁在播"）=====
    @Test fun `在线人数_带加号与纯数字与万`() {
        assertEquals("2000+", Extractor.pickOnline(listOf("返回", "2000+人正在看", "搜索")))
        assertEquals("53", Extractor.pickOnline(listOf("返回", "53人正在看", "详情页")))
        assertEquals("2.8万", Extractor.pickOnline(listOf("2.8万人正在看")))
        assertEquals("", Extractor.pickOnline(listOf("详情页", "沉浸", "41.4万播放")))   // 无在线数=预加载页特征
    }

    @Test fun `在线人数_真机样本逐条核对`() {
        assertEquals("2000+", Extractor.pickOnline(sampleDelivery))
        assertEquals("5", Extractor.pickOnline(sampleWanwan))
        assertEquals("124", Extractor.pickOnline(sampleChef))
        assertEquals("", Extractor.pickOnline(listOf("xx人正在看")))   // 未渲染占位
    }

    // ===== v0.5.0：视图ID直取（锚点ID=title/name/fans/story_ctrl_online，2026-09-28小米13活体树dump实测）=====
    private fun n(text: String, y: Int, id: String) = Extractor.Node(text, y, id)

    @Test fun `ID直取_单卡完整字段`() {
        val nodes = listOf(
            n("53人正在看", 200, "story_ctrl_online"),
            n("修车大全", 1870, "name"),
            n("53万粉丝", 1933, "fans"),
            n("你们眼中有钱人的劳斯莱斯会不会是这样的呢  ‎41.4万播放", 2050, "title"),
            n("1.4万", 1331, "like_text"),
        )
        val c = Extractor.pickCardById(nodes, 2400)!!
        assertEquals("你们眼中有钱人的劳斯莱斯会不会是这样的呢", c.title)
        assertEquals("修车大全", c.name)
        assertEquals("53万粉丝", c.fans)
        assertEquals("53", c.online)
        assertEquals("41.4万", c.views)
    }

    @Test fun `ID直取_活体真样本_雍正王朝合集`() {
        // 2026-09-28 小米13 活体树 dump 原文（bounds 换算 y 中心）
        val nodes = listOf(
            n("11人正在看", 163, "story_ctrl_online"),
            n("15:25 / 19:54", 1346, "story_ctrl_pause_seek"),
            n("貔柴", 1872, "name"),
            n("100.7万粉丝", 1933, "fans"),
            n("合集 · 第17集     【雍正王朝17】八阿哥教教大家什么叫秉公办案  ‎101.1万播放 ", 2055, "title"),
            n("作者声明：个人观点，仅供参考", 2143, "story_ctrl_argue"),
            n("1.4万", 1331, "like_text"),
        )
        val c = Extractor.pickCardById(nodes, 2400)!!
        assertEquals("【雍正王朝17】八阿哥教教大家什么叫秉公办案", c.title)
        assertEquals("貔柴", c.name)
        assertEquals("100.7万粉丝", c.fans)
        assertEquals("11", c.online)
        assertEquals("101.1万", c.views)
    }

    @Test fun `ID直取_双卡过渡_新卡在带内旧卡在顶缘_取新卡且邻页不抢`() {
        val nodes = listOf(
            n("旧视频标题  10万播放", 300, "title"),     // 旧卡正滑出屏幕（顶缘，已在带外）
            n("旧UP主", 250, "name"),
            n("新视频标题  20万播放", 1700, "title"),    // 新卡正滑入
            n("新UP主", 1650, "name"),
            n("1.2万粉丝", 1730, "fans"),
        )
        val c = Extractor.pickCardById(nodes, 2400)!!
        assertEquals("新视频标题", c.title)
        assertEquals("新UP主", c.name)                   // 邻页UP主不抢当前页
        assertEquals("20万", c.views)
    }

    @Test fun `ID直取_标题不粘连播放数_仍取原文`() {
        val nodes = listOf(
            n("123人正在看", 200, "story_ctrl_online"),
            n("某UP", 1870, "name"),
            n("纯标题没有播放数", 2050, "title"),
        )
        val c = Extractor.pickCardById(nodes, 2400)!!
        assertEquals("纯标题没有播放数", c.title)
        assertEquals("某UP", c.name)
        assertEquals("", c.views)
    }

    @Test fun `ID直取_无锚点_回退null`() {
        assertNull(Extractor.pickCardById(
            listOf(n("返回", 100, "abc"), n("搜索", 200, "xyz")), 2400))
    }

    @Test fun `场景判定ID版_竖屏流必有story锚点`() {
        assertTrue(Extractor.idInFeed(listOf(n("53人正在看", 200, "story_ctrl_online"))))
        assertTrue(Extractor.idInFeed(listOf(n("", 0, "story_view_pager"))))
        assertFalse(Extractor.idInFeed(listOf(n("返回", 100, "abc"), n("搜索", 200, "xyz"))))
    }

    // ===== v0.5.2：相邻卡预读（B站把下一条渲染在屏外，滑动瞬间就能拿到）=====
    @Test fun `相邻预读_下方滑入卡`() {
        val nodes = listOf(
            n("当前视频标题  30万播放", 2050, "title"),
            n("当前UP", 1870, "name"),
            n("下一条视频标题  15万播放", 2900, "title"),   // 屏外下方（预加载）
            n("下一条UP", 2720, "name"),
            n("5万粉丝", 2780, "fans"),
        )
        val c = Extractor.pickAdjacentCardById(nodes, 2400)!!
        assertEquals("下一条视频标题", c.title)
        assertEquals("下一条UP", c.name)
        assertEquals("15万", c.views)
    }

    @Test fun `相邻预读_上方滑入卡_回滑方向`() {
        val nodes = listOf(
            n("当前视频标题  30万播放", 2050, "title"),
            n("上一条视频标题  8万播放", -200, "title"),   // 屏外上方（回滑时的预加载）
            n("上一条UP", -230, "name"),
        )
        val c = Extractor.pickAdjacentCardById(nodes, 2400)!!
        assertEquals("上一条视频标题", c.title)
        assertEquals("上一条UP", c.name)
    }

    @Test fun `相邻预读_取离屏最近那张`() {
        val nodes = listOf(
            n("上方卡  1万播放", -200, "title"),
            n("下方卡  2万播放", 2900, "title"),   // 距离：上200 vs 下500 → 取上方
        )
        val c = Extractor.pickAdjacentCardById(nodes, 2400)!!
        assertEquals("上方卡", c.title)
    }

    @Test fun `相邻预读_远卡排除与无邻卡`() {
        assertNull(Extractor.pickAdjacentCardById(
            listOf(n("太远的卡  1万播放", 7000, "title")), 2400))   // 2.3H之外=回收页不碰
        assertNull(Extractor.pickAdjacentCardById(
            listOf(n("当前卡  1万播放", 2050, "title")), 2400))     // 全在屏内=无邻卡
    }

    // ===== v0.6.2：互动数据信号（被动可得的数字，送Jev富化）=====
    @Test fun `ID直取_互动数据信号`() {
        val nodes = listOf(
            n("11人正在看", 163, "story_ctrl_online"),
            n("15:25 / 19:54", 1346, "story_ctrl_pause_seek"),
            n("貔柴", 1872, "name"),
            n("100.7万粉丝", 1933, "fans"),
            n("标题啦  1.2万播放", 2055, "title"),
            n("1.4万", 1331, "like_text"),
            n("417", 1515, "comment_text"),
            n("含AI生成", 2100, "story_ctrl_argue"),
        )
        val c = Extractor.pickCardById(nodes, 2400)!!
        assertTrue(c.signals.contains("赞1.4万"))
        assertTrue(c.signals.contains("评417"))
        assertTrue(c.signals.contains("时长19:54"))
        assertTrue(c.signals.contains("标注:含AI生成"))
    }

    // ===== v0.7.2：营销号阈值（标题党≠营销号，真机"味0.1被标营销号"误伤教训）=====
    @Test fun `阈值_标题党低味不再是营销号`() {
        val j = JevClient.Judgment("engagement_bait", 0.95, 0.1, 0.8)   // v0.9.0 宽口径分类
        assertFalse(j.isMarketing)
        assertTrue(j.isClickbait)
    }

    @Test fun `阈值_硬广或高味仍是营销号`() {
        assertTrue(JevClient.Judgment("sales_funnel", 0.9, 3.2, 0.2).isMarketing)
        assertTrue(JevClient.Judgment("original_content", 0.9, 2.6, 0.0, 0.9).isMarketing)   // 农场度≥2.5+农场置信够
        assertFalse(JevClient.Judgment("sales_funnel", 0.5, 3.0, 0.3).isMarketing)  // 分类置信不足且非极端农场
    }

    @Test fun `阈值_极端农场分不受分类置信约束_EdgeAITech案`() {
        // 真机案：中配搬运农场度3.6、分类置信仅0.37 → 旧规则被一票否决放行（bug）
        val j = JevClient.Judgment("farm_repost", 0.37, 3.6, 0.5)
        assertTrue(j.isMarketing)
        // 2.5~3.5之间仍需置信≥0.8（宁漏勿误底线）
        val j2 = JevClient.Judgment("farm_repost", 0.37, 2.6, 0.5, 0.4)
        assertFalse(j2.isMarketing)
        assertTrue(j2.isBorderline)   // 但要标"存疑"供人工复核
    }

    // ===== v0.9.2：历史持久化（重启恢复）=====
    @Test fun `FeedLog_重启恢复_记录与判定都在`() {
        FeedLog.clear()
        FeedLog.add(true, "视频A", "UP甲", "1.2万", 500, "id", "3")
        FeedLog.setJudgment("视频A", "营销号·搬运农场 味2.6 信0.81")
        val snap = FeedLog.raw()
        // 模拟重启：清空后按快照恢复
        FeedLog.clear()
        snap.forEach { FeedLog.restore(it) }
        val restored = FeedLog.raw().last()
        assertEquals("视频A", restored.title)
        assertEquals("营销号·搬运农场 味2.6 信0.81", restored.judgment)   // 判定回填也随快照存活
    }

    // ===== 场景白名单：非竖屏流页面 =====
    @Test fun `场景_历史记录页_不提取`() {
        val historyPage = listOf("历史记录", "历史记录", "是我孤陋寡闻了？还是这是什么最新的黑科技？长安汽车", "INSTAXH", "2026年9月28日 17:51")
        assertFalse(Extractor.inFeed(historyPage))
    }

    @Test fun `场景_加载占位文本_不作标题`() {
        val loading = listOf("返回", "正在玩命加载数据...", "详情页", "沉浸")
        assertTrue(Extractor.inFeed(loading))   // 是竖屏流
        // 加载占位含"玩命加载"黑词，不当选标题（兜底里被滤除）
        val t = Extractor.pickTitle(loading)
        assertTrue(t == null || !t.contains("玩命"))
    }

    @Test fun `场景_免责声明_不作标题`() {
        val withDisclaimer = sampleDelivery + "个人观点，仅供参考"
        val t = Extractor.pickTitle(withDisclaimer)!!
        assertTrue(!t.contains("仅供参考"))
    }
}
