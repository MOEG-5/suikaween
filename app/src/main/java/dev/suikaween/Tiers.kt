package dev.suikaween

/**
 * The evolution chain. Two creatures of the same tier that touch merge into the next one,
 * the same way two cherries become a strawberry in Suika.
 */
enum class Creature(val displayName: String, val radius: Float, val color: Int) {
    CANDY_CORN("Candy Corn", 3.3f, 0xFFFF9F1C.toInt()),
    EYEBALL("Eyeball", 4.4f, 0xFFF4F1E8.toInt()),
    SPIDER("Spider", 5.6f, 0xFF2B2233.toInt()),
    BAT("Bat", 6.9f, 0xFF5B3F7A.toInt()),
    GHOST("Ghost", 8.4f, 0xFFE3ECFF.toInt()),
    SKULL("Skull", 10.0f, 0xFFE8DFC8.toInt()),
    BLACK_CAT("Black Cat", 11.7f, 0xFF262130.toInt()),
    CAULDRON("Cauldron", 13.5f, 0xFF3A3F47.toInt()),
    MUMMY("Mummy", 15.5f, 0xFFD9CBA3.toInt()),
    PUMPKIN("Pumpkin", 17.9f, 0xFFF07B1A.toInt()),
    JACK_O_LANTERN("Jack-o'-Lantern", 20.8f, 0xFFE8650C.toInt());

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
