package dev.nyushu.optimizer.core.switch

enum class TitleKind { BASE_GAME, UPDATE, DLC, OTHER }

object TitleId {
    private val HEX16 = Regex("^[0-9A-Fa-f]{16}$")
    private val IN_NAME = Regex("""\[([0-9A-Fa-f]{16})]""")

    fun isValid(id: String) = HEX16.matches(id)
    fun normalize(id: String): String? = id.trim().takeIf { isValid(it) }?.uppercase()

    /** Extracts a title id written as `[0100XXXXXXXXXXXX]` in a file name. Heuristic: a naming convention, not proof. */
    fun fromFileName(name: String): String? =
        IN_NAME.findAll(name).map { it.groupValues[1].uppercase() }.firstOrNull { kindOf(it) != TitleKind.OTHER }

    /** Application ids start with 01; the last three hex digits identify base (000), update (800) or DLC. */
    fun kindOf(id: String): TitleKind {
        val n = normalize(id) ?: return TitleKind.OTHER
        if (!n.startsWith("01")) return TitleKind.OTHER
        return when (n.takeLast(3)) {
            "000" -> TitleKind.BASE_GAME
            "800" -> TitleKind.UPDATE
            else -> TitleKind.DLC
        }
    }

    fun baseOf(id: String): String? {
        val n = normalize(id) ?: return null
        return if (kindOf(n) == TitleKind.UPDATE) n.dropLast(3) + "000" else n
    }
}
