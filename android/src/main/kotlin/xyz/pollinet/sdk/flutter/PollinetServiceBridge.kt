package xyz.pollinet.sdk.flutter

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.pollinet.sdk.BleService

/**
 * BroadcastReceiver that lets other same-signed apps (e.g. Pollistem)
 * start BleService or queue signed Solana transactions for BLE mesh relay.
 *
 * Usage from another app:
 *   val intent = Intent("xyz.pollinet.sdk.action.QUEUE_TRANSACTION")
 *   intent.putExtra("transaction_base64", base64EncodedTx)
 *   context.sendBroadcast(intent, "xyz.pollinet.sdk.permission.BIND_POLLINET_SERVICE")
 */
class PollinetServiceBridge : BroadcastReceiver() {

    companion object {
        private const val TAG = "PollinetServiceBridge"
        const val ACTION_QUEUE_TX = "xyz.pollinet.sdk.action.QUEUE_TRANSACTION"
        const val ACTION_START_SERVICE = "xyz.pollinet.sdk.action.START_SERVICE"
        const val EXTRA_TX_BASE64 = "transaction_base64"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "External request to start BleService")
                startBleService(context)
            }

            ACTION_QUEUE_TX -> {
                val base64Tx = intent.getStringExtra(EXTRA_TX_BASE64)
                if (base64Tx.isNullOrBlank()) {
                    Log.w(TAG, "QUEUE_TRANSACTION received with empty payload")
                    return
                }

                Log.d(TAG, "External QUEUE_TRANSACTION (${base64Tx.length} chars)")

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        bindAndQueue(context, base64Tx)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to queue external transaction", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun startBleService(context: Context) {
        val bleIntent = Intent(context, BleService::class.java).apply {
            action = BleService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(bleIntent)
        } else {
            context.startService(bleIntent)
        }
    }

    private fun bindAndQueue(context: Context, base64Tx: String) {
        startBleService(context)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as BleService.LocalBinder).getService()
                service.queueTransactionFromBase64(base64Tx)
                Log.d(TAG, "External transaction queued via BleService")
                context.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }

        context.bindService(
            Intent(context, BleService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }
}
