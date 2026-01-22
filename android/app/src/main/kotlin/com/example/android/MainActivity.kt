package com.example.android

import android.app.Activity
import android.content.Context
import android.util.Base64
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.pollinet.sdk.PolliNetSDK
import xyz.pollinet.sdk.SdkConfig
import xyz.pollinet.sdk.PolliNetException

class MainActivity : FlutterActivity() {
    private val CHANNEL = "pollinet_sdk"
    private var sdk: PolliNetSDK? = null
    private var isInitialized = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "initialize" -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
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
                            
                            val initResult = PolliNetSDK.initialize(config)
                            initResult.fold(
                                onSuccess = { initializedSdk ->
                                    sdk = initializedSdk
                                    isInitialized = true
                                    result.success(true)
                                },
                                onFailure = { error ->
                                    result.error("INIT_ERROR", "Failed to initialize Pollinet SDK: ${error.message}", null)
                                }
                            )
                        } catch (e: Exception) {
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
                            // Transaction Builders
                            "createUnsignedTransaction" -> {
                                val requestMap = call.argument<Map<String, Any>>("request")
                                    ?: throw IllegalArgumentException("request is required")
                                val request = xyz.pollinet.sdk.CreateUnsignedTransactionRequest(
                                    sender = requestMap["sender"] as String,
                                    recipient = requestMap["recipient"] as String,
                                    feePayer = requestMap["feePayer"] as String,
                                    amount = (requestMap["amount"] as Number).toLong(),
                                    nonceAccount = requestMap["nonceAccount"] as? String,
                                    nonceData = (requestMap["nonceData"] as? Map<String, Any>)?.let { map ->
                                        xyz.pollinet.sdk.CachedNonceData(
                                            nonceAccount = map["nonceAccount"] as String,
                                            authority = map["authority"] as String,
                                            blockhash = map["blockhash"] as String,
                                            lamportsPerSignature = (map["lamportsPerSignature"] as Number).toLong(),
                                            cachedAt = (map["cachedAt"] as Number).toLong(),
                                            used = map["used"] as Boolean
                                        )
                                    }
                                )
                                val txResult = sdk?.createUnsignedTransaction(request)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(txResult)
                            }
                            "createUnsignedSplTransaction" -> {
                                val requestMap = call.argument<Map<String, Any>>("request")
                                    ?: throw IllegalArgumentException("request is required")
                                val request = xyz.pollinet.sdk.CreateUnsignedSplTransactionRequest(
                                    senderWallet = requestMap["senderWallet"] as String,
                                    recipientWallet = requestMap["recipientWallet"] as String,
                                    feePayer = requestMap["feePayer"] as String,
                                    mintAddress = requestMap["mintAddress"] as String,
                                    amount = (requestMap["amount"] as Number).toLong(),
                                    nonceAccount = requestMap["nonceAccount"] as? String,
                                    nonceData = (requestMap["nonceData"] as? Map<String, Any>)?.let { map ->
                                        xyz.pollinet.sdk.CachedNonceData(
                                            nonceAccount = map["nonceAccount"] as String,
                                            authority = map["authority"] as String,
                                            blockhash = map["blockhash"] as String,
                                            lamportsPerSignature = (map["lamportsPerSignature"] as Number).toLong(),
                                            cachedAt = (map["cachedAt"] as Number).toLong(),
                                            used = map["used"] as Boolean
                                        )
                                    }
                                )
                                val txResult = sdk?.createUnsignedSplTransaction(request)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(txResult)
                            }
                            // Signature Helpers
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
                                val txResult = sdk?.applySignature(base64Tx, signerPubkey, signatureBytes)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(txResult)
                            }
                            "verifyAndSerialize" -> {
                                val base64Tx = call.argument<String>("base64Tx")
                                    ?: throw IllegalArgumentException("base64Tx is required")
                                val txResult = sdk?.verifyAndSerialize(base64Tx)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(txResult)
                            }
                            // Fragmentation API
                            "fragment" -> {
                                val txBytesBase64 = call.argument<String>("txBytes")
                                    ?: throw IllegalArgumentException("txBytes is required")
                                val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
                                val maxPayload = call.argument<Int?>("maxPayload")
                                val fragmentResult = sdk?.fragment(txBytes, maxPayload)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(mapOf(
                                    "fragments" to fragmentResult.fragments.map { fragment ->
                                        mapOf(
                                            "id" to fragment.id,
                                            "index" to fragment.index,
                                            "total" to fragment.total,
                                            "data" to fragment.data,
                                            "fragmentType" to fragment.fragmentType,
                                            "checksum" to fragment.checksum
                                        )
                                    }
                                ))
                            }
                            // Offline Bundle Management
                            "prepareOfflineBundle" -> {
                                val count = call.argument<Int>("count")
                                    ?: throw IllegalArgumentException("count is required")
                                val senderKeypairBase64 = call.argument<String>("senderKeypair")
                                    ?: throw IllegalArgumentException("senderKeypair is required")
                                val senderKeypair = Base64.decode(senderKeypairBase64, Base64.NO_WRAP)
                                val bundleFile = call.argument<String?>("bundleFile")
                                val bundleResult = sdk?.prepareOfflineBundle(count, senderKeypair, bundleFile)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(mapOf(
                                    "nonceCaches" to bundleResult.nonceCaches.map { nonce ->
                                        mapOf(
                                            "nonceAccount" to nonce.nonceAccount,
                                            "authority" to nonce.authority,
                                            "blockhash" to nonce.blockhash,
                                            "lamportsPerSignature" to nonce.lamportsPerSignature,
                                            "cachedAt" to nonce.cachedAt,
                                            "used" to nonce.used
                                        )
                                    },
                                    "maxTransactions" to bundleResult.maxTransactions,
                                    "createdAt" to bundleResult.createdAt
                                ))
                            }
                            "createOfflineTransaction" -> {
                                val senderKeypairBase64 = call.argument<String>("senderKeypair")
                                    ?: throw IllegalArgumentException("senderKeypair is required")
                                val senderKeypair = Base64.decode(senderKeypairBase64, Base64.NO_WRAP)
                                val nonceAuthorityKeypairBase64 = call.argument<String>("nonceAuthorityKeypair")
                                    ?: throw IllegalArgumentException("nonceAuthorityKeypair is required")
                                val nonceAuthorityKeypair = Base64.decode(nonceAuthorityKeypairBase64, Base64.NO_WRAP)
                                val recipient = call.argument<String>("recipient")
                                    ?: throw IllegalArgumentException("recipient is required")
                                val amount = call.argument<Int>("amount")
                                    ?: throw IllegalArgumentException("amount is required")
                                val txResult = sdk?.createOfflineTransaction(
                                    senderKeypair,
                                    nonceAuthorityKeypair,
                                    recipient,
                                    amount.toLong()
                                )?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(txResult)
                            }
                            "submitOfflineTransaction" -> {
                                val transactionBase64 = call.argument<String>("transactionBase64")
                                    ?: throw IllegalArgumentException("transactionBase64 is required")
                                val verifyNonce = call.argument<Boolean>("verifyNonce") ?: true
                                val txResult = sdk?.submitOfflineTransaction(transactionBase64, verifyNonce)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(txResult)
                            }
                            // MWA Support
                            "createUnsignedOfflineTransaction" -> {
                                val senderPubkey = call.argument<String>("senderPubkey")
                                    ?: throw IllegalArgumentException("senderPubkey is required")
                                val nonceAuthorityPubkey = call.argument<String>("nonceAuthorityPubkey")
                                    ?: throw IllegalArgumentException("nonceAuthorityPubkey is required")
                                val recipient = call.argument<String>("recipient")
                                    ?: throw IllegalArgumentException("recipient is required")
                                val amount = call.argument<Int>("amount")
                                    ?: throw IllegalArgumentException("amount is required")
                                val nonceDataMap = call.argument<Map<String, Any>?>("nonceData")
                                val nonceData = nonceDataMap?.let { map ->
                                    xyz.pollinet.sdk.CachedNonceData(
                                        nonceAccount = map["nonceAccount"] as String,
                                        authority = map["authority"] as String,
                                        blockhash = map["blockhash"] as String,
                                        lamportsPerSignature = (map["lamportsPerSignature"] as Number).toLong(),
                                        cachedAt = (map["cachedAt"] as Number).toLong(),
                                        used = map["used"] as Boolean
                                    )
                                }
                                val txResult = sdk?.createUnsignedOfflineTransaction(
                                    senderPubkey,
                                    nonceAuthorityPubkey,
                                    recipient,
                                    amount.toLong(),
                                    nonceData
                                )?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(txResult)
                            }
                            "getRequiredSigners" -> {
                                val unsignedTransactionBase64 = call.argument<String>("unsignedTransactionBase64")
                                    ?: throw IllegalArgumentException("unsignedTransactionBase64 is required")
                                val signersResult = sdk?.getRequiredSigners(unsignedTransactionBase64)?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(signersResult)
                            }
                            "getAvailableNonce" -> {
                                val nonceResult = sdk?.getAvailableNonce()?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(nonceResult?.let { nonce ->
                                    mapOf(
                                        "nonceAccount" to nonce.nonceAccount,
                                        "authority" to nonce.authority,
                                        "blockhash" to nonce.blockhash,
                                        "lamportsPerSignature" to nonce.lamportsPerSignature,
                                        "cachedAt" to nonce.cachedAt,
                                        "used" to nonce.used
                                    )
                                })
                            }
                            "refreshOfflineBundle" -> {
                                val refreshResult = sdk?.refreshOfflineBundle()?.fold(
                                    onSuccess = { it },
                                    onFailure = { throw it }
                                ) ?: throw IllegalStateException("SDK not initialized")
                                result.success(refreshResult)
                            }
                            else -> {
                                result.notImplemented()
                            }
                        }
                    }
                }
            }
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
