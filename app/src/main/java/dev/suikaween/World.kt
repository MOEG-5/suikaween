package dev.suikaween

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One creature in the jar.
 *
 * Physically it is a circle with a collision radius a little smaller than its drawn radius, so
 * neighbours visibly press into each other. The drawn outline is a ring of [SEGMENTS] springy
 * radial vertices ([rad]) that flatten against whatever they touch and wobble back.
 */
class Blob(var creature: Creature, var x: Float, var y: Float) {
    /** Drawn radius; grows from a smaller size right after a merge. */
    var r = creature.radius
    var vx = 0f
    var vy = 0f
    internal var px = x
    internal var py = y
    var angle = 0f
    var omega = 0f
    /** Seconds since this blob entered the world. */
    var age = 0f
    var alive = true
    internal var merged = false

    val mass: Float get() = r * r
    val collisionRadius: Float get() = r * World.COLLIDE_FRACTION

    val rad = FloatArray(World.SEGMENTS) { r }
    internal val radVel = FloatArray(World.SEGMENTS)
    internal val target = FloatArray(World.SEGMENTS)
    private var lastVx = 0f
    private var lastVy = 0f

    internal fun impactJiggle(frameDt: Float) {
        val dvx = vx - lastVx
        val dvy = vy - lastVy
        lastVx = vx
        lastVy = vy
        val dv = sqrt(dvx * dvx + dvy * dvy)
        if (frameDt <= 0f || dv < 25f) return
        // Splat: vertices facing the direction of the sudden velocity change get pushed in.
        val strength = min(dv * 0.012f, 0.6f) * r
        val nx = dvx / dv
        val ny = dvy / dv
        for (k in 0 until World.SEGMENTS) {
            val facing = -(World.COS[k] * nx + World.SIN[k] * ny)
            if (facing > 0f) radVel[k] -= strength * facing * 6f
        }
    }
}

class MergeEvent(val x: Float, val y: Float, val creature: Creature?, val points: Int, val from: Creature)

/**
 * Suika-style physics in world units: the jar is [WIDTH] wide and [HEIGHT] tall, y points down.
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 */
class World(private val random: Random = Random.Default) {
    companion object {
        const val WIDTH = 100f
        const val HEIGHT = 135f
        /** Creatures whose top stays above this line for [DANGER_TIME] end the game. */
        const val DANGER_Y = 22f
        const val DROP_Y = 10f
        const val DANGER_TIME = 2.5f

        const val SEGMENTS = 32
        val COS = FloatArray(SEGMENTS) { cos(2 * PI * it / SEGMENTS).toFloat() }
        val SIN = FloatArray(SEGMENTS) { sin(2 * PI * it / SEGMENTS).toFloat() }

        const val GRAVITY = 320f
        const val SUBSTEPS = 10
        const val FIXED_DT = 1f / 60f

        /** Collision radius / drawn radius. The gap is the visible "squish" between bodies. */
        const val COLLIDE_FRACTION = 0.9f
        /** Fraction of tangential slip removed per substep. Low = slippery. */
        const val FRICTION = 0.015f
        const val WALL_FRICTION = 0.01f
        const val LINEAR_DAMPING = 0.12f
        const val ANGULAR_DAMPING = 0.8f
        const val MAX_SPEED = 400f

        /** Outline springs: stiffness, damping, and neighbour coupling (makes ripples). */
        const val SHAPE_K = 700f
        const val SHAPE_DAMP = 14f
        const val SHAPE_COUPLING = 300f
        const val MAX_INFLATE = 1.12f

        const val GROW_TIME = 0.14f
        const val DROP_COOLDOWN = 0.45f
    }

    val blobs = ArrayList<Blob>()
    val events = ArrayList<MergeEvent>()

    var score = 0
        private set
    var gameOver = false
        private set
    /** 0..1, how close the danger timer is to ending the game. */
    var danger = 0f
        private set

    var current: Creature = randomDroppable()
        private set
    var next: Creature = randomDroppable()
        private set
    var cooldown = 0f
        private set

    private var dangerTimer = 0f
    private var accumulator = 0f
    private val growing = HashMap<Blob, Float>()

    fun reset() {
        blobs.clear()
        events.clear()
        growing.clear()
        score = 0
        gameOver = false
        danger = 0f
        dangerTimer = 0f
        accumulator = 0f
        cooldown = 0f
        current = randomDroppable()
        next = randomDroppable()
    }

    private fun randomDroppable(): Creature {
        // Weighted toward the smallest creatures.
        val weights = intArrayOf(5, 5, 4, 3, 2)
        var roll = random.nextInt(weights.sum())
        for (i in weights.indices) {
            roll -= weights[i]
            if (roll < 0) return Creature.entries[i]
        }
        return Creature.CANDY_CORN
    }

    fun clampDropX(x: Float, creature: Creature = current): Float {
        val r = creature.radius
        return x.coerceIn(r, WIDTH - r)
    }

    fun canDrop(): Boolean = !gameOver && cooldown <= 0f

    /** Drops the held creature at [x]. Returns the new blob, or null if not allowed yet. */
    fun drop(x: Float): Blob? {
        if (!canDrop()) return null
        val blob = add(current, clampDropX(x), DROP_Y)
        current = next
        next = randomDroppable()
        cooldown = DROP_COOLDOWN
        return blob
    }

    fun add(creature: Creature, x: Float, y: Float): Blob {
        val b = Blob(creature, x, y)
        b.angle = (random.nextFloat() - 0.5f) * 0.6f
        blobs.add(b)
        return b
    }

    /** Advances the simulation by a real-time [frameDt], using fixed steps internally. */
    fun update(frameDt: Float) {
        val dt = min(frameDt, 0.1f)
        accumulator += dt
        var steps = 0
        while (accumulator >= FIXED_DT && steps < 6) {
            step(FIXED_DT)
            accumulator -= FIXED_DT
            steps++
        }
        if (steps == 6) accumulator = 0f
        updateShapes(dt)
    }

    fun step(dt: Float) {
        if (gameOver) return
        cooldown = max(0f, cooldown - dt)
        val h = dt / SUBSTEPS
        repeat(SUBSTEPS) { substep(h) }
        for (b in blobs) b.age += dt
        resolveMerges()
        updateGrowth(dt)
        updateDanger(dt)
    }

    private fun substep(h: Float) {
        val n = blobs.size
        val linDamp = 1f - LINEAR_DAMPING * h
        val angDamp = 1f - ANGULAR_DAMPING * h
        for (b in blobs) {
            b.vy += GRAVITY * h
            b.vx *= linDamp
            b.vy *= linDamp
            b.omega *= angDamp
            b.px = b.x
            b.py = b.y
            b.x += b.vx * h
            b.y += b.vy * h
            b.angle += b.omega * h
        }

        // Positional contact resolution (stable, stiff, but slightly undersized circles).
        for (i in 0 until n) {
            val a = blobs[i]
            for (j in i + 1 until n) {
                val b = blobs[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val rs = a.collisionRadius + b.collisionRadius
                val d2 = dx * dx + dy * dy
                if (d2 >= rs * rs) continue
                if (a.creature == b.creature && !a.merged && !b.merged) {
                    a.merged = true
                    b.merged = true
                }
                val d = sqrt(d2)
                val nx: Float
                val ny: Float
                if (d < 1e-4f) { nx = 0f; ny = 1f } else { nx = dx / d; ny = dy / d }
                val pen = rs - d
                val wa = 1f / a.mass
                val wb = 1f / b.mass
                val corr = pen / (wa + wb)
                a.x -= nx * corr * wa
                a.y -= ny * corr * wa
                b.x += nx * corr * wb
                b.y += ny * corr * wb
            }
        }
        for (b in blobs) {
            val cr = b.collisionRadius
            if (b.x < cr) b.x = cr
            if (b.x > WIDTH - cr) b.x = WIDTH - cr
            if (b.y > HEIGHT - cr) b.y = HEIGHT - cr
        }

        // Derive velocities from the corrected positions (position-based dynamics).
        for (b in blobs) {
            b.vx = (b.x - b.px) / h
            b.vy = (b.y - b.py) / h
        }

        // Low friction pass: removes a small part of the tangential slip, which also spins things.
        for (i in 0 until n) {
            val a = blobs[i]
            for (j in i + 1 until n) {
                val b = blobs[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val rs = a.collisionRadius + b.collisionRadius + 0.05f
                val d2 = dx * dx + dy * dy
                if (d2 >= rs * rs || d2 < 1e-8f) continue
                val d = sqrt(d2)
                friction(a, b, dx / d, dy / d, FRICTION)
            }
        }
        for (b in blobs) {
            val cr = b.collisionRadius + 0.05f
            if (b.x <= cr) friction(b, null, -1f, 0f, WALL_FRICTION)
            if (b.x >= WIDTH - cr) friction(b, null, 1f, 0f, WALL_FRICTION)
            if (b.y >= HEIGHT - cr) friction(b, null, 0f, 1f, WALL_FRICTION * 3f)
        }

        for (b in blobs) {
            val s2 = b.vx * b.vx + b.vy * b.vy
            if (s2 > MAX_SPEED * MAX_SPEED) {
                val f = MAX_SPEED / sqrt(s2)
                b.vx *= f
                b.vy *= f
            }
        }
    }

    /**
     * Tangential friction impulse between [a] and [b] (or a static wall when [b] is null) along
     * contact normal (nx, ny) pointing from a to b. Treats each blob as a uniform disc.
     */
    private fun friction(a: Blob, b: Blob?, nx: Float, ny: Float, amount: Float) {
        val tx = -ny
        val ty = nx
        val ra = a.collisionRadius
        val wa = 1f / a.mass
        var vrel = -(a.vx * tx + a.vy * ty) - a.omega * ra
        var k = 3f * wa
        if (b != null) {
            val rb = b.collisionRadius
            val wb = 1f / b.mass
            vrel += (b.vx * tx + b.vy * ty) - b.omega * rb
            k += 3f * wb
        }
        val jt = -vrel / k * amount
        a.vx -= jt * wa * tx
        a.vy -= jt * wa * ty
        a.omega -= jt * ra * 2f * wa / (ra * ra)
        if (b != null) {
            val rb = b.collisionRadius
            val wb = 1f / b.mass
            b.vx += jt * wb * tx
            b.vy += jt * wb * ty
            b.omega -= jt * rb * 2f * wb / (rb * rb)
        }
    }

    private fun resolveMerges() {
        val pending = blobs.filter { it.merged }
        if (pending.isEmpty()) return
        val used = HashSet<Blob>()
        val created = ArrayList<Blob>()
        for (a in pending) {
            if (a in used) continue
            // Partner: closest touching blob of the same creature that is also flagged.
            var best: Blob? = null
            var bestD = Float.MAX_VALUE
            for (b in pending) {
                if (b === a || b in used || b.creature != a.creature) continue
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < bestD && d <= a.collisionRadius + b.collisionRadius + 0.1f) {
                    bestD = d
                    best = b
                }
            }
            if (best == null) continue
            used.add(a)
            used.add(best)
            a.alive = false
            best.alive = false
            val mx = (a.x + best.x) / 2f
            val my = (a.y + best.y) / 2f
            val nextCreature = a.creature.next
            if (nextCreature == null) {
                score += Creature.FINAL_PAIR_BONUS
                events.add(MergeEvent(mx, my, null, Creature.FINAL_PAIR_BONUS, a.creature))
            } else {
                score += nextCreature.mergeScore
                val nb = Blob(nextCreature, mx, my)
                nb.vx = (a.vx + best.vx) / 2f
                nb.vy = (a.vy + best.vy) / 2f
                nb.omega = (a.omega + best.omega) / 2f
                nb.angle = (a.angle + best.angle) / 2f
                nb.age = 10f // merged creatures count as settled for the danger check
                val startR = a.creature.radius
                nb.r = startR
                for (k in 0 until SEGMENTS) {
                    nb.rad[k] = startR * 0.85f
                    nb.radVel[k] = nextCreature.radius * 3f
                }
                growing[nb] = 0f
                created.add(nb)
                events.add(MergeEvent(mx, my, nextCreature, nextCreature.mergeScore, a.creature))
            }
        }
        blobs.removeAll { !it.alive }
        for (b in blobs) b.merged = false
        blobs.addAll(created)
    }

    private fun updateGrowth(dt: Float) {
        if (growing.isEmpty()) return
        val iter = growing.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val b = e.key
            val t = min(1f, e.value + dt / GROW_TIME)
            val from = Creature.entries[max(0, b.creature.ordinal - 1)].radius
            val eased = 1f - (1f - t) * (1f - t)
            b.r = from + (b.creature.radius - from) * eased
            if (t >= 1f || !b.alive) {
                b.r = b.creature.radius
                iter.remove()
            } else {
                e.setValue(t)
            }
        }
    }

    private fun updateDanger(dt: Float) {
        var over = false
        for (b in blobs) {
            if (b.age > 1.2f && b.y - b.collisionRadius < DANGER_Y && abs(b.vy) < 60f) {
                over = true
                break
            }
        }
        dangerTimer = if (over) dangerTimer + dt else max(0f, dangerTimer - dt * 2f)
        danger = (dangerTimer / DANGER_TIME).coerceIn(0f, 1f)
        if (dangerTimer >= DANGER_TIME) gameOver = true
    }

    /**
     * Updates the soft outlines. Each vertex's rest length is clipped against neighbouring
     * bodies (a shared flat contact face, split in proportion to size) and the jar walls; the
     * free vertices then inflate to roughly preserve area, and springs chase those targets.
     */
    fun updateShapes(frameDt: Float) {
        val dt = min(frameDt, 1f / 30f)
        val n = blobs.size
        for (i in 0 until n) {
            val a = blobs[i]
            val t = a.target
            for (k in 0 until SEGMENTS) t[k] = a.r
            var constrained = 0L // bitmask, SEGMENTS <= 64
            fun clipPlane(nx: Float, ny: Float, dist: Float) {
                for (k in 0 until SEGMENTS) {
                    val c = COS[k] * nx + SIN[k] * ny
                    if (c <= 0.05f) continue
                    val lim = dist / c
                    if (lim < t[k]) {
                        t[k] = lim
                        constrained = constrained or (1L shl k)
                    }
                }
            }
            for (j in 0 until n) {
                if (j == i) continue
                val b = blobs[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val rs = a.r + b.r
                val d2 = dx * dx + dy * dy
                if (d2 >= rs * rs || d2 < 1e-6f) continue
                val d = sqrt(d2)
                val overlap = rs - d
                val dist = max(a.r - overlap * a.r / rs, a.r * 0.55f)
                clipPlane(dx / d, dy / d, dist)
            }
            if (a.x - a.r < 0f) clipPlane(-1f, 0f, max(a.x, a.r * 0.55f))
            if (a.x + a.r > WIDTH) clipPlane(1f, 0f, max(WIDTH - a.x, a.r * 0.55f))
            if (a.y + a.r > HEIGHT) clipPlane(0f, 1f, max(HEIGHT - a.y, a.r * 0.55f))

            if (constrained != 0L) {
                var sum = 0f
                for (k in 0 until SEGMENTS) sum += t[k] * t[k]
                val full = SEGMENTS * a.r * a.r
                val inflate = min(sqrt(full / max(sum, 1e-3f)), MAX_INFLATE)
                for (k in 0 until SEGMENTS) {
                    if (constrained and (1L shl k) == 0L) t[k] *= inflate
                }
            }

            a.impactJiggle(frameDt)
            val rad = a.rad
            val vel = a.radVel
            for (k in 0 until SEGMENTS) {
                val prev = rad[(k + SEGMENTS - 1) % SEGMENTS]
                val nxt = rad[(k + 1) % SEGMENTS]
                val acc = SHAPE_K * (t[k] - rad[k]) +
                    SHAPE_COUPLING * (prev + nxt - 2f * rad[k]) -
                    SHAPE_DAMP * vel[k]
                vel[k] += acc * dt
            }
            for (k in 0 until SEGMENTS) {
                rad[k] = (rad[k] + vel[k] * dt).coerceIn(a.r * 0.45f, a.r * 1.3f)
            }
        }
    }
}
