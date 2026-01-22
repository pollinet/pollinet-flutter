library pollinet_sdk;

import 'package:flutter/services.dart';

/// Pollinet SDK for Flutter
/// 
/// Provides access to the Pollinet Android SDK for offline Solana transaction
/// propagation over Bluetooth Low Energy (BLE) mesh networks.
class PollinetSdk {
  static const MethodChannel _channel = MethodChannel('pollinet_sdk');

  /// Initialize the Pollinet SDK
  /// 
  /// [config] - Optional SDK configuration
  /// 
  /// Returns `true` if initialization was successful, throws an exception otherwise.
  static Future<bool> initialize({SdkConfig? config}) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'initialize',
        config != null ? {'config': config.toMap()} : null,
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Shutdown the SDK and release resources
  static Future<bool> shutdown() async {
    try {
      final result = await _channel.invokeMethod<bool>('shutdown');
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get the SDK version
  static Future<String> version() async {
    try {
      final result = await _channel.invokeMethod<String>('version');
      return result ?? 'unknown';
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Transport API
  // =========================================================================

  /// Push inbound data from GATT characteristic
  static Future<bool> pushInbound(String dataBase64) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'pushInbound',
        {'data': dataBase64},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get next outbound frame to send
  static Future<String?> nextOutbound({int maxLen = 1024}) async {
    try {
      final result = await _channel.invokeMethod<String?>(
        'nextOutbound',
        {'maxLen': maxLen},
      );
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Periodic tick for protocol state machine
  static Future<List<String>> tick() async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>('tick');
      return (result ?? []).map((e) => e.toString()).toList();
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get current transport metrics
  static Future<MetricsSnapshot> metrics() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('metrics');
      if (result == null) {
        throw PollinetException('METRICS_ERROR', 'Failed to get metrics');
      }
      return MetricsSnapshot.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Clear a transaction from buffers
  static Future<bool> clearTransaction(String txId) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'clearTransaction',
        {'txId': txId},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Transaction Builders
  // =========================================================================

  /// Create an unsigned SOL transfer transaction
  static Future<String> createUnsignedTransaction({
    required String sender,
    required String recipient,
    required String feePayer,
    required int amount,
    String? nonceAccount,
    CachedNonceData? nonceData,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'request': {
          'sender': sender,
          'recipient': recipient,
          'feePayer': feePayer,
          'amount': amount,
          if (nonceAccount != null) 'nonceAccount': nonceAccount,
          if (nonceData != null) 'nonceData': nonceData.toMap(),
        },
      };
      final result = await _channel.invokeMethod<String>('createUnsignedTransaction', arguments);
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to create transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Create an unsigned SPL token transfer transaction
  static Future<String> createUnsignedSplTransaction({
    required String senderWallet,
    required String recipientWallet,
    required String feePayer,
    required String mintAddress,
    required int amount,
    String? nonceAccount,
    CachedNonceData? nonceData,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'request': {
          'senderWallet': senderWallet,
          'recipientWallet': recipientWallet,
          'feePayer': feePayer,
          'mintAddress': mintAddress,
          'amount': amount,
          if (nonceAccount != null) 'nonceAccount': nonceAccount,
          if (nonceData != null) 'nonceData': nonceData.toMap(),
        },
      };
      final result = await _channel.invokeMethod<String>('createUnsignedSplTransaction', arguments);
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to create SPL transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Signature Helpers
  // =========================================================================

  /// Prepare sign payload - Extract message bytes that need to be signed
  static Future<String?> prepareSignPayload(String base64Tx) async {
    try {
      final result = await _channel.invokeMethod<String?>(
        'prepareSignPayload',
        {'base64Tx': base64Tx},
      );
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Apply signature to a transaction
  static Future<String> applySignature({
    required String base64Tx,
    required String signerPubkey,
    required String signatureBytesBase64,
  }) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'applySignature',
        {
          'base64Tx': base64Tx,
          'signerPubkey': signerPubkey,
          'signatureBytes': signatureBytesBase64,
        },
      );
      if (result == null) {
        throw PollinetException('SIGNATURE_ERROR', 'Failed to apply signature');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Verify and serialize transaction for submission/fragmentation
  static Future<String> verifyAndSerialize(String base64Tx) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'verifyAndSerialize',
        {'base64Tx': base64Tx},
      );
      if (result == null) {
        throw PollinetException('VERIFY_ERROR', 'Failed to verify transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Fragmentation API
  // =========================================================================

  /// Fragment a transaction for BLE transmission
  static Future<FragmentList> fragment({
    required String txBytesBase64,
    int? maxPayload,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'txBytes': txBytesBase64,
        if (maxPayload != null) 'maxPayload': maxPayload,
      };
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('fragment', arguments);
      if (result == null) {
        throw PollinetException('FRAGMENT_ERROR', 'Failed to fragment transaction');
      }
      return FragmentList.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Offline Bundle Management
  // =========================================================================

  /// Prepare offline bundle for creating transactions without internet
  static Future<OfflineTransactionBundle> prepareOfflineBundle({
    required int count,
    required String senderKeypairBase64,
    String? bundleFile,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'count': count,
        'senderKeypair': senderKeypairBase64,
        if (bundleFile != null) 'bundleFile': bundleFile,
      };
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('prepareOfflineBundle', arguments);
      if (result == null) {
        throw PollinetException('BUNDLE_ERROR', 'Failed to prepare offline bundle');
      }
      return OfflineTransactionBundle.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Create transaction completely offline using cached nonce data
  static Future<String> createOfflineTransaction({
    required String senderKeypairBase64,
    required String nonceAuthorityKeypairBase64,
    required String recipient,
    required int amount,
  }) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'createOfflineTransaction',
        {
          'senderKeypair': senderKeypairBase64,
          'nonceAuthorityKeypair': nonceAuthorityKeypairBase64,
          'recipient': recipient,
          'amount': amount,
        },
      );
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to create offline transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Submit offline-created transaction to blockchain
  static Future<String> submitOfflineTransaction({
    required String transactionBase64,
    bool verifyNonce = true,
  }) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'submitOfflineTransaction',
        {
          'transactionBase64': transactionBase64,
          'verifyNonce': verifyNonce,
        },
      );
      if (result == null) {
        throw PollinetException('SUBMIT_ERROR', 'Failed to submit transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // MWA (Mobile Wallet Adapter) Support
  // =========================================================================

  /// Create UNSIGNED offline transaction for MWA/Seed Vault signing
  static Future<String> createUnsignedOfflineTransaction({
    required String senderPubkey,
    required String nonceAuthorityPubkey,
    required String recipient,
    required int amount,
    CachedNonceData? nonceData,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'senderPubkey': senderPubkey,
        'nonceAuthorityPubkey': nonceAuthorityPubkey,
        'recipient': recipient,
        'amount': amount,
        if (nonceData != null) 'nonceData': nonceData.toMap(),
      };
      final result = await _channel.invokeMethod<String>('createUnsignedOfflineTransaction', arguments);
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to create unsigned offline transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get list of public keys that need to sign this transaction
  static Future<List<String>> getRequiredSigners(String unsignedTransactionBase64) async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>(
        'getRequiredSigners',
        {'unsignedTransactionBase64': unsignedTransactionBase64},
      );
      return (result ?? []).map((e) => e.toString()).toList();
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get an available nonce account from cached bundle
  static Future<CachedNonceData?> getAvailableNonce() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>?>('getAvailableNonce');
      if (result == null) {
        return null;
      }
      return CachedNonceData.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Refresh all cached nonce data in the offline bundle
  static Future<int> refreshOfflineBundle() async {
    try {
      final result = await _channel.invokeMethod<int>('refreshOfflineBundle');
      return result ?? 0;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Create UNSIGNED offline SPL token transfer for MWA/Seed Vault signing
  static Future<String> createUnsignedOfflineSplTransaction({
    required String senderWallet,
    required String recipientWallet,
    required String mintAddress,
    required int amount,
    required String feePayer,
    CachedNonceData? nonceData,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'senderWallet': senderWallet,
        'recipientWallet': recipientWallet,
        'mintAddress': mintAddress,
        'amount': amount,
        'feePayer': feePayer,
        if (nonceData != null) 'nonceData': nonceData.toMap(),
      };
      final result = await _channel.invokeMethod<String>('createUnsignedOfflineSplTransaction', arguments);
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to create unsigned offline SPL transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Create an unsigned governance vote transaction
  static Future<String> createUnsignedVote({
    required String voter,
    required String proposalId,
    required String voteAccount,
    required int choice,
    required String feePayer,
    String? nonceAccount,
    CachedNonceData? nonceData,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'voter': voter,
        'proposalId': proposalId,
        'voteAccount': voteAccount,
        'choice': choice,
        'feePayer': feePayer,
        if (nonceAccount != null) 'nonceAccount': nonceAccount,
        if (nonceData != null) 'nonceData': nonceData.toMap(),
      };
      final result = await _channel.invokeMethod<String>('createUnsignedVote', arguments);
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to create unsigned vote');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Create unsigned nonce account creation transactions for MWA signing
  static Future<List<UnsignedNonceTransaction>> createUnsignedNonceTransactions({
    required int count,
    required String payerPubkey,
  }) async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>(
        'createUnsignedNonceTransactions',
        {'count': count, 'payerPubkey': payerPubkey},
      );
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to create unsigned nonce transactions');
      }
      return result.map((e) => UnsignedNonceTransaction.fromMap(Map<String, dynamic>.from(e))).toList();
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Cache nonce account data from on-chain accounts
  static Future<int> cacheNonceAccounts(List<String> nonceAccounts) async {
    try {
      final result = await _channel.invokeMethod<int>(
        'cacheNonceAccounts',
        {'nonceAccounts': nonceAccounts},
      );
      return result ?? 0;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Add nonce signature to a payer-signed transaction
  static Future<String> addNonceSignature({
    required String payerSignedTransactionBase64,
    required List<String> nonceKeypairBase64,
  }) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'addNonceSignature',
        {
          'payerSignedTransactionBase64': payerSignedTransactionBase64,
          'nonceKeypairBase64': nonceKeypairBase64,
        },
      );
      if (result == null) {
        throw PollinetException('SIGNATURE_ERROR', 'Failed to add nonce signature');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Refresh the blockhash in an unsigned transaction
  static Future<String> refreshBlockhashInUnsignedTransaction(String unsignedTxBase64) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'refreshBlockhashInUnsignedTransaction',
        {'unsignedTxBase64': unsignedTxBase64},
      );
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to refresh blockhash');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get transaction message bytes that need to be signed by MWA
  static Future<String> getTransactionMessageToSign(String unsignedTransactionBase64) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'getTransactionMessageToSign',
        {'unsignedTransactionBase64': unsignedTransactionBase64},
      );
      if (result == null) {
        throw PollinetException('TX_ERROR', 'Failed to get transaction message');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // BLE Mesh Operations
  // =========================================================================

  /// Fragment a signed transaction for BLE transmission
  static Future<List<FragmentData>> fragmentTransaction(String transactionBytesBase64) async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>(
        'fragmentTransaction',
        {'transactionBytes': transactionBytesBase64},
      );
      if (result == null) {
        throw PollinetException('FRAGMENT_ERROR', 'Failed to fragment transaction');
      }
      return result.map((e) => FragmentData.fromMap(Map<String, dynamic>.from(e))).toList();
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Reconstruct a transaction from BLE fragments
  static Future<String> reconstructTransaction(List<FragmentData> fragments) async {
    try {
      final result = await _channel.invokeMethod<String>(
        'reconstructTransaction',
        {'fragments': fragments.map((f) => f.toMap()).toList()},
      );
      if (result == null) {
        throw PollinetException('RECONSTRUCT_ERROR', 'Failed to reconstruct transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get fragmentation statistics for a transaction
  static Future<FragmentationStats> getFragmentationStats(String transactionBytesBase64) async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getFragmentationStats',
        {'transactionBytes': transactionBytesBase64},
      );
      if (result == null) {
        throw PollinetException('STATS_ERROR', 'Failed to get fragmentation stats');
      }
      return FragmentationStats.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Prepare a transaction for broadcast over BLE mesh
  static Future<BroadcastPreparation> prepareBroadcast(String transactionBytesBase64) async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'prepareBroadcast',
        {'transactionBytes': transactionBytesBase64},
      );
      if (result == null) {
        throw PollinetException('BROADCAST_ERROR', 'Failed to prepare broadcast');
      }
      return BroadcastPreparation.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Autonomous Transaction Relay System
  // =========================================================================

  /// Push a received transaction into the auto-submission queue
  static Future<PushResponse> pushReceivedTransaction(String transactionBytesBase64) async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'pushReceivedTransaction',
        {'transactionBytes': transactionBytesBase64},
      );
      if (result == null) {
        throw PollinetException('PUSH_ERROR', 'Failed to push received transaction');
      }
      return PushResponse.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get next received transaction for auto-submission
  static Future<ReceivedTransaction?> nextReceivedTransaction() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>?>('nextReceivedTransaction');
      if (result == null) {
        return null;
      }
      return ReceivedTransaction.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get count of transactions waiting for auto-submission
  static Future<int> getReceivedQueueSize() async {
    try {
      final result = await _channel.invokeMethod<int>('getReceivedQueueSize');
      return result ?? 0;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get fragment reassembly info for all incomplete transactions
  static Future<FragmentReassemblyInfoList> getFragmentReassemblyInfo() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getFragmentReassemblyInfo');
      if (result == null) {
        throw PollinetException('INFO_ERROR', 'Failed to get fragment reassembly info');
      }
      return FragmentReassemblyInfoList.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Mark a transaction as successfully submitted
  static Future<bool> markTransactionSubmitted(String transactionBytesBase64) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'markTransactionSubmitted',
        {'transactionBytes': transactionBytesBase64},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Clean up old submitted transaction hashes
  static Future<bool> cleanupOldSubmissions() async {
    try {
      final result = await _channel.invokeMethod<bool>('cleanupOldSubmissions');
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Debug outbound queue (non-destructive peek)
  static Future<OutboundQueueDebug> debugOutboundQueue() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('debugOutboundQueue');
      if (result == null) {
        throw PollinetException('DEBUG_ERROR', 'Failed to get debug info');
      }
      return OutboundQueueDebug.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Queue Management
  // =========================================================================

  /// Push transaction to outbound queue with priority
  static Future<bool> pushOutboundTransaction({
    required String txBytesBase64,
    required String txId,
    required List<FragmentFFI> fragments,
    Priority priority = Priority.normal,
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'pushOutboundTransaction',
        {
          'txBytes': txBytesBase64,
          'txId': txId,
          'fragments': fragments.map((f) => f.toMap()).toList(),
          'priority': priority.name.toUpperCase(),
        },
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Accept and queue a pre-signed transaction from external partners
  static Future<String> acceptAndQueueExternalTransaction({
    required String base64SignedTx,
    int? maxPayload,
  }) async {
    try {
      final arguments = <String, dynamic>{
        'base64SignedTx': base64SignedTx,
        if (maxPayload != null) 'maxPayload': maxPayload,
      };
      final result = await _channel.invokeMethod<String>('acceptAndQueueExternalTransaction', arguments);
      if (result == null) {
        throw PollinetException('QUEUE_ERROR', 'Failed to accept and queue transaction');
      }
      return result;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Pop next transaction from outbound queue
  static Future<OutboundTransaction?> popOutboundTransaction() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>?>('popOutboundTransaction');
      if (result == null) {
        return null;
      }
      return OutboundTransaction.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get outbound queue size
  static Future<int> getOutboundQueueSize() async {
    try {
      final result = await _channel.invokeMethod<int>('getOutboundQueueSize');
      return result ?? 0;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Add transaction to retry queue
  static Future<bool> addToRetryQueue({
    required String txBytesBase64,
    required String txId,
    required String error,
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'addToRetryQueue',
        {
          'txBytes': txBytesBase64,
          'txId': txId,
          'error': error,
        },
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Pop next ready retry item
  static Future<RetryItem?> popReadyRetry() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>?>('popReadyRetry');
      if (result == null) {
        return null;
      }
      return RetryItem.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get retry queue size
  static Future<int> getRetryQueueSize() async {
    try {
      final result = await _channel.invokeMethod<int>('getRetryQueueSize');
      return result ?? 0;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Queue confirmation for relay back to origin
  static Future<bool> queueConfirmation({
    required String txId,
    required String signature,
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'queueConfirmation',
        {'txId': txId, 'signature': signature},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Pop next confirmation from queue
  static Future<Confirmation?> popConfirmation() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>?>('popConfirmation');
      if (result == null) {
        return null;
      }
      return Confirmation.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get confirmation queue size
  static Future<int> getConfirmationQueueSize() async {
    try {
      final result = await _channel.invokeMethod<int>('getConfirmationQueueSize');
      return result ?? 0;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Get metrics for all queues
  static Future<QueueMetrics> getQueueMetrics() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getQueueMetrics');
      if (result == null) {
        throw PollinetException('METRICS_ERROR', 'Failed to get queue metrics');
      }
      return QueueMetrics.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Cleanup stale fragments from reassembly buffer
  static Future<int> cleanupStaleFragments() async {
    try {
      final result = await _channel.invokeMethod<int>('cleanupStaleFragments');
      return result ?? 0;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Cleanup expired confirmations and retry items
  static Future<CleanupExpiredResponse> cleanupExpired() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('cleanupExpired');
      if (result == null) {
        throw PollinetException('CLEANUP_ERROR', 'Failed to cleanup expired items');
      }
      return CleanupExpiredResponse.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  // =========================================================================
  // Queue Persistence
  // =========================================================================

  /// Save all queues to disk (force save)
  static Future<bool> saveQueues() async {
    try {
      final result = await _channel.invokeMethod<bool>('saveQueues');
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Auto-save queues if needed (debounced)
  static Future<bool> autoSaveQueues() async {
    try {
      final result = await _channel.invokeMethod<bool>('autoSaveQueues');
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Clear all queues
  static Future<bool> clearAllQueues() async {
    try {
      final result = await _channel.invokeMethod<bool>('clearAllQueues');
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }

  /// Relay a received confirmation
  static Future<bool> relayConfirmation(Confirmation confirmation) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'relayConfirmation',
        {'confirmation': confirmation.toMap()},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      throw PollinetException(e.code, e.message ?? 'Unknown error');
    }
  }
}

// =============================================================================
// Data Types
// =============================================================================

/// SDK Configuration
class SdkConfig {
  final int version;
  final String? rpcUrl;
  final bool enableLogging;
  final String? logLevel;
  final String? storageDirectory;

  SdkConfig({
    this.version = 1,
    this.rpcUrl,
    this.enableLogging = true,
    this.logLevel,
    this.storageDirectory,
  });

  Map<String, dynamic> toMap() {
    return {
      'version': version,
      if (rpcUrl != null) 'rpcUrl': rpcUrl,
      'enableLogging': enableLogging,
      if (logLevel != null) 'logLevel': logLevel,
      if (storageDirectory != null) 'storageDirectory': storageDirectory,
    };
  }
}

/// Transport metrics snapshot
class MetricsSnapshot {
  final int fragmentsBuffered;
  final int transactionsComplete;
  final int reassemblyFailures;
  final String lastError;
  final int updatedAt;

  MetricsSnapshot({
    required this.fragmentsBuffered,
    required this.transactionsComplete,
    required this.reassemblyFailures,
    required this.lastError,
    required this.updatedAt,
  });

  factory MetricsSnapshot.fromMap(Map<String, dynamic> map) {
    return MetricsSnapshot(
      fragmentsBuffered: map['fragmentsBuffered'] as int? ?? 0,
      transactionsComplete: map['transactionsComplete'] as int? ?? 0,
      reassemblyFailures: map['reassemblyFailures'] as int? ?? 0,
      lastError: map['lastError'] as String? ?? '',
      updatedAt: map['updatedAt'] as int? ?? 0,
    );
  }
}

/// Transaction fragment
class Fragment {
  final String id;
  final int index;
  final int total;
  final String data; // base64
  final String fragmentType;
  final String checksum; // base64

  Fragment({
    required this.id,
    required this.index,
    required this.total,
    required this.data,
    required this.fragmentType,
    required this.checksum,
  });

  factory Fragment.fromMap(Map<String, dynamic> map) {
    return Fragment(
      id: map['id'] as String,
      index: map['index'] as int,
      total: map['total'] as int,
      data: map['data'] as String,
      fragmentType: map['fragmentType'] as String,
      checksum: map['checksum'] as String,
    );
  }
}

/// List of fragments
class FragmentList {
  final List<Fragment> fragments;

  FragmentList({required this.fragments});

  factory FragmentList.fromMap(Map<String, dynamic> map) {
    final fragmentsList = map['fragments'] as List<dynamic>? ?? [];
    return FragmentList(
      fragments: fragmentsList.map((e) => Fragment.fromMap(Map<String, dynamic>.from(e))).toList(),
    );
  }
}

/// Cached nonce data
class CachedNonceData {
  final String nonceAccount;
  final String authority;
  final String blockhash;
  final int lamportsPerSignature;
  final int cachedAt;
  final bool used;

  CachedNonceData({
    required this.nonceAccount,
    required this.authority,
    required this.blockhash,
    required this.lamportsPerSignature,
    required this.cachedAt,
    required this.used,
  });

  Map<String, dynamic> toMap() {
    return {
      'nonceAccount': nonceAccount,
      'authority': authority,
      'blockhash': blockhash,
      'lamportsPerSignature': lamportsPerSignature,
      'cachedAt': cachedAt,
      'used': used,
    };
  }

  factory CachedNonceData.fromMap(Map<String, dynamic> map) {
    return CachedNonceData(
      nonceAccount: map['nonceAccount'] as String,
      authority: map['authority'] as String,
      blockhash: map['blockhash'] as String,
      lamportsPerSignature: map['lamportsPerSignature'] as int,
      cachedAt: map['cachedAt'] as int,
      used: map['used'] as bool,
    );
  }
}

/// Offline transaction bundle
class OfflineTransactionBundle {
  final List<CachedNonceData> nonceCaches;
  final int maxTransactions;
  final int createdAt;

  OfflineTransactionBundle({
    required this.nonceCaches,
    required this.maxTransactions,
    required this.createdAt,
  });

  int get availableNonces => nonceCaches.where((n) => !n.used).length;
  int get usedNonces => nonceCaches.where((n) => n.used).length;
  int get totalNonces => nonceCaches.length;

  factory OfflineTransactionBundle.fromMap(Map<String, dynamic> map) {
    final nonceCachesList = map['nonceCaches'] as List<dynamic>? ?? [];
    return OfflineTransactionBundle(
      nonceCaches: nonceCachesList
          .map((e) => CachedNonceData.fromMap(Map<String, dynamic>.from(e)))
          .toList(),
      maxTransactions: map['maxTransactions'] as int? ?? 0,
      createdAt: map['createdAt'] as int? ?? 0,
    );
  }
}

/// Unsigned nonce transaction
class UnsignedNonceTransaction {
  final String unsignedTransactionBase64;
  final List<String> nonceKeypairBase64;
  final List<String> noncePubkey;

  UnsignedNonceTransaction({
    required this.unsignedTransactionBase64,
    required this.nonceKeypairBase64,
    required this.noncePubkey,
  });

  factory UnsignedNonceTransaction.fromMap(Map<String, dynamic> map) {
    return UnsignedNonceTransaction(
      unsignedTransactionBase64: map['unsignedTransactionBase64'] as String,
      nonceKeypairBase64: (map['nonceKeypairBase64'] as List<dynamic>).map((e) => e.toString()).toList(),
      noncePubkey: (map['noncePubkey'] as List<dynamic>).map((e) => e.toString()).toList(),
    );
  }
}

/// Fragment data for BLE transmission
class FragmentData {
  final String transactionId;
  final int fragmentIndex;
  final int totalFragments;
  final String dataBase64;

  FragmentData({
    required this.transactionId,
    required this.fragmentIndex,
    required this.totalFragments,
    required this.dataBase64,
  });

  Map<String, dynamic> toMap() {
    return {
      'transactionId': transactionId,
      'fragmentIndex': fragmentIndex,
      'totalFragments': totalFragments,
      'dataBase64': dataBase64,
    };
  }

  factory FragmentData.fromMap(Map<String, dynamic> map) {
    return FragmentData(
      transactionId: map['transactionId'] as String,
      fragmentIndex: map['fragmentIndex'] as int,
      totalFragments: map['totalFragments'] as int,
      dataBase64: map['dataBase64'] as String,
    );
  }
}

/// Fragmentation statistics
class FragmentationStats {
  final int originalSize;
  final int fragmentCount;
  final int maxFragmentSize;
  final int avgFragmentSize;
  final int totalOverhead;
  final double efficiency;

  FragmentationStats({
    required this.originalSize,
    required this.fragmentCount,
    required this.maxFragmentSize,
    required this.avgFragmentSize,
    required this.totalOverhead,
    required this.efficiency,
  });

  factory FragmentationStats.fromMap(Map<String, dynamic> map) {
    return FragmentationStats(
      originalSize: map['originalSize'] as int? ?? 0,
      fragmentCount: map['fragmentCount'] as int? ?? 0,
      maxFragmentSize: map['maxFragmentSize'] as int? ?? 0,
      avgFragmentSize: map['avgFragmentSize'] as int? ?? 0,
      totalOverhead: map['totalOverhead'] as int? ?? 0,
      efficiency: (map['efficiency'] as num?)?.toDouble() ?? 0.0,
    );
  }
}

/// Fragment packet for broadcast
class FragmentPacket {
  final String transactionId;
  final int fragmentIndex;
  final int totalFragments;
  final String packetBytes; // Base64-encoded

  FragmentPacket({
    required this.transactionId,
    required this.fragmentIndex,
    required this.totalFragments,
    required this.packetBytes,
  });

  factory FragmentPacket.fromMap(Map<String, dynamic> map) {
    return FragmentPacket(
      transactionId: map['transactionId'] as String,
      fragmentIndex: map['fragmentIndex'] as int,
      totalFragments: map['totalFragments'] as int,
      packetBytes: map['packetBytes'] as String,
    );
  }
}

/// Broadcast preparation
class BroadcastPreparation {
  final String transactionId;
  final List<FragmentPacket> fragmentPackets;

  BroadcastPreparation({
    required this.transactionId,
    required this.fragmentPackets,
  });

  factory BroadcastPreparation.fromMap(Map<String, dynamic> map) {
    final packetsList = map['fragmentPackets'] as List<dynamic>? ?? [];
    return BroadcastPreparation(
      transactionId: map['transactionId'] as String,
      fragmentPackets: packetsList.map((e) => FragmentPacket.fromMap(Map<String, dynamic>.from(e))).toList(),
    );
  }
}

/// Push response
class PushResponse {
  final bool added;
  final int queueSize;

  PushResponse({
    required this.added,
    required this.queueSize,
  });

  factory PushResponse.fromMap(Map<String, dynamic> map) {
    return PushResponse(
      added: map['added'] as bool? ?? false,
      queueSize: map['queueSize'] as int? ?? 0,
    );
  }
}

/// Received transaction
class ReceivedTransaction {
  final String txId;
  final String transactionBase64;
  final int receivedAt;

  ReceivedTransaction({
    required this.txId,
    required this.transactionBase64,
    required this.receivedAt,
  });

  factory ReceivedTransaction.fromMap(Map<String, dynamic> map) {
    return ReceivedTransaction(
      txId: map['txId'] as String,
      transactionBase64: map['transactionBase64'] as String,
      receivedAt: map['receivedAt'] as int? ?? 0,
    );
  }
}

/// Fragment reassembly info
class FragmentReassemblyInfo {
  final String transactionId;
  final int totalFragments;
  final int receivedFragments;
  final List<int> receivedIndices;
  final List<int> fragmentSizes;
  final int totalBytesReceived;

  FragmentReassemblyInfo({
    required this.transactionId,
    required this.totalFragments,
    required this.receivedFragments,
    required this.receivedIndices,
    required this.fragmentSizes,
    required this.totalBytesReceived,
  });

  factory FragmentReassemblyInfo.fromMap(Map<String, dynamic> map) {
    return FragmentReassemblyInfo(
      transactionId: map['transactionId'] as String,
      totalFragments: map['totalFragments'] as int? ?? 0,
      receivedFragments: map['receivedFragments'] as int? ?? 0,
      receivedIndices: (map['receivedIndices'] as List<dynamic>?)?.map((e) => e as int).toList() ?? [],
      fragmentSizes: (map['fragmentSizes'] as List<dynamic>?)?.map((e) => e as int).toList() ?? [],
      totalBytesReceived: map['totalBytesReceived'] as int? ?? 0,
    );
  }
}

/// Fragment reassembly info list
class FragmentReassemblyInfoList {
  final List<FragmentReassemblyInfo> transactions;

  FragmentReassemblyInfoList({required this.transactions});

  factory FragmentReassemblyInfoList.fromMap(Map<String, dynamic> map) {
    final transactionsList = map['transactions'] as List<dynamic>? ?? [];
    return FragmentReassemblyInfoList(
      transactions: transactionsList.map((e) => FragmentReassemblyInfo.fromMap(Map<String, dynamic>.from(e))).toList(),
    );
  }
}

/// Outbound queue debug info
class FragmentDebugInfo {
  final int index;
  final int size;

  FragmentDebugInfo({
    required this.index,
    required this.size,
  });

  factory FragmentDebugInfo.fromMap(Map<String, dynamic> map) {
    return FragmentDebugInfo(
      index: map['index'] as int? ?? 0,
      size: map['size'] as int? ?? 0,
    );
  }
}

/// Outbound queue debug
class OutboundQueueDebug {
  final int totalFragments;
  final List<FragmentDebugInfo> fragments;

  OutboundQueueDebug({
    required this.totalFragments,
    required this.fragments,
  });

  factory OutboundQueueDebug.fromMap(Map<String, dynamic> map) {
    final fragmentsList = map['fragments'] as List<dynamic>? ?? [];
    return OutboundQueueDebug(
      totalFragments: map['totalFragments'] as int? ?? 0,
      fragments: fragmentsList.map((e) => FragmentDebugInfo.fromMap(Map<String, dynamic>.from(e))).toList(),
    );
  }
}

/// Transaction priority
enum Priority {
  high,
  normal,
  low;
}

/// Fragment for FFI
class FragmentFFI {
  final String transactionId;
  final int fragmentIndex;
  final int totalFragments;
  final String dataBase64;

  FragmentFFI({
    required this.transactionId,
    required this.fragmentIndex,
    required this.totalFragments,
    required this.dataBase64,
  });

  Map<String, dynamic> toMap() {
    return {
      'transactionId': transactionId,
      'fragmentIndex': fragmentIndex,
      'totalFragments': totalFragments,
      'dataBase64': dataBase64,
    };
  }
}

/// Outbound transaction
class OutboundTransaction {
  final String txId;
  final String originalBytes; // base64
  final int fragmentCount;
  final Priority priority;
  final int createdAt;
  final int retryCount;

  OutboundTransaction({
    required this.txId,
    required this.originalBytes,
    required this.fragmentCount,
    required this.priority,
    required this.createdAt,
    required this.retryCount,
  });

  factory OutboundTransaction.fromMap(Map<String, dynamic> map) {
    final priorityStr = (map['priority'] as String?)?.toLowerCase() ?? 'normal';
    final priority = Priority.values.firstWhere(
      (p) => p.name == priorityStr,
      orElse: () => Priority.normal,
    );
    return OutboundTransaction(
      txId: map['txId'] as String,
      originalBytes: map['originalBytes'] as String,
      fragmentCount: map['fragmentCount'] as int? ?? 0,
      priority: priority,
      createdAt: map['createdAt'] as int? ?? 0,
      retryCount: map['retryCount'] as int? ?? 0,
    );
  }
}

/// Retry item
class RetryItem {
  final String txBytes; // base64
  final String txId;
  final int attemptCount;
  final String lastError;
  final int nextRetryInSecs;
  final int ageSeconds;

  RetryItem({
    required this.txBytes,
    required this.txId,
    required this.attemptCount,
    required this.lastError,
    required this.nextRetryInSecs,
    required this.ageSeconds,
  });

  factory RetryItem.fromMap(Map<String, dynamic> map) {
    return RetryItem(
      txBytes: map['txBytes'] as String,
      txId: map['txId'] as String,
      attemptCount: map['attemptCount'] as int? ?? 0,
      lastError: map['lastError'] as String,
      nextRetryInSecs: map['nextRetryInSecs'] as int? ?? 0,
      ageSeconds: map['ageSeconds'] as int? ?? 0,
    );
  }
}

/// Confirmation status
enum ConfirmationStatusType {
  success,
  failed;
}

/// Confirmation
class Confirmation {
  final String txId;
  final ConfirmationStatusType statusType;
  final String? signature; // For SUCCESS
  final String? error; // For FAILED
  final int timestamp;
  final int relayCount;

  Confirmation({
    required this.txId,
    required this.statusType,
    this.signature,
    this.error,
    required this.timestamp,
    required this.relayCount,
  });

  Map<String, dynamic> toMap() {
    return {
      'txId': txId,
      'status': statusType == ConfirmationStatusType.success
          ? {'SUCCESS': signature}
          : {'FAILED': error},
      'timestamp': timestamp,
      'relayCount': relayCount,
    };
  }

  factory Confirmation.fromMap(Map<String, dynamic> map) {
    final statusMap = map['status'] as Map<String, dynamic>;
    final statusType = statusMap.containsKey('SUCCESS')
        ? ConfirmationStatusType.success
        : ConfirmationStatusType.failed;
    final signature = statusMap['SUCCESS'] as String?;
    final error = statusMap['FAILED'] as String?;

    return Confirmation(
      txId: map['txId'] as String,
      statusType: statusType,
      signature: signature,
      error: error,
      timestamp: map['timestamp'] as int? ?? 0,
      relayCount: map['relayCount'] as int? ?? 0,
    );
  }
}

/// Queue metrics
class QueueMetrics {
  final int outboundSize;
  final int outboundHighPriority;
  final int outboundNormalPriority;
  final int outboundLowPriority;
  final int confirmationSize;
  final int retrySize;
  final double retryAvgAttempts;

  QueueMetrics({
    required this.outboundSize,
    required this.outboundHighPriority,
    required this.outboundNormalPriority,
    required this.outboundLowPriority,
    required this.confirmationSize,
    required this.retrySize,
    required this.retryAvgAttempts,
  });

  factory QueueMetrics.fromMap(Map<String, dynamic> map) {
    return QueueMetrics(
      outboundSize: map['outboundSize'] as int? ?? 0,
      outboundHighPriority: map['outboundHighPriority'] as int? ?? 0,
      outboundNormalPriority: map['outboundNormalPriority'] as int? ?? 0,
      outboundLowPriority: map['outboundLowPriority'] as int? ?? 0,
      confirmationSize: map['confirmationSize'] as int? ?? 0,
      retrySize: map['retrySize'] as int? ?? 0,
      retryAvgAttempts: (map['retryAvgAttempts'] as num?)?.toDouble() ?? 0.0,
    );
  }
}

/// Cleanup expired response
class CleanupExpiredResponse {
  final int confirmationsCleaned;
  final int retriesCleaned;

  CleanupExpiredResponse({
    required this.confirmationsCleaned,
    required this.retriesCleaned,
  });

  factory CleanupExpiredResponse.fromMap(Map<String, dynamic> map) {
    return CleanupExpiredResponse(
      confirmationsCleaned: map['confirmationsCleaned'] as int? ?? 0,
      retriesCleaned: map['retriesCleaned'] as int? ?? 0,
    );
  }
}

/// Exception thrown by Pollinet SDK operations
class PollinetException implements Exception {
  final String code;
  final String message;

  PollinetException(this.code, this.message);

  @override
  String toString() => 'PollinetException($code): $message';
}
