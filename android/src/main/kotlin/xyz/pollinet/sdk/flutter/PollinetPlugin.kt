package xyz.pollinet.sdk.flutter

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import xyz.pollinet.sdk.*

class PollinetPlugin : FlutterPlugin, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var applicationContext: Context? = null
    private var sdk: PolliNetSDK? = null
    private var bleService: BleService? = null
    private var isInitialized = false
    private var serviceBound = false

    private val PERMISSION_REQUEST_CODE = 1001
    private var pendingPermissionResult: MethodChannel.Result? = null

    // Deferred that completes when BleService binds
    private var serviceDeferred = CompletableDeferred<BleService>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            android.util.Log.d("PollinetPlugin", "BleService connected")
            val localBinder = binder as? BleService.LocalBinder
            val service = localBinder?.getService()
            if (service != null) {
                bleService = service
                serviceBound = true
                if (!serviceDeferred.isCompleted) {
                    serviceDeferred.complete(service)
                }
            } else {
                android.util.Log.e("PollinetPlugin", "Failed to get BleService from binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.w("PollinetPlugin", "BleService disconnected")
            bleService = null
            serviceBound = false
            serviceDeferred = CompletableDeferred()
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "pollinet_sdk")
        channel.setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        unbindBleService()
        applicationContext = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener { requestCode, permissions, grantResults ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                val status = mutableMapOf<String, String>()
                for (i in permissions.indices) {
                    val permission = permissions[i]
                    val granted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                    status[permission] = if (granted) "granted" else "denied"
                }
                pendingPermissionResult?.success(status)
                pendingPermissionResult = null
                true
            }
            false
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    // =========================================================================
    // BleService lifecycle
    // =========================================================================

    private fun startAndBindBleService(context: Context) {
        val intent = Intent(context, BleService::class.java).apply {
            action = BleService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        android.util.Log.d("PollinetPlugin", "BleService start + bind requested")
    }

    private fun unbindBleService() {
        if (serviceBound) {
            try {
                applicationContext?.unbindService(serviceConnection)
            } catch (e: Exception) {
                android.util.Log.w("PollinetPlugin", "Error unbinding BleService: ${e.message}")
            }
            serviceBound = false
            bleService = null
        }
    }

    private fun stopBleService() {
        val ctx = applicationContext ?: return
        unbindBleService()
        try {
            val intent = Intent(ctx, BleService::class.java).apply {
                action = BleService.ACTION_STOP
            }
            ctx.stopService(intent)
        } catch (e: Exception) {
            android.util.Log.w("PollinetPlugin", "Error stopping BleService: ${e.message}")
        }
    }

    /**
     * Access the single PolliNetSDK instance owned by BleService.
     * BleService.sdk is public (with private set), so direct access works.
     * Both the plugin and BleService share this single Rust FFI handle
     * so queues, state, and transport are unified.
     */
    private fun getSdkFromBleService(): PolliNetSDK? {
        return bleService?.sdk
    }

    // =========================================================================
    // Method call handler
    // =========================================================================

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "checkPermissions" -> {
                val permissions = getRequiredPermissions()
                val status = checkPermissionsStatus(permissions)
                result.success(status)
            }
            "requestPermissions" -> {
                requestPermissions(result)
            }
            "initialize" -> {
                android.util.Log.d("PollinetPlugin", "Initialize method called (BleService path)")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val ctx = applicationContext
                            ?: throw IllegalStateException("Application context not available")

                        val configMap = call.argument<Map<String, Any>>("config")
                        val config = if (configMap != null) {
                            SdkConfig(
                                version = (configMap["version"] as? Number)?.toInt() ?: 1,
                                rpcUrl = configMap["rpcUrl"] as? String,
                                enableLogging = configMap["enableLogging"] as? Boolean ?: true,
                                logLevel = configMap["logLevel"] as? String ?: "info",
                                storageDirectory = configMap["storageDirectory"] as? String
                            )
                        } else {
                            SdkConfig(
                                version = 1,
                                rpcUrl = null,
                                enableLogging = true,
                                logLevel = "info",
                                storageDirectory = null
                            )
                        }

                        // Reset deferred if we're re-initializing
                        if (serviceDeferred.isCompleted) {
                            serviceDeferred = CompletableDeferred()
                        }

                        // 1. Start BleService as a foreground service and bind to it
                        android.util.Log.d("PollinetPlugin", "Starting BleService...")
                        startAndBindBleService(ctx)

                        // 2. Wait for service binding (timeout 15s)
                        android.util.Log.d("PollinetPlugin", "Waiting for BleService binding...")
                        val service = withTimeout(15_000) { serviceDeferred.await() }
                        android.util.Log.d("PollinetPlugin", "BleService bound, calling initializeSdk...")

                        // 3. Initialize SDK inside BleService (creates single Rust FFI handle,
                        //    starts event worker, sending loop, network listener, auto-save)
                        service.initializeSdk(config).fold(
                            onSuccess = {
                                // 4. Get the SDK reference from BleService via reflection
                                //    so Dart method calls use the same Rust handle as BLE transport
                                sdk = getSdkFromBleService()
                                if (sdk != null) {
                                    isInitialized = true
                                    android.util.Log.d("PollinetPlugin", "SDK initialized via BleService — BLE transport active")
                                    result.success(true)
                                } else {
                                    android.util.Log.e("PollinetPlugin", "SDK initialized but reflection failed")
                                    result.error("INIT_ERROR", "SDK initialized but could not obtain reference", null)
                                }
                            },
                            onFailure = { error ->
                                android.util.Log.e("PollinetPlugin", "BleService.initializeSdk failed: ${error.message}")
                                result.error("INIT_ERROR", "Failed to initialize Pollinet SDK: ${error.message}", null)
                            }
                        )
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        android.util.Log.e("PollinetPlugin", "BleService binding timed out")
                        result.error("INIT_ERROR", "BleService binding timed out — check foreground service permissions", null)
                    } catch (e: Exception) {
                        android.util.Log.e("PollinetPlugin", "Exception during initialization: ${e.message}", e)
                        result.error("INIT_ERROR", "Failed to initialize Pollinet SDK: ${e.message}", null)
                    }
                }
            }
            "shutdown" -> {
                try {
                    sdk?.shutdown()
                    sdk = null
                    isInitialized = false
                    stopBleService()
                    result.success(true)
                } catch (e: Exception) {
                    result.error("SHUTDOWN_ERROR", "Failed to shutdown Pollinet SDK: ${e.message}", e)
                }
            }
            "version" -> {
                try {
                    val version = PolliNetSDK.version()
                    result.success(version)
                } catch (e: Exception) {
                    result.error("VERSION_ERROR", "Failed to get version: ${e.message}", e)
                }
            }
            "checkBatteryOptimization" -> {
                checkBatteryOptimization(result)
            }
            "requestBatteryOptimization" -> {
                requestBatteryOptimization(result)
            }
            else -> {
                handleAsyncCall(result) {
                    when (call.method) {
                        // =============================================================
                        // Transport API
                        // =============================================================
                        "pushInbound" -> {
                            val dataBase64 = call.argument<String>("data")
                                ?: throw IllegalArgumentException("data is required")
                            val data = Base64.decode(dataBase64, Base64.NO_WRAP)
                            sdk?.pushInbound(data)?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "nextOutbound" -> {
                            val maxLen = call.argument<Int>("maxLen") ?: 1024
                            val outbound = sdk?.nextOutbound(maxLen)
                            result.success(outbound?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                        }
                        "tick" -> {
                            val tickResult = sdk?.tick()?.fold(
                                onSuccess = { it },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                            result.success(tickResult)
                        }
                        "metrics" -> {
                            val metrics = sdk?.metrics()?.fold(
                                onSuccess = { it },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                            result.success(mapOf(
                                "fragmentsBuffered" to metrics.fragmentsBuffered,
                                "transactionsComplete" to metrics.transactionsComplete,
                                "reassemblyFailures" to metrics.reassemblyFailures,
                                "lastError" to metrics.lastError,
                                "updatedAt" to metrics.updatedAt
                            ))
                        }
                        "clearTransaction" -> {
                            val txId = call.argument<String>("txId")
                                ?: throw IllegalArgumentException("txId is required")
                            sdk?.clearTransaction(txId)?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        // =============================================================
                        // Transaction Builders
                        // =============================================================
                        "createUnsignedTransaction" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val request = call.argument<Map<String, Any>>("request")
                                        ?: throw IllegalArgumentException("request is required")
                                    
                                    val sender = request["sender"] as? String
                                        ?: throw IllegalArgumentException("sender is required")
                                    val recipient = request["recipient"] as? String
                                        ?: throw IllegalArgumentException("recipient is required")
                                    val feePayer = request["feePayer"] as? String
                                        ?: throw IllegalArgumentException("feePayer is required")
                                    val amount = request["amount"] as? Int
                                        ?: throw IllegalArgumentException("amount is required")
                                    val nonceAccount = request["nonceAccount"] as? String
                                    
                                    val txResult = sdk?.createUnsignedTransaction(
                                        sender = sender,
                                        recipient = recipient,
                                        feePayer = feePayer,
                                        amount = amount.toLong(),
                                        nonceAccount = nonceAccount
                                    ) ?: throw IllegalStateException("SDK not initialized")
                                    
                                    txResult.fold(
                                        onSuccess = { txId -> result.success(txId) },
                                        onFailure = { error -> result.error("TX_ERROR", "Failed to create transaction: ${error.message}", null) }
                                    )
                                } catch (e: Exception) {
                                    result.error("TX_ERROR", "Failed to create transaction: ${e.message}", e)
                                }
                            }
                        }
                        "createUnsignedSplTransaction" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val request = call.argument<Map<String, Any>>("request")
                                        ?: throw IllegalArgumentException("request is required")
                                    val senderWallet = request["senderWallet"] as? String
                                        ?: throw IllegalArgumentException("senderWallet is required")
                                    val recipientWallet = request["recipientWallet"] as? String
                                        ?: throw IllegalArgumentException("recipientWallet is required")
                                    val feePayer = request["feePayer"] as? String
                                        ?: throw IllegalArgumentException("feePayer is required")
                                    val mintAddress = request["mintAddress"] as? String
                                        ?: throw IllegalArgumentException("mintAddress is required")
                                    val amount = (request["amount"] as? Number)?.toLong()
                                        ?: throw IllegalArgumentException("amount is required")
                                    val nonceAccount = request["nonceAccount"] as? String

                                    sdk?.createUnsignedSplTransaction(
                                        senderWallet = senderWallet,
                                        recipientWallet = recipientWallet,
                                        feePayer = feePayer,
                                        mintAddress = mintAddress,
                                        amount = amount,
                                        nonceAccount = nonceAccount
                                    )?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("TX_ERROR", "Failed to create SPL transaction: ${it.message}", null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("TX_ERROR", "Failed to create SPL transaction: ${e.message}", null)
                                }
                            }
                        }
                        "createUnsignedNonceTransactions" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val count = call.argument<Int>("count")
                                        ?: throw IllegalArgumentException("count is required")
                                    val payerPubkey = call.argument<String>("payerPubkey")
                                        ?: throw IllegalArgumentException("payerPubkey is required")
                                    
                                    val nonceTxsResult = sdk?.createUnsignedNonceTransactions(
                                        count = count,
                                        payerPubkey = payerPubkey
                                    ) ?: throw IllegalStateException("SDK not initialized")
                                    
                                    nonceTxsResult.fold(
                                        onSuccess = { nonceTxs ->
                                            val resultList = nonceTxs.map { tx ->
                                                mapOf(
                                                    "unsignedTransactionBase64" to tx.unsignedTransactionBase64,
                                                    "nonceKeypairBase64" to tx.nonceKeypairBase64,
                                                    "noncePubkey" to tx.noncePubkey
                                                )
                                            }
                                            result.success(resultList)
                                        },
                                        onFailure = { error ->
                                            result.error("NONCE_ERROR", "Failed to create nonce transactions: ${error.message}", null)
                                        }
                                    )
                                } catch (e: Exception) {
                                    result.error("NONCE_ERROR", "Failed to create nonce transactions: ${e.message}", e)
                                }
                            }
                        }

                        // =============================================================
                        // Signature Helpers
                        // =============================================================
                        "prepareSignPayload" -> {
                            val base64Tx = call.argument<String>("base64Tx")
                                ?: throw IllegalArgumentException("base64Tx is required")
                            val payload = sdk?.prepareSignPayload(base64Tx)
                            result.success(payload?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                        }
                        "applySignature" -> {
                            val base64Tx = call.argument<String>("base64Tx")
                                ?: throw IllegalArgumentException("base64Tx is required")
                            val signerPubkey = call.argument<String>("signerPubkey")
                                ?: throw IllegalArgumentException("signerPubkey is required")
                            val signatureBase64 = call.argument<String>("signatureBytes")
                                ?: throw IllegalArgumentException("signatureBytes is required")
                            val signatureBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
                            sdk?.applySignature(base64Tx, signerPubkey, signatureBytes)?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "verifyAndSerialize" -> {
                            val base64Tx = call.argument<String>("base64Tx")
                                ?: throw IllegalArgumentException("base64Tx is required")
                            sdk?.verifyAndSerialize(base64Tx)?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        // =============================================================
                        // Fragmentation
                        // =============================================================
                        "fragment" -> {
                            val txBytesBase64 = call.argument<String>("txBytes")
                                ?: throw IllegalArgumentException("txBytes is required")
                            val maxPayload = call.argument<Int>("maxPayload")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            sdk?.fragment(txBytes, maxPayload)?.fold(
                                onSuccess = { fragmentList ->
                                    val fragments = fragmentList.fragments.map { f ->
                                        mapOf(
                                            "id" to f.id,
                                            "index" to f.index,
                                            "total" to f.total,
                                            "data" to f.data,
                                            "fragment_type" to f.fragmentType,
                                            "checksum" to f.checksum
                                        )
                                    }
                                    result.success(mapOf("fragments" to fragments))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        // =============================================================
                        // Offline Bundle Management
                        // =============================================================
                        "prepareOfflineBundle" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val count = call.argument<Int>("count")
                                        ?: throw IllegalArgumentException("count is required")
                                    val senderKeypairBase64 = call.argument<String>("senderKeypair")
                                        ?: throw IllegalArgumentException("senderKeypair is required")
                                    val bundleFile = call.argument<String>("bundleFile")
                                    val senderKeypair = Base64.decode(senderKeypairBase64, Base64.NO_WRAP)

                                    sdk?.prepareOfflineBundle(count, senderKeypair, bundleFile)?.fold(
                                        onSuccess = { bundle ->
                                            result.success(mapOf(
                                                "version" to bundle.version,
                                                "nonceCaches" to bundle.nonceCaches.map { n ->
                                                    mapOf(
                                                        "version" to n.version,
                                                        "nonceAccount" to n.nonceAccount,
                                                        "authority" to n.authority,
                                                        "blockhash" to n.blockhash,
                                                        "lamportsPerSignature" to n.lamportsPerSignature,
                                                        "cachedAt" to n.cachedAt,
                                                        "used" to n.used
                                                    )
                                                },
                                                "maxTransactions" to bundle.maxTransactions,
                                                "createdAt" to bundle.createdAt
                                            ))
                                        },
                                        onFailure = { result.error("BUNDLE_ERROR", "Failed to prepare offline bundle: ${it.message}", null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("BUNDLE_ERROR", "Failed to prepare offline bundle: ${e.message}", null)
                                }
                            }
                        }
                        "createOfflineTransaction" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val senderKeypairBase64 = call.argument<String>("senderKeypair")
                                        ?: throw IllegalArgumentException("senderKeypair is required")
                                    val nonceAuthorityBase64 = call.argument<String>("nonceAuthorityKeypair")
                                        ?: throw IllegalArgumentException("nonceAuthorityKeypair is required")
                                    val recipient = call.argument<String>("recipient")
                                        ?: throw IllegalArgumentException("recipient is required")
                                    val amount = call.argument<Int>("amount")?.toLong()
                                        ?: throw IllegalArgumentException("amount is required")

                                    sdk?.createOfflineTransaction(
                                        senderKeypair = Base64.decode(senderKeypairBase64, Base64.NO_WRAP),
                                        nonceAuthorityKeypair = Base64.decode(nonceAuthorityBase64, Base64.NO_WRAP),
                                        recipient = recipient,
                                        amount = amount
                                    )?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("TX_ERROR", "Failed to create offline transaction: ${it.message}", null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("TX_ERROR", "Failed to create offline transaction: ${e.message}", null)
                                }
                            }
                        }
                        "submitOfflineTransaction" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val txBase64 = call.argument<String>("transactionBase64")
                                        ?: throw IllegalArgumentException("transactionBase64 is required")
                                    val verifyNonce = call.argument<Boolean>("verifyNonce") ?: true

                                    sdk?.submitOfflineTransaction(txBase64, verifyNonce)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("SUBMIT_ERROR", "Failed to submit offline transaction: ${it.message}", null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("SUBMIT_ERROR", "Failed to submit offline transaction: ${e.message}", null)
                                }
                            }
                        }

                        // =============================================================
                        // MWA unsigned offline transactions
                        // =============================================================
                        "createUnsignedOfflineTransaction" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val senderPubkey = call.argument<String>("senderPubkey")
                                        ?: throw IllegalArgumentException("senderPubkey is required")
                                    val nonceAuthorityPubkey = call.argument<String>("nonceAuthorityPubkey")
                                        ?: throw IllegalArgumentException("nonceAuthorityPubkey is required")
                                    val recipient = call.argument<String>("recipient")
                                        ?: throw IllegalArgumentException("recipient is required")
                                    val amount = call.argument<Int>("amount")?.toLong()
                                        ?: throw IllegalArgumentException("amount is required")

                                    sdk?.createUnsignedOfflineTransaction(senderPubkey, nonceAuthorityPubkey, recipient, amount)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("TX_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("TX_ERROR", e.message, null)
                                }
                            }
                        }
                        "createUnsignedOfflineSplTransaction" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val senderWallet = call.argument<String>("senderWallet")
                                        ?: throw IllegalArgumentException("senderWallet is required")
                                    val recipientWallet = call.argument<String>("recipientWallet")
                                        ?: throw IllegalArgumentException("recipientWallet is required")
                                    val mintAddress = call.argument<String>("mintAddress")
                                        ?: throw IllegalArgumentException("mintAddress is required")
                                    val amount = call.argument<Int>("amount")?.toLong()
                                        ?: throw IllegalArgumentException("amount is required")
                                    val feePayer = call.argument<String>("feePayer")
                                        ?: throw IllegalArgumentException("feePayer is required")

                                    sdk?.createUnsignedOfflineSplTransaction(senderWallet, recipientWallet, mintAddress, amount, feePayer)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("TX_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("TX_ERROR", e.message, null)
                                }
                            }
                        }
                        "getRequiredSigners" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val unsignedTx = call.argument<String>("unsignedTransactionBase64")
                                        ?: throw IllegalArgumentException("unsignedTransactionBase64 is required")
                                    sdk?.getRequiredSigners(unsignedTx)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("SIGNER_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("SIGNER_ERROR", e.message, null)
                                }
                            }
                        }
                        "getAvailableNonce" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    sdk?.getAvailableNonce()?.fold(
                                        onSuccess = { nonce ->
                                            if (nonce == null) {
                                                result.success(null)
                                            } else {
                                                result.success(mapOf(
                                                    "nonceAccount" to nonce.nonceAccount,
                                                    "authority" to nonce.authority,
                                                    "blockhash" to nonce.blockhash,
                                                    "lamportsPerSignature" to nonce.lamportsPerSignature,
                                                    "cachedAt" to nonce.cachedAt,
                                                    "used" to nonce.used
                                                ))
                                            }
                                        },
                                        onFailure = { result.error("NONCE_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("NONCE_ERROR", e.message, null)
                                }
                            }
                        }
                        "refreshOfflineBundle" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    sdk?.refreshOfflineBundle()?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("REFRESH_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("REFRESH_ERROR", e.message, null)
                                }
                            }
                        }
                        "cacheNonceAccounts" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val nonceAccounts = call.argument<List<String>>("nonceAccounts")
                                        ?: throw IllegalArgumentException("nonceAccounts is required")
                                    sdk?.cacheNonceAccounts(nonceAccounts)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("CACHE_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("CACHE_ERROR", e.message, null)
                                }
                            }
                        }
                        "addNonceSignature" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val payerSignedTx = call.argument<String>("payerSignedTransactionBase64")
                                        ?: throw IllegalArgumentException("payerSignedTransactionBase64 is required")
                                    val nonceKeypairBase64 = call.argument<List<String>>("nonceKeypairBase64")
                                        ?: throw IllegalArgumentException("nonceKeypairBase64 is required")
                                    sdk?.addNonceSignature(payerSignedTx, nonceKeypairBase64)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("SIGN_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("SIGN_ERROR", e.message, null)
                                }
                            }
                        }
                        "refreshBlockhashInUnsignedTransaction" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val unsignedTx = call.argument<String>("unsignedTxBase64")
                                        ?: throw IllegalArgumentException("unsignedTxBase64 is required")
                                    sdk?.refreshBlockhashInUnsignedTransaction(unsignedTx)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("REFRESH_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("REFRESH_ERROR", e.message, null)
                                }
                            }
                        }
                        "getTransactionMessageToSign" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val unsignedTx = call.argument<String>("unsignedTransactionBase64")
                                        ?: throw IllegalArgumentException("unsignedTransactionBase64 is required")
                                    sdk?.getTransactionMessageToSign(unsignedTx)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("MSG_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("MSG_ERROR", e.message, null)
                                }
                            }
                        }
                        "createUnsignedVote" -> {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val voter = call.argument<String>("voter")
                                        ?: throw IllegalArgumentException("voter is required")
                                    val proposalId = call.argument<String>("proposalId")
                                        ?: throw IllegalArgumentException("proposalId is required")
                                    val voteAccount = call.argument<String>("voteAccount")
                                        ?: throw IllegalArgumentException("voteAccount is required")
                                    val choice = call.argument<Int>("choice")
                                        ?: throw IllegalArgumentException("choice is required")
                                    val feePayer = call.argument<String>("feePayer")
                                        ?: throw IllegalArgumentException("feePayer is required")
                                    val nonceAccount = call.argument<String>("nonceAccount")

                                    sdk?.createUnsignedVote(voter, proposalId, voteAccount, choice, feePayer, nonceAccount)?.fold(
                                        onSuccess = { result.success(it) },
                                        onFailure = { result.error("VOTE_ERROR", it.message, null) }
                                    ) ?: result.error("NOT_INITIALIZED", "SDK not initialized", null)
                                } catch (e: Exception) {
                                    result.error("VOTE_ERROR", e.message, null)
                                }
                            }
                        }

                        // =============================================================
                        // BLE Mesh Operations
                        // =============================================================
                        "fragmentTransaction" -> {
                            val txBytesBase64 = call.argument<String>("transactionBytes")
                                ?: throw IllegalArgumentException("transactionBytes is required")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            sdk?.fragmentTransaction(txBytes)?.fold(
                                onSuccess = { fragments ->
                                    result.success(fragments.map { f ->
                                        mapOf(
                                            "transactionId" to f.transactionId,
                                            "fragmentIndex" to f.fragmentIndex,
                                            "totalFragments" to f.totalFragments,
                                            "dataBase64" to f.dataBase64
                                        )
                                    })
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "reconstructTransaction" -> {
                            val fragmentsRaw = call.argument<List<Map<String, Any>>>("fragments")
                                ?: throw IllegalArgumentException("fragments is required")
                            val fragments = fragmentsRaw.map { f ->
                                FragmentData(
                                    transactionId = f["transactionId"] as String,
                                    fragmentIndex = (f["fragmentIndex"] as Number).toInt(),
                                    totalFragments = (f["totalFragments"] as Number).toInt(),
                                    dataBase64 = f["dataBase64"] as String
                                )
                            }
                            sdk?.reconstructTransaction(fragments)?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "getFragmentationStats" -> {
                            val txBytesBase64 = call.argument<String>("transactionBytes")
                                ?: throw IllegalArgumentException("transactionBytes is required")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            sdk?.getFragmentationStats(txBytes)?.fold(
                                onSuccess = { stats ->
                                    result.success(mapOf(
                                        "originalSize" to stats.originalSize,
                                        "fragmentCount" to stats.fragmentCount,
                                        "maxFragmentSize" to stats.maxFragmentSize,
                                        "avgFragmentSize" to stats.avgFragmentSize,
                                        "totalOverhead" to stats.totalOverhead,
                                        "efficiency" to stats.efficiency
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "prepareBroadcast" -> {
                            val txBytesBase64 = call.argument<String>("transactionBytes")
                                ?: throw IllegalArgumentException("transactionBytes is required")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            sdk?.prepareBroadcast(txBytes)?.fold(
                                onSuccess = { prep ->
                                    result.success(mapOf(
                                        "transactionId" to prep.transactionId,
                                        "fragmentPackets" to prep.fragmentPackets.map { p ->
                                            mapOf(
                                                "transactionId" to p.transactionId,
                                                "fragmentIndex" to p.fragmentIndex,
                                                "totalFragments" to p.totalFragments,
                                                "packetBytes" to p.packetBytes
                                            )
                                        }
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        // =============================================================
                        // Autonomous Transaction Relay
                        // =============================================================
                        "pushReceivedTransaction" -> {
                            val txBytesBase64 = call.argument<String>("transactionBytes")
                                ?: throw IllegalArgumentException("transactionBytes is required")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            sdk?.pushReceivedTransaction(txBytes)?.fold(
                                onSuccess = { resp ->
                                    result.success(mapOf("added" to resp.added, "queueSize" to resp.queueSize))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "nextReceivedTransaction" -> {
                            sdk?.nextReceivedTransaction()?.fold(
                                onSuccess = { tx ->
                                    if (tx == null) result.success(null)
                                    else result.success(mapOf(
                                        "txId" to tx.txId,
                                        "transactionBase64" to tx.transactionBase64,
                                        "receivedAt" to tx.receivedAt
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "getReceivedQueueSize" -> {
                            sdk?.getReceivedQueueSize()?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "getFragmentReassemblyInfo" -> {
                            sdk?.getFragmentReassemblyInfo()?.fold(
                                onSuccess = { info ->
                                    result.success(mapOf("transactions" to info.transactions.map { t ->
                                        mapOf(
                                            "transactionId" to t.transactionId,
                                            "totalFragments" to t.totalFragments,
                                            "receivedFragments" to t.receivedFragments,
                                            "receivedIndices" to t.receivedIndices,
                                            "fragmentSizes" to t.fragmentSizes,
                                            "totalBytesReceived" to t.totalBytesReceived
                                        )
                                    }))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "markTransactionSubmitted" -> {
                            val txBytesBase64 = call.argument<String>("transactionBytes")
                                ?: throw IllegalArgumentException("transactionBytes is required")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            sdk?.markTransactionSubmitted(txBytes)?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "cleanupOldSubmissions" -> {
                            sdk?.cleanupOldSubmissions()?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "debugOutboundQueue" -> {
                            sdk?.debugOutboundQueue()?.fold(
                                onSuccess = { debug ->
                                    result.success(mapOf(
                                        "totalFragments" to debug.totalFragments,
                                        "fragments" to debug.fragments.map { f ->
                                            mapOf("index" to f.index, "size" to f.size)
                                        }
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        // =============================================================
                        // Queue Management — uses BleService.queueSignedTransaction
                        // for outbound ops to trigger BLE sending loop
                        // =============================================================
                        "pushOutboundTransaction" -> {
                            val txBytesBase64 = call.argument<String>("txBytes")
                                ?: throw IllegalArgumentException("txBytes is required")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            val priorityStr = call.argument<String>("priority") ?: "NORMAL"
                            val priority = try { Priority.valueOf(priorityStr) } catch (_: Exception) { Priority.NORMAL }

                            // Route through BleService so the sending loop is triggered immediately
                            val service = bleService
                            if (service != null) {
                                service.queueSignedTransaction(txBytes, priority).fold(
                                    onSuccess = { result.success(true) },
                                    onFailure = { throw it }
                                )
                            } else {
                                // Fallback: queue via SDK directly (sending loop picks up on next poll)
                                val txId = call.argument<String>("txId")
                                    ?: throw IllegalArgumentException("txId is required")
                                val fragmentsRaw = call.argument<List<Map<String, Any>>>("fragments")
                                    ?: throw IllegalArgumentException("fragments is required")
                                val fragments = fragmentsRaw.map { f ->
                                    FragmentFFI(
                                        transactionId = f["transactionId"] as String,
                                        fragmentIndex = (f["fragmentIndex"] as Number).toInt(),
                                        totalFragments = (f["totalFragments"] as Number).toInt(),
                                        dataBase64 = f["dataBase64"] as String
                                    )
                                }
                                sdk?.pushOutboundTransaction(txBytes, txId, fragments, priority)?.fold(
                                    onSuccess = { result.success(true) },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                            }
                        }
                        "acceptAndQueueExternalTransaction" -> {
                            val base64SignedTx = call.argument<String>("base64SignedTx")
                                ?: throw IllegalArgumentException("base64SignedTx is required")

                            // Route through BleService for immediate BLE delivery
                            val service = bleService
                            if (service != null) {
                                val txBytes = Base64.decode(base64SignedTx, Base64.NO_WRAP)
                                service.queueSignedTransaction(txBytes, Priority.NORMAL).fold(
                                    onSuccess = {
                                        // Return a txId (SHA-256 hash of the bytes)
                                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                                        val txId = digest.digest(txBytes).joinToString("") { "%02x".format(it) }
                                        result.success(txId)
                                    },
                                    onFailure = { throw it }
                                )
                            } else {
                                // Fallback: use SDK directly
                                val maxPayload = call.argument<Int>("maxPayload")
                                sdk?.acceptAndQueueExternalTransaction(base64SignedTx, maxPayload)?.fold(
                                    onSuccess = { result.success(it) },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                            }
                        }
                        "popOutboundTransaction" -> {
                            sdk?.popOutboundTransaction()?.fold(
                                onSuccess = { tx ->
                                    if (tx == null) result.success(null)
                                    else result.success(mapOf(
                                        "txId" to tx.txId,
                                        "originalBytes" to tx.originalBytes,
                                        "fragmentCount" to tx.fragmentCount,
                                        "priority" to tx.priority.name,
                                        "createdAt" to tx.createdAt,
                                        "retryCount" to tx.retryCount
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "getOutboundQueueSize" -> {
                            sdk?.getOutboundQueueSize()?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "addToRetryQueue" -> {
                            val txBytesBase64 = call.argument<String>("txBytes")
                                ?: throw IllegalArgumentException("txBytes is required")
                            val txId = call.argument<String>("txId")
                                ?: throw IllegalArgumentException("txId is required")
                            val error = call.argument<String>("error")
                                ?: throw IllegalArgumentException("error is required")
                            val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                            sdk?.addToRetryQueue(txBytes, txId, error)?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "popReadyRetry" -> {
                            sdk?.popReadyRetry()?.fold(
                                onSuccess = { item ->
                                    if (item == null) result.success(null)
                                    else result.success(mapOf(
                                        "txBytes" to item.txBytes,
                                        "txId" to item.txId,
                                        "attemptCount" to item.attemptCount,
                                        "lastError" to item.lastError,
                                        "nextRetryInSecs" to item.nextRetryInSecs,
                                        "ageSeconds" to item.ageSeconds
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "getRetryQueueSize" -> {
                            sdk?.getRetryQueueSize()?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        // =============================================================
                        // Confirmations
                        // =============================================================
                        "queueConfirmation" -> {
                            val txId = call.argument<String>("txId")
                                ?: throw IllegalArgumentException("txId is required")
                            val signature = call.argument<String>("signature")
                                ?: throw IllegalArgumentException("signature is required")
                            sdk?.queueConfirmation(txId, signature)?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "popConfirmation" -> {
                            sdk?.popConfirmation()?.fold(
                                onSuccess = { conf ->
                                    if (conf == null) result.success(null)
                                    else result.success(mapOf(
                                        "txId" to conf.txId,
                                        "status" to when (conf.status) {
                                            is ConfirmationStatus.Success -> mapOf("SUCCESS" to (conf.status as ConfirmationStatus.Success).signature)
                                            is ConfirmationStatus.Failed -> mapOf("FAILED" to (conf.status as ConfirmationStatus.Failed).error)
                                        },
                                        "timestamp" to conf.timestamp,
                                        "relayCount" to conf.relayCount
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "getConfirmationQueueSize" -> {
                            sdk?.getConfirmationQueueSize()?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "getQueueMetrics" -> {
                            sdk?.getQueueMetrics()?.fold(
                                onSuccess = { m ->
                                    result.success(mapOf(
                                        "outboundSize" to m.outboundSize,
                                        "outboundHighPriority" to m.outboundHighPriority,
                                        "outboundNormalPriority" to m.outboundNormalPriority,
                                        "outboundLowPriority" to m.outboundLowPriority,
                                        "confirmationSize" to m.confirmationSize,
                                        "retrySize" to m.retrySize,
                                        "retryAvgAttempts" to m.retryAvgAttempts
                                    ))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "cleanupStaleFragments" -> {
                            sdk?.cleanupStaleFragments()?.fold(
                                onSuccess = { result.success(it) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "cleanupExpired" -> {
                            sdk?.cleanupExpired()?.fold(
                                onSuccess = { (conf, retries) ->
                                    result.success(mapOf("confirmationsCleaned" to conf, "retriesCleaned" to retries))
                                },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        // =============================================================
                        // Queue Persistence
                        // =============================================================
                        "saveQueues" -> {
                            sdk?.saveQueues()?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "autoSaveQueues" -> {
                            sdk?.autoSaveQueues()?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "clearAllQueues" -> {
                            sdk?.clearAllQueues()?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }
                        "relayConfirmation" -> {
                            val confMap = call.argument<Map<String, Any>>("confirmation")
                                ?: throw IllegalArgumentException("confirmation is required")
                            val txId = confMap["txId"] as? String
                                ?: throw IllegalArgumentException("txId is required")
                            @Suppress("UNCHECKED_CAST")
                            val statusMap = confMap["status"] as? Map<String, Any>
                                ?: throw IllegalArgumentException("status is required")
                            val timestamp = (confMap["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                            val relayCount = (confMap["relayCount"] as? Number)?.toInt() ?: 0
                            val status = when {
                                statusMap.containsKey("SUCCESS") -> ConfirmationStatus.Success(statusMap["SUCCESS"] as String)
                                statusMap.containsKey("FAILED") -> ConfirmationStatus.Failed(statusMap["FAILED"] as String)
                                else -> throw IllegalArgumentException("Invalid confirmation status")
                            }
                            val confirmation = Confirmation(txId, status, timestamp, relayCount)
                            sdk?.relayConfirmation(confirmation)?.fold(
                                onSuccess = { result.success(true) },
                                onFailure = { throw it }
                            ) ?: throw IllegalStateException("SDK not initialized")
                        }

                        else -> {
                            result.notImplemented()
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Permission helpers
    // =========================================================================
    
    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        return permissions
    }
    
    private fun checkPermissionsStatus(permissions: List<String>): Map<String, String> {
        val status = mutableMapOf<String, String>()
        val ctx = activity ?: applicationContext ?: return status
        
        for (permission in permissions) {
            val granted = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
            status[permission] = if (granted) "granted" else "denied"
        }
        
        return status
    }
    
    private fun requestPermissions(result: MethodChannel.Result) {
        val act = activity
        if (act == null) {
            result.error("NO_ACTIVITY", "Activity not available", null)
            return
        }
        
        val permissions = getRequiredPermissions().toTypedArray()
        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(act, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isEmpty()) {
            val status = checkPermissionsStatus(permissions.toList())
            result.success(status)
            return
        }
        
        pendingPermissionResult = result
        
        ActivityCompat.requestPermissions(
            act,
            permissionsToRequest.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }
    
    private fun checkBatteryOptimization(result: MethodChannel.Result) {
        val ctx = activity ?: applicationContext
        if (ctx == null) {
            result.error("NO_CONTEXT", "Context not available", null)
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = ctx.packageName
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
                result.success(isIgnoring)
            } catch (e: Exception) {
                result.error("BATTERY_CHECK_ERROR", "Failed to check battery optimization: ${e.message}", null)
            }
        } else {
            result.success(true)
        }
    }
    
    private fun requestBatteryOptimization(result: MethodChannel.Result) {
        val act = activity
        if (act == null) {
            result.error("NO_ACTIVITY", "Activity not available", null)
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = act.getSystemService(Activity.POWER_SERVICE) as PowerManager
                val packageName = act.packageName
                
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    act.startActivity(intent)
                    result.success(true)
                } else {
                    result.success(true)
                }
            } catch (e: Exception) {
                try {
                    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    act.startActivity(settingsIntent)
                    result.success(true)
                } catch (e2: Exception) {
                    result.error("BATTERY_REQUEST_ERROR", "Failed to request battery optimization: ${e2.message}", null)
                }
            }
        } else {
            result.success(true)
        }
    }
    
    private fun handleAsyncCall(result: MethodChannel.Result, block: suspend () -> Unit) {
        if (!isInitialized || sdk == null) {
            result.error("NOT_INITIALIZED", "SDK must be initialized", null)
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } catch (e: PolliNetException) {
                result.error(e.code, e.message, null)
            } catch (e: Exception) {
                result.error("SDK_ERROR", e.message ?: "Unknown error", null)
            }
        }
    }
}
