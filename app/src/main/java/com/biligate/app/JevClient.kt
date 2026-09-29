package com.biligate.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 判断引擎：调 Jev (TypeSafe AI) systemone 接口，三维判断。
 * 与桌面验证脚本 _jev_mktg_judge.py 同一套题目（精简为跳过决策必需的三维）。
 */
object JevClient {

    data class Judgment(
        val contentType: String,   // v0.9.0宽口径: original_content/farm_repost/engagement_bait/sales_funnel/normal_news
        val confidence: Double,    // content_type 的置信度
        val mktgLevel: Double,     // v0.9.0起语义=农场度 0-4（0纯原创…4纯农场量产）
        val clickbait: Double,     // 标题党 0-1
        val mktgConfidence: Double = 0.0,  // v0.9.1 农场度题自身置信度（无则回退分类置信度）
    ) {
        /** v0.9.1 保险丝修正（EdgeAITech案：中配搬运农场度3.6被分类置信0.37一票否决）：
         *  置信门槛改看农场度题自己的置信；农场度>=3.5极端高分时不再受置信约束——
         *  对"是哪类"犹豫≠对"农场味浓"犹豫。宁漏勿误底线：2.5~3.5之间仍需置信>=0.8。 */
        val isMarketing: Boolean
            get() = (confidence >= 0.8 && contentType == "sales_funnel") ||
                (mktgLevel >= 2.5 && (mktgConfidence >= 0.8 || mktgLevel >= 3.5))

        /** 互动钓鱼/标题党（仅记录行提示，农场度不够不标营销号） */
        val isClickbait: Boolean
            get() = confidence >= 0.8 && contentType == "engagement_bait" && mktgLevel < 2.5

        /** v0.9.1 边缘态：农场度超线但置信不足被放行——记录行标"存疑"供人工复核 */
        val isBorderline: Boolean
            get() = !isMarketing && mktgLevel >= 2.5
    }

    private const val API_URL = "https://api.typesafe.ai/v1/systemone"

    private fun client(dnsDirect: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
        if (dnsDirect) {
            b.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == "api.typesafe.ai")
                        listOf(InetAddress.getByAddress(hostname, byteArrayOf(44.toByte(), 227.toByte(), 31.toByte(), 201.toByte())))
                    else Dns.SYSTEM.lookup(hostname)
            })
        }
        return b.build()
    }

    private fun questions(): JSONObject = JSONObject().apply {
        put("content_type", JSONObject().apply {
            put("type", "choice")
            put("instructions", "Classify this short-video. Judge from title, uploader name, engagement numbers and farm hints in the state. " +
                "Farm tells: template narration formats (逐条记录/评论区告诉我 with pre-loaded answers), tag/title mismatch, " +
                "'content from web' disclaimers, numbered matrix account names, 求关注-baiting names (in signals). " +
                "Engagement reading (实证: farm repost got 10k views but only 2 coins): views in thousands with <10 coins = " +
                "users refuse to pay tribute = reposted/farm content → farm_repost + elevate farm level; " +
                "high coin rate = genuine valuable original. " +
                "Pure punchy title alone is NOT farm — real creators use hooks too; look for substance behind the hook.")
            put("criteria", JSONObject().apply {
                put("original_content", "Original personal creation: self-filmed, own voice/expertise, real effort visible (vlog, tutorial, gameplay, performance, craft)")
                put("farm_repost", "Content-farm repost/aggregation: repackaged material from other sources, template list formats, tag-title mismatch, mass-production signs")
                put("engagement_bait", "Engagement bait: fake interaction prompts, exaggerated hooks with little substance, curiosity exploitation")
                put("sales_funnel", "Sales funnel: selling products/courses, or diverting to WeChat/public-account/external platforms")
                put("normal_news", "Neutral news or official media reporting")
            })
        })
        put("mktg_level", JSONObject().apply {
            put("type", "score")
            put("instructions", "Farm level: how much is this factory-farm content vs genuine original creation? (NOT sales intent — production quality/originality)")
            put("criteria", org.json.JSONArray().apply {
                put("clearly original genuine creation"); put("mostly original, minor borrowed elements"); put("significant aggregation/repackaging")
                put("mostly factory template content"); put("pure content-farm production")
            })
        })
        put("clickbait", JSONObject().apply {
            put("type", "noul")
            put("instructions", "How clickbait is the title? (exaggerated claims, curiosity gaps, urgency) 0=honest, 1=extreme clickbait")
        })
    }

    /** v0.7.0：主进程改密钥后经广播写入本进程（跨进程 SharedPreferences 缓存不可靠） */
    @Volatile var keyOverride: String? = null

    /** 输入视频标题+UP主（+被动互动数据），返回判断；全失败返回 null（调用方降级放行）。服务进程专用（写DiagStore） */
    suspend fun judge(ctx: android.content.Context, title: String, uploader: String, signals: String = ""): Judgment? {
        val (j, err) = judgeWithError(ctx, title, uploader, signals)
        DiagStore.put(ctx, "last_jev_err", err)
        return j
    }

    /** 同 judge 但不写 DiagStore（供主进程"连通测试"直接调用，避免污染服务进程的诊断文件） */
    suspend fun judgeWithError(ctx: android.content.Context, title: String, uploader: String, signals: String = ""): Pair<Judgment?, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                val state = buildString {
                    append("视频标题：$title\nUP主：$uploader")
                    if (signals.isNotBlank()) append("\n互动与数据：$signals")
                }
                put("state", state)
                put("model", "jev-latest")
                put("questions", questions())
            }
            // v0.8.2 密钥净化：真机曾因粘贴的key混入不可见字符(0x8bca)致OkHttp抛
            // "Unexpected char in Authorization value"闪退——只留ASCII，双进程全覆盖
            val key = (keyOverride ?: Prefs.jevKey(ctx)).filter { it.code < 128 }.trim()
            val req = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val attempts = if (Prefs.dnsDirect(ctx)) listOf(true, false) else listOf(false)
            var lastErr = "无尝试"
            for (direct in attempts) {
                try {
                    client(direct).newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) { lastErr = "HTTP ${resp.code}"; return@use }
                        val root = JSONObject(resp.body!!.string())
                        val answers = root.getJSONObject("answers")
                        val ct = answers.getJSONObject("content_type")
                        return@withContext Judgment(
                            contentType = ct.getString("choice"),
                            confidence = ct.optDouble("confidence", 0.0),
                            mktgLevel = answers.getJSONObject("mktg_level").optDouble("score", 0.0),
                            clickbait = answers.getJSONObject("clickbait").optDouble("noul", 0.0),
                            // v0.9.1: score题自带confidence则用之，无则回退分类置信
                            mktgConfidence = answers.getJSONObject("mktg_level").let { m ->
                                if (m.has("confidence")) m.optDouble("confidence", 0.0) else ct.optDouble("confidence", 0.0)
                            },
                        ) to ""
                    }
                } catch (e: Exception) {
                    lastErr = (e.message ?: e.javaClass.simpleName).take(80) + if (direct) "(IP直连)" else "(系统DNS)"
                }
            }
            null to lastErr
        }
}
