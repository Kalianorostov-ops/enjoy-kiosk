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

    /** Обновлять программу самой, когда выйдет новая версия. */
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoupd", true)
        set(v) = sp.edit().putBoolean("autoupd", v).apply()

    /** Номер скачанного, но ещё не установленного обновления (0 — нет). */
    var updVer: Int
        get() = sp.getInt("updver", 0)
        set(v) = sp.edit().putInt("updver", v).apply()

    /** Когда последний раз проверяли обновление. */
    var updAt: Long
        get() = sp.getLong("updat", 0L)
        set(v) = sp.edit().putLong("updat", v).apply()

    /** Чем закончилась последняя проверка/установка — для меню и админки. */
    var updNote: String
        get() = sp.getString("updnote", "") ?: ""
        set(v) = sp.edit().putString("updnote", v).apply()

    /** ⚙️ 1.0.7: держать системные настройки в порядке (включается командой «Настроить» из админки). */
    var setupAuto: Boolean
        get() = sp.getBoolean("setupauto", false)
        set(v) = sp.edit().putBoolean("setupauto", v).apply()

    var setupAt: Long
        get() = sp.getLong("setupat", 0L)
        set(v) = sp.edit().putLong("setupat", v).apply()

    var setupNote: String
        get() = sp.getString("setupnote", "") ?: ""
        set(v) = sp.edit().putString("setupnote", v).apply()

    /** 🚨 1.0.8: режим «Планшет пропал» — держится и после перезагрузки, даже без интернета. */
    var lost: Boolean
        get() = sp.getBoolean("lost", false)
        set(v) = sp.edit().putBoolean("lost", v).apply()

    /** Текст экрана «Пропал»: {"title","text","phone"} */
    var lostMsg: String
        get() = sp.getString("lostmsg", "") ?: ""
        set(v) = sp.edit().putString("lostmsg", v).apply()

    /** Последнее известное место: {"lat","lon","acc","at","p"} */
    var lastLoc: String
        get() = sp.getString("lastloc", "") ?: ""
        set(v) = sp.edit().putString("lastloc", v).apply()

    /** Google-аккаунт для защиты от сброса (числовой ID), который уже применили. */
    var frpId: String
        get() = sp.getString("frpid", "") ?: ""
        set(v) = sp.edit().putString("frpid", v).apply()

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
