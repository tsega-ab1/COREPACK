package com.corepack.pet.agent

/**
 * Keeps the most sensitive things out of what the pet reads from the screen. Pure text rules, so they can be tested.
 * These are conservative defaults, not a promise: the screen holds anything the user opens.
 */
object ScreenGuard {
    /** Words in an app's package name that mark banking, wallets, password managers and authenticators. */
    private val SENSITIVE_APP_WORDS = listOf(
        "bank", "wallet", "authenticator", "password", "passwd", "keepass", "bitwarden", "lastpass",
        "1password", "onepassword", "agilebits", "dashlane", "vault", "paypal", "mpesa", "telebirr", "otp",
    )

    private val CODE_WORDS = listOf("otp", "verification", "one-time", "one time", "security code", "passcode", "pin", "your code", "code is", "password")
    private val CARD_LIKE = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    private val SHORT_NUMBER = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /**
     * Why the pet must not read this app, or null if it may. [extra] adds exact package names (or prefixes ending in a
     * dot) the user wants kept private, for apps the built-in words do not catch.
     */
    fun blockedReason(packageName: String?, extra: Collection<String> = emptyList()): String? {
        val p = packageName?.lowercase() ?: return null
        val listed = extra.any { e -> val x = e.trim().lowercase(); x.isNotEmpty() && (p == x || (x.endsWith(".") && p.startsWith(x))) }
        if (listed || SENSITIVE_APP_WORDS.any { it in p }) {
            return "the pet does not read this app. It looks like banking, a wallet, a password manager or an authenticator, which stay private."
        }
        return null
    }

    /** Hides card numbers, and short codes in text that talks about codes, passwords or PINs. */
    fun redact(text: String): String {
        var out = CARD_LIKE.replace(text) { m -> if (luhnOk(m.value.filter { it.isDigit() })) "(card number hidden)" else m.value }
        val lower = out.lowercase()
        if (CODE_WORDS.any { it in lower }) out = SHORT_NUMBER.replace(out, "(hidden)")
        return out
    }

    private fun luhnOk(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
