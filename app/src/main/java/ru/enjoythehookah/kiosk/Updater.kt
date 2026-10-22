package ru.enjoythehookah.kiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновление самой программы без участия человека.
 * Раз в несколько часов смотрим последний выпуск на GitHub; если он новее — скачиваем APK
 * и ставим в спокойный момент (планшет спит ночью или экрана давно не касались).
 * Молча ставить может только «владелец устройства»; иначе обновление просто не трогаем.
 */
object Updater {

    private const val API = "https://api.github.com/repos/Kalianorostov-ops/enjoy-kiosk/releases/latest"
    private const val APK = "https://github.com/Kalianorostov-ops/enjoy-kiosk/releases/latest/download/enjoy-kiosk.apk"
    private const val NAME = "update.apk"

    @Volatile
    private var busy = false

    fun file(ctx: Context): File = File(ctx.cacheDir, NAME)

    /** Скачанное обновление готово к установке? */
    fun ready(ctx: Context): Boolean {
        val p = Prefs(ctx)
        return p.updVer > BuildConfig.VERSION_CODE && file(ctx).length() > 100_000L
    }

    /**
     * Проверить выпуск и, если он новее, скачать APK. Работает в отдельном потоке.
     * @param done вызывается в том же потоке: версия (0 — нечего ставить) и текст для журнала.
     */
    fun check(ctx: Context, done: ((Int, String) -> Unit)? = null) {
        if (busy) return
        busy = true
        Thread {
            var ver = 0
            var note: String
            try {
                val p = Prefs(ctx)
                p.updAt = System.currentTimeMillis()
                ver = latestVersion()
                when {
                    ver <= 0 -> note = "выпуск не прочитался"
                    ver <= BuildConfig.VERSION_CODE -> {
                        note = "обновлений нет (есть " + BuildConfig.VERSION_CODE + ")"
                        ver = 0
                    }
                    ready(ctx) && p.updVer == ver -> note = "обновление 1.0." + ver + " уже скачано"
                    else -> {
                        val f = file(ctx)
                        if (download(APK, f)) {
                            p.updVer = ver
                            note = "скачано обновление 1.0." + ver
                        } else {
                            note = "не удалось скачать 1.0." + ver
                            ver = 0
                        }
                    }
                }
            } catch (e: Throwable) {
                note = "ошибка проверки: " + (e.message ?: "")
                ver = 0
            }
            Prefs(ctx).updNote = note
            Log.i("EnjoyKiosk", "update: " + note)
            busy = false
            done?.invoke(ver, note)
        }.start()
    }

    /** Номер последнего выпуска: тег вида v1.0.37 → 37. */
    private fun latestVersion(): Int {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "EnjoyKiosk/" + BuildConfig.VERSION_NAME)
        }
        try {
            if (c.responseCode != 200) return 0
            val tag = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("tag_name", "")
            val last = tag.substringAfterLast('.', "")
            return last.filter { it.isDigit() }.toIntOrNull() ?: 0
        } finally {
            try { c.disconnect() } catch (_: Exception) {}
        }
    }

    private fun download(url: String, to: File): Boolean {
        var from = url
        var hops = 0
        while (hops < 5) {
            val c = (URL(from).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "EnjoyKiosk/" + BuildConfig.VERSION_NAME)
            }
            try {
                val code = c.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    from = c.getHeaderField("Location") ?: return false
                    hops++
                    continue
                }
                if (code != 200) return false
                val tmp = File(to.parentFile, to.name + ".part")
                c.inputStream.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) } }
                if (tmp.length() < 100_000L) { tmp.delete(); return false }
                if (to.exists()) to.delete()
                return tmp.renameTo(to)
            } catch (e: Throwable) {
                Log.w("EnjoyKiosk", "download: " + e.message)
                return false
            } finally {
                try { c.disconnect() } catch (_: Exception) {}
            }
        }
        return false
    }

    /** Поставить скачанное обновление. У «владельца устройства» — без вопросов и окон. */
    fun install(ctx: Context): Boolean {
        if (!ready(ctx)) return false
        val f = file(ctx)
        return try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("apk", 0, f.length()).use { out ->
                    f.inputStream().use { ins -> ins.copyTo(out, 64 * 1024) }
                    s.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val intent = Intent(ctx, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
                s.commit(PendingIntent.getBroadcast(ctx, 7, intent, flags).intentSender)
            }
            Prefs(ctx).updNote = "ставим 1.0." + Prefs(ctx).updVer
            true
        } catch (e: Throwable) {
            Prefs(ctx).updNote = "установка не прошла: " + (e.message ?: "")
            Log.w("EnjoyKiosk", "install: " + e.message)
            false
        }
    }

    /** Забыть скачанное (после удачной установки или если файл битый). */
    fun forget(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
        Prefs(ctx).updVer = 0
    }
}
