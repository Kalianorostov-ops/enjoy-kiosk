package ru.enjoythehookah.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После включения планшета или обновления программы — сразу открыть экран стола. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Kiosk.applyOwnerPolicies(context)
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
