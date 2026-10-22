package ru.enjoythehookah.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Чем закончилась установка обновления. */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Prefs(context).updNote = "обновление установлено"
                Updater.forget(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // не «владелец устройства»: тихо ставить нельзя, окно гостям не показываем
                Prefs(context).updNote = "обновление ждёт подтверждения вручную"
            }
            else -> {
                Prefs(context).updNote = "обновление не установилось (" + status + ") " + msg
                Updater.forget(context)
            }
        }
        Log.i("EnjoyKiosk", "install result: " + status + " " + msg)
    }

    companion object {
        const val ACTION = "ru.enjoythehookah.kiosk.INSTALL_RESULT"
    }
}
