package com.biligate.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置中心：全部走 SharedPreferences，键如下
 *   enabled        Boolean  总开关（默认 true）
 *   mode           Int      0=提示后自动跳 1=仅提示不跳 2=直接跳（默认0）
 *   dns_direct     Boolean  Jev API IP直连（默认true，公司WiFi无DNS时用）
 *   dump_debug     Boolean  节点dump模式（校准期用，把B站界面文本打进日志）
 *   blacklist      String   JSON {"UP主名": 累计判定次数}，>=3 视为黑名单
 *   whitelist      String   JSON ["UP主名", ...]
 *   stats_date     String   "yyyy-MM-dd" 当日；跨日重置计数
 *   stats_skipped  Int      今日自动跳过条数
 */
object Prefs {
    private const val FILE = "biligate"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    fun mode(ctx: Context) = sp(ctx).getInt("mode", 0)
    fun setMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("mode", v).apply()

    fun dnsDirect(ctx: Context) = sp(ctx).getBoolean("dns_direct", true)
    fun setDnsDirect(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("dns_direct", v).apply()

    fun dumpDebug(ctx: Context) = sp(ctx).getBoolean("dump_debug", false)
    fun setDumpDebug(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("dump_debug", v).apply()

    /** v0.7.1：出厂零密钥——用户自己在App里填（BYOK）；空=判断接口未配置（Jev调用会401放行） */
    fun jevKey(ctx: Context): String = sp(ctx).getString("jev_key", "") ?: ""

    /** v0.7.0：BYOK——用户自填密钥（留空=回退内置调试密钥） */
    fun setJevKey(ctx: Context, v: String) = sp(ctx).edit().putString("jev_key", v).apply()

    fun statsSkipped(ctx: Context): Int = sp(ctx).getInt("stats_skipped", 0)

    /** 服务存活标志：onServiceConnected 写 true，onDestroy/onUnbind 写 false */
    fun serviceAlive(ctx: Context) = sp(ctx).getBoolean("service_alive", false)
    fun setServiceAlive(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("service_alive", v).putLong("service_alive_ts", System.currentTimeMillis()).apply()
    fun serviceAliveTs(ctx: Context) = sp(ctx).getLong("service_alive_ts", 0L)
    fun incSkipped(ctx: Context) = sp(ctx).edit().putInt("stats_skipped", statsSkipped(ctx) + 1).apply()

    /** 今日判定总数（含放行） */
    fun statsJudged(ctx: Context): Int = sp(ctx).getInt("stats_judged", 0)
    fun incJudged(ctx: Context) = sp(ctx).edit().putInt("stats_judged", statsJudged(ctx) + 1).apply()

    /** 今日营销号判定数（纯提示模式，不跳过） */
    fun statsBlocked(ctx: Context): Int = sp(ctx).getInt("stats_blocked", 0)
    fun incBlocked(ctx: Context) = sp(ctx).edit().putInt("stats_blocked", statsBlocked(ctx) + 1).apply()

    /** 干货视频是否也弹提示（默认关——每条都弹太吵） */
    fun tipGenuine(ctx: Context) = sp(ctx).getBoolean("tip_genuine", false)
    fun setTipGenuine(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("tip_genuine", v).apply()

    fun resetStatsIfNewDay(ctx: Context) {
        val today = android.text.format.DateFormat.format("yyyy-MM-dd", java.util.Date()).toString()
        if (sp(ctx).getString("stats_date", "") != today) {
            sp(ctx).edit().putString("stats_date", today)
                .putInt("stats_skipped", 0).putInt("stats_judged", 0).putInt("stats_blocked", 0).apply()
        }
    }

    /** 自检诊断数据（服务写，设置页读；值截断800字符防爆——v0.4.4 起样本带坐标/视图ID，300 会腰斩） */
    fun diag(ctx: Context, key: String): String = sp(ctx).getString("diag_$key", "") ?: ""
    fun setDiag(ctx: Context, key: String, v: String) =
        sp(ctx).edit().putString("diag_$key", v.take(800)).apply()

    fun clearAll(ctx: Context) = sp(ctx).edit().clear().apply()
}
