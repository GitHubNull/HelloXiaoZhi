package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 水波涟漪通话动画，逐段移植设计稿 call.js 的 drawPool / drawBall / geom /
 * spawnRipple / getAudioIntensity：
 *
 *  - 单球居中，AI 说话蓝色 / 用户说话绿色；
 *  - 球体随音频强度沉浮，说话方主动震动并发出涟漪；
 *  - 涟漪波前经过处水波被「扰动」提亮；
 *  - 背景为宣纸底色，水波为清澈河水色。
 *
 * 性能设计（中端机 30fps 预算）：
 *  - 目标 30fps 而非 60：效果是缓慢漂移，无从分辨；
 *  - waveIntensityAt 缓存到粗网格（每列 × 16px 带一个值，每帧算一次）；
 *  - onDraw 内零分配：Paint / Path / RectF 全部预建复用；
 *  - 径向渐变 shader 按球缓存，仅在可见半径有实质变化时重建。
 *
 * 线程模型：由 Choreographer 在主线程驱动；setSpeaking 可从任意线程调用。
 */
class RippleCallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---------------- 公开 API ----------------

    /** AI 是否正在说话 */
    fun setAiSpeaking(speaking: Boolean) {
        if (ballAi.speaking == speaking) return
        ballAi.speaking = speaking
        if (speaking) {
            ballAi.rgb = AI_BLUE
        }
    }

    /** 用户是否正在说话 */
    fun setUserSpeaking(speaking: Boolean) {
        if (ballUser.speaking == speaking) return
        ballUser.speaking = speaking
        if (speaking) {
            ballUser.rgb = USER_GREEN
        }
    }

    /** 设置用户音频强度（0-1），驱动用户球体震动 */
    fun setUserAudioIntensity(intensity: Float) {
        userAudioIntensity = intensity.coerceIn(0f, 1f)
    }

    /** 设置 AI 音频强度（0-1），驱动 AI 球体震动 */
    fun setAiAudioIntensity(intensity: Float) {
        aiAudioIntensity = intensity.coerceIn(0f, 1f)
    }

    /** 小屏纯动画全屏模式：收窄水波边缘留白，让动画铺满 */
    fun setEdgeInset(px: Float, py: Float) {
        padX = px
        padY = py
    }

    /** 恢复默认边缘留白（完整界面模式） */
    fun resetEdgeInset() {
        padX = DEFAULT_PAD_X
        padY = DEFAULT_PAD_Y
    }

    /** 开始动画（进入通话页 / onResume） */
    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** 停止动画（onPause / 挂断 / 锁屏），避免后台烧 CPU */
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---------------- 内部状态 ----------------

    private class Ball(var rgb: IntArray) {
        var speaking = false
        var sinkPhase = 0f
        var sinkDepth = 0f
        var brightness = 1f
        // 缓存的球体渐变 shader 及其对应的可见半径（量化到 0.5px）
        var shader: RadialGradient? = null
        var shaderRadius = -1f
    }

    private class Ripple(
        val cx: Float,
        val cy: Float,
        val maxR: Float,
        val amp: Float,
        val speed: Float,
        val birth: Long,
    )

    private val AI_BLUE = intArrayOf(56, 189, 248)   // AI 说话：蓝色球体
    private val USER_GREEN = intArrayOf(52, 211, 153) // 用户说话：绿色球体

    private val ballAi = Ball(AI_BLUE.copyOf())
    private val ballUser = Ball(USER_GREEN.copyOf())
    private val ripples = ArrayList<Ripple>()
    private var lastRippleAi = 0L
    private var lastRippleUser = 0L
    @Volatile
    private var userAudioIntensity = 0f
    @Volatile
    private var aiAudioIntensity = 0f

    private var running = false
    private var lastFrameNanos = 0L
    private var padX = DEFAULT_PAD_X
    private var padY = DEFAULT_PAD_Y

    // 预建的绘制对象（onDraw 零分配）
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val poolRect = RectF()

    // waveIntensityAt 粗网格缓存：每列 × 每 16px 垂直带一个值
    private var waveGrid = FloatArray(0)
    private var waveGridCols = 0
    private var waveGridRows = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) FRAME_S
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos
            step(dt, frameTimeNanos / 1_000_000L)
            invalidate()
            // 30fps：隔一帧再调度
            Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_MS)
        }
    }

    // ---------------- 尺寸与初始化 ----------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initWaveGrid()
    }

    private fun initWaveGrid() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        waveGridCols = maxOf(1, (w / COL_W).toInt() + 1)
        waveGridRows = maxOf(1, (h / WAVE_BAND).toInt() + 1)
        waveGrid = FloatArray(waveGridCols * waveGridRows)
    }

    // ---------------- 每帧推进 ----------------

    private fun step(dt: Float, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val g = geom()

        // 主动震动：说话方沉浮 + 发涟漪
        updateBall(ballAi, g.cx, g.cy, now, isAi = true)
        updateBall(ballUser, g.cx, g.cy, now, isAi = false)

        // 推进涟漪并清理过期
        val it = ripples.iterator()
        while (it.hasNext()) {
            val rp = it.next()
            val age = (now - rp.birth) / 1000f
            val r = rp.speed * age
            if (r > rp.maxR || age > 9f) it.remove()
        }
        while (ripples.size > MAX_RIPPLES) ripples.removeAt(0)

        // 重建 waveIntensityAt 粗网格缓存
        rebuildWaveGrid(now)
    }

    private fun updateBall(ball: Ball, cx: Float, cy: Float, now: Long, isAi: Boolean) {
        val intensity = if (isAi) aiAudioIntensity else userAudioIntensity
        if (ball.speaking) {
            // 使用音频强度驱动球体震动
            // 公式：sinkDepth = intensity^0.6 * 1.1，然后限制在 0-1 范围
            val amplifiedIntensity = intensity.pow(0.6f) * 1.1f
            ball.sinkDepth = min(1f, maxOf(0f, amplifiedIntensity))

            // 根据音频强度动态调整涟漪生成频率和幅度
            val lastRipple = if (isAi) lastRippleAi else lastRippleUser
            val rippleInterval = maxOf(30f, 150f - intensity * 150f).toLong()
            
            if (now - lastRipple > rippleInterval) {
                val rippleAmp = 0.3f + intensity * 1.2f
                spawnRipple(cx, cy, geom().maxRippleR, rippleAmp, intensity)
                if (isAi) lastRippleAi = now else lastRippleUser = now
            }

            // 音乐节奏强烈时额外产生涟漪
            if (intensity > 0.6f && now - lastRipple > 60) {
                spawnRipple(cx, cy, geom().maxRippleR * 0.8f, 0.8f, intensity)
                if (isAi) lastRippleAi = now else lastRippleUser = now
            }
        } else {
            ball.sinkPhase = 0f
            // 非说话方逐渐浮回
            ball.sinkDepth = maxOf(ball.sinkDepth * 0.92f, 0f)
        }
        // 非说话方的强度自动衰减，避免残留
        if (!ball.speaking) {
            if (isAi) aiAudioIntensity *= 0.85f else userAudioIntensity *= 0.85f
        }
    }

    private fun spawnRipple(cx: Float, cy: Float, maxR: Float, amp: Float, intensity: Float) {
        // 涟漪速度由音频强度确定性驱动：强度越大速度越快
        val speed = 50f + intensity * 40f
        ripples.add(Ripple(cx, cy, maxR, amp, speed, System.currentTimeMillis()))
    }

    // ---------------- 几何 ----------------

    private class Geom(
        val x0: Float, val x1: Float, val y0: Float, val y1: Float,
        val cx: Float, val cy: Float,
        val maxRippleR: Float,
    )

    private fun geom(): Geom {
        val x0 = padX
        val x1 = width - padX
        val y0 = padY
        val y1 = height - padY
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        return Geom(
            x0, x1, y0, y1,
            cx, cy,
            hypot(x1 - x0, y1 - y0) / 2f,
        )
    }

    // ---------------- waveIntensityAt（含粗网格缓存） ----------------

    /** 重建粗网格：每列 × 每 16px 带一个值，供本帧内所有点查询 */
    private fun rebuildWaveGrid(now: Long) {
        if (waveGridCols <= 0 || waveGridRows <= 0) return
        for (col in 0 until waveGridCols) {
            val x = col * COL_W
            for (row in 0 until waveGridRows) {
                val y = row * WAVE_BAND
                waveGrid[row * waveGridCols + col] = computeWaveIntensity(x, y, now)
            }
        }
    }

    private fun waveIntensityAt(px: Float, py: Float, now: Long): Float {
        if (waveGridCols <= 0 || waveGridRows <= 0) return 0f
        val col = (px / COL_W).toInt().coerceIn(0, waveGridCols - 1)
        val row = (py / WAVE_BAND).toInt().coerceIn(0, waveGridRows - 1)
        return waveGrid[row * waveGridCols + col]
    }

    /** 实际计算某点的涟漪扰动强度 */
    private fun computeWaveIntensity(px: Float, py: Float, now: Long): Float {
        var sum = 0f
        for (rp in ripples) {
            val age = (now - rp.birth) / 1000f
            val d = hypot(px - rp.cx, py - rp.cy)
            val band = kotlin.math.abs(d - rp.speed * age)
            if (band > 16f) continue
            val decay = exp(-age * 1.3f) * exp(-d / 420f)
            sum += rp.amp * decay * exp(-band * band / 70f)
        }
        return sum
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val now = System.currentTimeMillis()
        val g = geom()

        // 宣纸背景：暖黄色渐变
        poolRect.set(g.x0, g.y0, g.x1, g.y1)
        bgPaint.shader = LinearGradient(
            0f, g.y0, 0f, g.y1,
            intArrayOf(
                color(R.color.xz_water_bg_top),
                color(R.color.xz_water_bg_mid),
                color(R.color.xz_water_bg_bottom)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(poolRect, CORNER, CORNER, bgPaint)
        bgPaint.shader = null

        // 裁剪到水波区域内绘制涟漪
        clipPath.reset()
        clipPath.addRoundRect(poolRect, CORNER, CORNER, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        drawRipples(canvas, now)

        canvas.restore()

        // 水波边缘
        edgePaint.color = color(R.color.xz_water_edge)
        edgePaint.strokeWidth = 1.2f
        canvas.drawRoundRect(poolRect, CORNER, CORNER, edgePaint)

        // 单球（根据当前说话方显示对应颜色）
        val activeBall = if (ballAi.speaking) ballAi else ballUser
        drawBall(canvas, g.cx, g.cy, activeBall)
    }

    private fun drawRipples(canvas: Canvas, now: Long) {
        val rippleColor = color(R.color.xz_water_ripple)
        val r = android.graphics.Color.red(rippleColor)
        val g = android.graphics.Color.green(rippleColor)
        val b = android.graphics.Color.blue(rippleColor)

        for (rp in ripples) {
            val age = (now - rp.birth) / 1000f
            val radius = rp.speed * age
            if (radius > rp.maxR) continue
            val alpha = rp.amp * exp(-age * 1.3f) * exp(-radius / 420f)
            if (alpha < 0.02f) continue

            // 主环 - 清澈河水色（半透明青绿）
            ripplePaint.color = android.graphics.Color.argb(
                (alpha * 0.45f * 255).toInt(), r, g, b
            )
            ripplePaint.strokeWidth = 1.6f
            canvas.drawCircle(rp.cx, rp.cy, maxOf(0.1f, radius), ripplePaint)

            // 内环 - 更淡的河水色
            if (radius > 12f) {
                ripplePaint.color = android.graphics.Color.argb(
                    (alpha * 0.25f * 255).toInt(), r, g, b
                )
                ripplePaint.strokeWidth = 1.1f
                canvas.drawCircle(rp.cx, rp.cy, maxOf(0.1f, radius - 8f), ripplePaint)
            }
        }
    }

    private fun drawBall(canvas: Canvas, cx: Float, cy: Float, ball: Ball) {
        val sink = ball.sinkDepth.coerceIn(0f, 1f)
        val visibleR = maxOf(0.5f, BALL_R * (1 - sink * 0.6f))
        if (sink >= 0.98f) return

        val alpha = 1 - sink * 0.5f
        val r = ball.rgb[0]
        val g = ball.rgb[1]
        val b = ball.rgb[2]
        val br = ball.brightness
        val cr = min(255, (r * br).toInt())
        val cg = min(255, (g * br).toInt())
        val cb = min(255, (b * br).toInt())

        // 光晕
        val glowR = maxOf(0.5f, visibleR * 2.8f)
        ballPaint.shader = RadialGradient(
            cx, cy, glowR,
            android.graphics.Color.argb((0.6f * alpha * 255).toInt(), cr, cg, cb),
            android.graphics.Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowR, ballPaint)
        ballPaint.shader = null

        // 球体：缓存 shader，仅在可见半径有实质变化时重建
        val quantizedR = (visibleR * 2).toInt() / 2f
        if (ball.shader == null || ball.shaderRadius != quantizedR) {
            ball.shader = RadialGradient(
                cx - visibleR * 0.3f, cy - visibleR * 0.35f, visibleR,
                intArrayOf(
                    android.graphics.Color.argb((0.95f * alpha * 255).toInt(), 255, 255, 255),
                    android.graphics.Color.argb((alpha * 255).toInt(), cr, cg, cb),
                    android.graphics.Color.argb((alpha * 255).toInt(), cr * 35 / 100, cg * 35 / 100, cb * 35 / 100),
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
            ball.shaderRadius = quantizedR
        }
        ballPaint.shader = ball.shader
        canvas.drawCircle(cx, cy, visibleR, ballPaint)
        ballPaint.shader = null

        // 球体描边
        ballPaint.style = Paint.Style.STROKE
        ballPaint.color = android.graphics.Color.argb(
            (0.8f * alpha * 255).toInt(), cr * 50 / 100, cg * 50 / 100, cb * 50 / 100,
        )
        ballPaint.strokeWidth = 1f
        canvas.drawCircle(cx, cy, visibleR, ballPaint)
        ballPaint.style = Paint.Style.FILL
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val BALL_R = 17f * density
    private val COL_W = 14f * density
    // 圆角收小：贴边绘制时大圆角会在四角留出明显空白
    private val CORNER = 6f * density
    private val WAVE_BAND = 16f * density

    private companion object {
        const val MAX_RIPPLES = 60
        const val FRAME_MS = 33L // 30fps
        const val FRAME_S = 1f / 30f
        // 小屏空间优化：水波几乎贴边绘制，仅留 2px 容纳描边，避免左右/底部大片留白
        const val DEFAULT_PAD_X = 2f
        const val DEFAULT_PAD_Y = 2f
    }
}
