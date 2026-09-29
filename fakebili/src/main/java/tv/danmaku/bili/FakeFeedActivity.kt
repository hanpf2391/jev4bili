package tv.danmaku.bili

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 仿真靶场：界面文本结构逐节点复刻红米真机上B站"看看"竖屏流样本，
 * 供 BiliGate 守门员在模拟器上做端到端自测（事件→提取→FeedLog记录）。
 * 样本与 ExtractionTest.kt 回归集同源。
 */
class FakeFeedActivity : Activity() {

    private val samples = listOf(
        listOf("返回", "2000+人正在看", "搜索", "更多", "(0/0)", "UP主头像",
            "xwtx的日记本", "xwtx的日记本", "3333粉丝", "关注",
            "一天跑80单的南京外卖大爷，怒怼平台，炮轰无良外卖博主  ‎2.8万播放",
            "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像"),
        listOf("返回", "5人正在看", "搜索", "更多", "(0/0)", "UP主头像",
            "按尼康快门", "按尼康快门", "199粉丝", "关注",
            "万万没想到  ‎35.5万播放", "含虚构演绎内容", "萬州曼哈頓",
            "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像", "香蕉猫日记"),
        listOf("返回", "7人正在看", "搜索", "更多", "(0/0)", "UP主头像",
            "海报视频", "海报视频", "2.3万粉丝", "关注",
            "分集 · 第1集     疑似CSTG年会彩排流出！！！！  ‎2.8万播放",
            "老人拾荒21年惊获42万养老金  ‎2.8万播放",
            "点赞", "评论", "投币", "收藏", "分享", "详情页", "沉浸", "UP主头像", "香蕉猫日记"),
        listOf("返回", "401人正在看", "搜索", "更多", "(0/0)", "UP主头像",
            "贤宝宝Baby", "贤宝宝Baby", "关注", "601.7万粉丝",
            "我找了一群人把我包装成富二代.....  155.2万播放",
            "点赞", "评论", "投币", "收藏", "分享", "沉浸", "UP主头像", "王者知安", "王者知安", "还是我自己来吧"),
    )

    private var idx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 160, 40, 40)
        }
        // 逐文本节点渲染（仿真B站界面的无障碍节点结构）
        samples[idx].forEach { s ->
            texts.addView(TextView(this).apply {
                text = s
                textSize = if (s.contains("播放")) 18f else 13f
                setTextColor(if (s.contains("播放")) Color.WHITE else Color.GRAY)
                setPadding(0, 10, 0, 10)
            })
        }
        // 仿真 ViewPager 预加载：把"相邻视频"的标题/UP主渲染在屏幕外（bounds 出屏），
        // 验证 v0.4.1 可见性过滤能否排除邻视频文本（历史全部错位案的根因）
        val neighbor = (idx + 1) % samples.size
        val glued = samples[neighbor].firstOrNull { it.contains("播放") } ?: "邻视频标题  ‎99.9万播放"
        texts.addView(TextView(this).apply {
            text = glued
            translationY = 5000f   // 屏幕外
            setTextColor(Color.DKGRAY)
        })
        samples[neighbor].firstOrNull { it.length in 2..12 }?.let {
            texts.addView(TextView(this).apply { text = it; translationY = 5000f })
        }
        // 按钮固定屏幕底部（不随内容滚动，坐标恒定便于 adb 自动点击）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA1E3A5F.toInt())
        }
        bar.addView(Button(this).apply {
            text = "下一个视频（模拟翻页）"
            setOnClickListener { idx = (idx + 1) % samples.size; render() }
        })
        bar.addView(Button(this).apply {
            text = "重播当前（同标题去重验证）"
            setOnClickListener { render() }
        })
        bar.addView(TextView(this).apply {
            text = "样本 ${idx + 1}/${samples.size}"
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
        })
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(android.widget.ScrollView(this@FakeFeedActivity).apply { addView(texts) },
                android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bar, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM))
        }
        setContentView(root)
    }
}
