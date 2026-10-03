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
