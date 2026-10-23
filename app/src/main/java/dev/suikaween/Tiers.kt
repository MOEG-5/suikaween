package dev.suikaween

import kotlin.math.sqrt

/**
 * The evolution chain. Two creatures of the same tier that touch merge into the next one,
 * the same way two cherries become a strawberry in Suika.
 *
 * [radius] is the radius of a circle with the same area as the creature, so tiers stay
 * comparable whatever their shape. [aspect] is long axis / short axis (1 = round). A [tall]
 * creature's art is stretched along its long axis (ghosts are taller than wide); otherwise the
 * long axis runs sideways (pumpkins are squat). The art always rotates with the body.
 */
enum class Creature(
    val displayName: String,
    val radius: Float,
    val aspect: Float,
    val tall: Boolean,
    val color: Int,
) {
    CANDY_CORN("Candy Corn", 5.0f, 1.30f, true, 0xFFFF9F1C.toInt()),
    EYEBALL("Eyeball", 5.8f, 1.00f, false, 0xFFF4F1E8.toInt()),
    SPIDER("Spider", 6.8f, 1.00f, false, 0xFF2B2233.toInt()),
    BAT("Bat", 8.0f, 1.30f, false, 0xFF5B3F7A.toInt()),
    GHOST("Ghost", 9.3f, 1.20f, true, 0xFFE3ECFF.toInt()),
    SKULL("Skull", 10.9f, 1.00f, false, 0xFFE8DFC8.toInt()),
    BLACK_CAT("Black Cat", 12.7f, 1.15f, false, 0xFF262130.toInt()),
    CAULDRON("Cauldron", 14.8f, 1.00f, false, 0xFF3A3F47.toInt()),
    MUMMY("Mummy", 17.3f, 1.20f, true, 0xFFD9CBA3.toInt()),
    PUMPKIN("Pumpkin", 20.2f, 1.20f, false, 0xFFF07B1A.toInt()),
    JACK_O_LANTERN("Jack-o'-Lantern", 23.8f, 1.15f, false, 0xFFE8650C.toInt());

    /** sqrt(aspect), cached: long half-axis = radius * this, short half-axis = radius / this. */
    val sqrtAspect: Float = sqrt(aspect)

    /** Half the width / height of the body at rest, relative to [radius] = 1. */
    val artScaleX: Float = if (tall) 1f / sqrtAspect else sqrtAspect
    val artScaleY: Float = if (tall) sqrtAspect else 1f / sqrtAspect

    /** Half the width of the (undeformed) creature when it is dropped. */
    val halfWidth: Float get() = radius * artScaleX
    val halfHeight: Float get() = radius * artScaleY

    /** Angle of the long axis when the creature is upright. */
    val restAngle: Float get() = if (tall) (Math.PI / 2).toFloat() else 0f

    /** Points awarded for creating this creature (triangular numbers, like the original). */
    val mergeScore: Int get() = (ordinal + 1) * (ordinal + 2) / 2

    val next: Creature? get() = entries.getOrNull(ordinal + 1)

    companion object {
        /** Only the smallest few creatures are ever handed to the player. */
        const val DROPPABLE = 5
        val LAST = entries.last()
        /** Bonus for pairing two of the final creature: they vanish in a burst. */
        const val FINAL_PAIR_BONUS = 100
    }
}
