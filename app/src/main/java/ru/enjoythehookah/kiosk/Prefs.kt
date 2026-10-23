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

    /**
     * 🚨 1.0.15: «Пропал» включился сам — планшет долго не видит Wi-Fi заведения и не на зарядке.
     * Держится и после перезагрузки; снимается сам, когда планшет снова в сети заведения.
     */
    var lostAuto: Boolean
        get() = sp.getBoolean("lostauto", false)
        set(v) = sp.edit().putBoolean("lostauto", v).apply()

    /** С какого момента планшет вне сети заведения (0 — в сети или на зарядке). */
    var awaySince: Long
        get() = sp.getLong("awaysince", 0L)
        set(v) = sp.edit().putLong("awaysince", v).apply()

    /** Wi-Fi сети заведения (по одной в строке): запоминаются первые сутки после обновления и из админки «📶 Wi-Fi». */
    var homeSsids: String
        get() = sp.getString("homessids", "") ?: ""
        set(v) = sp.edit().putString("homessids", v).apply()

    /** До какого времени программа сама запоминает сеть заведения (первые сутки). */
    var homeLearnUntil: Long
        get() = sp.getLong("homelearn", 0L)
        set(v) = sp.edit().putLong("homelearn", v).apply()

    fun homeSet(): Set<String> = homeSsids.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun addHome(ssid: String) {
        val s = ssid.trim()
        if (s.isEmpty()) return
        val h = homeSet()
        if (s !in h && h.size < 10) homeSsids = (h + s).joinToString("\n")
    }

    /** Текст экрана «Пропал»: {"title","text","phone"} */
    var lostMsg: String
        get() = sp.getString("lostmsg", "") ?: ""
        set(v) = sp.edit().putString("lostmsg", v).apply()

    /** 📶 1.0.11: итог последней настройки Wi-Fi из админки */
    var wifiRes: String
        get() = sp.getString("wifires", "") ?: ""
        set(v) = sp.edit().putString("wifires", v).apply()

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
