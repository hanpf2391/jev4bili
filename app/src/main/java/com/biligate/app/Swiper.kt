package com.biligate.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * 执行器·拟人上滑：弧线轨迹手势 + 执行前随机延迟（防检测：随机延迟/弧线/非匀速）。
 */
object Swiper {

    /** 随机延迟后执行上滑（模拟看客划走） */
    fun swipeUpAfterDelay(service: AccessibilityService, delayMinMs: Long = 300, delayMaxMs: Long = 900) {
        val delay = Random.nextLong(delayMinMs, delayMaxMs)
        Handler(Looper.getMainLooper()).postDelayed({ swipeUpNow(service) }, delay)
    }

    fun swipeUpNow(service: AccessibilityService) {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val x0 = w * 0.5f + Random.nextInt(-40, 40)
        val x1 = w * 0.52f + Random.nextInt(-60, 60)
        val x2 = w * 0.47f + Random.nextInt(-60, 60)
        val y0 = h * (0.70f + Random.nextFloat() * 0.06f)
        val y1 = h * (0.46f + Random.nextFloat() * 0.06f)
        val y2 = h * (0.24f + Random.nextFloat() * 0.05f)

        val first = GestureDescription.StrokeDescription(arc(x0, y0, x1, y1), 0, Random.nextLong(90, 150), true)
        val second = GestureDescription.StrokeDescription(arc(x1, y1, x2, y2), 0, Random.nextLong(60, 110))
        val gesture = GestureDescription.Builder()
            .addStroke(first)
            .addStroke(second)
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    /** 带随机弧度的曲线路径 */
    private fun arc(x0: Float, y0: Float, x1: Float, y1: Float) = Path().apply {
        moveTo(x0, y0)
        quadTo(
            x0 + (x1 - x0) * 0.5f + Random.nextInt(-50, 50),
            y0 + (y1 - y0) * 0.45f,
            x1, y1,
        )
    }
}
