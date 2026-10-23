package ru.enjoythehookah.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PersistableBundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** Права «владельца устройства»: полная блокировка планшета под экран стола. */
object Kiosk {
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    const val OWNER_INFO = "Это устройство принадлежит Enjoy the Hookah. Нашли? Позвоните: 8 961 427-19-99"

    /**
     * 📶 1.0.11: Wi-Fi сети из админки: [{"s":"имя сети","p":"пароль"}]. Программа — владелец устройства,
     * поэтому Android разрешает ей добавлять и менять сети. Пароль никуда не выводится; итог — «имя ✓/✗».
     */
    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, json: String): String {
        val p = Prefs(ctx)
        if (!isOwner(ctx)) return "нет прав владельца устройства".also { p.wifiRes = it }
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val arr = try { org.json.JSONArray(json) } catch (_: Exception) { return "ошибка данных".also { p.wifiRes = it } }
        grantLocation(ctx, false)
        try { if (!wm.isWifiEnabled) wm.isWifiEnabled = true } catch (_: Throwable) {}
        val known: List<android.net.wifi.WifiConfiguration> = try { wm.configuredNetworks ?: emptyList() } catch (_: Throwable) { emptyList() }
        val res = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ssid = o.optString("s").trim()
            val pass = o.optString("p")
            if (ssid.isEmpty()) continue
            val q = "\"" + ssid + "\""
            val c = android.net.wifi.WifiConfiguration()
            c.SSID = q
            if (pass.isEmpty()) c.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            else c.preSharedKey = "\"" + pass + "\""
            val old = known.firstOrNull { it.SSID == q }
            var id = -1
            try {
                if (old != null) { c.networkId = old.networkId; id = wm.updateNetwork(c) }
                if (id < 0) id = wm.addNetwork(c)
            } catch (e: Throwable) { Log.w("EnjoyKiosk", "wifi: " + e.message) }
            p.addHome(ssid)   // 🚨 1.0.15: сеть из админки — сеть заведения
            if (id >= 0) { try { wm.enableNetwork(id, false) } catch (_: Throwable) {}; res.add("$ssid ✓") } else res.add("$ssid ✗")
        }
        val r = res.joinToString(", ").ifEmpty { "нет сетей" }
        p.wifiRes = r
        return r
    }

    /** Имя сети, к которой планшет подключён сейчас (пусто — неизвестно). */
    @Suppress("DEPRECATION")
    fun wifiSsid(ctx: Context): String = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val s = wm.connectionInfo?.ssid ?: ""
        if (s.isEmpty() || s.contains("unknown ssid")) "" else s.trim('"')
    } catch (_: Throwable) { "" }

    /** Включён ли сейчас режим закрепления (киоск или обычное закрепление экрана). */
    fun isLocked(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private val restrictions = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
        UserManager.DISALLOW_USB_FILE_TRANSFER,
        // гость не сбросит планшет, не сменит сеть и не сдвинет время (расписание ночи зависит от часов)
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_CONFIG_WIFI,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
    )

    /** Включить все ограничения. Вызывается после настройки и при каждом запуске. */
    fun applyOwnerPolicies(ctx: Context) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        safe { d.setLockTaskPackages(a, arrayOf(ctx.packageName)) }
        // без меню питания, шторки, уведомлений, кнопок «Домой» и «Недавние»
        if (Build.VERSION.SDK_INT >= 28) safe { d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
        safe { d.setKeyguardDisabled(a, true) }
        safe { d.setStatusBarDisabled(a, true) }
        // экран не гаснет, пока планшет на зарядке (AC | USB | беспроводная)
        safe { d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7") }
        // программа — «рабочий стол»: после перезагрузки и кнопки «Домой» открывается экран стола
        safe {
            val f = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(a, f, ComponentName(ctx, MainActivity::class.java))
        }
        restrictions.forEach { r -> safe { d.addUserRestriction(a, r) } }
        // 🚨 1.0.15: геолокация включена всегда — без неё Android не скажет имя Wi-Fi сети (нужно для «Пропал» без интернета)
        grantLocation(ctx, true)
        // 🚨 1.0.15: кому принадлежит планшет — записано в сам Android (экран блокировки, «Об устройстве»), работает без интернета
        if (Build.VERSION.SDK_INT >= 24) {
            safe { d.setDeviceOwnerLockScreenInfo(a, OWNER_INFO) }
            safe { d.setOrganizationName(a, "Enjoy the Hookah") }
        }
        // обновления Android ставятся ночью, с 04:00 до 08:00 — не в смену
        safe {
            d.setSystemUpdatePolicy(
                a,
                android.app.admin.SystemUpdatePolicy.createWindowedInstallPolicy(4 * 60, 8 * 60)
            )
        }
    }

    // ---------- ⚙️ 1.0.7: настройки планшета из админки ----------

    const val MSK = "Europe/Moscow"

    /**
     * Привести системные настройки к нужным: автояркость выкл, время — автоматически, пояс Москва,
     * громкость уведомлений и системных звуков 0. Только то, что Android разрешает владельцу устройства.
     * Возвращает, что поменяли (для админки).
     */
    fun applySetup(ctx: Context): String {
        val done = mutableListOf<String>()
        val p = Prefs(ctx)
        if (isOwner(ctx)) {
            val d = dpm(ctx)
            val a = admin(ctx)
            if (Build.VERSION.SDK_INT >= 28 && autoBrightness(ctx) != 0) safe {
                d.setSystemSetting(a, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString())
                done.add("автояркость выкл")
            }
            if (autoTime(ctx) != 1) safe {
                if (Build.VERSION.SDK_INT >= 30) d.setAutoTimeEnabled(a, true)
                else d.setGlobalSetting(a, Settings.Global.AUTO_TIME, "1")
                done.add("время автоматически")
            }
            if (java.util.TimeZone.getDefault().id != MSK) safe {
                if (Build.VERSION.SDK_INT >= 30) {
                    d.setAutoTimeZoneEnabled(a, false)
                    if (d.setTimeZone(a, MSK)) done.add("пояс Москва")
                } else {
                    val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                    am.setTimeZone(MSK)
                    done.add("пояс Москва")
                }
            }
            // обновления Android — только ночью (на случай, если политику сбросили)
            if (d.systemUpdatePolicy == null) safe {
                d.setSystemUpdatePolicy(a, android.app.admin.SystemUpdatePolicy.createWindowedInstallPolicy(4 * 60, 8 * 60))
                done.add("обновления ночью")
            }
        }
        // громкость уведомлений и системных звуков (касания) — 0; звук страницы стола идёт через «медиа» и не меняется
        val au = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (st in intArrayOf(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM)) safe {
            if (au.getStreamVolume(st) > 0) {
                au.setStreamVolume(st, 0, 0)
                done.add(if (st == android.media.AudioManager.STREAM_SYSTEM) "звук касаний 0" else "уведомления 0")
            }
        }
        p.setupAuto = true
        p.setupAt = System.currentTimeMillis()
        p.setupNote = if (done.isEmpty()) "всё уже было в порядке" else done.joinToString(", ")
        return p.setupNote
    }

    fun autoBrightness(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
    } catch (e: Throwable) { -1 }

    fun autoTime(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME)
    } catch (e: Throwable) { -1 }

    /** Состояние настроек для админки (короткие ключи — отчёт идёт вместе со сведениями о планшете). */
    fun settingsReport(ctx: Context): org.json.JSONObject {
        val p = Prefs(ctx)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val au = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val o = org.json.JSONObject()
        o.put("ab", autoBrightness(ctx))
        o.put("at", autoTime(ctx))
        o.put("tz", java.util.TimeZone.getDefault().id)
        try { o.put("ps", if (pm.isPowerSaveMode) 1 else 0) } catch (_: Throwable) {}
        try { o.put("bo", if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) 1 else 0) } catch (_: Throwable) {}
        try { o.put("nv", au.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)) } catch (_: Throwable) {}
        try { o.put("sv", au.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM)) } catch (_: Throwable) {}
        try { o.put("so", Settings.Global.getInt(ctx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN)) } catch (_: Throwable) {}
        if (isOwner(ctx)) try { o.put("up", if (dpm(ctx).systemUpdatePolicy != null) 1 else 0) } catch (_: Throwable) {}
        o.put("auto", if (p.setupAuto) 1 else 0)
        if (p.setupAt > 0) {
            o.put("fxat", p.setupAt / 1000L)
            o.put("fx", p.setupNote.take(100))
        }
        return o
    }

    // ---------- 🚨 1.0.8: кража ----------

    /** Выдать себе разрешение на местоположение (владелец устройства может сам). on — ещё и включить геолокацию. */
    fun grantLocation(ctx: Context, on: Boolean) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        for (perm in arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) safe {
            d.setPermissionGrantState(a, ctx.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        if (on && Build.VERSION.SDK_INT >= 30) safe { d.setLocationEnabled(a, true) }
    }

    /**
     * Защита от сброса: после сброса кнопками (Recovery) планшет попросит войти в этот Google-аккаунт.
     * id — числовой ID аккаунта Google; пустой — снять защиту. Возвращает frpState.
     */
    fun setFrp(ctx: Context, id: String): Int {
        if (!isOwner(ctx) || Build.VERSION.SDK_INT < 30) return -1
        val d = dpm(ctx)
        val a = admin(ctx)
        try {
            if (id.isBlank()) d.setFactoryResetProtectionPolicy(a, null)
            else d.setFactoryResetProtectionPolicy(a, android.app.admin.FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(listOf(id))
                .setFactoryResetProtectionEnabled(true)
                .build())
            Prefs(ctx).frpId = id
        } catch (e: UnsupportedOperationException) {
            return -1
        } catch (e: Throwable) {
            Log.w("EnjoyKiosk", "frp: " + e.message)
        }
        return frpState(ctx)
    }

    /** 1 — защита от сброса включена, 0 — нет, -1 — планшет не поддерживает. */
    fun frpState(ctx: Context): Int {
        if (!isOwner(ctx) || Build.VERSION.SDK_INT < 30) return -1
        return try {
            val p = dpm(ctx).getFactoryResetProtectionPolicy(admin(ctx))
            if (p != null && p.isFactoryResetProtectionEnabled && p.factoryResetProtectionAccounts.isNotEmpty()) 1 else 0
        } catch (e: UnsupportedOperationException) { -1 } catch (e: Throwable) { 0 }
    }

    /** Перезагрузить планшет (команда из админки). Только в режиме владельца устройства. */
    fun reboot(ctx: Context): Boolean {
        if (!isOwner(ctx)) return false
        return try {
            dpm(ctx).reboot(admin(ctx))
            true
        } catch (e: Throwable) {
            Log.w("EnjoyKiosk", "reboot: " + e.message)
            false
        }
    }

    /** Временно снять ограничения (вход в настройки Android по PIN). */
    fun relaxOwnerPolicies(ctx: Context) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        safe { d.setStatusBarDisabled(a, false) }
        safe { d.setKeyguardDisabled(a, false) }
        restrictions.forEach { r -> safe { d.clearUserRestriction(a, r) } }
    }

    /** Полностью снять режим владельца: планшет снова обычный. */
    fun removeOwner(ctx: Context) {
        if (!isOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        relaxOwnerPolicies(ctx)
        safe { d.setSystemUpdatePolicy(a, null) }
        safe { d.clearPackagePersistentPreferredActivities(a, ctx.packageName) }
        safe { d.setLockTaskPackages(a, emptyArray<String>()) }
        safe { d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0") }
        @Suppress("DEPRECATION")
        safe { d.clearDeviceOwnerApp(ctx.packageName) }
    }

    /** Ссылка и PIN, переданные в QR-коде настройки планшета. */
    fun saveExtras(ctx: Context, extras: PersistableBundle?) {
        if (extras == null) return
        val p = Prefs(ctx)
        val url = extras.getString("url")
        if (Prefs.isTableUrl(url)) p.url = url!!.trim()
        val pin = extras.getString("pin")
        if (!pin.isNullOrBlank() && pin.length >= 4) p.pin = pin
    }

    inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.w("EnjoyKiosk", "policy: " + e.message) }
    }
}
