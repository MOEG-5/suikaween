package dev.suikaween

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * Renders real frames with Robolectric's native (Skia) graphics. Catches crashes in the drawing
 * code and writes screenshots to app/build/screenshots for eyeballing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderTest {
    private fun render(world: World, name: String) {
        val view = GameView(RuntimeEnvironment.getApplication(), world)
        val w = 1080
        val h = 2160
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.getPixel(w / 2, h / 2) != 0)
    }

    @Test
    fun midGame() {
        val rnd = Random(5)
        val world = World(Random(11))
        repeat(60 * 70) { frame ->
            if (frame % 45 == 0 && world.canDrop()) world.drop(20f + rnd.nextFloat() * 60f)
            world.update(1f / 60f)
            if (world.gameOver) return@repeat
        }
        render(world, "mid_game")
    }

    @Test
    fun everyCreature() {
        val world = World(Random(1))
        // Lay the roster out in rows from the floor up, biggest first, each at its resting angle.
        var x = 0f
        var floor = World.HEIGHT
        var rowHeight = 0f
        for (c in Creature.entries.reversed()) {
            if (x + 2 * c.halfWidth > World.WIDTH) {
                x = 0f
                floor -= rowHeight + 3f
                rowHeight = 0f
            }
            val b = world.add(c, x + c.halfWidth, floor - c.halfHeight * World.COLLIDE_FRACTION)
            b.angle = c.restAngle
            b.refreshAxis()
            x += 2 * c.halfWidth
            rowHeight = maxOf(rowHeight, 2 * c.halfHeight)
        }
        world.updateShapes(1f / 60f)
        render(world, "every_creature")
    }
}
