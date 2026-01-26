package xyz.pollinet.sdk.flutter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.pollinet.sdk.PolliNetSDK
import xyz.pollinet.sdk.SdkConfig
import xyz.pollinet.sdk.PolliNetException

class PollinetPlugin : FlutterPlugin, ActivityAware {
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var sdk: PolliNetSDK? = null
    private var isInitialized = false
    
    // Permission request code
    private val PERMISSION_REQUEST_CODE = 1001
    
    // Pending permission result callback
    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "pollinet_sdk")
        channel.setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
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
                android.util.Log.d("PollinetPlugin", "Initialize method called")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        android.util.Log.d("PollinetPlugin", "Starting initialization in coroutine")
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
                        
                        android.util.Log.d("PollinetPlugin", "Calling PolliNetSDK.initialize()")
                        val initResult = PolliNetSDK.initialize(config)
                        android.util.Log.d("PollinetPlugin", "PolliNetSDK.initialize() returned")
                        
                        initResult.fold(
                            onSuccess = { initializedSdk ->
                                android.util.Log.d("PollinetPlugin", "SDK initialized successfully")
                                sdk = initializedSdk
                                isInitialized = true
                                android.util.Log.d("PollinetPlugin", "Calling result.success(true)")
                                result.success(true)
                                android.util.Log.d("PollinetPlugin", "result.success() called")
                            },
                            onFailure = { error ->
                                android.util.Log.e("PollinetPlugin", "SDK initialization failed: ${error.message}")
                                result.error("INIT_ERROR", "Failed to initialize Pollinet SDK: ${error.message}", null)
                            }
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("PollinetPlugin", "Exception during initialization: ${e.message}", e)
                        result.error("INIT_ERROR", "Failed to initialize Pollinet SDK: ${e.message}", e)
                    }
                }
            }
            "shutdown" -> {
                try {
                    sdk?.shutdown()
                    sdk = null
                    isInitialized = false
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
                        // Transport API
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
                        else -> {
                            result.notImplemented()
                        }
                    }
                }
            }
        }
    }
    
    // Get required permissions based on Android version
    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        val act = activity ?: return permissions
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+) requires runtime permissions
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and below
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        // Location permission may be needed for BLE scanning on older versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        return permissions
    }
    
    // Check permission status
    private fun checkPermissionsStatus(permissions: List<String>): Map<String, String> {
        val status = mutableMapOf<String, String>()
        val act = activity ?: return status
        
        for (permission in permissions) {
            val granted = ContextCompat.checkSelfPermission(act, permission) == PackageManager.PERMISSION_GRANTED
            status[permission] = if (granted) "granted" else "denied"
        }
        
        return status
    }
    
    // Request permissions
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
            // All permissions already granted
            val status = checkPermissionsStatus(permissions.toList())
            result.success(status)
            return
        }
        
        // Store the result callback
        pendingPermissionResult = result
        
        // Request permissions
        ActivityCompat.requestPermissions(
            act,
            permissionsToRequest.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }
    
    // Check battery optimization status
    private fun checkBatteryOptimization(result: MethodChannel.Result) {
        val act = activity
        if (act == null) {
            result.error("NO_ACTIVITY", "Activity not available", null)
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = act.getSystemService(Activity.POWER_SERVICE) as PowerManager
                val packageName = act.packageName
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
                result.success(isIgnoring)
            } catch (e: Exception) {
                result.error("BATTERY_CHECK_ERROR", "Failed to check battery optimization: ${e.message}", null)
            }
        } else {
            // Android M (API 23) and below don't have battery optimization
            result.success(true)
        }
    }
    
    // Request battery optimization exemption
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
                    result.success(true) // Already exempted
                }
            } catch (e: Exception) {
                // Fallback: Open battery settings directly
                try {
                    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    act.startActivity(settingsIntent)
                    result.success(true)
                } catch (e2: Exception) {
                    result.error("BATTERY_REQUEST_ERROR", "Failed to request battery optimization: ${e2.message}", null)
                }
            }
        } else {
            // Android M (API 23) and below don't have battery optimization
            result.success(true)
        }
    }
    
    // Helper function to handle async SDK calls
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
