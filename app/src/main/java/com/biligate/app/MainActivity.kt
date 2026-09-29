package com.biligate.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var svcState: TextView
    private lateinit var stats: TextView
    private lateinit var whiteList: TextView
    private lateinit var blackList: TextView
    private lateinit var etJevKey: TextInputEditText
    private lateinit var rowA11y: TextView
    private lateinit var rowConn: TextView
    private lateinit var rowKey: TextView
    private lateinit var btnOpenA11y: MaterialButton
    private lateinit var btnGotoKey: MaterialButton

    /** v0.7.0 连通测试专用协程（一次性调用，无生命周期绑定） */
    private val testScope = CoroutineScope(Dispatchers.IO)

    private val diagHandler = Handler(Looper.getMainLooper())
    /** v0.9.4 记录页筛选状态：all/block/sus/pass */
    private var recordFilter = "all"
    private val diagRefresher = object : Runnable {
        override fun run() {
            refresh()   // v0.8.8 就绪清单每秒活刷——旧逻辑只在onResume刷一次，开着App等状态变化时清单是死的
            refreshHomeStats()
            // v0.9.7 记录页=设计稿单条卡（v0.9.4文本流水退役）；v0.10.2 运行自检卡已删（诊断只留"复制"）
            renderRecordsUI(DiagStore.load(this@MainActivity))
            diagHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
    }

    /** v0.8.1 崩溃解剖器：主进程任何未捕获异常 → 堆栈落盘 biligate_crash.log，随"复制诊断"带出 */
    private fun installCrashHandler() {
        val f = java.io.File(filesDir, "biligate_crash.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
                f.appendText("\n===== $ts =====\n${e.javaClass.name}: ${e.message}\n${e.stackTrace.joinToString("\n")}\n")
            } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        diagHandler.post(diagRefresher)
    }

    override fun onPause() {
        super.onPause()
        diagHandler.removeCallbacks(diagRefresher)
    }

    private fun bindViews() {
        svcState = findViewById(R.id.svcState)
        stats = findViewById(R.id.stats)
        whiteList = findViewById(R.id.whiteList)
        blackList = findViewById(R.id.blackList)
        etJevKey = findViewById(R.id.etJevKey)
        rowA11y = findViewById(R.id.rowA11y)
        rowConn = findViewById(R.id.rowConn)
        rowKey = findViewById(R.id.rowKey)
        btnOpenA11y = findViewById(R.id.btnOpenA11y)
        btnGotoKey = findViewById(R.id.btnGotoKey)
    }

    private fun setupListeners() {
        // v0.9.3 底部导航三栏切换（同Activity内visibility切换，全部接线保持原样）
        val pageHome = findViewById<View>(R.id.pageHome)
        val pageRecords = findViewById<View>(R.id.pageRecords)
        val pageSettings = findViewById<View>(R.id.pageSettings)
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        fun showPage(id: Int) {
            pageHome.visibility = if (id == R.id.nav_home) View.VISIBLE else View.GONE
            pageRecords.visibility = if (id == R.id.nav_records) View.VISIBLE else View.GONE
            pageSettings.visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
        }
        bottomNav.setOnItemSelectedListener { item -> showPage(item.itemId); true }

        // v0.9.4 暂停/恢复守护（设计稿：实时守护卡）
        val btnPause = findViewById<MaterialButton>(R.id.btnPause)
        btnPause.text = if (Prefs.enabled(this)) "暂停守护" else "恢复守护"
        btnPause.setOnClickListener {
            val ne = !Prefs.enabled(this)
            Prefs.setEnabled(this, ne)
            btnPause.text = if (ne) "暂停守护" else "恢复守护"
            Toast.makeText(this, if (ne) "已恢复守护" else "已暂停，B站内不再判定", Toast.LENGTH_SHORT).show()
        }

        // v0.9.4 记录筛选 chips（全部/拦截/存疑/放行）
        val chips = listOf(
            "all" to findViewById<MaterialButton>(R.id.chipAll),
            "block" to findViewById<MaterialButton>(R.id.chipBlock),
            "sus" to findViewById<MaterialButton>(R.id.chipSus),
            "pass" to findViewById<MaterialButton>(R.id.chipPass))
        chips.forEach { (k, btn) ->
            btn.setOnClickListener {
                recordFilter = k
                // 设计稿 .chip[aria-pressed=true]：深夜蓝底白字；未选中=白底边框
                chips.forEach { (_, b) ->
                    b.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        getColor(if (b == btn) R.color.chip_active else R.color.canvas))
                    b.setTextColor(getColor(if (b == btn) R.color.canvas else R.color.fg2))
                    b.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.divider))
                }
            }
        }
        chips.first().second.performClick()   // 初始态：全部

        btnOpenA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        // v0.7.3 向导②：直达本App应用设置（自启动/省电无限制）
        findViewById<MaterialButton>(R.id.btnAppSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        }
        // v0.9.3 未配置密钥时一键跳到设置页填写（直达tab）
        btnGotoKey.setOnClickListener {
            bottomNav.selectedItemId = R.id.nav_settings
            etJevKey.requestFocus()
        }
        // v0.8.3 一键清空（小米密码自动填充有时锁死编辑，清空按钮兜底）
        findViewById<MaterialButton>(R.id.btnClearKey).setOnClickListener {
            etJevKey.setText("")
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialSwitch>(R.id.swTipGenuine).apply {
            isChecked = Prefs.tipGenuine(this@MainActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setTipGenuine(this@MainActivity, c) }
        }

        // v0.10.2 BYOK双通道：TypeSafe直连 / 自定义端点（选择即时保存，Base URL随密钥一起保存）
        val providerGroup = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.providerGroup)
        val etApiBase = findViewById<TextInputEditText>(R.id.etApiBase)
        val baseInput = findViewById<View>(R.id.baseInput)
        fun renderProvider() {
            val custom = Prefs.apiProvider(this) == "custom"
            findViewById<MaterialButton>(R.id.btnProviderTypesafe).isChecked = !custom
            findViewById<MaterialButton>(R.id.btnProviderCustom).isChecked = custom
            baseInput.visibility = if (custom) View.VISIBLE else View.GONE
        }
        providerGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                Prefs.setApiProvider(this, if (id == R.id.btnProviderCustom) "custom" else "typesafe")
                renderProvider()
            }
        }
        etApiBase.setText(Prefs.customApiBase(this))
        renderProvider()

        findViewById<MaterialButton>(R.id.btnAddWhite).setOnClickListener {
            val et = findViewById<TextInputEditText>(R.id.etUploader)
            val name = et.text?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) {
                UpBlacklist.addToWhitelist(this, name)
                et.setText(""); refresh()
                Toast.makeText(this, "已加白名单：$name", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.btnClearList).setOnClickListener {
            UpBlacklist.clearAll(this); refresh()
            Toast.makeText(this, "已清空名单", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnCopyDiag).setOnClickListener { copyDiag() }

        // v0.10.0 关于卡：版本 / 开源仓库 / 反馈 / 隐私策略
        findViewById<TextView>(R.id.aboutVersion).text = runCatching {
            "JEV4Bili ${packageManager.getPackageInfo(packageName, 0).versionName} · Jev 驱动的B站刷流守门员 · 纯无障碍方案"
        }.getOrDefault("JEV4Bili · Jev 驱动的B站刷流守门员")
        fun copyLink(url: String, label: String) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, url))
            Toast.makeText(this, "已复制$url", Toast.LENGTH_LONG).show()
        }
        val repoUrl = getString(R.string.repo_url)
        findViewById<MaterialButton>(R.id.btnRepo).setOnClickListener { copyLink(repoUrl, "仓库地址") }
        findViewById<MaterialButton>(R.id.btnFeedback).setOnClickListener { copyLink("$repoUrl/issues", "反馈地址") }
        findViewById<MaterialButton>(R.id.btnPrivacy).setOnClickListener {
            val dpPad = (20 * resources.displayMetrics.density).toInt()
            val tv = TextView(this).apply {
                text = getString(R.string.privacy_policy)
                textSize = 13f
                setPadding(dpPad, (16 * resources.displayMetrics.density).toInt(), dpPad, dpPad)
                setTextIsSelectable(true)
            }
            val scroll = android.widget.ScrollView(this).apply { addView(tv) }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("隐私策略")
                .setView(scroll)
                .setPositiveButton("我知道了", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.btnClearFeed).setOnClickListener {
            // v0.5.4：内存环在 :a11y 进程，发广播让服务进程清
            sendBroadcast(android.content.Intent(GateKeeperService.CLEAR_ACTION).setPackage(packageName))
            findViewById<TextView>(R.id.feedList).text = "已清空，去B站刷几条…"
        }
        // v0.8.5 导出评测包：把记录按行复制，粘贴给Claude做"地面真相"对照评测
        findViewById<MaterialButton>(R.id.btnExportEval).setOnClickListener {
            val s = DiagStore.load(this)
            if (s.records.isEmpty()) {
                Toast.makeText(this, "没有记录可导出", Toast.LENGTH_SHORT).show()
            } else {
                val text = buildString {
                    append("BiliGate评测包 v0.8.5 共${s.records.size}条\n")
                    append("格式: 时间 | 标题 | UP主 | 播放量 | 来源 | 判定\n\n")
                    s.records.forEach { r ->
                        append("${r.time} | ${r.title} | ${r.uploader} | ${r.views} | ${r.source} | ${r.judgment.ifBlank { "未判定" }}\n")
                    }
                }
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("BiliGate评测包", text))
                Toast.makeText(this, "评测包已复制（${s.records.size}条），粘贴给Claude", Toast.LENGTH_LONG).show()
            }
        }

        // v0.7.0 BYOK：密钥保存（跨进程广播给服务）+ 连通测试（真实调一次Jev，不污染服务诊断）
        etJevKey.setText(Prefs.jevKey(this))
        findViewById<MaterialButton>(R.id.btnSaveKey).setOnClickListener {
            // v0.8.2 保存时净化：粘贴的key可能混入不可见字符（曾致0x8bca闪退），只留ASCII
            val raw = etJevKey.text?.toString()?.trim().orEmpty()
            val k = raw.filter { it.code < 128 }
            if (k != raw) {
                etJevKey.setText(k)
                Toast.makeText(this, "已自动去除密钥里的异常字符", Toast.LENGTH_SHORT).show()
            }
            Prefs.setJevKey(this, k)
            // v0.10.2 自定义端点随密钥一起保存
            Prefs.setCustomApiBase(this, etApiBase.text?.toString()?.trim().orEmpty())
            sendBroadcast(android.content.Intent(GateKeeperService.KEY_ACTION)
                .setPackage(packageName).putExtra("key", k))
            Toast.makeText(this, "密钥已保存", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnTestKey).setOnClickListener {
            val k = etJevKey.text?.toString()?.trim().orEmpty().filter { it.code < 128 }
            JevClient.keyOverride = k
            Toast.makeText(this, "连通测试中…（约2-6秒）", Toast.LENGTH_SHORT).show()
            // v0.8.2 协程套保险：任何异常转成Toast，绝不让连通测试闪退程序
            testScope.launch {
                val (j, err) = runCatching {
                    JevClient.judgeWithError(this@MainActivity, "连通测试：普通教程视频", "测试UP主", "")
                }.getOrElse { e -> null to ("异常:${e.javaClass.simpleName} ${e.message?.take(60) ?: ""}") }
                runOnUiThread {
                    if (j != null)
                        Toast.makeText(this@MainActivity,
                            "✓ 连通成功：${j.contentType} 置信${"%.2f".format(j.confidence)} 味${"%.1f".format(j.mktgLevel)}", Toast.LENGTH_LONG).show()
                    else
                        Toast.makeText(this@MainActivity, "✗ 连通失败：$err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refresh() {
        Prefs.resetStatsIfNewDay(this)
        stats.text = "今日判定 ${Prefs.statsJudged(this)} 条 · 其中营销号 ${Prefs.statsBlocked(this)} 条 · 已拉黑 ${UpBlacklist.blacklistNames(this).size} 个UP"
        // v0.8.0 照Jarvis清单逻辑：逐项✓/✗ + 顶部就绪灯（就绪=开关✓+连接✓+密钥✓）
        val s = DiagStore.load(this)
        val alive = s.aliveAt > 0 && System.currentTimeMillis() - s.aliveAt < 10_000
        val toggleOn = a11yToggleOn()
        val keyOk = Prefs.jevKey(this).isNotBlank()
        val ready = alive && keyOk
        svcState.text = if (ready) "● 守护中" else "○ 尚未就绪"
        svcState.setTextColor(getColor(if (ready) R.color.accent_green else R.color.text_secondary))
        svcState.setBackgroundResource(if (ready) R.drawable.pill_bg_ok else R.drawable.pill_bg_off)
        // v0.9.5 步骤图标状态（done=绿底✓ / todo=蓝底序号）+ 进度线权重
        fun ico(id: Int, done: Boolean, n: Int) {
            val tv = findViewById<TextView>(id)
            tv.text = if (done) "✓" else "$n"
            tv.setBackgroundResource(if (done) R.drawable.step_done else R.drawable.step_todo)
            tv.setTextColor(getColor(if (done) R.color.accent_green else R.color.primary))
        }
        ico(R.id.stepIco1, toggleOn, 1)
        ico(R.id.stepIco2, alive, 2)
        ico(R.id.stepIco3, keyOk, 3)
        val doneCount = listOf(toggleOn, alive, keyOk).count { it }
        val lp = findViewById<View>(R.id.progressFill).layoutParams as android.widget.LinearLayout.LayoutParams
        lp.weight = doneCount.toFloat()
        findViewById<View>(R.id.progressFill).layoutParams = lp
        findViewById<android.widget.LinearLayout>(R.id.progressBar).weightSum = 3f
        rowA11y.text = if (toggleOn) "无障碍服务 · 已开启" else "无障碍服务 · 未开启"
        btnOpenA11y.visibility = if (toggleOn) View.GONE else View.VISIBLE
        rowConn.text = if (alive) "服务连接正常（心跳在线）"
            else if (toggleOn) "开关已开但未连上——关一下再开"
            else "先完成第 1 步"
        rowKey.text = if (keyOk) "密钥已配置" else "填自己的 Jev Key，费用走你的账户"
        btnGotoKey.visibility = if (keyOk) View.GONE else View.VISIBLE
        whiteList.text = "白名单：${UpBlacklist.whitelist(this).joinToString("、").ifBlank { "（空）" }}"
        blackList.text = "黑名单：${UpBlacklist.blacklistNames(this).joinToString("、").ifBlank { "（空）" }}"
    }

    /** v0.9.4 首页战绩数字；v0.9.5 加图例/7天柱（真数据分桶，无历史日期为0） */
    private fun refreshHomeStats() {
        val s = DiagStore.load(this)
        val block = s.records.count { it.judgment.contains("营销号") }
        val sus = s.records.count { it.judgment.contains("存疑") }
        val pass = s.records.count { it.judgment.isNotBlank() && !it.judgment.contains("营销号") && !it.judgment.contains("存疑") }
        findViewById<TextView>(R.id.heroCount).text = Prefs.statsBlocked(this).toString()
        findViewById<TextView>(R.id.legendBlock).text = "营销号 $block"
        findViewById<TextView>(R.id.legendSus).text = "存疑 $sus"
        findViewById<TextView>(R.id.legendPass).text = "放行 $pass"
        findViewById<TextView>(R.id.statScanned).text = Prefs.statsJudged(this).toString()
        val avg = s.records.filter { it.ok }.map { it.captureMs }.average()
        findViewById<TextView>(R.id.statAvg).text = if (avg.isNaN()) "-" else "${(avg / 1000.0).let { "%.2f".format(it) }}s"
        findViewById<TextView>(R.id.statBlack).text = UpBlacklist.blacklistNames(this).size.toString()
        // v0.9.8 副标题照设计稿：M月d日 周X · 刷过 N 条 · 为你拦下 N 条
        val cNow = java.util.Calendar.getInstance()
        val md = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(cNow.time)
        val wd = listOf("周日","周一","周二","周三","周四","周五","周六")[cNow.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        findViewById<TextView>(R.id.recordsSub).text = "$md $wd · 刷过 ${s.records.size} 条 · 为你拦下 $block 条"
        // v0.9.6 设计稿 hero-date：MM.dd 周X（等宽小字，右上角）
        val now = java.util.Calendar.getInstance()
        val d = java.text.SimpleDateFormat("MM.dd", java.util.Locale.CHINA).format(now.time)
        val w = listOf("周日","周一","周二","周三","周四","周五","周六")[now.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        findViewById<TextView>(R.id.heroDate).text = "$d $w"

        // 7 天柱：拦截判定按 MM-dd 分桶（Record.time 已带日期前缀）
        val fmtDay = java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
        val fmtW = java.text.SimpleDateFormat("E", java.util.Locale.CHINA)
        val buckets = HashMap<String, Int>()
        s.records.filter { it.judgment.contains("营销号") }.forEach { r ->
            val d = r.time.take(5)
            if (d.matches(Regex("\\d{2}-\\d{2}"))) buckets[d] = (buckets[d] ?: 0) + 1
        }
        val barIds = listOf(R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4, R.id.bar5, R.id.bar6, R.id.bar7)
        val labelIds = listOf(R.id.barN1, R.id.barN2, R.id.barN3, R.id.barN4, R.id.barN5, R.id.barN6, R.id.barN7)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -6)
        var total = 0
        barIds.forEachIndexed { i, id ->
            val key = fmtDay.format(cal.time)
            val n = buckets[key] ?: 0
            total += n
            val v = findViewById<View>(id)
            // v0.10.1 柱高：非零才起柱；分母兜底max(周max,3)防"单条顶格"误读（柱顶另有数字标签）
            v.layoutParams.height = if (n == 0) dp(4)
                else (dp(4) + (dp(52) * n.toFloat() / maxOf(buckets.values.maxOrNull() ?: 1, 3))).toInt()
            v.requestLayout()
            val lb = findViewById<TextView>(labelIds[i])
            if (n > 0) { lb.text = n.toString(); lb.visibility = View.VISIBLE } else lb.visibility = View.GONE
            if (i < 6) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        findViewById<TextView>(R.id.barsTotal).text = "合计 $total"
    }

    /** v0.9.8 记录页照设计稿渲染：分组头（拦截/存疑/放行）+ .rec 单条卡
     *  （●圆点胶囊=类型+置信度% / 底部置信度彩条 / 元信息加粗置信 / ∨展开）
     *  变更检测：filter+数量+末条判定 都没变就跳过重建（每秒tick不折腾30个view） */
    private var lastRenderKey = ""
    private fun renderRecordsUI(s: DiagStore.Snap) {
        val block = s.records.filter { it.judgment.contains("营销号") }
        val sus = s.records.filter { it.judgment.contains("存疑") }
        val pass = s.records.filter { it.judgment.isNotBlank() && !it.judgment.contains("营销号") && !it.judgment.contains("存疑") }
        // chips 计数照设计稿（全部 156 / 拦截 47 / 存疑 12 / 放行）
        findViewById<TextView>(R.id.chipAll).text = "全部 ${s.records.size}"
        findViewById<TextView>(R.id.chipBlock).text = "拦截 ${block.size}"
        findViewById<TextView>(R.id.chipSus).text = "存疑 ${sus.size}"
        findViewById<TextView>(R.id.chipPass).text = "放行 ${pass.size}"
        val recs = when (recordFilter) {
            "block" -> block; "sus" -> sus; "pass" -> pass; else -> s.records
        }
        val key = "$recordFilter|${recs.size}|${recs.lastOrNull()?.judgment ?: ""}"
        if (key == lastRenderKey) return
        lastRenderKey = key
        val list = findViewById<android.widget.LinearLayout>(R.id.recList)
        val empty = findViewById<TextView>(R.id.feedList)
        list.removeAllViews()
        if (recs.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = if (recordFilter == "all") "暂无记录，去B站刷几条…" else "该筛选下暂无记录"
            return
        }
        empty.visibility = View.GONE
        if (recordFilter == "all") {
            // 设计稿三组：拦截组头→卡…存疑组头→卡…放行组头→卡（组内最新在前）
            if (block.isNotEmpty()) { addGroupHeader(list, "拦截 · 建议划走", block.size, "点卡片查看依据"); block.takeLast(30).reversed().forEach { addRecCard(list, it) } }
            if (sus.isNotEmpty()) { addGroupHeader(list, "存疑 · 仅提示", sus.size, "证据不足时不拦截"); sus.takeLast(30).reversed().forEach { addRecCard(list, it) } }
            if (pass.isNotEmpty()) { addGroupHeader(list, "放行 · 默认通过", pass.size, "不打断浏览"); pass.takeLast(30).reversed().forEach { addRecCard(list, it) } }
        } else {
            recs.takeLast(30).reversed().forEach { addRecCard(list, it) }
        }
    }

    /** 设计稿分组头：黑粗标题+灰计数 | 右侧灰提示 */
    private fun addGroupHeader(list: android.widget.LinearLayout, title: String, n: Int, hint: String) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(dp(2), dp(14), dp(2), dp(8))
        val t = TextView(this)
        t.text = title; t.textSize = 16f
        t.setTypeface(null, android.graphics.Typeface.BOLD)
        t.setTextColor(getColor(R.color.text_primary))
        row.addView(t)
        val c = TextView(this)
        c.text = "  $n 条"; c.textSize = 12f
        c.setTextColor(getColor(R.color.text_secondary))
        row.addView(c, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val h = TextView(this)
        h.text = hint; h.textSize = 12f
        h.setTextColor(getColor(R.color.text_secondary))
        row.addView(h)
        list.addView(row)
    }

    /** 从判定串取置信度（信0.81→81），无则0 */
    private fun confPct(j: String): Int =
        (Regex("信([0-9.]+)").find(j)?.groupValues?.get(1)?.toFloatOrNull()?.times(100)?.toInt() ?: 0).coerceIn(0, 99)

    /** 设计稿胶囊文案：拦截=搬运号/标题党/营销号+%，存疑=存疑+% */
    private fun pillLabel(j: String): String = when {
        j.contains("搬运") -> "搬运号"
        j.contains("标题党") || j.contains("互动钓鱼") -> "标题党"
        else -> "营销号"
    }

    /** 设计稿 .rec 单条卡填充 */
    private fun addRecCard(list: android.widget.LinearLayout, r: DiagStore.Record) {
        val v = layoutInflater.inflate(R.layout.record_item, list, false)
        val pill = v.findViewById<TextView>(R.id.recPill)
        val isBlock = r.judgment.contains("营销号")
        val isSus = r.judgment.contains("存疑")
        val fill = v.findViewById<View>(R.id.recFill)
        when {
            isBlock -> {
                pill.text = "● ${pillLabel(r.judgment)} ${confPct(r.judgment)}%"
                pill.setBackgroundResource(R.drawable.pill_danger); pill.setTextColor(getColor(R.color.danger_red))
                fill.setBackgroundColor(getColor(R.color.danger_red)); setFillWidth(v, fill, confPct(r.judgment))
            }
            isSus -> {
                pill.text = "● 存疑 ${confPct(r.judgment)}%"
                pill.setBackgroundResource(R.drawable.pill_warn); pill.setTextColor(getColor(R.color.warn_text))
                fill.setBackgroundColor(getColor(R.color.warn_yellow)); setFillWidth(v, fill, confPct(r.judgment))
            }
            r.judgment.isNotBlank() -> {
                pill.text = "● 放行"
                pill.setBackgroundResource(R.drawable.pill_ok); pill.setTextColor(getColor(R.color.accent_green))
                fill.visibility = View.GONE
            }
            else -> {
                pill.text = "● 记录中"
                pill.setBackgroundResource(R.drawable.pill_neutral); pill.setTextColor(getColor(R.color.fg2))
                fill.visibility = View.GONE
            }
        }
        v.findViewById<TextView>(R.id.recTitle).text = r.title.ifBlank { "（未识别标题）" }
        // 设计稿只显 HH:mm
        v.findViewById<TextView>(R.id.recTime).text = if (r.time.length >= 11) r.time.substring(6, 11) else r.time
        val onl = if (r.online.isNotBlank()) " · ◉${r.online}在线" else ""
        val vw = if (r.views.isNotBlank()) " ▶${r.views}" else ""
        // 元信息照设计稿：灰底色，置信串（味X.X 信X.XX）黑色加粗=「N条依据」的诚实对应物
        val conf = Regex("味[0-9.]+ 信[0-9.]+").find(r.judgment)?.value
        val bold = if (conf != null) "<font color=\"#181D26\"><b>$conf</b></font>"
                   else if (r.judgment.isNotBlank()) "<font color=\"#181D26\"><b>已判定</b></font>" else "判定中"
        v.findViewById<TextView>(R.id.recMeta).text = android.text.Html.fromHtml(
            "${r.uploader.ifBlank { "?" }}$vw · ${r.source}$onl · $bold", android.text.Html.FROM_HTML_MODE_LEGACY)
        val detail = v.findViewById<TextView>(R.id.recDetail)
        detail.text = buildString {
            append(r.judgment.ifBlank { "（判定中…）" })
            append("\n到手 ${r.captureMs}ms · ${r.time}")
        }
        val chev = v.findViewById<TextView>(R.id.recChevron)
        v.setOnClickListener {
            val show = detail.visibility != View.VISIBLE
            detail.visibility = if (show) View.VISIBLE else View.GONE
            chev.text = if (show) "∧" else "∨"
        }
        list.addView(v)
    }

    /** 底部置信度彩条宽度=卡片宽×%（须等布局出宽） */
    private fun setFillWidth(card: View, fill: View, pct: Int) {
        card.post {
            if (card.width > 0) {
                fill.layoutParams.width = (card.width * pct / 100).coerceAtLeast(20)
                fill.requestLayout()
            }
        }
    }

    private fun copyDiag() {
        val s = DiagStore.load(this)
        val d = { k: String -> s.diag[k].orEmpty() }
        val text = buildString {
            append("BiliGate诊断 v0.9.8\n")
            append("快照脚印:${d("step_hist").ifBlank { "-" }}\n")
            append("获取记录:\n${DiagStore.renderRecords(s, 30)}\n")
            append("事件流:\n${DiagStore.renderEvents(s, 80)}\n")
            append("服务上线:${s.connectedAt.ifBlank { "未连接" }}\n")
            append("总事件:${s.totalEvents} B站事件:${s.eventCount}\n")
            append("窗口节点:${d("root_info")}\n")
            append("文本数:${d("texts_count")}\n")
            append("标题:${d("last_title")}\n")
            append("UP主:${d("last_uploader")}\n")
            append("判定:${d("last_judge")}\n")
            append("Jev错误:${d("last_jev_err")}\n")
            append("文本样本:${d("texts_sample")}\n")
            val crash = runCatching {
                java.io.File(filesDir, "biligate_crash.log").readText().lineSequence().toList().takeLast(30).joinToString("\n")
            }.getOrDefault("")
            if (crash.isNotBlank()) append("最近崩溃:\n$crash\n")
        }
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("BiliGate诊断", text))
        Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show()
    }

    /** v0.5.4：只查开关状态（服务活不活看 DiagStore 心跳） */
    /** v0.8.0 开关检测双路：Settings.Secure 字符串包含（Jarvis同款）+ AccessibilityManager 兜底 */
    private fun a11yToggleOn(): Boolean {
        runCatching {
            val enabled = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (enabled.contains(packageName)) return true
        }
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
