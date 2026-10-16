package dev.suikaween

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Procedural drawings for each creature. Everything is drawn in a unit space where the body is a
 * circle of radius 1 centred on the origin; the caller sets up translation, rotation and scale.
 *
 * [drawBehind] draws parts that poke outside the body (legs, wings, ears, stems), [drawFace]
 * draws details that are clipped to the squishy body outline.
 */
object CreatureArt {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(-0.35f, -0.45f, 0.75f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = RadialGradient(0.25f, 0.35f, 1.3f, 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
    }

    fun outlineColor(c: Creature): Int = darken(c.color, 0.55f)

    fun darken(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * f).toInt(),
        (Color.green(color) * f).toInt(),
        (Color.blue(color) * f).toInt(),
    )

    private fun f(color: Int): Paint = fill.apply { this.color = color }
    private fun s(color: Int, width: Float): Paint = stroke.apply { this.color = color; strokeWidth = width }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) = c.drawCircle(x, y, r, f(color))

    private fun ovalAt(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int) {
        oval.set(x - rx, y - ry, x + rx, y + ry)
        c.drawOval(oval, f(color))
    }

    /** Two shiny cartoon eyes. */
    private fun eyes(c: Canvas, spread: Float, y: Float, size: Float, iris: Int = Color.BLACK) {
        for (sx in floatArrayOf(-spread, spread)) {
            ovalAt(c, sx, y, size, size * 1.15f, iris)
            circle(c, sx - size * 0.3f, y - size * 0.4f, size * 0.35f, Color.WHITE)
        }
    }

    private fun smile(c: Canvas, y: Float, w: Float, color: Int, width: Float) {
        oval.set(-w, y - w * 0.8f, w, y + w * 0.6f)
        c.drawArc(oval, 20f, 140f, false, s(color, width))
    }

    private fun blush(c: Canvas, spread: Float, y: Float, color: Int = 0x55FF5577) {
        ovalAt(c, -spread, y, 0.14f, 0.08f, color)
        ovalAt(c, spread, y, 0.14f, 0.08f, color)
    }

    fun drawBehind(c: Canvas, creature: Creature, time: Float) {
        when (creature) {
            Creature.SPIDER -> {
                val wiggle = kotlin.math.sin(time * 6f) * 0.06f
                val legs = s(0xFF1A141F.toInt(), 0.12f)
                for (side in intArrayOf(-1, 1)) {
                    for (i in 0 until 4) {
                        val a = -0.55f + i * 0.4f + if (i % 2 == 0) wiggle else -wiggle
                        path.reset()
                        path.moveTo(0f, 0f)
                        path.lineTo(side * 1.15f, a - 0.35f)
                        path.lineTo(side * 1.4f, a + 0.25f)
                        c.drawPath(path, legs)
                    }
                }
            }
            Creature.BAT -> {
                val flap = kotlin.math.sin(time * 9f) * 0.15f
                val wing = f(0xFF3E2A57.toInt())
                for (side in intArrayOf(-1, 1)) {
                    path.reset()
                    path.moveTo(side * 0.6f, -0.3f)
                    path.lineTo(side * 1.55f, -0.75f - flap)
                    path.lineTo(side * 1.45f, -0.15f - flap)
                    path.lineTo(side * 1.25f, 0.05f - flap * 0.5f)
                    path.lineTo(side * 1.05f, 0.0f)
                    path.lineTo(side * 0.85f, 0.25f)
                    path.lineTo(side * 0.6f, 0.3f)
                    path.close()
                    c.drawPath(path, wing)
                }
                for (side in intArrayOf(-1, 1)) {
                    path.reset()
                    path.moveTo(side * 0.25f, -0.8f)
                    path.lineTo(side * 0.45f, -1.25f)
                    path.lineTo(side * 0.65f, -0.65f)
                    path.close()
                    c.drawPath(path, f(creature.color))
                }
            }
            Creature.BLACK_CAT -> {
                for (side in intArrayOf(-1, 1)) {
                    path.reset()
                    path.moveTo(side * 0.25f, -0.85f)
                    path.lineTo(side * 0.7f, -1.3f)
                    path.lineTo(side * 0.85f, -0.5f)
                    path.close()
                    c.drawPath(path, f(creature.color))
                    path.reset()
                    path.moveTo(side * 0.38f, -0.82f)
                    path.lineTo(side * 0.66f, -1.12f)
                    path.lineTo(side * 0.74f, -0.62f)
                    path.close()
                    c.drawPath(path, f(0xFF8A4F6E.toInt()))
                }
            }
            Creature.PUMPKIN, Creature.JACK_O_LANTERN -> {
                path.reset()
                path.moveTo(-0.08f, -0.85f)
                path.quadTo(-0.05f, -1.15f, 0.12f, -1.3f)
                path.lineTo(0.2f, -1.2f)
                path.quadTo(0.08f, -1.05f, 0.1f, -0.85f)
                path.close()
                c.drawPath(path, f(0xFF4E6B2A.toInt()))
                if (creature == Creature.PUMPKIN) {
                    path.reset()
                    path.moveTo(0.1f, -1.0f)
                    path.quadTo(0.45f, -1.25f, 0.55f, -1.0f)
                    c.drawPath(path, s(0xFF5F8A30.toInt(), 0.05f))
                }
            }
            Creature.CAULDRON -> {
                // Legs
                for (side in floatArrayOf(-0.55f, 0.55f)) {
                    path.reset()
                    path.moveTo(side - 0.12f, 0.75f)
                    path.lineTo(side * 1.25f, 1.12f)
                    path.lineTo(side + 0.12f * kotlin.math.sign(side), 0.75f)
                    path.close()
                    c.drawPath(path, f(0xFF22252B.toInt()))
                }
            }
            else -> Unit
        }
    }

    fun drawFace(c: Canvas, creature: Creature, time: Float) {
        when (creature) {
            Creature.CANDY_CORN -> {
                c.drawRect(-1.5f, 0.3f, 1.5f, 1.5f, f(0xFFFFD23F.toInt()))
                c.drawRect(-1.5f, -1.5f, 1.5f, -0.4f, f(0xFFFFF8E7.toInt()))
                eyes(c, 0.3f, -0.05f, 0.12f)
                smile(c, 0.08f, 0.18f, Color.BLACK, 0.07f)
            }
            Creature.EYEBALL -> {
                val veins = s(0xAAD03030.toInt(), 0.045f)
                for (i in 0 until 6) {
                    val a = i * 1.05f + 0.3f
                    val cx = kotlin.math.cos(a)
                    val sy = kotlin.math.sin(a)
                    path.reset()
                    path.moveTo(cx * 1.05f, sy * 1.05f)
                    path.quadTo(cx * 0.8f + sy * 0.12f, sy * 0.8f - cx * 0.12f, cx * 0.6f, sy * 0.6f)
                    c.drawPath(path, veins)
                }
                val look = kotlin.math.sin(time * 1.3f) * 0.12f
                circle(c, look, 0f, 0.5f, 0xFF3FA34D.toInt())
                circle(c, look, 0f, 0.38f, 0xFF2C7A39.toInt())
                circle(c, look, 0f, 0.24f, Color.BLACK)
                circle(c, look - 0.15f, -0.17f, 0.1f, Color.WHITE)
            }
            Creature.SPIDER -> {
                circle(c, 0f, 0.45f, 0.35f, 0xFF3B2F47.toInt())
                for ((x, y, r) in listOf(Triple(-0.28f, -0.25f, 0.17f), Triple(0.28f, -0.25f, 0.17f),
                    Triple(-0.5f, -0.5f, 0.09f), Triple(0.5f, -0.5f, 0.09f),
                    Triple(-0.12f, -0.55f, 0.08f), Triple(0.12f, -0.55f, 0.08f))) {
                    circle(c, x, y, r, 0xFFFF3B3B.toInt())
                    circle(c, x - r * 0.3f, y - r * 0.35f, r * 0.3f, 0xCCFFFFFF.toInt())
                }
                // Fangs
                for (side in floatArrayOf(-0.1f, 0.1f)) {
                    path.reset()
                    path.moveTo(side - 0.06f, 0.05f)
                    path.lineTo(side + 0.06f, 0.05f)
                    path.lineTo(side, 0.22f)
                    path.close()
                    c.drawPath(path, f(0xFFEDE6F2.toInt()))
                }
            }
            Creature.BAT -> {
                ovalAt(c, 0f, 0.3f, 0.55f, 0.5f, 0xFF7A5C9C.toInt())
                eyes(c, 0.32f, -0.25f, 0.17f, 0xFFFFD447.toInt())
                circle(c, -0.32f, -0.22f, 0.08f, Color.BLACK)
                circle(c, 0.32f, -0.22f, 0.08f, Color.BLACK)
                smile(c, 0.0f, 0.25f, 0xFF1E1328.toInt(), 0.07f)
                for (side in floatArrayOf(-0.14f, 0.14f)) {
                    path.reset()
                    path.moveTo(side - 0.07f, 0.17f)
                    path.lineTo(side + 0.07f, 0.17f)
                    path.lineTo(side, 0.36f)
                    path.close()
                    c.drawPath(path, f(Color.WHITE))
                }
            }
            Creature.GHOST -> {
                // Wavy tail hem
                path.reset()
                path.moveTo(-1.5f, 1.5f)
                path.lineTo(-1.5f, 0.75f)
                var x = -1.5f
                val wave = kotlin.math.sin(time * 3f) * 0.05f
                while (x < 1.5f) {
                    path.quadTo(x + 0.15f, 0.62f + wave, x + 0.3f, 0.75f)
                    path.quadTo(x + 0.45f, 0.88f - wave, x + 0.6f, 0.75f)
                    x += 0.6f
                }
                path.lineTo(1.5f, 1.5f)
                path.close()
                c.drawPath(path, f(0xFFC3D2F2.toInt()))
                ovalAt(c, -0.3f, -0.2f, 0.16f, 0.24f, 0xFF20203A.toInt())
                ovalAt(c, 0.3f, -0.2f, 0.16f, 0.24f, 0xFF20203A.toInt())
                circle(c, -0.35f, -0.28f, 0.06f, Color.WHITE)
                circle(c, 0.25f, -0.28f, 0.06f, Color.WHITE)
                ovalAt(c, 0f, 0.22f, 0.13f, 0.17f + kotlin.math.sin(time * 2f) * 0.03f, 0xFF20203A.toInt())
                blush(c, 0.55f, 0.08f, 0x44FF7799)
            }
            Creature.SKULL -> {
                val dark = 0xFF2A2230.toInt()
                path.reset()
                path.moveTo(-0.55f, -0.35f)
                path.quadTo(-0.3f, -0.5f, -0.1f, -0.25f)
                path.quadTo(-0.12f, 0.05f, -0.35f, 0.08f)
                path.quadTo(-0.6f, 0.05f, -0.55f, -0.35f)
                path.close()
                c.drawPath(path, f(dark))
                c.save()
                c.scale(-1f, 1f)
                c.drawPath(path, f(dark))
                c.restore()
                circle(c, -0.3f, -0.15f, 0.07f, 0xFFFF8A1C.toInt())
                circle(c, 0.3f, -0.15f, 0.07f, 0xFFFF8A1C.toInt())
                path.reset()
                path.moveTo(0f, 0.12f)
                path.lineTo(-0.09f, 0.3f)
                path.lineTo(0.09f, 0.3f)
                path.close()
                c.drawPath(path, f(dark))
                c.drawRect(-0.42f, 0.45f, 0.42f, 0.7f, f(dark))
                for (i in 0 until 5) {
                    val tx = -0.36f + i * 0.18f
                    c.drawRect(tx - 0.07f, 0.47f, tx + 0.07f, 0.68f, f(0xFFF3EBD6.toInt()))
                }
                c.drawLine(0.55f, -0.75f, 0.42f, -0.55f, s(0x66000000, 0.035f))
                c.drawLine(0.42f, -0.55f, 0.5f, -0.45f, s(0x66000000, 0.035f))
            }
            Creature.BLACK_CAT -> {
                ovalAt(c, -0.33f, -0.1f, 0.2f, 0.22f, 0xFF9BE15D.toInt())
                ovalAt(c, 0.33f, -0.1f, 0.2f, 0.22f, 0xFF9BE15D.toInt())
                val blink = (kotlin.math.sin(time * 0.9f) > 0.97f)
                if (blink) {
                    ovalAt(c, -0.33f, -0.1f, 0.21f, 0.05f, creature.color)
                    ovalAt(c, 0.33f, -0.1f, 0.21f, 0.05f, creature.color)
                } else {
                    ovalAt(c, -0.33f, -0.1f, 0.05f, 0.18f, Color.BLACK)
                    ovalAt(c, 0.33f, -0.1f, 0.05f, 0.18f, Color.BLACK)
                }
                path.reset()
                path.moveTo(-0.07f, 0.12f)
                path.lineTo(0.07f, 0.12f)
                path.lineTo(0f, 0.2f)
                path.close()
                c.drawPath(path, f(0xFFE58FB1.toInt()))
                val w = s(0x99FFFFFF.toInt(), 0.025f)
                for (side in intArrayOf(-1, 1)) {
                    c.drawLine(side * 0.2f, 0.25f, side * 0.75f, 0.15f, w)
                    c.drawLine(side * 0.2f, 0.3f, side * 0.75f, 0.33f, w)
                }
                oval.set(-0.2f, 0.12f, 0f, 0.35f)
                c.drawArc(oval, 0f, 180f, false, s(0xFFBFBFBF.toInt(), 0.03f))
                oval.set(0f, 0.12f, 0.2f, 0.35f)
                c.drawArc(oval, 0f, 180f, false, s(0xFFBFBFBF.toInt(), 0.03f))
            }
            Creature.CAULDRON -> {
                // Bubbling brew
                val slime = 0xFF6EE04A.toInt()
                path.reset()
                path.moveTo(-1.5f, -0.45f)
                var x = -1.5f
                while (x < 1.5f) {
                    val bob = kotlin.math.sin(time * 4f + x * 3f) * 0.05f
                    path.quadTo(x + 0.15f, -0.62f + bob, x + 0.3f, -0.45f)
                    x += 0.3f
                }
                path.lineTo(1.5f, -1.5f)
                path.lineTo(-1.5f, -1.5f)
                path.close()
                c.drawPath(path, f(slime))
                for (i in 0 until 3) {
                    val phase = (time * 0.7f + i * 0.33f) % 1f
                    circle(c, -0.4f + i * 0.4f, -0.55f - phase * 0.4f, 0.08f + phase * 0.05f, 0xCC9CFF7A.toInt())
                }
                c.drawRect(-1.5f, -0.45f, 1.5f, -0.3f, f(0xFF2A2E35.toInt()))
                eyes(c, 0.3f, 0.15f, 0.13f, 0xFF9CFF7A.toInt())
                smile(c, 0.35f, 0.2f, 0xFF9CFF7A.toInt(), 0.06f)
            }
            Creature.MUMMY -> {
                val band = s(0xFFB9A980.toInt(), 0.09f)
                for (i in 0 until 9) {
                    val y = -0.95f + i * 0.24f
                    val tilt = if (i % 2 == 0) 0.12f else -0.1f
                    c.drawLine(-1.2f, y - tilt, 1.2f, y + tilt, band)
                }
                c.drawRect(-0.75f, -0.32f, 0.75f, 0.02f, f(0xFF2B2222.toInt()))
                circle(c, -0.3f, -0.15f, 0.13f, Color.WHITE)
                circle(c, -0.3f, -0.15f, 0.06f, Color.BLACK)
                ovalAt(c, 0.32f, -0.12f, 0.08f, 0.1f, Color.WHITE)
                circle(c, 0.32f, -0.12f, 0.04f, Color.BLACK)
                c.drawLine(-0.8f, 0.0f, 0.8f, -0.25f, s(0xFFD9CBA3.toInt(), 0.1f))
            }
            Creature.PUMPKIN -> {
                val rib = s(0x55A23E00, 0.07f)
                for (rx in floatArrayOf(0.35f, 0.7f)) {
                    oval.set(-rx, -1f, rx, 1f)
                    c.drawOval(oval, rib)
                }
                c.drawLine(0f, -1f, 0f, 1f, rib)
                eyes(c, 0.35f, -0.05f, 0.11f, 0xFF4A2200.toInt())
                smile(c, 0.1f, 0.2f, 0xFF4A2200.toInt(), 0.06f)
                blush(c, 0.55f, 0.2f)
            }
            Creature.JACK_O_LANTERN -> {
                val rib = s(0x55A23E00, 0.06f)
                for (rx in floatArrayOf(0.35f, 0.7f)) {
                    oval.set(-rx, -1f, rx, 1f)
                    c.drawOval(oval, rib)
                }
                val flicker = 0.85f + 0.15f * kotlin.math.sin(time * 13f) * kotlin.math.sin(time * 7.3f)
                val glow = Color.argb(255, 255, (205 * flicker).toInt(), (60 * flicker).toInt())
                for (side in intArrayOf(-1, 1)) {
                    path.reset()
                    path.moveTo(side * 0.15f, -0.05f)
                    path.lineTo(side * 0.6f, -0.05f)
                    path.lineTo(side * 0.38f, -0.45f)
                    path.close()
                    c.drawPath(path, f(glow))
                }
                path.reset()
                path.moveTo(0f, 0.05f)
                path.lineTo(-0.1f, 0.2f)
                path.lineTo(0.1f, 0.2f)
                path.close()
                c.drawPath(path, f(glow))
                path.reset()
                path.moveTo(-0.7f, 0.25f)
                path.quadTo(0f, 0.5f, 0.7f, 0.25f)
                path.quadTo(0.55f, 0.8f, 0f, 0.8f)
                path.quadTo(-0.55f, 0.8f, -0.7f, 0.25f)
                path.close()
                c.drawPath(path, f(glow))
                c.drawRect(-0.35f, 0.3f, -0.18f, 0.48f, f(creature.color))
                c.drawRect(0.18f, 0.3f, 0.35f, 0.48f, f(creature.color))
                c.drawRect(-0.08f, 0.62f, 0.08f, 0.85f, f(creature.color))
            }
        }
    }

    /** Soft highlight and shadow on top of the body for a gummy look. */
    fun drawLighting(c: Canvas) {
        c.drawRect(-1.6f, -1.6f, 1.6f, 1.6f, shade)
        c.drawRect(-1.6f, -1.6f, 1.6f, 1.6f, shine)
    }

    /** Draws a rigid (undeformed) creature at the origin of radius 1; used for previews and HUD. */
    fun drawIcon(c: Canvas, creature: Creature, time: Float, outlineWidth: Float = 0.08f) {
        drawBehind(c, creature, time)
        path.reset()
        path.addCircle(0f, 0f, 1f, Path.Direction.CW)
        c.drawPath(path, f(creature.color))
        c.save()
        c.clipPath(path)
        drawFace(c, creature, time)
        drawLighting(c)
        c.restore()
        c.drawCircle(0f, 0f, 1f, s(outlineColor(creature), outlineWidth))
    }
}
