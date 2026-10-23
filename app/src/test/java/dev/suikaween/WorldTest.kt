package dev.suikaween

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class WorldTest {
    private fun run(world: World, seconds: Float) {
        repeat((seconds * 60).toInt()) { world.update(1f / 60f) }
    }

    // Extents of a blob's collision capsule.
    private fun Blob.bottom() = y + abs(uy) * capHalf + capRadius
    private fun Blob.top() = y - abs(uy) * capHalf - capRadius
    private fun Blob.left() = x - abs(ux) * capHalf - capRadius
    private fun Blob.right() = x + abs(ux) * capHalf + capRadius

    @Test
    fun twoMatchingCreaturesMergeIntoTheNextTier() {
        val world = World(Random(1))
        world.add(Creature.BAT, 50f, 120f)
        world.add(Creature.BAT, 52f, 100f)
        run(world, 2f)
        assertEquals(1, world.blobs.size)
        assertEquals(Creature.GHOST, world.blobs[0].creature)
        assertEquals(Creature.GHOST.mergeScore, world.score)
        // Grown to full size after the pop-in.
        assertEquals(Creature.GHOST.radius, world.blobs[0].r, 1e-4f)
    }

    @Test
    fun twoFinalCreaturesVanishWithBonus() {
        val world = World(Random(1))
        world.add(Creature.JACK_O_LANTERN, 50f, 110f)
        world.add(Creature.JACK_O_LANTERN, 53f, 60f)
        run(world, 3f)
        assertTrue(world.blobs.isEmpty())
        assertEquals(Creature.FINAL_PAIR_BONUS, world.score)
    }

    @Test
    fun smallCreatureSlidesOffARoundOneToTheFloor() {
        val world = World(Random(1))
        val bottom = world.add(Creature.SKULL, 50f, 120f)
        val top = world.add(Creature.EYEBALL, 51f, 80f)
        run(world, 4f)
        assertEquals(2, world.blobs.size)
        // Slippery: the small one should have slid off the round skull down to the floor.
        assertEquals(World.HEIGHT, top.bottom(), 0.5f)
        assertEquals(World.HEIGHT, bottom.bottom(), 0.5f)
    }

    @Test
    fun randomPileStaysInsideJarAndStable() {
        val rnd = Random(42)
        val world = World(Random(7))
        repeat(60 * 90) { frame ->
            if (frame % 40 == 0 && world.canDrop()) world.drop(rnd.nextFloat() * World.WIDTH)
            world.update(1f / 60f)
        }
        assertTrue(world.score > 0)
        for (b in world.blobs) {
            assertFalse(b.x.isNaN() || b.y.isNaN() || b.angle.isNaN())
            assertTrue("left", b.left() >= -0.05f)
            assertTrue("right", b.right() <= World.WIDTH + 0.05f)
            assertTrue("floor", b.bottom() <= World.HEIGHT + 0.05f)
            for (r in b.rad) assertTrue(r > 0f && r <= b.longHalf * 1.3f + 1e-3f)
        }
    }

    @Test
    fun overfillingEndsTheGame() {
        val world = World(Random(3))
        var frames = 0
        while (!world.gameOver && frames < 60 * 600) {
            if (world.canDrop()) world.drop(World.WIDTH / 2f)
            world.update(1f / 60f)
            frames++
        }
        assertTrue(world.gameOver)
        assertFalse(world.canDrop())
    }

    @Test
    fun outlineFlattensAgainstFloor() {
        val world = World(Random(1))
        val b = world.add(Creature.SKULL, 50f, 100f)
        run(world, 3f)
        // Vertex pointing straight down (index SEGMENTS/4, y is down) is pressed flat by the floor.
        val down = b.rad[World.SEGMENTS / 4]
        assertTrue("down=$down r=${b.r}", down < b.r * 0.97f)
        assertTrue(down <= World.HEIGHT - b.y + 0.5f)
        // ...and the sides bulge to keep the area.
        assertTrue(b.rad[0] > b.r)
    }

    @Test
    fun ovalOutlineHasTheRightProportions() {
        val world = World(Random(1))
        val b = world.add(Creature.BAT, 50f, 60f)
        b.angle = 0f
        b.refreshAxis()
        repeat(60) { world.updateShapes(1f / 60f) }
        assertEquals(b.longHalf, b.rad[0], 0.15f)
        assertEquals(b.longHalf, b.rad[World.SEGMENTS / 2], 0.15f)
        assertEquals(b.shortHalf, b.rad[World.SEGMENTS / 4], 0.15f)
        assertTrue(b.longHalf > b.shortHalf * 1.25f)
        // Same area as the equal-area circle it stands for.
        assertEquals(b.r * b.r, b.longHalf * b.shortHalf, 1e-3f)
    }

    @Test
    fun wideOvalSettlesOnItsFlatSide() {
        val world = World(Random(1))
        val b = world.add(Creature.PUMPKIN, 50f, 60f)
        b.angle = 0.9f
        b.refreshAxis()
        run(world, 5f)
        assertTrue("uy=${b.uy}", abs(b.uy) < 0.1f)
        assertEquals(World.HEIGHT - b.capRadius, b.y, 0.6f)
    }

    @Test
    fun tallOvalTopplesOntoItsSide() {
        val world = World(Random(1))
        val b = world.add(Creature.GHOST, 50f, 60f)
        run(world, 5f)
        // Standing on end is unstable, so after the fall the long axis lies horizontal.
        assertTrue("uy=${b.uy}", abs(b.uy) < 0.15f)
        assertEquals(World.HEIGHT, b.bottom(), 0.6f)
    }

    @Test
    fun ovalsStackWithoutJitterAndComeToRest() {
        val world = World(Random(1))
        val order = listOf(Creature.PUMPKIN, Creature.MUMMY, Creature.BLACK_CAT, Creature.BAT, Creature.GHOST,
            Creature.SKULL, Creature.CANDY_CORN, Creature.CAULDRON, Creature.SPIDER)
        var x = 30f
        for (c in order) {
            world.add(c, x, 20f)
            x = if (x > 60f) 30f else x + 17f
            run(world, 0.8f)
        }
        run(world, 12f)
        assertFalse(world.gameOver)
        for (b in world.blobs) {
            assertTrue("speed ${b.creature} ${hypot(b.vx, b.vy)}", hypot(b.vx, b.vy) < 3f)
            assertTrue("spin ${b.creature} ${b.omega}", abs(b.omega) < 0.8f)
        }
    }

    @Test
    fun biggestCreatureBarelyFitsTwiceAcrossTheJar() {
        // Collision widths of two side by side: just under the jar width, so it is possible but tight.
        val twoAcross = 2f * 2f * Creature.LAST.halfWidth * World.COLLIDE_FRACTION
        assertTrue("twoAcross=$twoAcross", twoAcross < World.WIDTH)
        assertTrue("twoAcross=$twoAcross", twoAcross > World.WIDTH * 0.85f)

        val world = World(Random(1))
        val r = Creature.LAST
        val inset = r.halfWidth * World.COLLIDE_FRACTION + 0.2f
        val y = World.HEIGHT - r.radius / r.sqrtAspect * World.COLLIDE_FRACTION
        val left = world.add(r, inset, y)
        val right = world.add(r, World.WIDTH - inset, y)
        left.angle = 0f; left.refreshAxis()
        right.angle = 0f; right.refreshAxis()
        run(world, 2f)
        // They don't touch, so they neither merge nor get shoved around.
        assertEquals(2, world.blobs.size)
        assertEquals(inset, left.x, 0.5f)
        assertEquals(World.WIDTH - inset, right.x, 0.5f)
    }

    @Test
    fun creatureProportionsAreSane() {
        for (c in Creature.entries) {
            assertTrue("${c.name} aspect", c.aspect in 1f..1.5f)
            assertEquals(c.radius * c.radius, c.halfWidth * c.halfHeight, 1e-3f)
        }
        // Each tier is bigger than the last, by area and by the longer axis.
        for (c in Creature.entries.drop(1)) {
            val prev = Creature.entries[c.ordinal - 1]
            assertTrue(c.radius > prev.radius)
            assertTrue(maxOf(c.halfWidth, c.halfHeight) > maxOf(prev.halfWidth, prev.halfHeight))
        }
    }
}
