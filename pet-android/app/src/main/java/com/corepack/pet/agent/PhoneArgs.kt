package com.corepack.pet.agent

/** Checks the arguments of phone actions before anything is launched. Pure, so it can be tested. */
object PhoneArgs {
    fun hourMinute(h: Any?, m: Any?): Pair<Int, Int>? {
        val hour = numberArg(h)?.toInt() ?: return null
        val minute = numberArg(m)?.toInt() ?: 0
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    fun seconds(v: Any?): Int? = numberArg(v)?.toInt()?.takeIf { it in 1..86_400 }

    fun httpUrl(s: String?): String? {
        val u = s?.trim() ?: return null
        val ok = (u.startsWith("https://") || u.startsWith("http://")) && u.length in 10..2000 && u.none { it.isWhitespace() }
        return if (ok) u else null
    }

    /** Keeps digits and + * #; rejects anything that does not look like a phone number. */
    fun phoneNumber(s: String?): String? {
        val n = s?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' } ?: return null
        return if (n.length in 3..20 && n.count { it.isDigit() } >= 3) n else null
    }

    sealed class AppPick {
        class One(val label: String, val pkg: String) : AppPick()
        class Many(val labels: List<String>) : AppPick()
        object None : AppPick()
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Picks the app the user means from (label, package) pairs: exact, then starts-with, then contains. */
    fun pickApp(query: String, apps: List<Pair<String, String>>): AppPick {
        val q = norm(query)
        if (q.isEmpty()) return AppPick.None
        val levels = listOf<(String) -> Boolean>({ it == q }, { it.startsWith(q) }, { it.contains(q) })
        for (match in levels) {
            val hits = apps.filter { match(norm(it.first)) }.distinctBy { it.second }
            if (hits.size == 1) return AppPick.One(hits[0].first, hits[0].second)
            if (hits.size > 1) return AppPick.Many(hits.map { it.first }.distinct().take(4))
        }
        return AppPick.None
    }
}
