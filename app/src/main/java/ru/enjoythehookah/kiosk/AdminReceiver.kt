package ru.enjoythehookah.kiosk

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

/** Приёмник прав администратора/владельца устройства. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val extras: PersistableBundle? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        Kiosk.saveExtras(context, extras)
        Kiosk.applyOwnerPolicies(context)
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Kiosk.applyOwnerPolicies(context)
    }
}
