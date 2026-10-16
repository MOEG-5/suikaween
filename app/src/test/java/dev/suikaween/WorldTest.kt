package dev.suikaween

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WorldTest {
    private fun run(world: World, seconds: Float) {
        repeat((seconds * 60).toInt()) { world.update(1f / 60f) }
    }

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
    fun differentCreaturesRestOnTopOfEachOther() {
        val world = World(Random(1))
        val bottom = world.add(Creature.SKULL, 50f, 120f)
        val top = world.add(Creature.EYEBALL, 51f, 80f)
        run(world, 4f)
        assertEquals(2, world.blobs.size)
        // Slippery: the small one should have slid off the round skull down to the floor.
        assertEquals(World.HEIGHT - top.collisionRadius, top.y, 0.5f)
        assertEquals(World.HEIGHT - bottom.collisionRadius, bottom.y, 0.5f)
    }

    @Test
    fun randomPileStaysInsideJarAndStable() {
        val rnd = Random(42)
        val world = World(Random(7))
        var t = 0f
        repeat(60 * 90) { frame ->
            if (frame % 40 == 0 && world.canDrop()) world.drop(rnd.nextFloat() * World.WIDTH)
            world.update(1f / 60f)
            t += 1f / 60f
        }
        assertTrue(world.score > 0)
        for (b in world.blobs) {
            assertFalse(b.x.isNaN() || b.y.isNaN())
            assertTrue("x in jar", b.x >= b.collisionRadius - 0.01f && b.x <= World.WIDTH - b.collisionRadius + 0.01f)
            assertTrue("above floor", b.y <= World.HEIGHT - b.collisionRadius + 0.01f)
            for (r in b.rad) assertTrue(r > 0f && r <= b.r * 1.3f + 1e-3f)
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
        val b = world.add(Creature.PUMPKIN, 50f, 100f)
        run(world, 3f)
        // Vertex pointing straight down (index SEGMENTS/4, y is down) is pressed flat by the floor.
        val down = b.rad[World.SEGMENTS / 4]
        assertTrue("down=$down r=${b.r}", down < b.r * 0.97f)
        assertTrue(down <= World.HEIGHT - b.y + 0.5f)
        // ...and the sides bulge to keep the area.
        assertTrue(b.rad[0] > b.r)
    }
}
