package ru.enjoythehookah.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle

/** Мастер настройки Android спрашивает режим: отвечаем «полностью управляемое устройство». */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Kiosk.saveExtras(this, extrasOf(intent))
        if (Build.VERSION.SDK_INT >= 29) {
            val result = Intent().putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            )
            setResult(RESULT_OK, result)
        } else {
            setResult(RESULT_OK)
        }
        finish()
    }
}

/** Последний шаг мастера настройки: включаем ограничения киоска. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Kiosk.saveExtras(this, extrasOf(intent))
        Kiosk.applyOwnerPolicies(this)
        setResult(RESULT_OK)
        finish()
    }
}

internal fun extrasOf(intent: Intent?): PersistableBundle? {
    if (intent == null) return null
    return if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
    else
        @Suppress("DEPRECATION") intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
}
