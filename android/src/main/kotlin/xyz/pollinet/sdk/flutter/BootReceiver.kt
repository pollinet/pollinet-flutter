package xyz.pollinet.sdk.flutter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import xyz.pollinet.sdk.BleService

/**
 * Restarts BleService after device reboot when the user opted in to auto-start.
 *
 * The opt-in flag is stored in SharedPreferences under the key [PREF_AUTO_START].
 * Flutter code (or the plugin itself) can toggle it via:
 *   context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
 *       .edit().putBoolean(PREF_AUTO_START, true).apply()
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PollinetBootReceiver"
        const val PREFS_NAME = "pollinet_sdk_prefs"
        const val PREF_AUTO_START = "ble_auto_start"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)

        if (!autoStart) {
            Log.d(TAG, "Boot completed — auto-start disabled, skipping")
            return
        }

        Log.d(TAG, "Boot completed — auto-starting BleService")
        val bleIntent = Intent(context, BleService::class.java).apply {
            action = BleService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(bleIntent)
        } else {
            context.startService(bleIntent)
        }
    }
}
