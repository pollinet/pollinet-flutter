package com.example.pollinet_sdk

import android.app.Activity
import android.content.Context
import android.util.Base64
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.pollinet.sdk.PolliNetSDK
import xyz.pollinet.sdk.SdkConfig
import xyz.pollinet.sdk.PolliNetException

/** PollinetSdkPlugin */
class PollinetSdkPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {
  private lateinit var channel : MethodChannel
  private var activity: Activity? = null
  private var context: Context? = null
  
  // SDK instance
  private var sdk: PolliNetSDK? = null
  private var isInitialized = false

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "pollinet_sdk")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "initialize" -> {
        CoroutineScope(Dispatchers.IO).launch {
          try {
            if (context == null) {
              result.error("INIT_ERROR", "Context is not available", null)
              return@launch
            }
            
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
      // Transport API
      "pushInbound" -> handleAsyncCall(result) {
        val dataBase64 = call.argument<String>("data")
          ?: throw IllegalArgumentException("data is required")
        val data = Base64.decode(dataBase64, Base64.NO_WRAP)
        sdk?.pushInbound(data)?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      
      "nextOutbound" -> handleAsyncCall(result) {
        val maxLen = call.argument<Int>("maxLen") ?: 1024
        val outbound = sdk?.nextOutbound(maxLen)
        result.success(outbound?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
      }
      
      "tick" -> handleAsyncCall(result) {
        val tickResult = sdk?.tick()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(tickResult)
      }
      
      "metrics" -> handleAsyncCall(result) {
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
      
      "clearTransaction" -> handleAsyncCall(result) {
        val txId = call.argument<String>("txId")
          ?: throw IllegalArgumentException("txId is required")
        sdk?.clearTransaction(txId)?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      // Transaction Builders
      "createUnsignedTransaction" -> handleAsyncCall(result) {
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
      
      "createUnsignedSplTransaction" -> handleAsyncCall(result) {
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
      "prepareSignPayload" -> handleAsyncCall(result) {
        val base64Tx = call.argument<String>("base64Tx")
          ?: throw IllegalArgumentException("base64Tx is required")
        val payload = sdk?.prepareSignPayload(base64Tx)
        result.success(payload?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
      }
      
      "applySignature" -> handleAsyncCall(result) {
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
      
      "verifyAndSerialize" -> handleAsyncCall(result) {
        val base64Tx = call.argument<String>("base64Tx")
          ?: throw IllegalArgumentException("base64Tx is required")
        val txResult = sdk?.verifyAndSerialize(base64Tx)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult)
      }
      
      // Fragmentation API
      "fragment" -> handleAsyncCall(result) {
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
      "prepareOfflineBundle" -> handleAsyncCall(result) {
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
      
      "createOfflineTransaction" -> handleAsyncCall(result) {
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
      
      "submitOfflineTransaction" -> handleAsyncCall(result) {
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
      "createUnsignedOfflineTransaction" -> handleAsyncCall(result) {
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
      
      "getRequiredSigners" -> handleAsyncCall(result) {
        val unsignedTransactionBase64 = call.argument<String>("unsignedTransactionBase64")
          ?: throw IllegalArgumentException("unsignedTransactionBase64 is required")
        val signersResult = sdk?.getRequiredSigners(unsignedTransactionBase64)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(signersResult)
      }
      
      "getAvailableNonce" -> handleAsyncCall(result) {
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
      
      "refreshOfflineBundle" -> handleAsyncCall(result) {
        val refreshResult = sdk?.refreshOfflineBundle()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(refreshResult)
      }
      
      // Additional MWA Support methods
      "createUnsignedOfflineSplTransaction" -> handleAsyncCall(result) {
        val senderWallet = call.argument<String>("senderWallet")
          ?: throw IllegalArgumentException("senderWallet is required")
        val recipientWallet = call.argument<String>("recipientWallet")
          ?: throw IllegalArgumentException("recipientWallet is required")
        val mintAddress = call.argument<String>("mintAddress")
          ?: throw IllegalArgumentException("mintAddress is required")
        val amount = call.argument<Int>("amount")
          ?: throw IllegalArgumentException("amount is required")
        val feePayer = call.argument<String>("feePayer")
          ?: throw IllegalArgumentException("feePayer is required")
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
        val txResult = sdk?.createUnsignedOfflineSplTransaction(
          senderWallet,
          recipientWallet,
          mintAddress,
          amount.toLong(),
          feePayer,
          nonceData
        )?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult)
      }
      
      "createUnsignedVote" -> handleAsyncCall(result) {
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
        val nonceAccount = call.argument<String?>("nonceAccount")
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
        val txResult = sdk?.createUnsignedVote(
          voter,
          proposalId,
          voteAccount,
          choice,
          feePayer,
          nonceAccount,
          nonceData
        )?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult)
      }
      
      "createUnsignedNonceTransactions" -> handleAsyncCall(result) {
        val count = call.argument<Int>("count")
          ?: throw IllegalArgumentException("count is required")
        val payerPubkey = call.argument<String>("payerPubkey")
          ?: throw IllegalArgumentException("payerPubkey is required")
        val txResult = sdk?.createUnsignedNonceTransactions(count, payerPubkey)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult.map { tx ->
          mapOf(
            "unsignedTransactionBase64" to tx.unsignedTransactionBase64,
            "nonceKeypairBase64" to tx.nonceKeypairBase64,
            "noncePubkey" to tx.noncePubkey
          )
        })
      }
      
      "cacheNonceAccounts" -> handleAsyncCall(result) {
        val nonceAccounts = call.argument<List<String>>("nonceAccounts")
          ?: throw IllegalArgumentException("nonceAccounts is required")
        val cacheResult = sdk?.cacheNonceAccounts(nonceAccounts)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(cacheResult)
      }
      
      "addNonceSignature" -> handleAsyncCall(result) {
        val payerSignedTransactionBase64 = call.argument<String>("payerSignedTransactionBase64")
          ?: throw IllegalArgumentException("payerSignedTransactionBase64 is required")
        val nonceKeypairBase64 = call.argument<List<String>>("nonceKeypairBase64")
          ?: throw IllegalArgumentException("nonceKeypairBase64 is required")
        val txResult = sdk?.addNonceSignature(payerSignedTransactionBase64, nonceKeypairBase64)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult)
      }
      
      "refreshBlockhashInUnsignedTransaction" -> handleAsyncCall(result) {
        val unsignedTxBase64 = call.argument<String>("unsignedTxBase64")
          ?: throw IllegalArgumentException("unsignedTxBase64 is required")
        val txResult = sdk?.refreshBlockhashInUnsignedTransaction(unsignedTxBase64)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult)
      }
      
      "getTransactionMessageToSign" -> handleAsyncCall(result) {
        val unsignedTransactionBase64 = call.argument<String>("unsignedTransactionBase64")
          ?: throw IllegalArgumentException("unsignedTransactionBase64 is required")
        val messageResult = sdk?.getTransactionMessageToSign(unsignedTransactionBase64)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(messageResult)
      }
      
      // BLE Mesh Operations
      "fragmentTransaction" -> handleAsyncCall(result) {
        val transactionBytesBase64 = call.argument<String>("transactionBytes")
          ?: throw IllegalArgumentException("transactionBytes is required")
        val transactionBytes = Base64.decode(transactionBytesBase64, Base64.NO_WRAP)
        val fragmentResult = sdk?.fragmentTransaction(transactionBytes)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(fragmentResult.map { fragment ->
          mapOf(
            "transactionId" to fragment.transactionId,
            "fragmentIndex" to fragment.fragmentIndex,
            "totalFragments" to fragment.totalFragments,
            "dataBase64" to fragment.dataBase64
          )
        })
      }
      
      "reconstructTransaction" -> handleAsyncCall(result) {
        val fragmentsList = call.argument<List<Map<String, Any>>>("fragments")
          ?: throw IllegalArgumentException("fragments is required")
        val fragments = fragmentsList.map { map ->
          xyz.pollinet.sdk.FragmentData(
            transactionId = map["transactionId"] as String,
            fragmentIndex = map["fragmentIndex"] as Int,
            totalFragments = map["totalFragments"] as Int,
            dataBase64 = map["dataBase64"] as String
          )
        }
        val txResult = sdk?.reconstructTransaction(fragments)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult)
      }
      
      "getFragmentationStats" -> handleAsyncCall(result) {
        val transactionBytesBase64 = call.argument<String>("transactionBytes")
          ?: throw IllegalArgumentException("transactionBytes is required")
        val transactionBytes = Base64.decode(transactionBytesBase64, Base64.NO_WRAP)
        val statsResult = sdk?.getFragmentationStats(transactionBytes)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(mapOf(
          "originalSize" to statsResult.originalSize,
          "fragmentCount" to statsResult.fragmentCount,
          "maxFragmentSize" to statsResult.maxFragmentSize,
          "avgFragmentSize" to statsResult.avgFragmentSize,
          "totalOverhead" to statsResult.totalOverhead,
          "efficiency" to statsResult.efficiency
        ))
      }
      
      "prepareBroadcast" -> handleAsyncCall(result) {
        val transactionBytesBase64 = call.argument<String>("transactionBytes")
          ?: throw IllegalArgumentException("transactionBytes is required")
        val transactionBytes = Base64.decode(transactionBytesBase64, Base64.NO_WRAP)
        val broadcastResult = sdk?.prepareBroadcast(transactionBytes)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(mapOf(
          "transactionId" to broadcastResult.transactionId,
          "fragmentPackets" to broadcastResult.fragmentPackets.map { packet ->
            mapOf(
              "transactionId" to packet.transactionId,
              "fragmentIndex" to packet.fragmentIndex,
              "totalFragments" to packet.totalFragments,
              "packetBytes" to packet.packetBytes
            )
          }
        ))
      }
      
      // Autonomous Transaction Relay System
      "pushReceivedTransaction" -> handleAsyncCall(result) {
        val transactionBytesBase64 = call.argument<String>("transactionBytes")
          ?: throw IllegalArgumentException("transactionBytes is required")
        val transactionBytes = Base64.decode(transactionBytesBase64, Base64.NO_WRAP)
        val pushResult = sdk?.pushReceivedTransaction(transactionBytes)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(mapOf(
          "added" to pushResult.added,
          "queueSize" to pushResult.queueSize
        ))
      }
      
      "nextReceivedTransaction" -> handleAsyncCall(result) {
        val txResult = sdk?.nextReceivedTransaction()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult?.let { tx ->
          mapOf(
            "txId" to tx.txId,
            "transactionBase64" to tx.transactionBase64,
            "receivedAt" to tx.receivedAt
          )
        })
      }
      
      "getReceivedQueueSize" -> handleAsyncCall(result) {
        val sizeResult = sdk?.getReceivedQueueSize()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(sizeResult)
      }
      
      "getFragmentReassemblyInfo" -> handleAsyncCall(result) {
        val infoResult = sdk?.getFragmentReassemblyInfo()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(mapOf(
          "transactions" to infoResult.transactions.map { tx ->
            mapOf(
              "transactionId" to tx.transactionId,
              "totalFragments" to tx.totalFragments,
              "receivedFragments" to tx.receivedFragments,
              "receivedIndices" to tx.receivedIndices,
              "fragmentSizes" to tx.fragmentSizes,
              "totalBytesReceived" to tx.totalBytesReceived
            )
          }
        ))
      }
      
      "markTransactionSubmitted" -> handleAsyncCall(result) {
        val transactionBytesBase64 = call.argument<String>("transactionBytes")
          ?: throw IllegalArgumentException("transactionBytes is required")
        val transactionBytes = Base64.decode(transactionBytesBase64, Base64.NO_WRAP)
        val markResult = sdk?.markTransactionSubmitted(transactionBytes)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(markResult)
      }
      
      "cleanupOldSubmissions" -> handleAsyncCall(result) {
        val cleanupResult = sdk?.cleanupOldSubmissions()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(cleanupResult)
      }
      
      "debugOutboundQueue" -> handleAsyncCall(result) {
        val debugResult = sdk?.debugOutboundQueue()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(mapOf(
          "totalFragments" to debugResult.totalFragments,
          "fragments" to debugResult.fragments.map { fragment ->
            mapOf(
              "index" to fragment.index,
              "size" to fragment.size
            )
          }
        ))
      }
      
      // Queue Management
      "pushOutboundTransaction" -> handleAsyncCall(result) {
        val txBytesBase64 = call.argument<String>("txBytes")
          ?: throw IllegalArgumentException("txBytes is required")
        val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
        val txId = call.argument<String>("txId")
          ?: throw IllegalArgumentException("txId is required")
        val fragmentsList = call.argument<List<Map<String, Any>>>("fragments")
          ?: throw IllegalArgumentException("fragments is required")
        val fragments = fragmentsList.map { map ->
          xyz.pollinet.sdk.FragmentFFI(
            transactionId = map["transactionId"] as String,
            fragmentIndex = map["fragmentIndex"] as Int,
            totalFragments = map["totalFragments"] as Int,
            dataBase64 = map["dataBase64"] as String
          )
        }
        val priorityStr = call.argument<String>("priority") ?: "NORMAL"
        val priority = when (priorityStr) {
          "HIGH" -> xyz.pollinet.sdk.Priority.HIGH
          "LOW" -> xyz.pollinet.sdk.Priority.LOW
          else -> xyz.pollinet.sdk.Priority.NORMAL
        }
        sdk?.pushOutboundTransaction(txBytes, txId, fragments, priority)?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      
      "acceptAndQueueExternalTransaction" -> handleAsyncCall(result) {
        val base64SignedTx = call.argument<String>("base64SignedTx")
          ?: throw IllegalArgumentException("base64SignedTx is required")
        val maxPayload = call.argument<Int?>("maxPayload")
        val txIdResult = sdk?.acceptAndQueueExternalTransaction(base64SignedTx, maxPayload)?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txIdResult)
      }
      
      "popOutboundTransaction" -> handleAsyncCall(result) {
        val txResult = sdk?.popOutboundTransaction()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(txResult?.let { tx ->
          mapOf(
            "txId" to tx.txId,
            "originalBytes" to tx.originalBytes,
            "fragmentCount" to tx.fragmentCount,
            "priority" to tx.priority.name,
            "createdAt" to tx.createdAt,
            "retryCount" to tx.retryCount
          )
        })
      }
      
      "getOutboundQueueSize" -> handleAsyncCall(result) {
        val sizeResult = sdk?.getOutboundQueueSize()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(sizeResult)
      }
      
      "addToRetryQueue" -> handleAsyncCall(result) {
        val txBytesBase64 = call.argument<String>("txBytes")
          ?: throw IllegalArgumentException("txBytes is required")
        val txBytes = Base64.decode(txBytesBase64, Base64.NO_WRAP)
        val txId = call.argument<String>("txId")
          ?: throw IllegalArgumentException("txId is required")
        val error = call.argument<String>("error")
          ?: throw IllegalArgumentException("error is required")
        sdk?.addToRetryQueue(txBytes, txId, error)?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      
      "popReadyRetry" -> handleAsyncCall(result) {
        val retryResult = sdk?.popReadyRetry()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(retryResult?.let { retry ->
          mapOf(
            "txBytes" to retry.txBytes,
            "txId" to retry.txId,
            "attemptCount" to retry.attemptCount,
            "lastError" to retry.lastError,
            "nextRetryInSecs" to retry.nextRetryInSecs,
            "ageSeconds" to retry.ageSeconds
          )
        })
      }
      
      "getRetryQueueSize" -> handleAsyncCall(result) {
        val sizeResult = sdk?.getRetryQueueSize()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(sizeResult)
      }
      
      "queueConfirmation" -> handleAsyncCall(result) {
        val txId = call.argument<String>("txId")
          ?: throw IllegalArgumentException("txId is required")
        val signature = call.argument<String>("signature")
          ?: throw IllegalArgumentException("signature is required")
        sdk?.queueConfirmation(txId, signature)?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      
      "popConfirmation" -> handleAsyncCall(result) {
        val confirmationResult = sdk?.popConfirmation()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(confirmationResult?.let { conf ->
          mapOf(
            "txId" to conf.txId,
            "status" to when (conf.status) {
              is xyz.pollinet.sdk.ConfirmationStatus.Success -> mapOf("SUCCESS" to conf.status.signature)
              is xyz.pollinet.sdk.ConfirmationStatus.Failed -> mapOf("FAILED" to conf.status.error)
            },
            "timestamp" to conf.timestamp,
            "relayCount" to conf.relayCount
          )
        })
      }
      
      "getConfirmationQueueSize" -> handleAsyncCall(result) {
        val sizeResult = sdk?.getConfirmationQueueSize()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(sizeResult)
      }
      
      "getQueueMetrics" -> handleAsyncCall(result) {
        val metricsResult = sdk?.getQueueMetrics()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(mapOf(
          "outboundSize" to metricsResult.outboundSize,
          "outboundHighPriority" to metricsResult.outboundHighPriority,
          "outboundNormalPriority" to metricsResult.outboundNormalPriority,
          "outboundLowPriority" to metricsResult.outboundLowPriority,
          "confirmationSize" to metricsResult.confirmationSize,
          "retrySize" to metricsResult.retrySize,
          "retryAvgAttempts" to metricsResult.retryAvgAttempts
        ))
      }
      
      "cleanupStaleFragments" -> handleAsyncCall(result) {
        val cleanupResult = sdk?.cleanupStaleFragments()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(cleanupResult)
      }
      
      "cleanupExpired" -> handleAsyncCall(result) {
        val cleanupResult = sdk?.cleanupExpired()?.fold(
          onSuccess = { it },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
        result.success(mapOf(
          "confirmationsCleaned" to cleanupResult.first,
          "retriesCleaned" to cleanupResult.second
        ))
      }
      
      // Queue Persistence
      "saveQueues" -> handleAsyncCall(result) {
        sdk?.saveQueues()?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      
      "autoSaveQueues" -> handleAsyncCall(result) {
        sdk?.autoSaveQueues()?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      
      "clearAllQueues" -> handleAsyncCall(result) {
        sdk?.clearAllQueues()?.fold(
          onSuccess = { result.success(true) },
          onFailure = { throw it }
        ) ?: throw IllegalStateException("SDK not initialized")
      }
      
      "relayConfirmation" -> handleAsyncCall(result) {
        val confirmationMap = call.argument<Map<String, Any>>("confirmation")
          ?: throw IllegalArgumentException("confirmation is required")
        val statusMap = confirmationMap["status"] as Map<String, dynamic>
        val status = when {
          statusMap.containsKey("SUCCESS") -> {
            xyz.pollinet.sdk.ConfirmationStatus.Success(statusMap["SUCCESS"] as String)
          }
          statusMap.containsKey("FAILED") -> {
            xyz.pollinet.sdk.ConfirmationStatus.Failed(statusMap["FAILED"] as String)
          }
          else -> throw IllegalArgumentException("Invalid confirmation status")
        }
        val confirmation = xyz.pollinet.sdk.Confirmation(
          txId = confirmationMap["txId"] as String,
          status = status,
          timestamp = (confirmationMap["timestamp"] as Number).toLong(),
          relayCount = confirmationMap["relayCount"] as Int
        )
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

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    // Cleanup: Shutdown SDK if initialized
    if (isInitialized) {
      try {
        sdk?.shutdown()
      } catch (e: Exception) {
        // Log error but don't throw
      }
    }
    channel.setMethodCallHandler(null)
    sdk = null
    isInitialized = false
  }
  
  // Helper function to handle async SDK calls
  private fun handleAsyncCall(result: Result, block: suspend () -> Unit) {
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

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
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
}
