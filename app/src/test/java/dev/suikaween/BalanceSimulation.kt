package dev.suikaween

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Balance tuning aid, not a regression test: lets simple bots play whole games and prints how
 * far they get. Skipped unless the SIM environment variable is set:
 *
 *     SIM=1 ./gradlew testDebugUnitTest --tests '*BalanceSimulation*' -i
 */
class BalanceSimulation {
    private class Result(val drops: Int, val score: Int, val maxTier: Int, val finals: Int, val peak: Float, val avgTop: Float, val avgCount: Float)

    /** Height of the pile's surface at x (smaller y = taller pile). */
    private fun surface(world: World, x: Float): Float {
        var top = World.HEIGHT
        for (b in world.blobs) {
            val reach = b.r * 1.2f
            val dx = abs(b.x - x)
            if (dx < reach) top = minOf(top, b.y - b.r * 0.9f * kotlin.math.sqrt(1f - (dx / reach) * (dx / reach)))
        }
        return top
    }

    private fun play(seed: Int, smart: Boolean, maxDrops: Int): Result {
        val rnd = Random(seed)
        val world = World(Random(seed * 31 + 7))
        var drops = 0
        var maxTier = 0
        var finals = 0
        var lastScore = 0
        var peak = World.HEIGHT
        var topSum = 0f
        var countSum = 0f
        while (!world.gameOver && drops < maxDrops) {
            while (!world.canDrop()) world.update(1f / 60f)
            val cur = world.current
            var x = rnd.nextFloat() * World.WIDTH
            if (smart) {
                // Prefer landing on an identical creature; otherwise the lowest spot, near a wall.
                val twin = world.blobs.filter { it.creature == cur }.minByOrNull { abs(it.x - World.WIDTH / 2f) }
                x = if (twin != null) twin.x else {
                    var bestX = World.WIDTH / 2f
                    var bestY = -1f
                    var cx = cur.radius
                    while (cx <= World.WIDTH - cur.radius) {
                        val s = surface(world, cx)
                        // Lower pile is better; tie-break toward the walls.
                        val score = s + minOf(cx, World.WIDTH - cx) * -0.02f
                        if (score > bestY) { bestY = score; bestX = cx }
                        cx += 3f
                    }
                    bestX
                }
            }
            world.drop(x)
            drops++
            // Let things settle.
            repeat(90) { world.update(1f / 60f) }
            for (b in world.blobs) maxTier = maxOf(maxTier, b.creature.ordinal)
            val top = world.blobs.minOfOrNull { it.y - it.r } ?: World.HEIGHT
            peak = minOf(peak, top)
            topSum += top
            countSum += world.blobs.size
            if (world.score - lastScore >= Creature.FINAL_PAIR_BONUS && world.blobs.none { it.creature == Creature.LAST } ) finals++
            lastScore = world.score
        }
        return Result(drops, world.score, maxTier, finals, peak, topSum / drops, countSum / drops)
    }

    @Test
    fun report() {
        assumeTrue(System.getenv("SIM") != null)
        for (smart in listOf(false, true)) {
            val results = (1..6).map { play(it, smart, 400) }
            println("SIM ${if (smart) "smart" else "random"} bot: " +
                results.joinToString("  ") { "drops=${it.drops} score=${it.score} tier=${it.maxTier}" })
            println("SIM   pile top (y, danger line at ${World.DANGER_Y}): avg=${"%.0f".format(results.map { it.avgTop }.average())} " +
                "peak=${"%.0f".format(results.map { it.peak }.average())}  avg creatures in jar=${"%.1f".format(results.map { it.avgCount }.average())}")
            println("SIM ${if (smart) "smart" else "random"} avg drops=${results.map { it.drops }.average().toInt()} " +
                "avg score=${results.map { it.score }.average().toInt()} avg tier=${"%.1f".format(results.map { it.maxTier }.average())}")
        }
    }
}
