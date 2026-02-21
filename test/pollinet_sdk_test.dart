import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pollinet_flutter/pollinet_sdk.dart';

/// These tests verify that every Dart SDK method:
///   1. Calls the correct method name on the 'pollinet_sdk' channel
///   2. Passes the correct argument keys (matching what Kotlin reads)
///   3. Correctly deserializes mock responses
///   4. Wraps PlatformException into PollinetException
///
/// If a method name or argument key is wrong, the test fails immediately —
/// catching the exact class of bug that caused the MissingPluginException.

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late MethodCall? lastCall;
  late dynamic Function(MethodCall) mockHandler;

  setUp(() {
    lastCall = null;
    mockHandler = (_) => null;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('pollinet_sdk'),
      (MethodCall call) async {
        lastCall = call;
        return mockHandler(call);
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('pollinet_sdk'),
      null,
    );
  });

  // ---------------------------------------------------------------------------
  // Helper: expect a specific PlatformException code to become PollinetException
  // ---------------------------------------------------------------------------
  void setUpSdkError(String code, String message) {
    mockHandler = (_) {
      throw PlatformException(code: code, message: message);
    };
  }

  // =========================================================================
  // 1. Core lifecycle
  // =========================================================================

  group('Core lifecycle', () {
    test('initialize sends correct method and args', () async {
      mockHandler = (_) => true;
      await PollinetSdk.initialize(
        config: SdkConfig(
          rpcUrl: 'https://api.devnet.solana.com',
          enableLogging: true,
          logLevel: 'debug',
          storageDirectory: '/tmp',
        ),
      );
      expect(lastCall!.method, 'initialize');
      final config = lastCall!.arguments['config'] as Map;
      expect(config['rpcUrl'], 'https://api.devnet.solana.com');
      expect(config['enableLogging'], true);
      expect(config['logLevel'], 'debug');
      expect(config['storageDirectory'], '/tmp');
    });

    test('shutdown sends correct method', () async {
      mockHandler = (_) => true;
      await PollinetSdk.shutdown();
      expect(lastCall!.method, 'shutdown');
    });

    test('version sends correct method', () async {
      mockHandler = (_) => '0.1.0';
      final v = await PollinetSdk.version();
      expect(lastCall!.method, 'version');
      expect(v, '0.1.0');
    });
  });

  // =========================================================================
  // 2. Permissions
  // =========================================================================

  group('Permissions', () {
    test('checkPermissions sends correct method', () async {
      mockHandler = (_) => <String, String>{'BLE_SCAN': 'granted'};
      final result = await PollinetSdk.checkPermissions();
      expect(lastCall!.method, 'checkPermissions');
      expect(result['BLE_SCAN'], 'granted');
    });

    test('requestPermissions sends correct method', () async {
      mockHandler = (_) => <String, String>{'BLE_SCAN': 'granted'};
      await PollinetSdk.requestPermissions();
      expect(lastCall!.method, 'requestPermissions');
    });

    test('checkBatteryOptimization sends correct method', () async {
      mockHandler = (_) => true;
      await PollinetSdk.checkBatteryOptimization();
      expect(lastCall!.method, 'checkBatteryOptimization');
    });

    test('requestBatteryOptimization sends correct method', () async {
      mockHandler = (_) => true;
      await PollinetSdk.requestBatteryOptimization();
      expect(lastCall!.method, 'requestBatteryOptimization');
    });
  });

  // =========================================================================
  // 3. Transport API
  // =========================================================================

  group('Transport API', () {
    test('pushInbound sends correct key "data"', () async {
      mockHandler = (_) => true;
      await PollinetSdk.pushInbound('AQID');
      expect(lastCall!.method, 'pushInbound');
      expect(lastCall!.arguments['data'], 'AQID');
    });

    test('nextOutbound sends correct key "maxLen"', () async {
      mockHandler = (_) => 'AQID';
      await PollinetSdk.nextOutbound(maxLen: 512);
      expect(lastCall!.method, 'nextOutbound');
      expect(lastCall!.arguments['maxLen'], 512);
    });

    test('tick sends correct method', () async {
      mockHandler = (_) => <String>['tx1'];
      final events = await PollinetSdk.tick();
      expect(lastCall!.method, 'tick');
      expect(events, ['tx1']);
    });

    test('metrics deserializes correctly', () async {
      mockHandler = (_) => {
        'fragmentsBuffered': 5,
        'transactionsComplete': 2,
        'reassemblyFailures': 0,
        'lastError': '',
        'updatedAt': 1000,
      };
      final m = await PollinetSdk.metrics();
      expect(lastCall!.method, 'metrics');
      expect(m.fragmentsBuffered, 5);
      expect(m.transactionsComplete, 2);
    });

    test('clearTransaction sends correct key "txId"', () async {
      mockHandler = (_) => true;
      await PollinetSdk.clearTransaction('abc123');
      expect(lastCall!.method, 'clearTransaction');
      expect(lastCall!.arguments['txId'], 'abc123');
    });
  });

  // =========================================================================
  // 4. Transaction builders
  // =========================================================================

  group('Transaction builders', () {
    test('createUnsignedTransaction sends nested "request" with correct keys',
        () async {
      mockHandler = (_) => 'base64tx';
      await PollinetSdk.createUnsignedTransaction(
        sender: 'sender1',
        recipient: 'recipient1',
        feePayer: 'payer1',
        amount: 1000000,
        nonceAccount: 'nonce1',
      );
      expect(lastCall!.method, 'createUnsignedTransaction');
      final req = lastCall!.arguments['request'] as Map;
      expect(req['sender'], 'sender1');
      expect(req['recipient'], 'recipient1');
      expect(req['feePayer'], 'payer1');
      expect(req['amount'], 1000000);
      expect(req['nonceAccount'], 'nonce1');
    });

    test('createUnsignedSplTransaction sends correct keys', () async {
      mockHandler = (_) => 'base64tx';
      await PollinetSdk.createUnsignedSplTransaction(
        senderWallet: 'sw',
        recipientWallet: 'rw',
        feePayer: 'fp',
        mintAddress: 'mint',
        amount: 500,
      );
      expect(lastCall!.method, 'createUnsignedSplTransaction');
      final req = lastCall!.arguments['request'] as Map;
      expect(req['senderWallet'], 'sw');
      expect(req['recipientWallet'], 'rw');
      expect(req['feePayer'], 'fp');
      expect(req['mintAddress'], 'mint');
      expect(req['amount'], 500);
    });
  });

  // =========================================================================
  // 5. Signature helpers — the applySignature key fix is critical here
  // =========================================================================

  group('Signature helpers', () {
    test('prepareSignPayload sends key "base64Tx"', () async {
      mockHandler = (_) => 'payload';
      await PollinetSdk.prepareSignPayload('tx1');
      expect(lastCall!.method, 'prepareSignPayload');
      expect(lastCall!.arguments['base64Tx'], 'tx1');
    });

    test('applySignature sends key "signatureBytes" (not "signature")',
        () async {
      mockHandler = (_) => 'signedTx';
      await PollinetSdk.applySignature(
        base64Tx: 'tx1',
        signerPubkey: 'pubkey1',
        signatureBytesBase64: 'sigBytes64',
      );
      expect(lastCall!.method, 'applySignature');
      expect(lastCall!.arguments['base64Tx'], 'tx1');
      expect(lastCall!.arguments['signerPubkey'], 'pubkey1');
      // This is the key Kotlin must read — was "signature" before the fix
      expect(lastCall!.arguments['signatureBytes'], 'sigBytes64');
      expect(lastCall!.arguments.containsKey('signature'), isFalse,
          reason: 'Dart must send "signatureBytes", not "signature"');
    });

    test('verifyAndSerialize sends key "base64Tx"', () async {
      mockHandler = (_) => 'verified';
      await PollinetSdk.verifyAndSerialize('tx1');
      expect(lastCall!.method, 'verifyAndSerialize');
      expect(lastCall!.arguments['base64Tx'], 'tx1');
    });
  });

  // =========================================================================
  // 6. Fragmentation
  // =========================================================================

  group('Fragmentation', () {
    test('fragment sends keys "txBytes" and "maxPayload"', () async {
      mockHandler = (_) => {
        'fragments': [
          {
            'id': 'f1',
            'index': 0,
            'total': 2,
            'data': 'AQID',
            'fragmentType': 'START',
            'checksum': 'chk',
          }
        ]
      };
      final fl = await PollinetSdk.fragment(
          txBytesBase64: 'tx1', maxPayload: 200);
      expect(lastCall!.method, 'fragment');
      expect(lastCall!.arguments['txBytes'], 'tx1');
      expect(lastCall!.arguments['maxPayload'], 200);
      expect(fl.fragments.length, 1);
      expect(fl.fragments[0].id, 'f1');
    });
  });

  // =========================================================================
  // 7. Offline bundle management
  // =========================================================================

  group('Offline bundle management', () {
    test('prepareOfflineBundle sends keys "count", "senderKeypair"', () async {
      mockHandler = (_) => {
        'version': 1,
        'nonceCaches': <Map>[],
        'maxTransactions': 5,
        'createdAt': 1000,
      };
      await PollinetSdk.prepareOfflineBundle(
        count: 5,
        senderKeypairBase64: 'keypair64',
        bundleFile: '/tmp/bundle',
      );
      expect(lastCall!.method, 'prepareOfflineBundle');
      expect(lastCall!.arguments['count'], 5);
      expect(lastCall!.arguments['senderKeypair'], 'keypair64');
      expect(lastCall!.arguments['bundleFile'], '/tmp/bundle');
    });

    test('prepareOfflineBundle deserializes nonceCaches', () async {
      mockHandler = (_) => {
        'version': 1,
        'nonceCaches': [
          {
            'nonceAccount': 'n1',
            'authority': 'auth1',
            'blockhash': 'bh1',
            'lamportsPerSignature': 5000,
            'cachedAt': 100,
            'used': false,
          }
        ],
        'maxTransactions': 1,
        'createdAt': 200,
      };
      final bundle = await PollinetSdk.prepareOfflineBundle(
        count: 1,
        senderKeypairBase64: 'kp',
      );
      expect(bundle.nonceCaches.length, 1);
      expect(bundle.nonceCaches[0].nonceAccount, 'n1');
      expect(bundle.availableNonces, 1);
      expect(bundle.usedNonces, 0);
    });

    test('createOfflineTransaction sends correct keys', () async {
      mockHandler = (_) => 'signedTx64';
      await PollinetSdk.createOfflineTransaction(
        senderKeypairBase64: 'skp',
        nonceAuthorityKeypairBase64: 'nakp',
        recipient: 'recv',
        amount: 100000,
      );
      expect(lastCall!.method, 'createOfflineTransaction');
      expect(lastCall!.arguments['senderKeypair'], 'skp');
      expect(lastCall!.arguments['nonceAuthorityKeypair'], 'nakp');
      expect(lastCall!.arguments['recipient'], 'recv');
      expect(lastCall!.arguments['amount'], 100000);
    });

    test('submitOfflineTransaction sends correct keys', () async {
      mockHandler = (_) => 'txSig';
      await PollinetSdk.submitOfflineTransaction(
        transactionBase64: 'tx64',
        verifyNonce: false,
      );
      expect(lastCall!.method, 'submitOfflineTransaction');
      expect(lastCall!.arguments['transactionBase64'], 'tx64');
      expect(lastCall!.arguments['verifyNonce'], false);
    });
  });

  // =========================================================================
  // 8. MWA / unsigned offline
  // =========================================================================

  group('MWA support', () {
    test('createUnsignedOfflineTransaction sends correct keys', () async {
      mockHandler = (_) => 'unsignedTx';
      await PollinetSdk.createUnsignedOfflineTransaction(
        senderPubkey: 'sp',
        nonceAuthorityPubkey: 'nap',
        recipient: 'r',
        amount: 50000,
      );
      expect(lastCall!.method, 'createUnsignedOfflineTransaction');
      expect(lastCall!.arguments['senderPubkey'], 'sp');
      expect(lastCall!.arguments['nonceAuthorityPubkey'], 'nap');
      expect(lastCall!.arguments['recipient'], 'r');
      expect(lastCall!.arguments['amount'], 50000);
    });

    test('createUnsignedOfflineSplTransaction sends correct keys', () async {
      mockHandler = (_) => 'unsignedSplTx';
      await PollinetSdk.createUnsignedOfflineSplTransaction(
        senderWallet: 'sw',
        recipientWallet: 'rw',
        mintAddress: 'mint',
        amount: 100,
        feePayer: 'fp',
      );
      expect(lastCall!.method, 'createUnsignedOfflineSplTransaction');
      expect(lastCall!.arguments['senderWallet'], 'sw');
      expect(lastCall!.arguments['recipientWallet'], 'rw');
      expect(lastCall!.arguments['mintAddress'], 'mint');
      expect(lastCall!.arguments['amount'], 100);
      expect(lastCall!.arguments['feePayer'], 'fp');
    });

    test('getRequiredSigners sends key "unsignedTransactionBase64"', () async {
      mockHandler = (_) => <String>['pub1', 'pub2'];
      final signers =
          await PollinetSdk.getRequiredSigners('unsignedTx64');
      expect(lastCall!.method, 'getRequiredSigners');
      expect(lastCall!.arguments['unsignedTransactionBase64'], 'unsignedTx64');
      expect(signers, ['pub1', 'pub2']);
    });

    test('getAvailableNonce returns null when none available', () async {
      mockHandler = (_) => null;
      final nonce = await PollinetSdk.getAvailableNonce();
      expect(lastCall!.method, 'getAvailableNonce');
      expect(nonce, isNull);
    });

    test('getAvailableNonce deserializes nonce data', () async {
      mockHandler = (_) => {
        'nonceAccount': 'n1',
        'authority': 'a1',
        'blockhash': 'bh1',
        'lamportsPerSignature': 5000,
        'cachedAt': 100,
        'used': false,
      };
      final nonce = await PollinetSdk.getAvailableNonce();
      expect(nonce, isNotNull);
      expect(nonce!.nonceAccount, 'n1');
      expect(nonce.used, false);
    });

    test('refreshOfflineBundle sends correct method', () async {
      mockHandler = (_) => 3;
      final count = await PollinetSdk.refreshOfflineBundle();
      expect(lastCall!.method, 'refreshOfflineBundle');
      expect(count, 3);
    });

    test('cacheNonceAccounts sends key "nonceAccounts"', () async {
      mockHandler = (_) => 2;
      await PollinetSdk.cacheNonceAccounts(['n1', 'n2']);
      expect(lastCall!.method, 'cacheNonceAccounts');
      expect(lastCall!.arguments['nonceAccounts'], ['n1', 'n2']);
    });

    test('addNonceSignature sends correct keys', () async {
      mockHandler = (_) => 'fullySigned';
      await PollinetSdk.addNonceSignature(
        payerSignedTransactionBase64: 'pstx',
        nonceKeypairBase64: ['kp1'],
      );
      expect(lastCall!.method, 'addNonceSignature');
      expect(lastCall!.arguments['payerSignedTransactionBase64'], 'pstx');
      expect(lastCall!.arguments['nonceKeypairBase64'], ['kp1']);
    });

    test('refreshBlockhashInUnsignedTransaction sends key "unsignedTxBase64"',
        () async {
      mockHandler = (_) => 'refreshedTx';
      await PollinetSdk.refreshBlockhashInUnsignedTransaction('oldTx');
      expect(lastCall!.method, 'refreshBlockhashInUnsignedTransaction');
      expect(lastCall!.arguments['unsignedTxBase64'], 'oldTx');
    });

    test('getTransactionMessageToSign sends key "unsignedTransactionBase64"',
        () async {
      mockHandler = (_) => 'msgBytes64';
      await PollinetSdk.getTransactionMessageToSign('utx');
      expect(lastCall!.method, 'getTransactionMessageToSign');
      expect(lastCall!.arguments['unsignedTransactionBase64'], 'utx');
    });

    test('createUnsignedNonceTransactions sends correct keys', () async {
      mockHandler = (_) => [
        {
          'unsignedTransactionBase64': 'utx1',
          'nonceKeypairBase64': ['kp1'],
          'noncePubkey': ['np1'],
        }
      ];
      final txs = await PollinetSdk.createUnsignedNonceTransactions(
        count: 1,
        payerPubkey: 'pp',
      );
      expect(lastCall!.method, 'createUnsignedNonceTransactions');
      expect(lastCall!.arguments['count'], 1);
      expect(lastCall!.arguments['payerPubkey'], 'pp');
      expect(txs.length, 1);
      expect(txs[0].noncePubkey, ['np1']);
    });

    test('createUnsignedVote sends correct keys', () async {
      mockHandler = (_) => 'voteTx';
      await PollinetSdk.createUnsignedVote(
        voter: 'v',
        proposalId: 'p',
        voteAccount: 'va',
        choice: 1,
        feePayer: 'fp',
        nonceAccount: 'na',
      );
      expect(lastCall!.method, 'createUnsignedVote');
      expect(lastCall!.arguments['voter'], 'v');
      expect(lastCall!.arguments['proposalId'], 'p');
      expect(lastCall!.arguments['voteAccount'], 'va');
      expect(lastCall!.arguments['choice'], 1);
      expect(lastCall!.arguments['feePayer'], 'fp');
      expect(lastCall!.arguments['nonceAccount'], 'na');
    });
  });

  // =========================================================================
  // 9. BLE mesh operations
  // =========================================================================

  group('BLE mesh operations', () {
    test('fragmentTransaction sends key "transactionBytes"', () async {
      mockHandler = (_) => [
        {
          'transactionId': 'tid',
          'fragmentIndex': 0,
          'totalFragments': 1,
          'dataBase64': 'AQID',
        }
      ];
      final frags = await PollinetSdk.fragmentTransaction('txBytes64');
      expect(lastCall!.method, 'fragmentTransaction');
      expect(lastCall!.arguments['transactionBytes'], 'txBytes64');
      expect(frags.length, 1);
      expect(frags[0].transactionId, 'tid');
    });

    test('reconstructTransaction sends key "fragments"', () async {
      mockHandler = (_) => 'reconstructed64';
      final frag = FragmentData(
        transactionId: 'tid',
        fragmentIndex: 0,
        totalFragments: 1,
        dataBase64: 'AQID',
      );
      await PollinetSdk.reconstructTransaction([frag]);
      expect(lastCall!.method, 'reconstructTransaction');
      final sentFrags = lastCall!.arguments['fragments'] as List;
      expect(sentFrags.length, 1);
      expect((sentFrags[0] as Map)['transactionId'], 'tid');
    });

    test('getFragmentationStats sends key "transactionBytes"', () async {
      mockHandler = (_) => {
        'originalSize': 100,
        'fragmentCount': 2,
        'maxFragmentSize': 60,
        'avgFragmentSize': 50,
        'totalOverhead': 10,
        'efficiency': 0.9,
      };
      final stats = await PollinetSdk.getFragmentationStats('txBytes64');
      expect(lastCall!.method, 'getFragmentationStats');
      expect(lastCall!.arguments['transactionBytes'], 'txBytes64');
      expect(stats.fragmentCount, 2);
      expect(stats.efficiency, closeTo(0.9, 0.01));
    });

    test('prepareBroadcast sends key "transactionBytes" and deserializes',
        () async {
      mockHandler = (_) => {
        'transactionId': 'tid',
        'fragmentPackets': [
          {
            'transactionId': 'tid',
            'fragmentIndex': 0,
            'totalFragments': 1,
            'packetBytes': 'packet64',
          }
        ],
      };
      final prep = await PollinetSdk.prepareBroadcast('txBytes64');
      expect(lastCall!.method, 'prepareBroadcast');
      expect(lastCall!.arguments['transactionBytes'], 'txBytes64');
      expect(prep.transactionId, 'tid');
      expect(prep.fragmentPackets.length, 1);
    });
  });

  // =========================================================================
  // 10. Relay system
  // =========================================================================

  group('Relay system', () {
    test('pushReceivedTransaction sends key "transactionBytes"', () async {
      mockHandler = (_) => {'added': true, 'queueSize': 1};
      final resp =
          await PollinetSdk.pushReceivedTransaction('txBytes64');
      expect(lastCall!.method, 'pushReceivedTransaction');
      expect(lastCall!.arguments['transactionBytes'], 'txBytes64');
      expect(resp.added, true);
    });

    test('nextReceivedTransaction returns null on empty', () async {
      mockHandler = (_) => null;
      final tx = await PollinetSdk.nextReceivedTransaction();
      expect(lastCall!.method, 'nextReceivedTransaction');
      expect(tx, isNull);
    });

    test('getReceivedQueueSize sends correct method', () async {
      mockHandler = (_) => 3;
      final size = await PollinetSdk.getReceivedQueueSize();
      expect(lastCall!.method, 'getReceivedQueueSize');
      expect(size, 3);
    });

    test('markTransactionSubmitted sends key "transactionBytes"', () async {
      mockHandler = (_) => true;
      await PollinetSdk.markTransactionSubmitted('txBytes64');
      expect(lastCall!.method, 'markTransactionSubmitted');
      expect(lastCall!.arguments['transactionBytes'], 'txBytes64');
    });

    test('cleanupOldSubmissions sends correct method', () async {
      mockHandler = (_) => true;
      await PollinetSdk.cleanupOldSubmissions();
      expect(lastCall!.method, 'cleanupOldSubmissions');
    });

    test('debugOutboundQueue deserializes with camelCase key', () async {
      mockHandler = (_) => {
        'totalFragments': 5,
        'fragments': [
          {'index': 0, 'size': 100}
        ],
      };
      final debug = await PollinetSdk.debugOutboundQueue();
      expect(lastCall!.method, 'debugOutboundQueue');
      expect(debug.totalFragments, 5);
      expect(debug.fragments.length, 1);
    });
  });

  // =========================================================================
  // 11. Queue management
  // =========================================================================

  group('Queue management', () {
    test('pushOutboundTransaction sends correct keys', () async {
      mockHandler = (_) => true;
      await PollinetSdk.pushOutboundTransaction(
        txBytesBase64: 'tx64',
        txId: 'tid',
        fragments: [
          FragmentFFI(
            transactionId: 'tid',
            fragmentIndex: 0,
            totalFragments: 1,
            dataBase64: 'AQID',
          ),
        ],
        priority: Priority.high,
      );
      expect(lastCall!.method, 'pushOutboundTransaction');
      expect(lastCall!.arguments['txBytes'], 'tx64');
      expect(lastCall!.arguments['txId'], 'tid');
      expect(lastCall!.arguments['priority'], 'HIGH');
      final frags = lastCall!.arguments['fragments'] as List;
      expect((frags[0] as Map)['transactionId'], 'tid');
    });

    test('acceptAndQueueExternalTransaction sends correct keys', () async {
      mockHandler = (_) => 'txId';
      await PollinetSdk.acceptAndQueueExternalTransaction(
        base64SignedTx: 'stx64',
        maxPayload: 200,
      );
      expect(lastCall!.method, 'acceptAndQueueExternalTransaction');
      expect(lastCall!.arguments['base64SignedTx'], 'stx64');
      expect(lastCall!.arguments['maxPayload'], 200);
    });

    test('popOutboundTransaction returns null on empty', () async {
      mockHandler = (_) => null;
      final tx = await PollinetSdk.popOutboundTransaction();
      expect(lastCall!.method, 'popOutboundTransaction');
      expect(tx, isNull);
    });

    test('getOutboundQueueSize sends correct method', () async {
      mockHandler = (_) => 7;
      expect(await PollinetSdk.getOutboundQueueSize(), 7);
      expect(lastCall!.method, 'getOutboundQueueSize');
    });

    test('addToRetryQueue sends correct keys', () async {
      mockHandler = (_) => true;
      await PollinetSdk.addToRetryQueue(
        txBytesBase64: 'tx64',
        txId: 'tid',
        error: 'timeout',
      );
      expect(lastCall!.method, 'addToRetryQueue');
      expect(lastCall!.arguments['txBytes'], 'tx64');
      expect(lastCall!.arguments['txId'], 'tid');
      expect(lastCall!.arguments['error'], 'timeout');
    });

    test('popReadyRetry returns null on empty', () async {
      mockHandler = (_) => null;
      expect(await PollinetSdk.popReadyRetry(), isNull);
      expect(lastCall!.method, 'popReadyRetry');
    });

    test('getRetryQueueSize sends correct method', () async {
      mockHandler = (_) => 2;
      expect(await PollinetSdk.getRetryQueueSize(), 2);
      expect(lastCall!.method, 'getRetryQueueSize');
    });
  });

  // =========================================================================
  // 12. Confirmations — the format fix is critical here
  // =========================================================================

  group('Confirmations', () {
    test('queueConfirmation sends keys "txId" and "signature"', () async {
      mockHandler = (_) => true;
      await PollinetSdk.queueConfirmation(txId: 'tid', signature: 'sig');
      expect(lastCall!.method, 'queueConfirmation');
      expect(lastCall!.arguments['txId'], 'tid');
      expect(lastCall!.arguments['signature'], 'sig');
    });

    test('popConfirmation deserializes SUCCESS with {"SUCCESS": sig} format',
        () async {
      mockHandler = (_) => {
        'txId': 'tid',
        'status': {'SUCCESS': 'txSig123'},
        'timestamp': 1000,
        'relayCount': 2,
      };
      final conf = await PollinetSdk.popConfirmation();
      expect(lastCall!.method, 'popConfirmation');
      expect(conf, isNotNull);
      expect(conf!.txId, 'tid');
      expect(conf.statusType, ConfirmationStatusType.success);
      expect(conf.signature, 'txSig123');
      expect(conf.relayCount, 2);
    });

    test('popConfirmation deserializes FAILED with {"FAILED": err} format',
        () async {
      mockHandler = (_) => {
        'txId': 'tid',
        'status': {'FAILED': 'nonce expired'},
        'timestamp': 1000,
        'relayCount': 0,
      };
      final conf = await PollinetSdk.popConfirmation();
      expect(conf!.statusType, ConfirmationStatusType.failed);
      expect(conf.error, 'nonce expired');
    });

    test('getConfirmationQueueSize sends correct method', () async {
      mockHandler = (_) => 4;
      expect(await PollinetSdk.getConfirmationQueueSize(), 4);
      expect(lastCall!.method, 'getConfirmationQueueSize');
    });

    test('relayConfirmation sends nested "confirmation" map with correct status format',
        () async {
      mockHandler = (_) => true;
      final conf = Confirmation(
        txId: 'tid',
        statusType: ConfirmationStatusType.success,
        signature: 'sig123',
        timestamp: 1000,
        relayCount: 1,
      );
      await PollinetSdk.relayConfirmation(conf);
      expect(lastCall!.method, 'relayConfirmation');
      final confMap = lastCall!.arguments['confirmation'] as Map;
      expect(confMap['txId'], 'tid');
      expect(confMap['timestamp'], 1000);
      expect(confMap['relayCount'], 1);
      final status = confMap['status'] as Map;
      expect(status.containsKey('SUCCESS'), isTrue,
          reason: 'Status must use {"SUCCESS": sig} format, not {"type": "SUCCESS"}');
      expect(status['SUCCESS'], 'sig123');
    });
  });

  // =========================================================================
  // 13. Queue metrics and cleanup
  // =========================================================================

  group('Queue metrics and cleanup', () {
    test('getQueueMetrics deserializes all fields', () async {
      mockHandler = (_) => {
        'outboundSize': 10,
        'outboundHighPriority': 3,
        'outboundNormalPriority': 5,
        'outboundLowPriority': 2,
        'confirmationSize': 1,
        'retrySize': 0,
        'retryAvgAttempts': 1.5,
      };
      final m = await PollinetSdk.getQueueMetrics();
      expect(lastCall!.method, 'getQueueMetrics');
      expect(m.outboundSize, 10);
      expect(m.outboundHighPriority, 3);
      expect(m.retryAvgAttempts, closeTo(1.5, 0.01));
    });

    test('cleanupStaleFragments sends correct method', () async {
      mockHandler = (_) => 5;
      expect(await PollinetSdk.cleanupStaleFragments(), 5);
      expect(lastCall!.method, 'cleanupStaleFragments');
    });

    test('cleanupExpired deserializes with camelCase keys', () async {
      mockHandler = (_) => {
        'confirmationsCleaned': 2,
        'retriesCleaned': 1,
      };
      final resp = await PollinetSdk.cleanupExpired();
      expect(lastCall!.method, 'cleanupExpired');
      expect(resp.confirmationsCleaned, 2);
      expect(resp.retriesCleaned, 1);
    });
  });

  // =========================================================================
  // 14. Queue persistence
  // =========================================================================

  group('Queue persistence', () {
    test('saveQueues sends correct method', () async {
      mockHandler = (_) => true;
      expect(await PollinetSdk.saveQueues(), true);
      expect(lastCall!.method, 'saveQueues');
    });

    test('autoSaveQueues sends correct method', () async {
      mockHandler = (_) => true;
      expect(await PollinetSdk.autoSaveQueues(), true);
      expect(lastCall!.method, 'autoSaveQueues');
    });

    test('clearAllQueues sends correct method', () async {
      mockHandler = (_) => true;
      expect(await PollinetSdk.clearAllQueues(), true);
      expect(lastCall!.method, 'clearAllQueues');
    });
  });

  // =========================================================================
  // 15. Error handling — SDK errors become PollinetException
  // =========================================================================

  group('Error handling', () {
    test('PlatformException wraps into PollinetException', () async {
      setUpSdkError('NOT_INITIALIZED', 'SDK must be initialized');
      expect(
        () => PollinetSdk.tick(),
        throwsA(isA<PollinetException>().having(
          (e) => e.code,
          'code',
          'NOT_INITIALIZED',
        )),
      );
    });

    test('BUNDLE_ERROR from prepareOfflineBundle', () async {
      setUpSdkError('BUNDLE_ERROR', 'Failed to prepare offline bundle');
      expect(
        () => PollinetSdk.prepareOfflineBundle(
          count: 1,
          senderKeypairBase64: 'kp',
        ),
        throwsA(isA<PollinetException>().having(
          (e) => e.code,
          'code',
          'BUNDLE_ERROR',
        )),
      );
    });

    test('TX_ERROR from createOfflineTransaction', () async {
      setUpSdkError('TX_ERROR', 'Failed to create offline transaction');
      expect(
        () => PollinetSdk.createOfflineTransaction(
          senderKeypairBase64: 'skp',
          nonceAuthorityKeypairBase64: 'nakp',
          recipient: 'r',
          amount: 1000,
        ),
        throwsA(isA<PollinetException>().having(
          (e) => e.code,
          'code',
          'TX_ERROR',
        )),
      );
    });
  });

  // =========================================================================
  // 16. getFragmentReassemblyInfo
  // =========================================================================

  group('Fragment reassembly', () {
    test('getFragmentReassemblyInfo deserializes correctly', () async {
      mockHandler = (_) => {
        'transactions': [
          {
            'transactionId': 'tid',
            'totalFragments': 4,
            'receivedFragments': 2,
            'receivedIndices': [0, 1],
            'fragmentSizes': [100, 100],
            'totalBytesReceived': 200,
          }
        ],
      };
      final info = await PollinetSdk.getFragmentReassemblyInfo();
      expect(lastCall!.method, 'getFragmentReassemblyInfo');
      expect(info.transactions.length, 1);
      expect(info.transactions[0].totalFragments, 4);
      expect(info.transactions[0].receivedIndices, [0, 1]);
    });
  });
}
