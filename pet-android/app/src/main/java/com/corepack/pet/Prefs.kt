package com.corepack.pet

import android.content.Context

object Prefs {
    private val DEFAULT_URL = BuildConfig.DEFAULT_GATEWAY_URL

    private fun sp(c: Context) = c.getSharedPreferences("corepack_pet", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String = sp(c).getString("base_url", DEFAULT_URL) ?: DEFAULT_URL

    /** The invite code or gateway token. Falls back to the one baked into the APK, if any. */
    fun token(c: Context): String =
        sp(c).getString("token", "")?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_INVITE

    /** The person's own API key. Empty means the server's shared key is used. */
    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""

    /** Which engine the person's own key is for: "gemini" or "xai". */
    fun provider(c: Context): String = sp(c).getString("provider", "gemini") ?: "gemini"

    /** Optional model name to use with the person's own key. */
    fun model(c: Context): String = sp(c).getString("model", "") ?: ""

    fun saveEngine(c: Context, provider: String, apiKey: String, model: String) {
        sp(c).edit().putString("provider", provider).putString("api_key", apiKey.trim())
            .putString("model", model.trim()).apply()
    }

    fun save(c: Context, url: String, token: String) {
        sp(c).edit().putString("base_url", url.trim()).putString("token", token.trim()).apply()
    }

    fun petX(c: Context): Int = sp(c).getInt("pet_x", -1)

    fun petY(c: Context): Int = sp(c).getInt("pet_y", -1)

    /** Pet width in dp. 96 (about 85dp tall): close to the on-screen size of a launcher icon. */
    const val PET_DP_DEFAULT = 96
    const val PET_DP_MIN = 56
    const val PET_DP_MAX = 160

    fun petWidthDp(c: Context): Int = sp(c).getInt("pet_dp", PET_DP_DEFAULT).coerceIn(PET_DP_MIN, PET_DP_MAX)

    fun savePetWidthDp(c: Context, dp: Int) {
        sp(c).edit().putInt("pet_dp", dp.coerceIn(PET_DP_MIN, PET_DP_MAX)).apply()
    }

    fun savePetPos(c: Context, x: Int, y: Int) {
        sp(c).edit().putInt("pet_x", x).putInt("pet_y", y).apply()
    }
}
