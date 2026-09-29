package com.biligate.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * UP主信誉库：同一UP主累计 STRIKE_LIMIT 次被判营销号 → 拉黑，此后秒跳不再调API。
 * 存储在 SharedPreferences（blacklist/whitelist 两键），无外部数据文件。
 */
object UpBlacklist {
    const val STRIKE_LIMIT = 3

    private fun sp(ctx: Context) = ctx.getSharedPreferences("biligate", Context.MODE_PRIVATE)

    private fun map(ctx: Context): MutableMap<String, Int> {
        val raw = sp(ctx).getString("blacklist", null) ?: return mutableMapOf()
        val m = mutableMapOf<String, Int>()
        runCatching {
            val o = JSONObject(raw)
            o.keys().forEach { m[it] = o.optInt(it) }
        }
        return m
    }

    private fun save(ctx: Context, m: Map<String, Int>) =
        sp(ctx).edit().putString("blacklist", JSONObject(m).toString()).apply()

    /** 记一次判定；返回 true 表示本次达到拉黑线 */
    fun recordStrike(ctx: Context, uploader: String): Boolean {
        val m = map(ctx)
        val n = (m[uploader] ?: 0) + 1
        m[uploader] = n
        save(ctx, m)
        return n >= STRIKE_LIMIT
    }

    fun isBlack(ctx: Context, uploader: String): Boolean =
        uploader.isNotBlank() && (map(ctx)[uploader] ?: 0) >= STRIKE_LIMIT

    fun blacklistNames(ctx: Context): List<String> =
        map(ctx).filter { it.value >= STRIKE_LIMIT }.keys.sorted()

    // ---- 白名单（用户手动加，永不判断） ----
    fun whitelist(ctx: Context): List<String> {
        val raw = sp(ctx).getString("whitelist", null) ?: return emptyList()
        val l = mutableListOf<String>()
        runCatching { JSONArray(raw).let { for (i in 0 until it.length()) l.add(it.getString(i)) } }
        return l
    }

    fun addToWhitelist(ctx: Context, name: String) {
        val l = whitelist(ctx).toMutableList()
        if (name.isNotBlank() && name !in l) { l.add(name); sp(ctx).edit().putString("whitelist", JSONArray(l).toString()).apply() }
    }

    fun clearAll(ctx: Context) =
        sp(ctx).edit().remove("blacklist").remove("whitelist").apply()
}
