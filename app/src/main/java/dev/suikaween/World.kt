package dev.suikaween

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One creature in the jar.
 *
 * Physically it is a capsule (a line segment with a radius; round creatures have a zero-length
 * segment) a little smaller than the drawn body, so neighbours visibly press into each other.
 * The drawn outline is a ring of [World.SEGMENTS] springy radial vertices ([rad]) that flatten
 * against whatever they touch and wobble back.
 */
class Blob(var creature: Creature, var x: Float, var y: Float) {
    /** Radius of the equal-area circle; grows from a smaller size right after a merge. */
    var r = creature.radius
    var vx = 0f
    var vy = 0f
    /** Direction of the long axis in radians (y points down). */
    var angle = creature.restAngle
    var omega = 0f
    /** Seconds since this blob entered the world. */
    var age = 0f
    var alive = true
    internal var merged = false

    internal var px = x
    internal var py = y
    internal var pAngle = angle
    /** Velocity before contacts were solved this substep. */
    internal var ovx = 0f
    internal var ovy = 0f
    internal var oOmega = 0f
    /** Unit vector along the long axis; call [refreshAxis] after changing [angle]. */
    internal var ux = 1f
    internal var uy = 0f

    val rad = FloatArray(World.SEGMENTS)
    internal val radVel = FloatArray(World.SEGMENTS)
    internal val target = FloatArray(World.SEGMENTS)
    internal var mask = 0L
    internal var baseSum = 0f
    private var lastVx = 0f
    private var lastVy = 0f

    /** Drawn half-axes of the (undeformed) oval. */
    val longHalf: Float get() = r * creature.sqrtAspect
    val shortHalf: Float get() = r / creature.sqrtAspect

    /** Collision capsule: half the spine length and the radius around it. */
    val capHalf: Float get() = (longHalf - shortHalf) * World.COLLIDE_FRACTION
    val capRadius: Float get() = shortHalf * World.COLLIDE_FRACTION

    val mass: Float get() = r * r
    val inertia: Float get() {
        val a = longHalf
        val b = shortHalf
        return mass * (a * a + b * b) / 4f
    }

    /** Rotation of the art relative to the physics axis, so faces stay the right way up. */
    val artAngle: Float get() = angle - creature.restAngle

    init {
        refreshAxis()
        fillBase(rad)
    }

    internal fun refreshAxis() {
        ux = cos(angle)
        uy = sin(angle)
    }

    /** Fills [out] with the undeformed outline radius in each of the ring's directions. */
    internal fun fillBase(out: FloatArray) {
        val a = longHalf
        val b = shortHalf
        if (creature.aspect == 1f) {
            out.fill(a)
            return
        }
        val a2 = a * a
        val b2 = b * b
        val ab = a * b
        for (k in 0 until World.SEGMENTS) {
            val lx = World.COS[k] * ux + World.SIN[k] * uy
            val ly = -World.COS[k] * uy + World.SIN[k] * ux
            out[k] = ab / sqrt(b2 * lx * lx + a2 * ly * ly)
        }
    }

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

/** Closest approach between two capsule spines; the normal points from a to b. */
private class Contact {
    var nx = 0f
    var ny = 1f
    var pax = 0f
    var pay = 0f
    var pbx = 0f
    var pby = 0f
    var dist = 0f
}

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
        const val DROP_Y = 11f
        const val DANGER_TIME = 2.5f

        const val SEGMENTS = 32
        val COS = FloatArray(SEGMENTS) { cos(2 * PI * it / SEGMENTS).toFloat() }
        val SIN = FloatArray(SEGMENTS) { sin(2 * PI * it / SEGMENTS).toFloat() }

        const val GRAVITY = 320f
        const val SUBSTEPS = 10
        const val FIXED_DT = 1f / 60f

        /** Collision size / drawn size. The gap is the visible "squish" between bodies. */
        const val COLLIDE_FRACTION = 0.9f
        /** Fraction of tangential slip removed per substep. Low = slippery. */
        const val FRICTION = 0.015f
        const val WALL_FRICTION = 0.01f
        const val LINEAR_DAMPING = 0.12f
        const val ANGULAR_DAMPING = 1.2f
        const val MAX_SPEED = 400f
        const val MAX_OMEGA = 40f

        /** Outline springs: stiffness, damping, and neighbour coupling (makes ripples). */
        const val SHAPE_K = 700f
        const val SHAPE_DAMP = 14f
        const val SHAPE_COUPLING = 300f
        const val MAX_INFLATE = 1.12f

        const val GROW_TIME = 0.14f
        const val DROP_COOLDOWN = 0.45f

        private const val EPS = 1e-6f
        /** How close counts as touching for velocity-level contact handling. */
        private const val TOUCH = 0.02f
        /** Largest overlap (per substep) that is allowed to turn into velocity: real motion at ~full speed. */
        private const val VELOCITY_CORRECTION_LIMIT = 0.5f
        /** Speed (units/s) and spin (rad/s) a contact may add to a body. */
        private const val PUSH_SPEED = 70f
        private const val PUSH_SPIN = 8f
        /** Spines closer than this (as sin^2 of the angle between them) lie flat on each other. */
        private const val PARALLEL = 0.03f
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
    private val contacts = arrayOf(Contact(), Contact())

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
        val hw = creature.halfWidth
        return x.coerceIn(hw, WIDTH - hw)
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
        // Slightly off-axis so ovals don't balance on their tips.
        b.angle = creature.restAngle + (random.nextFloat() - 0.5f) * 0.6f
        b.pAngle = b.angle
        b.refreshAxis()
        b.fillBase(b.rad)
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
        updateDanger(dt)
    }

    // ---- Rigid body step (position-based, small substeps) ----

    private fun substep(h: Float) {
        val linDamp = 1f - LINEAR_DAMPING * h
        val angDamp = 1f - ANGULAR_DAMPING * h
        for (b in blobs) {
            b.vy += GRAVITY * h
            b.vx *= linDamp
            b.vy *= linDamp
            b.omega *= angDamp
            b.px = b.x
            b.py = b.y
            b.pAngle = b.angle
            b.x += b.vx * h
            b.y += b.vy * h
            b.angle += b.omega * h
            b.refreshAxis()
        }
        // Growing a little every substep (not once per frame) keeps the overlap with neighbours tiny.
        updateGrowth(h)
        for (b in blobs) {
            b.ovx = b.vx
            b.ovy = b.vy
            b.oOmega = b.omega
        }

        solveContacts()
        for (b in blobs) solveWalls(b)

        // Derive velocities from the corrected positions.
        val inv = 1f / h
        for (b in blobs) {
            b.vx = (b.x - b.px) * inv
            b.vy = (b.y - b.py) * inv
            b.omega = ((b.angle - b.pAngle) * inv).coerceIn(-MAX_OMEGA, MAX_OMEGA)
            b.refreshAxis()
        }

        limitInjectedEnergy()
        applyContactVelocities()

        for (b in blobs) {
            val s2 = b.vx * b.vx + b.vy * b.vy
            if (s2 > MAX_SPEED * MAX_SPEED) {
                val f = MAX_SPEED / sqrt(s2)
                b.vx *= f
                b.vy *= f
            }
        }
    }

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    /**
     * Finds the contact point(s) between two spines of half-length [ha] and [hb], stored in
     * [contacts]; returns how many (two when the spines lie roughly parallel on each other, so
     * flat stacks get a stable two-point support instead of one that seesaws).
     */
    private fun findContacts(a: Blob, ha: Float, b: Blob, hb: Float): Int {
        val p1x = a.x - a.ux * ha
        val p1y = a.y - a.uy * ha
        val d1x = a.ux * 2f * ha
        val d1y = a.uy * 2f * ha
        val p2x = b.x - b.ux * hb
        val p2y = b.y - b.uy * hb
        val d2x = b.ux * 2f * hb
        val d2y = b.uy * 2f * hb
        val rx = p1x - p2x
        val ry = p1y - p2y
        val aa = d1x * d1x + d1y * d1y
        val ee = d2x * d2x + d2y * d2y
        val f = d2x * rx + d2y * ry
        val cc = d1x * rx + d1y * ry
        val bb = d1x * d2x + d1y * d2y

        var s = 0f
        var t = 0f
        var second = -1f
        if (aa < EPS && ee < EPS) {
            // two points
        } else if (aa < EPS) {
            t = clamp01(f / ee)
        } else if (ee < EPS) {
            s = clamp01(-cc / aa)
        } else {
            val denom = aa * ee - bb * bb
            if (denom <= PARALLEL * aa * ee) {
                // Overlap of b's projection with a's span; support it at both ends.
                val sb0 = -cc / aa
                val sb1 = sb0 + bb / aa
                val lo = max(0f, min(sb0, sb1))
                val hi = min(1f, max(sb0, sb1))
                if (lo <= hi) {
                    if (hi - lo > 0.02f) {
                        s = lo
                        second = hi
                    } else {
                        s = (lo + hi) / 2f
                    }
                } else {
                    s = if (max(sb0, sb1) < 0f) 0f else 1f
                }
                t = clamp01((bb * s + f) / ee)
            } else {
                s = clamp01((bb * f - cc * ee) / denom)
                t = (bb * s + f) / ee
                if (t < 0f) {
                    t = 0f
                    s = clamp01(-cc / aa)
                } else if (t > 1f) {
                    t = 1f
                    s = clamp01((bb - cc) / aa)
                }
            }
        }
        fillContact(contacts[0], a, ha, b, p1x + d1x * s, p1y + d1y * s, p2x + d2x * t, p2y + d2y * t)
        if (second < 0f) return 1
        val t2 = clamp01((bb * second + f) / ee)
        fillContact(contacts[1], a, ha, b, p1x + d1x * second, p1y + d1y * second, p2x + d2x * t2, p2y + d2y * t2)
        return 2
    }

    private fun fillContact(c: Contact, a: Blob, ha: Float, b: Blob, pax: Float, pay: Float, pbx: Float, pby: Float) {
        c.pax = pax
        c.pay = pay
        c.pbx = pbx
        c.pby = pby
        val dx = pbx - pax
        val dy = pby - pay
        val d = sqrt(dx * dx + dy * dy)
        c.dist = d
        if (d > 1e-4f) {
            c.nx = dx / d
            c.ny = dy / d
            return
        }
        // Spines touch or cross: push apart sideways (perpendicular to a's spine if it has one).
        val cx = b.x - a.x
        val cy = b.y - a.y
        var tx: Float
        var ty: Float
        if (ha > 1e-4f) {
            tx = -a.uy
            ty = a.ux
            if (tx * cx + ty * cy < 0f) { tx = -tx; ty = -ty }
        } else {
            val cl = sqrt(cx * cx + cy * cy)
            if (cl > 1e-4f) { tx = cx / cl; ty = cy / cl } else { tx = 0f; ty = 1f }
        }
        c.nx = tx
        c.ny = ty
    }

    private fun solveContacts() {
        val n = blobs.size
        for (i in 0 until n) {
            val a = blobs[i]
            val reachA = a.capHalf + a.capRadius
            for (j in i + 1 until n) {
                val b = blobs[j]
                val reach = reachA + b.capHalf + b.capRadius
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (dx * dx + dy * dy >= reach * reach) continue
                val count = findContacts(a, a.capHalf, b, b.capHalf)
                val rs = a.capRadius + b.capRadius
                var touching = false
                for (k in 0 until count) {
                    val c = contacts[k]
                    if (c.dist >= rs) continue
                    touching = true
                    pushApart(a, b, c, (rs - c.dist) / count)
                }
                if (touching && a.creature == b.creature) {
                    a.merged = true
                    b.merged = true
                }
            }
        }
    }

    /**
     * Moves [a] and [b] apart along the contact normal by [pen], sharing it by inverse mass and
     * inertia. Overlap beyond what real motion can cause in one substep (a freshly spawned
     * creature, a growth spurt) is fixed without feeding velocity, otherwise it fires bodies out
     * of the pile.
     */
    private fun pushApart(a: Blob, b: Blob, c: Contact, pen: Float) {
        val rca = a.capRadius
        val rcb = b.capRadius
        // Contact point: halfway between the two surface points.
        val cx = ((c.pax + c.nx * rca) + (c.pbx - c.nx * rcb)) / 2f
        val cy = ((c.pay + c.ny * rca) + (c.pby - c.ny * rcb)) / 2f
        val ra = (cx - a.x) * c.ny - (cy - a.y) * c.nx
        val rb = (cx - b.x) * c.ny - (cy - b.y) * c.nx
        val ma = a.mass
        val mb = b.mass
        val ia = a.inertia
        val ib = b.inertia
        val w = 1f / ma + ra * ra / ia + 1f / mb + rb * rb / ib
        val lambda = pen / w
        val free = max(0f, pen - VELOCITY_CORRECTION_LIMIT) / w
        val ax = c.nx / ma
        val ay = c.ny / ma
        val bx = c.nx / mb
        val by = c.ny / mb
        a.x -= ax * lambda
        a.y -= ay * lambda
        a.angle -= ra / ia * lambda
        b.x += bx * lambda
        b.y += by * lambda
        b.angle += rb / ib * lambda
        if (free > 0f) {
            a.px -= ax * free
            a.py -= ay * free
            a.pAngle -= ra / ia * free
            b.px += bx * free
            b.py += by * free
            b.pAngle += rb / ib * free
        }
        a.refreshAxis()
        b.refreshAxis()
    }

    private fun solveWalls(b: Blob) {
        val rc = b.capRadius
        val h = b.capHalf
        val ends = if (h > 1e-4f) 2 else 1
        for (e in 0 until ends) {
            val sgn = if (ends == 1) 0f else if (e == 0) -1f else 1f
            var ex = b.x + sgn * b.ux * h
            var ey = b.y + sgn * b.uy * h
            if (ex < rc) {
                wallPush(b, 1f, 0f, ex - rc, ey, rc - ex)
                ex = b.x + sgn * b.ux * h
                ey = b.y + sgn * b.uy * h
            }
            if (ex > WIDTH - rc) {
                wallPush(b, -1f, 0f, ex + rc, ey, ex + rc - WIDTH)
                ex = b.x + sgn * b.ux * h
                ey = b.y + sgn * b.uy * h
            }
            if (ey > HEIGHT - rc) wallPush(b, 0f, -1f, ex, ey + rc, ey + rc - HEIGHT)
        }
    }

    /** Pushes [b] away from a static wall (normal n points into the jar) by [pen] at contact (cx, cy). */
    private fun wallPush(b: Blob, nx: Float, ny: Float, cx: Float, cy: Float, pen: Float) {
        val rn = (cx - b.x) * ny - (cy - b.y) * nx
        val m = b.mass
        val i = b.inertia
        val w = 1f / m + rn * rn / i
        val lambda = pen / w
        val free = max(0f, pen - VELOCITY_CORRECTION_LIMIT) / w
        b.x += nx * lambda / m
        b.y += ny * lambda / m
        b.angle += rn * lambda / i
        if (free > 0f) {
            b.px += nx * free / m
            b.py += ny * free / m
            b.pAngle += rn * free / i
        }
        b.refreshAxis()
    }

    /**
     * Contact solving may stop a body but should never speed one up: in a jammed pile (a big merge
     * growing among neighbours) the stored compression would otherwise release as a burst and fire
     * creatures out of the jar. A body may only end up faster than it started by [PUSH_SPEED],
     * enough to be shoved aside by a growing or falling neighbour.
     */
    private fun limitInjectedEnergy() {
        for (b in blobs) {
            val before = b.ovx * b.ovx + b.ovy * b.ovy
            val limit = max(sqrt(before), PUSH_SPEED)
            val now2 = b.vx * b.vx + b.vy * b.vy
            if (now2 > limit * limit) {
                val f = limit / sqrt(now2)
                b.vx *= f
                b.vy *= f
            }
            val wLimit = max(abs(b.oOmega), PUSH_SPIN)
            b.omega = b.omega.coerceIn(-wLimit, wLimit)
        }
    }

    /**
     * Velocity-level contact handling, after velocities were derived from the corrected positions.
     * Contacts are inelastic: velocity that still points apart at a touching contact is cancelled
     * (so overlap fixes never bounce things), then friction removes a little of the sliding.
     */
    private fun applyContactVelocities() {
        val n = blobs.size
        for (i in 0 until n) {
            val a = blobs[i]
            val reachA = a.capHalf + a.capRadius + TOUCH
            for (j in i + 1 until n) {
                val b = blobs[j]
                val reach = reachA + b.capHalf + b.capRadius
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (dx * dx + dy * dy >= reach * reach) continue
                val count = findContacts(a, a.capHalf, b, b.capHalf)
                val rca = a.capRadius
                val rcb = b.capRadius
                val rs = rca + rcb + TOUCH
                for (k in 0 until count) {
                    val c = contacts[k]
                    if (c.dist >= rs) continue
                    val cx = ((c.pax + c.nx * rca) + (c.pbx - c.nx * rcb)) / 2f
                    val cy = ((c.pay + c.ny * rca) + (c.pby - c.ny * rcb)) / 2f
                    cancelSeparation(a, b, c.nx, c.ny, cx, cy)
                    friction(a, b, c.nx, c.ny, cx, cy, FRICTION / count)
                }
            }
        }
        for (b in blobs) {
            val rc = b.capRadius
            val h = b.capHalf
            val ends = if (h > 1e-4f) 2 else 1
            for (e in 0 until ends) {
                val sgn = if (ends == 1) 0f else if (e == 0) -1f else 1f
                val ex = b.x + sgn * b.ux * h
                val ey = b.y + sgn * b.uy * h
                if (ex <= rc + TOUCH) {
                    cancelSeparation(b, null, -1f, 0f, ex - rc, ey)
                    friction(b, null, -1f, 0f, ex - rc, ey, WALL_FRICTION / ends)
                }
                if (ex >= WIDTH - rc - TOUCH) {
                    cancelSeparation(b, null, 1f, 0f, ex + rc, ey)
                    friction(b, null, 1f, 0f, ex + rc, ey, WALL_FRICTION / ends)
                }
                if (ey >= HEIGHT - rc - TOUCH) {
                    cancelSeparation(b, null, 0f, 1f, ex, ey + rc)
                    friction(b, null, 0f, 1f, ex, ey + rc, WALL_FRICTION * 3f / ends)
                }
            }
        }
    }

    /**
     * Removes relative velocity at (cx, cy) that carries [a] and [b] (or a static wall when [b] is
     * null) apart along the normal (nx, ny), which points from a to b.
     */
    private fun cancelSeparation(a: Blob, b: Blob?, nx: Float, ny: Float, cx: Float, cy: Float) {
        val rax = cx - a.x
        val ray = cy - a.y
        // Velocity of b relative to a along n; positive means moving apart.
        var vn = -((a.vx - a.omega * ray) * nx + (a.vy + a.omega * rax) * ny)
        val ra = rax * ny - ray * nx
        var w = 1f / a.mass + ra * ra / a.inertia
        var rb = 0f
        if (b != null) {
            val rbx = cx - b.x
            val rby = cy - b.y
            vn += (b.vx - b.omega * rby) * nx + (b.vy + b.omega * rbx) * ny
            rb = rbx * ny - rby * nx
            w += 1f / b.mass + rb * rb / b.inertia
        }
        if (vn <= 0f) return
        val lambda = vn / w
        a.vx += nx * lambda / a.mass
        a.vy += ny * lambda / a.mass
        a.omega += ra * lambda / a.inertia
        if (b != null) {
            b.vx -= nx * lambda / b.mass
            b.vy -= ny * lambda / b.mass
            b.omega -= rb * lambda / b.inertia
        }
    }

    /**
     * Tangential friction impulse at contact point (cx, cy) between [a] and [b] (or a static wall
     * when [b] is null), along contact normal (nx, ny) pointing from a to b. Removes [amount] of
     * the relative slip, which also spins the bodies the way rolling contact would.
     */
    private fun friction(a: Blob, b: Blob?, nx: Float, ny: Float, cx: Float, cy: Float, amount: Float) {
        val tx = -ny
        val ty = nx
        val rax = cx - a.x
        val ray = cy - a.y
        var vrelx = -(a.vx - a.omega * ray)
        var vrely = -(a.vy + a.omega * rax)
        val rat = rax * ty - ray * tx
        var w = 1f / a.mass + rat * rat / a.inertia
        var rbt = 0f
        if (b != null) {
            val rbx = cx - b.x
            val rby = cy - b.y
            vrelx += b.vx - b.omega * rby
            vrely += b.vy + b.omega * rbx
            rbt = rbx * ty - rby * tx
            w += 1f / b.mass + rbt * rbt / b.inertia
        }
        val j = -(vrelx * tx + vrely * ty) / w * amount
        a.vx -= j * tx / a.mass
        a.vy -= j * ty / a.mass
        a.omega -= j * rat / a.inertia
        if (b != null) {
            b.vx += j * tx / b.mass
            b.vy += j * ty / b.mass
            b.omega += j * rbt / b.inertia
        }
    }

    // ---- Merging, growth and game over ----

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
                findContacts(a, a.capHalf, b, b.capHalf)
                val d = contacts[0].dist
                if (d < bestD && d <= a.capRadius + b.capRadius + 0.1f) {
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
                // Keep the way up: average the two art orientations on the circle.
                val sx = cos(a.artAngle) + cos(best.artAngle)
                val sy = sin(a.artAngle) + sin(best.artAngle)
                val art = if (abs(sx) + abs(sy) < 1e-3f) a.artAngle else atan2(sy, sx)
                nb.angle = art + nextCreature.restAngle
                nb.pAngle = nb.angle
                nb.refreshAxis()
                nb.age = 10f // merged creatures count as settled for the danger check
                nb.r = a.creature.radius
                nb.fillBase(nb.rad)
                for (k in 0 until SEGMENTS) {
                    nb.rad[k] *= 0.85f
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
            val top = b.y - (abs(b.uy) * b.capHalf + b.capRadius)
            if (b.age > 1.2f && top < DANGER_Y && abs(b.vy) < 60f) {
                over = true
                break
            }
        }
        dangerTimer = if (over) dangerTimer + dt else max(0f, dangerTimer - dt * 2f)
        danger = (dangerTimer / DANGER_TIME).coerceIn(0f, 1f)
        if (dangerTimer >= DANGER_TIME) gameOver = true
    }

    // ---- Soft outlines ----

    /** Clips outline targets against a plane at [dist] from the centre; returns the mask of clipped vertices. */
    private fun clip(t: FloatArray, nx: Float, ny: Float, dist: Float): Long {
        var mask = 0L
        for (k in 0 until SEGMENTS) {
            val c = COS[k] * nx + SIN[k] * ny
            if (c <= 0.05f) continue
            val lim = dist / c
            if (lim < t[k]) {
                t[k] = lim
                mask = mask or (1L shl k)
            }
        }
        return mask
    }

    /**
     * Updates the soft outlines. Each vertex's rest length is the oval's radius clipped against
     * neighbouring bodies (a shared flat contact face, split in proportion to size) and the jar
     * walls; the free vertices then inflate to roughly preserve area, and springs chase those
     * targets.
     */
    fun updateShapes(frameDt: Float) {
        val dt = min(frameDt, 1f / 30f)
        val n = blobs.size
        for (b in blobs) {
            b.refreshAxis()
            b.fillBase(b.target)
            var sum = 0f
            for (k in 0 until SEGMENTS) sum += b.target[k] * b.target[k]
            b.baseSum = sum
            b.mask = 0L
        }

        for (i in 0 until n) {
            val a = blobs[i]
            val ra = a.shortHalf
            val ha = a.longHalf - ra
            for (j in i + 1 until n) {
                val b = blobs[j]
                val rb = b.shortHalf
                val hb = b.longHalf - rb
                val reach = ha + ra + hb + rb
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (dx * dx + dy * dy >= reach * reach) continue
                findContacts(a, ha, b, hb)
                val c = contacts[0]
                val rs = ra + rb
                if (c.dist >= rs) continue
                val overlap = rs - c.dist
                val distA = (c.pax - a.x) * c.nx + (c.pay - a.y) * c.ny + ra - overlap * ra / rs
                val distB = -(c.pbx - b.x) * c.nx - (c.pby - b.y) * c.ny + rb - overlap * rb / rs
                a.mask = a.mask or clip(a.target, c.nx, c.ny, max(distA, ra * 0.55f))
                b.mask = b.mask or clip(b.target, -c.nx, -c.ny, max(distB, rb * 0.55f))
            }
        }

        for (a in blobs) {
            val rs = a.shortHalf
            val h = a.longHalf - rs
            val extX = abs(a.ux) * h + rs
            val extY = abs(a.uy) * h + rs
            if (a.x - extX < 0f) a.mask = a.mask or clip(a.target, -1f, 0f, max(a.x, rs * 0.55f))
            if (a.x + extX > WIDTH) a.mask = a.mask or clip(a.target, 1f, 0f, max(WIDTH - a.x, rs * 0.55f))
            if (a.y + extY > HEIGHT) a.mask = a.mask or clip(a.target, 0f, 1f, max(HEIGHT - a.y, rs * 0.55f))
        }

        for (a in blobs) {
            val t = a.target
            if (a.mask != 0L) {
                var sum = 0f
                for (k in 0 until SEGMENTS) sum += t[k] * t[k]
                val inflate = min(sqrt(a.baseSum / max(sum, 1e-3f)), MAX_INFLATE)
                for (k in 0 until SEGMENTS) {
                    if (a.mask and (1L shl k) == 0L) t[k] *= inflate
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
            val lo = a.shortHalf * 0.45f
            val hi = a.longHalf * 1.3f
            for (k in 0 until SEGMENTS) {
                rad[k] = (rad[k] + vel[k] * dt).coerceIn(lo, hi)
            }
        }
    }
}
