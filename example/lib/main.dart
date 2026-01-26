import 'dart:async';
import 'package:flutter/material.dart';
import 'package:pollinet_flutter/pollinet_sdk.dart';
import 'package:logger/logger.dart';

// Global logger instance
final logger = Logger(
  printer: PrettyPrinter(
    methodCount: 2,
    errorMethodCount: 8,
    lineLength: 120,
    colors: true,
    printEmojis: true,
    dateTimeFormat: DateTimeFormat.onlyTimeAndSinceStart,
  ),
);

void main() {
  logger.i('🚀 Starting Pollinet Example App');
  runApp(const PollinetExampleApp());
}

class PollinetExampleApp extends StatelessWidget {
  const PollinetExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Pollinet SDK Example',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const PollinetExamplePage(),
    );
  }
}

class PollinetExamplePage extends StatefulWidget {
  const PollinetExamplePage({super.key});

  @override
  State<PollinetExamplePage> createState() => _PollinetExamplePageState();
}

class _PollinetExamplePageState extends State<PollinetExamplePage> {
  String _status = 'Ready';
  bool _isInitialized = false;
  bool _permissionsGranted = false;
  String _sdkVersion = 'Unknown';
  Map<String, String> _permissionStatus = {};
  MetricsSnapshot? _metrics;
  bool? _batteryOptimizationExempted;
  Timer? _tickTimer;

  @override
  void initState() {
    super.initState();
    logger.i('📱 Page initialized - checking permissions and battery optimization');
    _checkPermissions();
    _checkBatteryOptimization();
  }

  @override
  void dispose() {
    logger.i('🔚 Disposing page - cancelling tick timer');
    _tickTimer?.cancel();
    super.dispose();
  }

  void _startBleProtocol() {
    // Start periodic tick every 1 second for BLE protocol processing
    logger.d('🔄 Starting BLE protocol tick timer');
    _tickTimer?.cancel();
    _tickTimer = Timer.periodic(const Duration(seconds: 1), (timer) async {
      if (_isInitialized) {
        try {
          logger.t('⏱️ Executing BLE tick');
          final completedTxIds = await PollinetSdk.tick();
          if (completedTxIds.isNotEmpty) {
            logger.i('✅ BLE Protocol Tick: ${completedTxIds.length} transactions completed');
            for (final txId in completedTxIds) {
              logger.i('  📦 Completed TX: $txId');
            }
            // Refresh metrics after completion
            _getMetrics();
          }
        } catch (e) {
          logger.e('⚠️ BLE tick error', error: e);
        }
      }
    });
    logger.i('🔄 BLE protocol tick started (1s interval)');
  }

  void _stopBleProtocol() {
    logger.i('⏹️ Stopping BLE protocol tick');
    _tickTimer?.cancel();
    _tickTimer = null;
    logger.d('⏹️ BLE protocol tick stopped');
  }

  Future<void> _checkPermissions() async {
    logger.d('🔍 Checking BLE permissions');
    try {
      final status = await PollinetSdk.checkPermissions();
      logger.i('✅ Permission check completed', error: status);
      setState(() {
        _permissionStatus = status;
        _permissionsGranted = status.values.every((s) => s == 'granted');
      });
      logger.d('Permissions granted: $_permissionsGranted');
    } catch (e) {
      logger.e('❌ Error checking permissions', error: e);
      setState(() {
        _status = 'Error checking permissions: $e';
      });
    }
  }

  Future<void> _requestPermissions() async {
    logger.i('📋 Requesting BLE permissions from user');
    try {
      setState(() => _status = 'Requesting permissions...');
      final result = await PollinetSdk.requestPermissions();
      logger.i('✅ Permission request completed', error: result);

      setState(() {
        _permissionStatus = result;
        _permissionsGranted = result.values.every((s) => s == 'granted');
        _status = _permissionsGranted
            ? 'Permissions granted'
            : 'Some permissions denied';

        logger.i('Status: $_status');
      });
    } catch (e) {
      logger.e('❌ Error requesting permissions', error: e);
      setState(() {
        _status = 'Error requesting permissions: $e';
      });
    }
  }

  Future<void> _initializeSdk() async {
    if (!_permissionsGranted) {
      logger.w('⚠️ Cannot initialize SDK - permissions not granted');
      setState(() => _status = 'Please grant permissions first');
      return;
    }

    try {
      setState(() => _status = 'Initializing SDK...');
      logger.i('🚀 Starting Pollinet SDK initialization with RPC...');

      // Initialize SDK with Solana RPC for transaction creation
      final config = SdkConfig(
        rpcUrl: 'https://api.devnet.solana.com', // Solana devnet RPC
        enableLogging: true,
      );
      logger.i('🌐 Configuring SDK with RPC: ${config.rpcUrl}');
      
      final success = await PollinetSdk.initialize(config: config);
      logger.d('SDK initialize() returned: $success');

      if (success) {
        logger.i('✅ SDK initialized successfully, fetching version...');
        try {
          final version = await PollinetSdk.version();
          logger.i('📌 SDK Version: $version');
          
          // Use a small delay to ensure state updates properly
          await Future.delayed(const Duration(milliseconds: 100));
          
          if (mounted) {
            setState(() {
              _isInitialized = true;
              _sdkVersion = version;
              _status = 'SDK Initialized (v$version) - BLE Active';
            });
            logger.i('✅ State updated - SDK is now active');
            // Start BLE protocol tick for scanning/advertising
            _startBleProtocol();
          } else {
            logger.w('⚠️ Widget not mounted, cannot update state');
          }
        } catch (e) {
          logger.e('❌ Error fetching SDK version', error: e);
          // Still mark as initialized even if version fails
          if (mounted) {
            setState(() {
              _isInitialized = true;
              _sdkVersion = 'Unknown';
              _status = 'SDK Initialized (version check failed)';
            });
          }
        }
      } else {
        logger.e('❌ SDK initialization returned false');
        setState(() => _status = 'Initialization failed');
      }
    } on PollinetException catch (e) {
      logger.e('❌ PollinetException during initialization', error: '${e.code} - ${e.message}');
      setState(() => _status = 'Error: ${e.message}');
    } catch (e, stackTrace) {
      logger.e('❌ Unexpected error during SDK initialization', error: e, stackTrace: stackTrace);
      setState(() => _status = 'Unexpected error: $e');
    }
  }

  Future<void> _getMetrics() async {
    if (!_isInitialized) {
      logger.w('⚠️ Cannot fetch metrics - SDK not initialized');
      setState(() => _status = 'SDK not initialized');
      return;
    }

    try {
      logger.d('📊 Fetching SDK metrics...');
      setState(() => _status = 'Fetching metrics...');
      final metrics = await PollinetSdk.metrics();
      logger.i('✅ Metrics fetched successfully', error: {
        'fragmentsBuffered': metrics.fragmentsBuffered,
        'transactionsComplete': metrics.transactionsComplete,
        'reassemblyFailures': metrics.reassemblyFailures,
        'lastError': metrics.lastError,
        'updatedAt': metrics.updatedAt,
      });
      setState(() {
        _metrics = metrics;
        _status = 'Metrics updated';
      });
    } on PollinetException catch (e) {
      logger.e('❌ PollinetException fetching metrics', error: '${e.code} - ${e.message}');
      setState(() => _status = 'Error: ${e.message}');
    } catch (e) {
      logger.e('❌ Unexpected error fetching metrics', error: e);
      setState(() => _status = 'Unexpected error: $e');
    }
  }

  Future<void> _shutdownSdk() async {
    if (!_isInitialized) {
      logger.w('⚠️ SDK already shutdown or not initialized');
      return;
    }

    try {
      logger.i('🛑 Shutting down Pollinet SDK...');
      setState(() => _status = 'Shutting down...');
      // Stop BLE protocol tick
      _stopBleProtocol();
      await PollinetSdk.shutdown();
      logger.i('✅ SDK shutdown complete');
      setState(() {
        _isInitialized = false;
        _sdkVersion = 'Unknown';
        _metrics = null;
        _status = 'SDK Shutdown';
      });
    } on PollinetException catch (e) {
      logger.e('❌ PollinetException during shutdown', error: '${e.code} - ${e.message}');
      setState(() => _status = 'Error: ${e.message}');
    } catch (e) {
      logger.e('❌ Unexpected error during shutdown', error: e);
      setState(() => _status = 'Unexpected error: $e');
    }
  }

  Future<void> _checkBatteryOptimization() async {
    logger.d('🔋 Checking battery optimization status');
    try {
      final exempted = await PollinetSdk.checkBatteryOptimization();
      logger.i('Battery optimization exempted: $exempted');
      setState(() {
        _batteryOptimizationExempted = exempted;
      });
    } catch (e) {
      logger.e('❌ Error checking battery optimization', error: e);
      // Silently fail - battery optimization check is optional
    }
  }

  Future<void> _requestBatteryOptimization() async {
    logger.i('🔋 Requesting battery optimization exemption');
    try {
      setState(() => _status = 'Opening battery settings...');
      await PollinetSdk.requestBatteryOptimization();
      logger.i('✅ Battery optimization request sent to system');
      // Check status after a delay to allow user to grant it
      Future.delayed(const Duration(seconds: 2), () {
        _checkBatteryOptimization();
      });
    } on PollinetException catch (e) {
      logger.e('❌ PollinetException requesting battery optimization', error: '${e.code} - ${e.message}');
      setState(() => _status = 'Error: ${e.message}');
    } catch (e) {
      logger.e('❌ Unexpected error requesting battery optimization', error: e);
      setState(() => _status = 'Unexpected error: $e');
    }
  }

  Future<void> _checkMetricsAndPrint() async {
    if (!_isInitialized) {
      logger.w('⚠️ Cannot fetch metrics - SDK not initialized');
      setState(() => _status = 'SDK not initialized');
      return;
    }

    try {
      logger.i('📊 Fetching and printing metrics...');
      setState(() => _status = 'Fetching metrics...');
      final metrics = await PollinetSdk.metrics();
      
      // Log metrics using logger
      logger.i('=== Pollinet Metrics ===');
      logger.i('Fragments Buffered: ${metrics.fragmentsBuffered}');
      logger.i('Transactions Complete: ${metrics.transactionsComplete}');
      logger.i('Reassembly Failures: ${metrics.reassemblyFailures}');
      logger.i('Last Error: ${metrics.lastError}');
      logger.i('Updated At: ${metrics.updatedAt}');
      logger.i('========================');
      
      setState(() {
        _metrics = metrics;
        _status = 'Metrics printed to console';
      });
    } on PollinetException catch (e) {
      logger.e('❌ PollinetException getting metrics', error: '${e.code} - ${e.message}');
      setState(() => _status = 'Error: ${e.message}');
    } catch (e) {
      logger.e('❌ Unexpected error getting metrics', error: e);
      setState(() => _status = 'Unexpected error: $e');
    }
  }

  Future<void> _createUnsignedTransactionAndPrint() async {
    if (!_isInitialized) {
      logger.w('⚠️ Cannot create transaction - SDK not initialized');
      setState(() => _status = 'SDK not initialized');
      return;
    }

    try {
      logger.i('📝 Creating unsigned transaction...');
      setState(() => _status = 'Creating unsigned transaction...');
      
      const sender = 'RtsKQm3gAGL1Tayhs7ojWE9qytWqVh4G7eJTaNJs7vX';
      const recipient = 'AtHGwWe2cZQ1WbsPVHFsCm4FqUDW8pcPLYXWsA89iuDE';
      const feePayer = 'RtsKQm3gAGL1Tayhs7ojWE9qytWqVh4G7eJTaNJs7vX';
      const amount = 1000000; // 0.001 SOL in lamports
      
      logger.i('📝 Creating transaction without nonce (using recent blockhash)');
      logger.d('Transaction params: sender=$sender, recipient=$recipient, amount=$amount');
      
      // Try creating without nonce account - SDK will use recent blockhash
      final unsignedTx = await PollinetSdk.createUnsignedTransaction(
        sender: sender,
        recipient: recipient,
        feePayer: feePayer,
        amount: amount,
        // Omit nonceAccount to use recent blockhash instead
      );
      
      // Log transaction using logger
      logger.i('=== Unsigned Transaction ===');
      logger.i('Sender: $sender');
      logger.i('Recipient: $recipient');
      logger.i('Fee Payer: $feePayer');
      logger.i('Amount: $amount lamports (${amount / 1000000000} SOL)');
      logger.i('Method: Recent Blockhash (no nonce)');
      logger.i('Transaction (Base64):');
      logger.i(unsignedTx);
      logger.i('============================');
      
      setState(() => _status = 'Unsigned transaction printed to console');
    } on PollinetException catch (e) {
      logger.e('❌ PollinetException creating transaction', error: '${e.code} - ${e.message}');
      setState(() => _status = 'Error: ${e.message}');
    } catch (e) {
      logger.e('❌ Unexpected error creating transaction', error: e);
      setState(() => _status = 'Unexpected error: $e');
    }
  }

  Future<void> _createUnsignedNonceAccountAndPrint() async {
    if (!_isInitialized) {
      logger.w('⚠️ Cannot create nonce account - SDK not initialized');
      setState(() => _status = 'SDK not initialized');
      return;
    }

    try {
      logger.i('🔑 Creating unsigned nonce account transactions...');
      setState(() => _status = 'Creating nonce account...');
      
      const payerPubkey = 'RtsKQm3gAGL1Tayhs7ojWE9qytWqVh4G7eJTaNJs7vX';
      const count = 1; // Create 1 nonce account
      
      logger.d('Nonce account params: payer=$payerPubkey, count=$count');
      
      final nonceTransactions = await PollinetSdk.createUnsignedNonceTransactions(
        count: count,
        payerPubkey: payerPubkey,
      );
      
      // Log nonce transactions using logger
      logger.i('=== Unsigned Nonce Account Transactions ===');
      logger.i('Number of nonce accounts: ${nonceTransactions.length}');
      
      for (var i = 0; i < nonceTransactions.length; i++) {
        final nonceTx = nonceTransactions[i];
        logger.i('--- Nonce Account #${i + 1} ---');
        logger.i('Nonce Pubkey: ${nonceTx.noncePubkey.join(", ")}');
        logger.i('Nonce Keypair (Base64): ${nonceTx.nonceKeypairBase64.join(", ")}');
        logger.i('Unsigned Transaction (Base64):');
        logger.i(nonceTx.unsignedTransactionBase64);
        logger.i('---');
      }
      logger.i('==========================================');
      
      setState(() => _status = 'Created ${nonceTransactions.length} nonce account transaction(s)');
    } on PollinetException catch (e) {
      logger.e('❌ PollinetException creating nonce account', error: '${e.code} - ${e.message}');
      setState(() => _status = 'Error: ${e.message}');
    } catch (e) {
      logger.e('❌ Unexpected error creating nonce account', error: e);
      setState(() => _status = 'Unexpected error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Pollinet SDK Example'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status Card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Status',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    const SizedBox(height: 8),
                    Text(_status, style: Theme.of(context).textTheme.bodyLarge),
                    if (_isInitialized) ...[
                      const SizedBox(height: 8),
                      Text(
                        'SDK Version: $_sdkVersion',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Permissions Section
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Permissions',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        Icon(
                          _permissionsGranted
                              ? Icons.check_circle
                              : Icons.error,
                          color: _permissionsGranted
                              ? Colors.green
                              : Colors.orange,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    if (_permissionStatus.isNotEmpty) ...[
                      ..._permissionStatus.entries.map(
                        (entry) => Padding(
                          padding: const EdgeInsets.symmetric(vertical: 4.0),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Expanded(
                                child: Text(
                                  entry.key.split('.').last,
                                  style: Theme.of(context).textTheme.bodyMedium,
                                ),
                              ),
                              Chip(
                                label: Text(entry.value),
                                backgroundColor: entry.value == 'granted'
                                    ? Colors.green.withOpacity(0.2)
                                    : Colors.red.withOpacity(0.2),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ] else
                      Text(
                        'No permission data',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: ElevatedButton.icon(
                            onPressed: _checkPermissions,
                            icon: const Icon(Icons.refresh),
                            label: const Text('Check'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: ElevatedButton.icon(
                            onPressed: _permissionsGranted
                                ? null
                                : _requestPermissions,
                            icon: const Icon(Icons.security),
                            label: const Text('Request'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Battery Optimization Section
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Battery Optimization',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        if (_batteryOptimizationExempted != null)
                          Icon(
                            _batteryOptimizationExempted!
                                ? Icons.check_circle
                                : Icons.warning,
                            color: _batteryOptimizationExempted!
                                ? Colors.green
                                : Colors.orange,
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    if (_batteryOptimizationExempted != null)
                      Text(
                        _batteryOptimizationExempted!
                            ? 'Exempted from battery optimization'
                            : 'Not exempted - BLE may be limited in background',
                        style: Theme.of(context).textTheme.bodyMedium,
                      )
                    else
                      Text(
                        'Checking status...',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: ElevatedButton.icon(
                            onPressed: _checkBatteryOptimization,
                            icon: const Icon(Icons.refresh),
                            label: const Text('Check Status'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: ElevatedButton.icon(
                            onPressed: _requestBatteryOptimization,
                            icon: const Icon(Icons.battery_saver),
                            label: const Text('Request Exemption'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // SDK Actions
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'SDK Actions',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    const SizedBox(height: 16),
                    if (!_isInitialized)
                      ElevatedButton.icon(
                        onPressed: _permissionsGranted ? _initializeSdk : null,
                        icon: const Icon(Icons.play_arrow),
                        label: Text(_status == 'Initializing SDK...' 
                            ? 'Initializing...' 
                            : 'Initialize SDK'),
                        style: ElevatedButton.styleFrom(
                          minimumSize: const Size(double.infinity, 48),
                        ),
                      )
                    else ...[
                      ElevatedButton.icon(
                        onPressed: _getMetrics,
                        icon: const Icon(Icons.analytics),
                        label: const Text('Get Metrics'),
                        style: ElevatedButton.styleFrom(
                          minimumSize: const Size(double.infinity, 48),
                        ),
                      ),
                      const SizedBox(height: 8),
                      ElevatedButton.icon(
                        onPressed: _checkMetricsAndPrint,
                        icon: const Icon(Icons.print),
                        label: const Text('Check Metrics & Print'),
                        style: ElevatedButton.styleFrom(
                          minimumSize: const Size(double.infinity, 48),
                        ),
                      ),
                      const SizedBox(height: 8),
                      ElevatedButton.icon(
                        onPressed: _createUnsignedTransactionAndPrint,
                        icon: const Icon(Icons.send),
                        label: const Text('Create Unsigned TX & Print'),
                        style: ElevatedButton.styleFrom(
                          minimumSize: const Size(double.infinity, 48),
                        ),
                      ),
                      const SizedBox(height: 8),
                      ElevatedButton.icon(
                        onPressed: _createUnsignedNonceAccountAndPrint,
                        icon: const Icon(Icons.key),
                        label: const Text('Create Nonce Account & Print'),
                        style: ElevatedButton.styleFrom(
                          minimumSize: const Size(double.infinity, 48),
                        ),
                      ),
                      const SizedBox(height: 8),
                      ElevatedButton.icon(
                        onPressed: _shutdownSdk,
                        icon: const Icon(Icons.stop),
                        label: const Text('Shutdown SDK'),
                        style: ElevatedButton.styleFrom(
                          minimumSize: const Size(double.infinity, 48),
                          backgroundColor: Colors.red,
                          foregroundColor: Colors.white,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),

            // Metrics Display
            if (_metrics != null) ...[
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Transport Metrics',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 16),
                      _buildMetricRow(
                        context,
                        'Fragments Buffered',
                        _metrics!.fragmentsBuffered.toString(),
                      ),
                      _buildMetricRow(
                        context,
                        'Transactions Complete',
                        _metrics!.transactionsComplete.toString(),
                      ),
                      _buildMetricRow(
                        context,
                        'Reassembly Failures',
                        _metrics!.reassemblyFailures.toString(),
                      ),
                      if (_metrics!.lastError.isNotEmpty) ...[
                        const SizedBox(height: 8),
                        Text(
                          'Last Error:',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        Text(
                          _metrics!.lastError,
                          style: Theme.of(
                            context,
                          ).textTheme.bodySmall?.copyWith(color: Colors.red),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildMetricRow(BuildContext context, String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: Theme.of(context).textTheme.bodyMedium),
          Text(
            value,
            style: Theme.of(
              context,
            ).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.bold),
          ),
        ],
      ),
    );
  }
}
