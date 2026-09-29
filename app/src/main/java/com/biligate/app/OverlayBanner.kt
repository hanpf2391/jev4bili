package com.biligate.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 无障碍专属悬浮横幅（TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限，全屏视频上也显示）。
 * 顶部胶囊：营销号红 / 干货绿 / 拉黑灰。
 */
object OverlayBanner {

    private val handler = Handler(Looper.getMainLooper())
    private var container: LinearLayout? = null
    private var hideRunnable: Runnable? = null

    fun showMarketing(service: AccessibilityService, text: String, autoHideMs: Long = 2200) =
        show(service, "⚠ $text", 0xFFDC2626.toInt(), autoHideMs)

    /** v0.9.9 存疑黄档：农场度超线但置信不足——之前这种也弹绿"正常"，用户刷时看不出存疑 */
    fun showSuspect(service: AccessibilityService, text: String, autoHideMs: Long = 2200) =
        show(service, "? $text", 0xFFB45309.toInt(), autoHideMs)

    fun showGenuine(service: AccessibilityService, text: String, autoHideMs: Long = 1500) =
        show(service, "✓ $text", 0xFF16A34A.toInt(), autoHideMs)

    fun showBlack(service: AccessibilityService, text: String, autoHideMs: Long = 1200) =
        show(service, text, 0xFF334155.toInt(), autoHideMs)

    private var tvRef: TextView? = null
    private var bgRef: GradientDrawable? = null
    private var wmRef: WindowManager? = null

    private fun show(service: AccessibilityService, text: String, bgColor: Int, autoHideMs: Long) {
        handler.post {
            runCatching {
                hideRunnable?.let { handler.removeCallbacks(it) }
                val dp = { v: Int -> TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt() }

                // v0.6.3 就地换字：窗口常驻、只换文字颜色（旧实现每弹一次删建一次窗口，
                // 快刷时弹窗连环闪跳——"旧的没消失新的又出来"；现在内容顺滑更替）
                // v0.8.4 每次弹出都做"弹一下"动画（缩放+淡入）——连续两个视频判定内容一致时
                // 换字看不出变化，用户会误以为是上一条的提示在延续；缩放任每次都肉眼可见
                val tv = tvRef
                if (tv != null && bgRef != null) {
                    tv.text = text
                    bgRef!!.setColor(bgColor)
                    tv.alpha = 0.2f
                    tv.scaleX = 0.92f
                    tv.scaleY = 0.92f
                    tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
                } else {
                    val wm = service.getSystemService(WindowManager::class.java) ?: return@post
                    val newTv = TextView(service).apply {
                        this.text = text
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        maxWidth = dp(320)
                    }
                    val newBg = GradientDrawable().apply {
                        setColor(bgColor)
                        cornerRadius = dp(18).toFloat()
                    }
                    val box = LinearLayout(service).apply {
                        background = newBg
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        elevation = dp(6).toFloat()
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        addView(newTv)
                    }
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = dp(72)
                    }
                    wm.addView(box, params)
                    container = box
                    tvRef = newTv
                    bgRef = newBg
                    wmRef = wm
                }
                val r = Runnable { hideImmediate() }
                hideRunnable = r
                handler.postDelayed(r, autoHideMs)
            }
        }
    }

    private fun hideImmediate() {
        val wm = wmRef
        container?.let { runCatching { wm?.removeView(it) } }
        container = null
        tvRef = null
        bgRef = null
        wmRef = null
    }
}
