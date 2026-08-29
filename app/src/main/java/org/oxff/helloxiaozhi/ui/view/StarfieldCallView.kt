package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 二进制星河通话动画，逐段移植设计稿 call.js 的 drawPool / drawBall / geom /
 * initDigitStreams / spawnRipple / waveIntensityAt：
 *
 *  - 0/1 数字流自上而下坠落，头部亮青、尾部指数衰减；
 *  - 左右两球（小智 / 你）在星河表面沉浮，说话方主动震动并发出涟漪；
 *  - 涟漪波前经过处数字被「扰动」提亮，非说话方被涟漪推得轻微下沉。
 *
 * 性能设计（中端机 30fps 预算）：
 *  - 目标 30fps 而非 60：效果是缓慢漂移，无从分辨；
 *  - waveIntensityAt 缓存到粗网格（每列 × 16px 带一个值，每帧算一次），
 *    把每帧约 1.8 万次 hypot/exp 降到约 450 次；
 *  - 数字头部发光用「大字低 alpha + 小字高 alpha」两次绘制，取代逐字
 *    setShadowLayer（后者会强制软件层）；
 *  - onDraw 内零分配：Paint / Path / RectF 全部预建复用；
 *  - 径向渐变 shader 按球缓存，仅在可见半径有实质变化时重建。
 *
 * 线程模型：由 Choreographer 在主线程驱动；setSpeaking 可从任意线程调用。
 */
class StarfieldCallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---------------- 公开 API ----------------

    /** 左球（小智 / AI）是否正在说话 */
    fun setAiSpeaking(speaking: Boolean) {
        if (ballAi.speaking == speaking) return
        ballAi.speaking = speaking
    }

    /** 右球（你 / 用户）是否正在说话 */
    fun setUserSpeaking(speaking: Boolean) {
        if (ballUser.speaking == speaking) return
        ballUser.speaking = speaking
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

    private class Ball(val baseColor: Int) {
        var speaking = false
        var sinkPhase = 0f
        var sinkDepth = 0f
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

    private class DigitStream(
        val x: Float,
        var y: Float,
        var speed: Float,
        val chars: CharArray,
        var headBright: Float,
    )

    private val ballAi = Ball(aiColor())
    private val ballUser = Ball(userColor())
    private val ripples = ArrayList<Ripple>()
    private val digitStreams = ArrayList<DigitStream>()
    private var lastRippleAi = 0L
    private var lastRippleUser = 0L

    private var running = false
    private var lastFrameNanos = 0L

    // 预建的绘制对象（onDraw 零分配）
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val digitGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
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
        initDigitStreams()
        initWaveGrid()
    }

    private fun initDigitStreams() {
        digitStreams.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cols = maxOf(4, (w / COL_W).toInt())
        repeat(cols) { i ->
            val len = 6 + Random.nextInt(10)
            digitStreams.add(
                DigitStream(
                    x = i * COL_W + COL_W / 2f + (Random.nextFloat() * 4f - 2f),
                    y = Random.nextFloat() * h,
                    speed = DIGIT_SPEED_MIN + Random.nextFloat() * (DIGIT_SPEED_MAX - DIGIT_SPEED_MIN),
                    chars = CharArray(len) { if (Random.nextBoolean()) '0' else '1' },
                    headBright = 0.6f + Random.nextFloat() * 0.4f,
                ),
            )
        }
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
        updateBall(ballAi, g.b1x, g.b1y, now, isLeft = true)
        updateBall(ballUser, g.b2x, g.b2y, now, isLeft = false)

        // 被动震动：非说话方被涟漪推得轻微下沉，否则逐渐浮回
        if (!ballAi.speaking) {
            val wave = waveIntensityAt(g.b1x, g.b1y, now)
            val passive = minOf(0.25f, wave * 0.2f) * (0.5f + 0.5f * sin(now / 90f))
            ballAi.sinkDepth = maxOf(ballAi.sinkDepth * 0.92f, passive)
        }
        if (!ballUser.speaking) {
            val wave = waveIntensityAt(g.b2x, g.b2y, now)
            val passive = minOf(0.25f, wave * 0.2f) * (0.5f + 0.5f * sin(now / 90f))
            ballUser.sinkDepth = maxOf(ballUser.sinkDepth * 0.92f, passive)
        }

        // 推进数字流
        for (s in digitStreams) {
            s.y += s.speed * dt
            val streamLen = s.chars.size * DIGIT_STEP
            if (s.y - streamLen > h + 20) {
                s.y = -Random.nextFloat() * 60
                s.speed = DIGIT_SPEED_MIN + Random.nextFloat() * (DIGIT_SPEED_MAX - DIGIT_SPEED_MIN)
                s.headBright = 0.6f + Random.nextFloat() * 0.4f
            }
            if (Random.nextFloat() < 0.08f) mutateStream(s)
        }

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

    private fun updateBall(ball: Ball, bx: Float, by: Float, now: Long, isLeft: Boolean) {
        if (ball.speaking) {
            ball.sinkPhase = (now / 1400f) % 1f
            val p = ball.sinkPhase
            val d = when {
                p < 0.25f -> p / 0.25f * 0.3f
                p < 0.5f -> 0.3f + (p - 0.25f) / 0.25f * 0.7f
                p < 0.75f -> 1f - (p - 0.5f) / 0.25f * 0.7f
                else -> 0.3f - (p - 0.75f) / 0.25f * 0.3f
            }
            ball.sinkDepth = d.coerceIn(0f, 1f)
            val lastRipple = if (isLeft) lastRippleAi else lastRippleUser
            if (now - lastRipple > RIPPLE_INTERVAL_MS) {
                spawnRipple(bx, by, geom().maxRippleR, 1.0f)
                if (isLeft) lastRippleAi = now else lastRippleUser = now
            }
        } else {
            ball.sinkPhase = 0f
        }
    }

    private fun spawnRipple(cx: Float, cy: Float, maxR: Float, amp: Float) {
        ripples.add(Ripple(cx, cy, maxR, amp, 60f + Random.nextFloat() * 15f, System.currentTimeMillis()))
    }

    private fun mutateStream(s: DigitStream) {
        val idx = Random.nextInt(s.chars.size)
        s.chars[idx] = if (Random.nextBoolean()) '0' else '1'
    }

    // ---------------- 几何 ----------------

    private class Geom(
        val x0: Float, val x1: Float, val y0: Float, val y1: Float,
        val b1x: Float, val b1y: Float, val b2x: Float, val b2y: Float,
        val maxRippleR: Float,
    )

    private fun geom(): Geom {
        val padX = 14f
        val padY = 10f
        val x0 = padX
        val x1 = width - padX
        val y0 = padY
        val y1 = height - padY
        val cy = (y0 + y1) / 2f
        return Geom(
            x0, x1, y0, y1,
            x0 + BALL_R + 14f, cy,
            x1 - BALL_R - 14f, cy,
            hypot(x1 - x0, y1 - y0),
        )
    }

    // ---------------- waveIntensityAt（含粗网格缓存） ----------------

    /** 重建粗网格：每列 × 每 16px 带一个值，供本帧内所有数字查询 */
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

    /** 实际计算某点的涟漪扰动强度（仅供粗网格与球体被动震动调用） */
    private fun computeWaveIntensity(px: Float, py: Float, now: Long): Float {
        var sum = 0f
        for (rp in ripples) {
            val age = (now - rp.birth) / 1000f
            val d = hypot(px - rp.cx, py - rp.cy)
            val band = kotlin.math.abs(d - rp.speed * age)
            if (band > 16f) continue
            val decay = exp(-age * 1.4f) * exp(-d / 300f)
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

        // 星河背景：深邃底色 + 微弱星云高光
        poolRect.set(g.x0, g.y0, g.x1, g.y1)
        bgPaint.shader = LinearGradient(
            0f, g.y0, 0f, g.y1,
            intArrayOf(color(R.color.xz_star_bg_top), color(R.color.xz_star_bg_mid), color(R.color.xz_star_bg_bottom)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(poolRect, CORNER, CORNER, bgPaint)
        bgPaint.shader = null

        // 裁剪到星河区域内绘制数字流与涟漪
        clipPath.reset()
        clipPath.addRoundRect(poolRect, CORNER, CORNER, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        drawDigitStreams(canvas, now)
        drawRipples(canvas, now)

        canvas.restore()

        // 星河边缘
        edgePaint.color = color(R.color.xz_star_edge)
        edgePaint.strokeWidth = 1.2f
        canvas.drawRoundRect(poolRect, CORNER, CORNER, edgePaint)

        // 双球
        drawBall(canvas, g.b1x, g.b1y, ballAi)
        drawBall(canvas, g.b2x, g.b2y, ballUser)
    }

    private fun drawDigitStreams(canvas: Canvas, now: Long) {
        digitPaint.textSize = DIGIT_TEXT_SIZE
        digitGlowPaint.textSize = DIGIT_TEXT_SIZE * 1.6f
        val headColor = color(R.color.xz_star_digit_head)
        val nearColor = color(R.color.xz_star_digit_near)
        val tailColor = color(R.color.xz_star_digit_tail)

        for (s in digitStreams) {
            for (j in s.chars.indices) {
                val dy = s.y - j * DIGIT_STEP
                if (dy < -14 || dy > height + 14) continue
                val isHead = j == 0
                val tailFade = exp(-j * 0.32f)
                val rippleBoost = minOf(1f, waveIntensityAt(s.x, dy, now) * 2.2f)
                var alpha = tailFade * (if (isHead) 0.95f else 0.55f)
                alpha = minOf(1f, alpha + rippleBoost * 0.6f)

                when {
                    isHead -> {
                        // 发光：先画一层放大的低 alpha，再画本体
                        digitGlowPaint.color = headColor
                        digitGlowPaint.alpha = (alpha * 0.35f * s.headBright * 255).toInt()
                        canvas.drawText(s.chars, j, 1, s.x, dy, digitGlowPaint)
                        digitPaint.color = headColor
                        digitPaint.alpha = (alpha * 255).toInt()
                        canvas.drawText(s.chars, j, 1, s.x, dy, digitPaint)
                    }
                    j < 3 -> {
                        digitPaint.color = nearColor
                        digitPaint.alpha = (alpha * 255).toInt()
                        canvas.drawText(s.chars, j, 1, s.x, dy, digitPaint)
                    }
                    else -> {
                        digitPaint.color = tailColor
                        digitPaint.alpha = (alpha * 0.7f * 255).toInt()
                        canvas.drawText(s.chars, j, 1, s.x, dy, digitPaint)
                    }
                }
            }
        }
    }

    private fun drawRipples(canvas: Canvas, now: Long) {
        val rippleColor = color(R.color.xz_star_ripple)
        for (rp in ripples) {
            val age = (now - rp.birth) / 1000f
            val r = rp.speed * age
            if (r > rp.maxR) continue
            val alpha = rp.amp * exp(-age * 1.3f) * exp(-r / 320f)
            if (alpha < 0.02f) continue
            // 主环
            ripplePaint.color = rippleColor
            ripplePaint.alpha = (alpha * 0.55f * 255).toInt()
            ripplePaint.strokeWidth = 1.4f
            canvas.drawCircle(rp.cx, rp.cy, maxOf(0.1f, r), ripplePaint)
            // 内环
            if (r > 8f) {
                ripplePaint.alpha = (alpha * 0.3f * 255).toInt()
                ripplePaint.strokeWidth = 1f
                canvas.drawCircle(rp.cx, rp.cy, maxOf(0.1f, r - 6f), ripplePaint)
            }
        }
    }

    private fun drawBall(canvas: Canvas, cx: Float, cy: Float, ball: Ball) {
        val sink = ball.sinkDepth.coerceIn(0f, 1f)
        val visibleR = maxOf(0.5f, BALL_R * (1 - sink * 0.85f))
        if (sink >= 0.98f) return

        val alpha = 1 - sink * 0.6f
        val baseColor = ball.baseColor
        val r = android.graphics.Color.red(baseColor)
        val g = android.graphics.Color.green(baseColor)
        val b = android.graphics.Color.blue(baseColor)

        // 光晕（随下沉减弱）
        val glowR = maxOf(0.5f, visibleR * 2.6f)
        ballPaint.shader = RadialGradient(
            cx, cy, glowR,
            android.graphics.Color.argb((0.55f * alpha * 255).toInt(), r, g, b),
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
                    android.graphics.Color.argb((alpha * 255).toInt(), r, g, b),
                    android.graphics.Color.argb((alpha * 255).toInt(), r * 35 / 100, g * 35 / 100, b * 35 / 100),
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
            (0.8f * alpha * 255).toInt(), r * 50 / 100, g * 50 / 100, b * 50 / 100,
        )
        ballPaint.strokeWidth = 1f
        canvas.drawCircle(cx, cy, visibleR, ballPaint)

        // 球与星河表面接触的阴影环（随下沉扩大变淡）
        ballPaint.color = android.graphics.Color.argb((0.35f * alpha * 255).toInt(), r, g, b)
        ballPaint.strokeWidth = 1.2f
        canvas.drawCircle(cx, cy, maxOf(0.5f, visibleR + 2.5f + sink * 4), ballPaint)
        ballPaint.style = Paint.Style.FILL
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)
    private fun aiColor(): Int = color(R.color.xz_ball_ai)
    private fun userColor(): Int = color(R.color.xz_ball_user)

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val BALL_R = 12f * density
    private val COL_W = 14f * density
    private val DIGIT_STEP = 13f * density
    private val DIGIT_TEXT_SIZE = 11f * scaledDensity
    private val CORNER = 8f * density
    private val WAVE_BAND = 16f * density

    private companion object {
        const val DIGIT_SPEED_MIN = 28f
        const val DIGIT_SPEED_MAX = 70f
        const val RIPPLE_INTERVAL_MS = 200L
        const val MAX_RIPPLES = 24
        const val FRAME_MS = 33L // 30fps
        const val FRAME_S = 1f / 30f
    }
}
