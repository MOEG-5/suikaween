package dev.suikaween

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@SuppressLint("ViewConstructor")
class GameView(context: Context, private val world: World) : View(context) {
    private val prefs = context.getSharedPreferences("suikaween", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)

    private var running = false
    private var lastNanos = 0L
    private var time = 0f
    private var gameOverTime = -1f

    private var aimX = World.WIDTH / 2f
    private var aiming = false

    // World -> screen transform
    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    private var background: Bitmap? = null
    private val stars = List(70) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.75f, Random.nextFloat()) }

    private val bodyPath = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFFFFF
    }
    private val jarRect = RectF()
    private val tmpRect = RectF()

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int, val size: Float)
    private class Popup(val x: Float, var y: Float, val label: String, var life: Float)
    private class Ring(val x: Float, val y: Float, val maxR: Float, var life: Float, val color: Int)
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private val rings = ArrayList<Ring>()

    init {
        isHapticFeedbackEnabled = true
    }

    fun resume() {
        running = true
        lastNanos = 0L
        postInvalidateOnAnimation()
    }

    fun pause() {
        running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Layout in world units: HUD (24) + jar + evolution strip (16), with small side margins.
        val totalW = World.WIDTH + 8f
        val totalH = World.HEIGHT + 24f + 18f
        scale = min(w / totalW, h / totalH)
        originX = (w - World.WIDTH * scale) / 2f
        originY = (h - totalH * scale) / 2f + 24f * scale
        jarRect.set(originX, originY, originX + World.WIDTH * scale, originY + World.HEIGHT * scale)
        dashed.strokeWidth = 0.4f * scale
        dashed.pathEffect = DashPathEffect(floatArrayOf(2f * scale, 2f * scale), 0f)
        background = buildBackground(w, h)
    }

    private fun buildBackground(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF140A26.toInt(), 0xFF2A1240.toInt(), 0xFF3D1A3A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Moon
        val mx = w * 0.55f
        val my = h * 0.075f
        val mr = min(w, h) * 0.07f
        p.shader = RadialGradient(mx, my, mr * 3f, 0x55FFE7A8, 0x00FFE7A8, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, mr * 3f, p)
        p.shader = null
        p.color = 0xFFFFF1C9.toInt()
        c.drawCircle(mx, my, mr, p)
        p.color = 0x22B8A27A
        c.drawCircle(mx - mr * 0.3f, my - mr * 0.2f, mr * 0.22f, p)
        c.drawCircle(mx + mr * 0.35f, my + mr * 0.3f, mr * 0.15f, p)
        // Hills and graveyard silhouette
        p.color = 0xFF120818.toInt()
        val hills = Path()
        hills.moveTo(0f, h * 0.9f)
        hills.quadTo(w * 0.25f, h * 0.8f, w * 0.5f, h * 0.88f)
        hills.quadTo(w * 0.75f, h * 0.95f, w.toFloat(), h * 0.84f)
        hills.lineTo(w.toFloat(), h.toFloat())
        hills.lineTo(0f, h.toFloat())
        hills.close()
        c.drawPath(hills, p)
        val stone = RectF()
        for ((fx, fy) in listOf(0.08f to 0.87f, 0.18f to 0.85f, 0.88f to 0.85f)) {
            val sw = w * 0.05f
            stone.set(w * fx, h * fy - sw * 1.3f, w * fx + sw, h * fy + sw)
            c.drawRoundRect(stone, sw / 2f, sw / 2f, p)
        }
        return bmp
    }

    // ---- Game loop ----

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f)
        lastNanos = now
        if (running) {
            time += dt
            world.update(dt)
            consumeEvents()
            updateEffects(dt)
            if (world.gameOver && gameOverTime < 0f) {
                gameOverTime = time
                if (world.score > best) {
                    best = world.score
                    prefs.edit().putInt("best", best).apply()
                }
            }
        }
        render(canvas)
        if (running) postInvalidateOnAnimation()
    }

    private fun consumeEvents() {
        if (world.events.isEmpty()) return
        for (e in world.events) {
            val c = e.creature ?: e.from
            val count = 10 + c.ordinal * 2
            repeat(count) {
                val a = Random.nextFloat() * 2f * PI.toFloat()
                val sp = 30f + Random.nextFloat() * 50f + c.ordinal * 4f
                val col = if (Random.nextBoolean()) c.color else 0xFFFFD447.toInt()
                particles.add(Particle(e.x, e.y, cos(a) * sp, sin(a) * sp - 20f, 0.6f + Random.nextFloat() * 0.4f, col, 0.6f + Random.nextFloat() * 0.8f))
            }
            rings.add(Ring(e.x, e.y, c.radius * 1.6f, 0.35f, c.color))
            popups.add(Popup(e.x, e.y, "+${e.points}", 0.9f))
            performHapticFeedback(
                if (c.ordinal >= 6) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP,
            )
        }
        world.events.clear()
        if (world.score > best) best = world.score
    }

    private fun updateEffects(dt: Float) {
        particles.removeAll { p ->
            p.vy += 120f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
            p.life <= 0f
        }
        popups.removeAll { p ->
            p.y -= 18f * dt
            p.life -= dt
            p.life <= 0f
        }
        rings.removeAll { r ->
            r.life -= dt
            r.life <= 0f
        }
    }

    // ---- Rendering ----

    private fun sx(x: Float) = originX + x * scale
    private fun sy(y: Float) = originY + y * scale

    private fun render(c: Canvas) {
        background?.let { c.drawBitmap(it, 0f, 0f, null) } ?: c.drawColor(0xFF140A26.toInt())
        drawStars(c)
        drawJar(c)

        c.save()
        c.clipRect(jarRect.left - 40f * scale, 0f, jarRect.right + 40f * scale, jarRect.bottom)
        for (b in world.blobs) drawBlob(c, b)
        c.restore()

        drawHeld(c)
        drawEffects(c)
        drawHud(c)
        if (world.gameOver) drawGameOver(c)
    }

    private fun drawStars(c: Canvas) {
        fill.style = Paint.Style.FILL
        for (s in stars) {
            val tw = 0.5f + 0.5f * sin(time * (1f + s[2] * 2f) + s[2] * 10f)
            fill.color = Color.argb((60 + 150 * tw).toInt(), 255, 245, 220)
            c.drawCircle(s[0] * width, s[1] * height, (1f + s[2] * 1.5f) * resources.displayMetrics.density, fill)
        }
    }

    private fun drawJar(c: Canvas) {
        val pad = 1.2f * scale
        tmpRect.set(jarRect.left - pad, jarRect.top - 6f * scale, jarRect.right + pad, jarRect.bottom + pad)
        fill.style = Paint.Style.FILL
        fill.color = 0x40100620
        c.drawRoundRect(tmpRect, 4f * scale, 4f * scale, fill)
        stroke.strokeWidth = 1.2f * scale
        stroke.color = 0xFF9A6BD1.toInt()
        val p = Path()
        p.moveTo(tmpRect.left, tmpRect.top)
        p.lineTo(tmpRect.left, tmpRect.bottom - 4f * scale)
        p.quadTo(tmpRect.left, tmpRect.bottom, tmpRect.left + 4f * scale, tmpRect.bottom)
        p.lineTo(tmpRect.right - 4f * scale, tmpRect.bottom)
        p.quadTo(tmpRect.right, tmpRect.bottom, tmpRect.right, tmpRect.bottom - 4f * scale)
        p.lineTo(tmpRect.right, tmpRect.top)
        c.drawPath(p, stroke)

        // Danger line: faint normally, pulsing red as the timer runs.
        val d = world.danger
        val pulse = if (d > 0f) 0.5f + 0.5f * sin(time * (8f + 12f * d)) else 0f
        stroke.color = if (d > 0f) Color.argb((120 + 135 * pulse).toInt(), 255, 60, 60) else 0x55FF8A3D
        stroke.strokeWidth = (0.5f + d) * scale
        stroke.pathEffect = DashPathEffect(floatArrayOf(3f * scale, 2f * scale), time * 10f)
        c.drawLine(jarRect.left, sy(World.DANGER_Y), jarRect.right, sy(World.DANGER_Y), stroke)
        stroke.pathEffect = null
    }

    private fun buildBodyPath(b: Blob) {
        val n = World.SEGMENTS
        bodyPath.reset()
        fun px(k: Int) = sx(b.x + b.rad[k] * World.COS[k])
        fun py(k: Int) = sy(b.y + b.rad[k] * World.SIN[k])
        // Smooth closed curve through the midpoints of the outline vertices.
        bodyPath.moveTo((px(n - 1) + px(0)) / 2f, (py(n - 1) + py(0)) / 2f)
        for (k in 0 until n) {
            val k2 = (k + 1) % n
            bodyPath.quadTo(px(k), py(k), (px(k) + px(k2)) / 2f, (py(k) + py(k2)) / 2f)
        }
        bodyPath.close()
    }

    private fun drawBlob(c: Canvas, b: Blob) {
        val cr = b.creature
        val px = sx(b.x)
        val py = sy(b.y)
        val unit = b.r * scale

        val artDeg = Math.toDegrees(b.artAngle.toDouble()).toFloat()
        val ax = unit * cr.artScaleX
        val ay = unit * cr.artScaleY

        c.save()
        c.translate(px, py)
        c.rotate(artDeg)
        c.scale(ax, ay)
        CreatureArt.drawBehind(c, cr, time + b.hashCode() % 100)
        c.restore()

        buildBodyPath(b)
        fill.style = Paint.Style.FILL
        fill.color = cr.color
        c.drawPath(bodyPath, fill)

        c.save()
        c.clipPath(bodyPath)
        c.save()
        c.translate(px, py)
        c.rotate(artDeg)
        c.scale(ax, ay)
        CreatureArt.drawFace(c, cr, time + b.hashCode() % 100)
        c.restore()
        // Lighting stays screen-aligned so the shine doesn't spin with the body.
        c.translate(px, py)
        c.scale(unit, unit)
        CreatureArt.drawLighting(c)
        c.restore()

        stroke.color = CreatureArt.outlineColor(cr)
        stroke.strokeWidth = max(1.5f, 0.07f * unit)
        c.drawPath(bodyPath, stroke)
    }

    private fun drawHeld(c: Canvas) {
        if (world.gameOver) return
        val cr = world.current
        val x = world.clampDropX(aimX)
        val ready = world.canDrop()
        if (ready) c.drawLine(sx(x), sy(World.DROP_Y), sx(x), jarRect.bottom, dashed)
        c.save()
        c.translate(sx(x), sy(World.DROP_Y))
        val bob = sin(time * 3f) * 0.04f
        val u = cr.radius * scale * (if (ready) 1f else 0.85f)
        c.scale(u, u * (1f + bob))
        CreatureArt.drawIcon(c, cr, time)
        c.restore()
    }

    private fun drawEffects(c: Canvas) {
        fill.style = Paint.Style.FILL
        for (r in rings) {
            val t = 1f - r.life / 0.35f
            stroke.color = (r.color and 0x00FFFFFF) or (((1f - t) * 200).toInt() shl 24)
            stroke.strokeWidth = (1f - t) * 1.5f * scale + 1f
            c.drawCircle(sx(r.x), sy(r.y), r.maxR * t * scale, stroke)
        }
        for (p in particles) {
            fill.color = (p.color and 0x00FFFFFF) or ((min(1f, p.life * 2f) * 255).toInt() shl 24)
            c.drawCircle(sx(p.x), sy(p.y), p.size * scale, fill)
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 5f * scale
        for (p in popups) {
            text.color = Color.argb((min(1f, p.life * 2f) * 255).toInt(), 255, 220, 120)
            c.drawText(p.label, sx(p.x), sy(p.y), text)
        }
        text.color = Color.WHITE
    }

    private fun drawHud(c: Canvas) {
        val top = originY - 24f * scale
        text.textAlign = Paint.Align.LEFT
        text.color = 0xFFFFB347.toInt()
        text.textSize = 4f * scale
        c.drawText("SCORE", jarRect.left, top + 6f * scale, text)
        text.color = Color.WHITE
        text.textSize = 8f * scale
        c.drawText(world.score.toString(), jarRect.left, top + 14.5f * scale, text)
        text.textSize = 3.4f * scale
        text.color = 0xAAFFFFFF.toInt()
        c.drawText("BEST $best", jarRect.left, top + 19.5f * scale, text)

        // Next bubble
        val nx = jarRect.right - 9f * scale
        val ny = top + 11f * scale
        fill.style = Paint.Style.FILL
        fill.color = 0x40FFFFFF
        c.drawCircle(nx, ny, 8f * scale, fill)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 3.2f * scale
        text.color = 0xFFFFB347.toInt()
        c.drawText("NEXT", nx, top + 1.5f * scale, text)
        c.save()
        c.translate(nx, ny)
        val nc = world.next
        val u = min(nc.radius, 6.2f / max(nc.artScaleX, nc.artScaleY)) * scale
        c.scale(u, u)
        CreatureArt.drawIcon(c, world.next, time)
        c.restore()

        // Evolution strip
        val stripY = jarRect.bottom + 10f * scale
        val count = Creature.entries.size
        val step = World.WIDTH / count
        for (cr in Creature.entries) {
            val cx = jarRect.left + (cr.ordinal + 0.5f) * step * scale
            c.save()
            c.translate(cx, stripY)
            val u = min(2.2f + cr.ordinal * 0.16f, 4.2f / max(cr.artScaleX, cr.artScaleY)) * scale
            c.scale(u, u)
            CreatureArt.drawIcon(c, cr, time)
            c.restore()
        }
        text.color = Color.WHITE
    }

    private fun drawGameOver(c: Canvas) {
        val t = time - gameOverTime
        fill.style = Paint.Style.FILL
        fill.color = Color.argb((min(1f, t * 2f) * 170).toInt(), 10, 0, 20)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFFF8A1C.toInt()
        text.textSize = 11f * scale
        val cx = width / 2f
        val cy = height / 2f
        c.drawText("GAME OVER", cx, cy - 10f * scale, text)
        text.color = Color.WHITE
        text.textSize = 6f * scale
        c.drawText("Score ${world.score}", cx, cy + 2f * scale, text)
        text.textSize = 4f * scale
        text.color = 0xCCFFFFFF.toInt()
        c.drawText("Best $best", cx, cy + 9f * scale, text)
        if (t > 1f) {
            text.color = Color.argb((180 + 75 * sin(time * 4f)).toInt(), 255, 210, 120)
            c.drawText("Tap to rise again", cx, cy + 22f * scale, text)
        }
    }

    // ---- Input ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wx = (event.x - originX) / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (world.gameOver) {
                    if (time - gameOverTime > 1f) restart()
                    return true
                }
                aiming = true
                aimX = wx
            }
            MotionEvent.ACTION_MOVE -> if (aiming) aimX = wx
            MotionEvent.ACTION_UP -> {
                if (aiming) {
                    aimX = wx
                    if (world.drop(aimX) != null) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                aiming = false
            }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
        return true
    }

    private fun restart() {
        world.reset()
        particles.clear()
        popups.clear()
        rings.clear()
        gameOverTime = -1f
        aimX = World.WIDTH / 2f
    }
}
