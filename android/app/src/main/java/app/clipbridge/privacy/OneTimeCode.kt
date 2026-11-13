package app.clipbridge.privacy

/**
 * Recognises copies that are one-time codes (OTPs, verification codes): the whole trimmed
 * text is 4–8 digits, optionally split once by a single space or dash ("482 913", "4829-13").
 */
object OneTimeCode {
    private val shape = Regex("""^(\d{4,8}|\d+[ -]\d+)$""")

    fun isCode(text: String): Boolean {
        val t = text.trim()
        if (!shape.matches(t)) return false
        return t.count { it.isDigit() } in 4..8
    }
}
