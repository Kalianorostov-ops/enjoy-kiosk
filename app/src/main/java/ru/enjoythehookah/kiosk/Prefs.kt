package ru.enjoythehookah.kiosk

import android.content.Context
import android.net.Uri

/** Настройки планшета: ссылка экрана стола и PIN для выхода. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()

    /** Будить экран, если гость погасил его кнопкой питания. */
    var wake: Boolean
        get() = sp.getBoolean("wake", true)
        set(v) = sp.edit().putBoolean("wake", v).apply()

    /** Временный выход из киоска (до перезапуска программы или «Вернуть киоск»). */
    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()

    companion object {
        const val HOST = "enjoythehookah.ru"

        /** Ссылка вида https://enjoythehookah.ru/table.html?t=4&k=ключ */
        fun isTableUrl(s: String?): Boolean {
            if (s.isNullOrBlank()) return false
            return try {
                val u = Uri.parse(s.trim())
                u.scheme == "https" && u.host == HOST && u.path == "/table.html" &&
                    !u.getQueryParameter("t").isNullOrBlank() && !u.getQueryParameter("k").isNullOrBlank()
            } catch (e: Exception) {
                false
            }
        }

        fun tableNo(s: String): String = try { Uri.parse(s).getQueryParameter("t") ?: "?" } catch (e: Exception) { "?" }
    }
}
