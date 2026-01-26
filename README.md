# Pollinet Flutter SDK

A Flutter package that provides access to the Pollinet Android SDK for offline Solana transaction propagation over Bluetooth Low Energy (BLE) mesh networks.

## Overview

Pollinet enables Solana transactions to be broadcast and relayed through a mesh network of Android devices using Bluetooth Low Energy, allowing transactions to reach the blockchain even when devices are offline or have limited connectivity. This SDK provides a Flutter interface to the native Android Pollinet SDK.

---

## 🚀 Quick Integration Checklist

Before you start, make sure you have:

- [ ] **Flutter 3.0.0+** installed
- [ ] **Android device** with BLE support (API 29+ / Android 10+)
- [ ] **5 minutes** for integration

### Integration Steps (5 Steps):

1. ✅ Add dependency to `pubspec.yaml`
2. ✅ Set `minSdk = 29` in `android/app/build.gradle.kts`
3. ✅ Request BLE permissions at runtime (Android 12+)
4. ✅ Initialize SDK with RPC URL
5. ✅ Start periodic `tick()` (1-second Timer) - **CRITICAL!**

**Time to integrate:** ~5 minutes  
**Lines of integration code:** ~50 lines

---

## ⚡ Key Requirements

| Requirement | Details | Why? |
|------------|---------|------|
| **minSdk 29** | Android 10+ | Native SDK requirement |
| **RPC URL** | Solana RPC endpoint | Transaction creation needs blockchain data |
| **BLE Tick** | `tick()` every 1 second | Drives BLE scanning/advertising |
| **Permissions** | Runtime BLE permissions | Android 12+ security |

⚠️ **Most Common Mistake**: Forgetting to call `tick()` periodically → No BLE activity!

---

## Features

### 🔐 Permission Management
- **Android 12+ Support**: Automatic handling of runtime permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`)
- **Backward Compatibility**: Support for Android 11 and below with legacy permissions
- **Permission Status Checking**: Check permission status before SDK operations

### 💸 Transaction Creation
- **SOL Transfers**: Create unsigned SOL transfer transactions
- **SPL Token Transfers**: Create unsigned SPL token transfer transactions
- **Governance Voting**: Create unsigned governance vote transactions
- **Nonce Account Management**: Create and manage nonce accounts for offline transactions

### 📡 BLE Mesh Transport
- **Transaction Fragmentation**: Automatically fragment large transactions for BLE transmission
- **Fragment Reassembly**: Reconstruct transactions from received fragments
- **Outbound Queue Management**: Queue transactions for BLE broadcast
- **Inbound Data Handling**: Process received transaction data from BLE mesh

### 🔄 Offline Transaction Support
- **Offline Bundle Preparation**: Prepare bundles of nonce accounts for offline use
- **Offline Transaction Creation**: Create transactions without internet connectivity
- **Nonce Caching**: Cache nonce account data for offline transaction creation
- **Bundle Refresh**: Refresh cached nonce data when online

### 🔑 Mobile Wallet Adapter (MWA) Support
- **Unsigned Transaction Creation**: Create unsigned transactions for external wallet signing
- **Required Signers**: Get list of public keys that need to sign transactions
- **Signature Application**: Apply signatures from external wallets
- **Transaction Verification**: Verify and serialize signed transactions

### 📊 Monitoring & Metrics
- **Transport Metrics**: Monitor fragments buffered, transactions completed, and errors
- **Queue Statistics**: Get information about outbound and received transaction queues
- **Fragment Reassembly Info**: Track incomplete transaction reassembly
- **Debug Tools**: Debug queue states and transaction processing

### 🛠️ Advanced Features
- **Transaction Broadcasting**: Prepare transactions for BLE mesh broadcast
- **Auto-Submission Queue**: Automatically submit received transactions
- **Queue Persistence**: Save and restore transaction queues
- **Cleanup Utilities**: Clean up stale fragments and expired confirmations

## Getting Started

### Prerequisites

- **Flutter SDK**: 3.0.0 or higher
- **Android**: Minimum API level 29 (Android 10.0)
- **Hardware**: Android device with Bluetooth Low Energy support
- **Permissions**: Android 12+ (API 31+) requires runtime permissions

### Installation

#### Step 1: Add Dependency

Add the package to your `pubspec.yaml`:

```yaml
dependencies:
  pollinet_flutter:
    path: ./pollinet-flutter  # Local path
    # OR from git:
    # git:
    #   url: https://github.com/pollinet/pollinet-flutter.git
    #   ref: main
```

#### Step 2: Install Dependencies

```bash
flutter pub get
```

#### Step 3: Configure Android Build (REQUIRED)

Update your app's `android/app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        minSdk = 29  // REQUIRED: Pollinet SDK requires Android 10+
        // ... other config
    }
}
```

#### Step 4: Permissions Setup

**Good News!** Permissions are automatically merged from the plugin's manifest. You don't need to manually add them to your app's manifest.

The plugin declares:
```xml
<!-- Android 12+ (API 31+) BLE Permissions -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Android 11 and below -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- General -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- BLE Hardware Feature -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

---

## Quick Start Integration Guide

### Complete Integration in 5 Steps

#### Step 1: Import the SDK

```dart
import 'package:pollinet_flutter/pollinet_sdk.dart';
import 'dart:async'; // For Timer
```

#### Step 2: Check and Request Permissions

```dart
Future<bool> _setupPermissions() async {
  // Check current permission status
  final status = await PollinetSdk.checkPermissions();
  print('Current permissions: $status');
  
  // Check if all permissions are granted
  final allGranted = status.values.every((s) => s == 'granted');
  
  if (!allGranted) {
    // Request permissions - shows system dialog
    final result = await PollinetSdk.requestPermissions();
    
    // Verify permissions were granted
    return result.values.every((s) => s == 'granted');
  }
  
  return true;
}
```

#### Step 3: Initialize SDK with RPC

**IMPORTANT**: Initialize with an RPC URL to enable transaction creation:

```dart
Future<bool> _initializeSDK() async {
  try {
    // Configure SDK with Solana RPC
    final config = SdkConfig(
      rpcUrl: 'https://api.devnet.solana.com',  // or mainnet-beta
      enableLogging: true,
      logLevel: 'info',
    );
    
    // Initialize
    final success = await PollinetSdk.initialize(config: config);
    
    if (success) {
      final version = await PollinetSdk.version();
      print('✅ Pollinet SDK v$version initialized');
      return true;
    }
    
    return false;
  } on PollinetException catch (e) {
    print('❌ SDK initialization failed: ${e.message}');
    return false;
  }
}
```

#### Step 4: Start BLE Protocol Tick (REQUIRED)

**CRITICAL**: The BLE protocol requires a periodic tick to process scanning/advertising:

```dart
Timer? _bleTickTimer;

void _startBleProtocol() {
  // Call tick() every 1 second to drive the BLE protocol
  _bleTickTimer = Timer.periodic(
    const Duration(seconds: 1),
    (timer) async {
      try {
        final completedTxIds = await PollinetSdk.tick();
        if (completedTxIds.isNotEmpty) {
          print('Completed transactions: $completedTxIds');
        }
      } catch (e) {
        print('Tick error: $e');
      }
    },
  );
  
  print('✅ BLE protocol tick started');
}

void _stopBleProtocol() {
  _bleTickTimer?.cancel();
  _bleTickTimer = null;
  print('🛑 BLE protocol tick stopped');
}

@override
void dispose() {
  _stopBleProtocol();
  super.dispose();
}
```

#### Step 5: Complete Initialization Flow

```dart
class MyApp extends StatefulWidget {
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  bool _isInitialized = false;
  bool _permissionsGranted = false;
  Timer? _bleTickTimer;
  
  @override
  void initState() {
    super.initState();
    _setup();
  }
  
  @override
  void dispose() {
    _bleTickTimer?.cancel();
    super.dispose();
  }
  
  Future<void> _setup() async {
    // 1. Check/request permissions
    _permissionsGranted = await _setupPermissions();
    if (!_permissionsGranted) {
      print('❌ Permissions not granted');
      return;
    }
    
    // 2. Initialize SDK
    _isInitialized = await _initializeSDK();
    if (!_isInitialized) {
      print('❌ SDK initialization failed');
      return;
    }
    
    // 3. Start BLE protocol tick
    _startBleProtocol();
    
    setState(() {});
    print('✅ Pollinet SDK ready!');
  }
  
  void _startBleProtocol() {
    _bleTickTimer = Timer.periodic(
      const Duration(seconds: 1),
      (timer) async {
        try {
          await PollinetSdk.tick();
        } catch (e) {
          print('Tick error: $e');
        }
      },
    );
  }
  
  // ... rest of your app
}
```

---

## Usage Examples

### Example 1: Check SDK Metrics

```dart
Future<void> _getMetrics() async {
  try {
    final metrics = await PollinetSdk.metrics();
    
    print('📊 SDK Metrics:');
    print('  Fragments buffered: ${metrics.fragmentsBuffered}');
    print('  Transactions complete: ${metrics.transactionsComplete}');
    print('  Reassembly failures: ${metrics.reassemblyFailures}');
    print('  Last error: ${metrics.lastError ?? "None"}');
    print('  Updated at: ${metrics.updatedAt}');
  } on PollinetException catch (e) {
    print('❌ Failed to get metrics: ${e.message}');
  }
}
```

### Example 2: Create Unsigned Transaction

```dart
Future<void> _createTransaction() async {
  try {
    final unsignedTx = await PollinetSdk.createUnsignedTransaction(
      request: TransactionRequest(
        sender: 'SenderPublicKeyBase58',
        recipient: 'RecipientPublicKeyBase58',
        feePayer: 'FeePayerPublicKeyBase58',
        amount: 1000000, // 0.001 SOL in lamports
      ),
    );
    
    print('✅ Unsigned transaction created:');
    print(unsignedTx);
    
    // Transaction is now ready for signing via Mobile Wallet Adapter
    // or other signing mechanism
  } on PollinetException catch (e) {
    print('❌ Transaction creation failed: ${e.message}');
  }
}
```

### Example 3: Create Nonce Accounts (Durable Transactions)

```dart
Future<void> _createNonceAccounts() async {
  try {
    final nonceTransactions = await PollinetSdk.createUnsignedNonceTransactions(
      count: 5,  // Create 5 nonce accounts
      payerPubkey: 'PayerPublicKeyBase58',
    );
    
    print('✅ Created ${nonceTransactions.length} nonce account transactions');
    
    for (var i = 0; i < nonceTransactions.length; i++) {
      final nonceTx = nonceTransactions[i];
      print('\nNonce Account #${i + 1}:');
      print('  Pubkey: ${nonceTx.noncePubkey}');
      print('  Keypair: ${nonceTx.nonceKeypairBase64}');
      print('  Unsigned TX: ${nonceTx.unsignedTransactionBase64.substring(0, 50)}...');
    }
  } on PollinetException catch (e) {
    print('❌ Nonce creation failed: ${e.message}');
  }
}
```

### Example 4: Battery Optimization (Background BLE)

```dart
Future<void> _requestBatteryExemption() async {
  // Check if already exempted
  final isExempted = await PollinetSdk.checkBatteryOptimization();
  
  if (!isExempted) {
    print('⚡ Requesting battery optimization exemption...');
    
    // Request exemption - opens system settings
    final granted = await PollinetSdk.requestBatteryOptimization();
    
    if (granted) {
      print('✅ Battery optimization exemption granted');
    } else {
      print('❌ Battery optimization exemption denied');
    }
  } else {
    print('✅ Already exempted from battery optimization');
  }
}
```

### Example 5: Clean Shutdown

```dart
Future<void> _shutdownSDK() async {
  try {
    // Stop BLE protocol tick
    _bleTickTimer?.cancel();
    _bleTickTimer = null;
    
    // Shutdown SDK
    final success = await PollinetSdk.shutdown();
    
    if (success) {
      print('✅ SDK shutdown complete');
      setState(() {
        _isInitialized = false;
      });
    }
  } catch (e) {
    print('❌ Shutdown error: $e');
  }
}

## Usage

### Basic Setup

#### 1. Request Permissions (Android 12+)

Before initializing the SDK, request required BLE permissions:

```dart
import 'package:pollinet_flutter/pollinet_sdk.dart';

// Check current permission status
final permissionStatus = await PollinetSdk.checkPermissions();
print('Permission status: $permissionStatus');
// Output: {android.permission.BLUETOOTH_SCAN: granted, ...}

// Request permissions (shows system dialog on Android 12+)
final result = await PollinetSdk.requestPermissions();

// Verify all permissions are granted
final allGranted = result.values.every((status) => status == 'granted');
if (!allGranted) {
  // Handle permission denial
  print('Some permissions were denied');
  return;
}
```

#### 2. Initialize the SDK

```dart
try {
  // Initialize with default configuration
  final success = await PollinetSdk.initialize();
  
  if (success) {
    final version = await PollinetSdk.version();
    print('Pollinet SDK initialized: $version');
  }
} on PollinetException catch (e) {
  print('SDK initialization failed: ${e.message}');
}
```

#### 3. Initialize with Custom Configuration

```dart
final config = SdkConfig(
  version: 1,
  rpcUrl: 'https://api.mainnet-beta.solana.com',
  enableLogging: true,
  logLevel: 'info',
  storageDirectory: '/path/to/storage', // Optional
);

await PollinetSdk.initialize(config: config);
```

### Creating Transactions

#### Create an Unsigned SOL Transfer

```dart
try {
  final unsignedTx = await PollinetSdk.createUnsignedTransaction(
    sender: 'YourSenderPublicKey',
    recipient: 'RecipientPublicKey',
    feePayer: 'FeePayerPublicKey',
    amount: 1000000, // lamports (0.001 SOL)
  );
  
  print('Unsigned transaction: $unsignedTx');
} on PollinetException catch (e) {
  print('Transaction creation failed: ${e.message}');
}
```

#### Create an Unsigned SPL Token Transfer

```dart
try {
  final unsignedTx = await PollinetSdk.createUnsignedSplTransaction(
    senderWallet: 'SenderTokenWalletAddress',
    recipientWallet: 'RecipientTokenWalletAddress',
    feePayer: 'FeePayerPublicKey',
    mintAddress: 'TokenMintAddress',
    amount: 100, // token amount
  );
  
  print('Unsigned SPL transaction: $unsignedTx');
} on PollinetException catch (e) {
  print('SPL transaction creation failed: ${e.message}');
}
```

### Working with Signatures

#### Prepare Transaction for Signing

```dart
// Get the message bytes that need to be signed
final signPayload = await PollinetSdk.prepareSignPayload(unsignedTx);

if (signPayload != null) {
  // Sign the payload with your wallet (e.g., using Solana Mobile Wallet Adapter)
  final signature = await yourWallet.sign(signPayload);
  
  // Apply the signature to the transaction
  final signedTx = await PollinetSdk.applySignature(
    base64Tx: unsignedTx,
    signerPubkey: 'YourPublicKey',
    signatureBytes: signature,
  );
  
  // Verify and serialize the signed transaction
  final serializedTx = await PollinetSdk.verifyAndSerialize(signedTx);
}
```

### Fragmenting Transactions for BLE

```dart
// Fragment a transaction for BLE transmission
final fragmentList = await PollinetSdk.fragment(
  txBytes: serializedTx,
  maxPayload: 512, // Optional: max fragment size
);

print('Transaction fragmented into ${fragmentList.fragments.length} pieces');

// Send each fragment over BLE
for (final fragment in fragmentList.fragments) {
  // Send fragment.data (base64) via BLE GATT characteristic
  await sendOverBLE(fragment.data);
}
```

### Offline Transaction Workflow

#### 1. Prepare Offline Bundle (When Online)

```dart
// Prepare a bundle with 10 nonce accounts for offline use
final bundle = await PollinetSdk.prepareOfflineBundle(
  count: 10,
  senderKeypair: yourKeypairBase64,
  bundleFile: '/path/to/bundle.json', // Optional: save to file
);

print('Bundle prepared: ${bundle.maxTransactions} transactions available');
print('Available nonces: ${bundle.availableNonces}');
```

#### 2. Create Offline Transaction (When Offline)

```dart
// Get an available nonce
final nonce = await PollinetSdk.getAvailableNonce();

if (nonce != null && !nonce.used) {
  // Create transaction using cached nonce
  final offlineTx = await PollinetSdk.createOfflineTransaction(
    senderKeypair: yourKeypairBase64,
    nonceAuthorityKeypair: nonceAuthorityKeypairBase64,
    recipient: 'RecipientPublicKey',
    amount: 1000000,
  );
  
  print('Offline transaction created: $offlineTx');
}
```

#### 3. Submit Offline Transaction (When Online)

```dart
// Submit the offline-created transaction
final txSignature = await PollinetSdk.submitOfflineTransaction(
  transactionBase64: offlineTx,
  verifyNonce: true,
);

print('Transaction submitted: $txSignature');
```

### Mobile Wallet Adapter (MWA) Workflow

```dart
// 1. Create unsigned transaction
final unsignedTx = await PollinetSdk.createUnsignedOfflineTransaction(
  senderPubkey: 'YourPublicKey',
  nonceAuthorityPubkey: 'NonceAuthorityPublicKey',
  recipient: 'RecipientPublicKey',
  amount: 1000000,
);

// 2. Get required signers
final signers = await PollinetSdk.getRequiredSigners(unsignedTx);
print('Required signers: $signers');

// 3. Get message bytes for MWA signing
final messageBytes = await PollinetSdk.getTransactionMessageToSign(unsignedTx);

// 4. Sign with MWA/Seed Vault
final signature = await mwaWallet.sign(messageBytes);

// 5. Apply signature
final signedTx = await PollinetSdk.applySignature(
  base64Tx: unsignedTx,
  signerPubkey: 'YourPublicKey',
  signatureBytes: signature,
);

// 6. Verify and serialize
final serializedTx = await PollinetSdk.verifyAndSerialize(signedTx);
```

### Monitoring and Metrics

```dart
// Get transport metrics
final metrics = await PollinetSdk.metrics();
print('Fragments buffered: ${metrics.fragmentsBuffered}');
print('Transactions complete: ${metrics.transactionsComplete}');
print('Reassembly failures: ${metrics.reassemblyFailures}');
print('Last error: ${metrics.lastError}');

// Get received queue size
final queueSize = await PollinetSdk.getReceivedQueueSize();
print('Transactions waiting: $queueSize');

// Get fragment reassembly info
final reassemblyInfo = await PollinetSdk.getFragmentReassemblyInfo();
print('Incomplete transactions: ${reassemblyInfo.incompleteTransactions.length}');
```

### BLE Transport Integration

```dart
// Push inbound data received from BLE
await PollinetSdk.pushInbound(receivedDataBase64);

// Get next outbound frame to send over BLE
final outboundFrame = await PollinetSdk.nextOutbound(maxLen: 512);
if (outboundFrame != null) {
  // Send outboundFrame over BLE
  await sendOverBLE(outboundFrame);
}

// Periodic tick for protocol state machine
final completedTxIds = await PollinetSdk.tick();
for (final txId in completedTxIds) {
  print('Transaction completed: $txId');
}
```

### Cleanup and Maintenance

```dart
// Cleanup stale fragments
final cleaned = await PollinetSdk.cleanupStaleFragments();
print('Cleaned up $cleaned stale fragments');

// Cleanup expired confirmations
final cleanupResult = await PollinetSdk.cleanupExpired();
print('Cleaned ${cleanupResult.confirmationsCleaned} confirmations');

// Save queues to disk
await PollinetSdk.saveQueues();

// Clear all queues (use with caution)
await PollinetSdk.clearAllQueues();
```

### Shutdown

```dart
// Shutdown SDK and release resources
await PollinetSdk.shutdown();
```

## Error Handling

All SDK methods throw `PollinetException` on error:

```dart
try {
  await PollinetSdk.initialize();
} on PollinetException catch (e) {
  print('Error code: ${e.code}');
  print('Error message: ${e.message}');
  
  // Handle specific error codes
  switch (e.code) {
    case 'INIT_ERROR':
      // Handle initialization error
      break;
    case 'NOT_INITIALIZED':
      // SDK not initialized
      break;
    case 'TX_ERROR':
      // Transaction error
      break;
    default:
      // Unknown error
  }
}
```

## API Reference

### Core Methods

- `initialize({SdkConfig? config})` - Initialize the SDK
- `shutdown()` - Shutdown SDK and release resources
- `version()` - Get SDK version

### Permission Management

- `checkPermissions()` - Check BLE permission status
- `requestPermissions()` - Request BLE permissions

### Transaction Creation

- `createUnsignedTransaction(...)` - Create unsigned SOL transfer
- `createUnsignedSplTransaction(...)` - Create unsigned SPL token transfer
- `createUnsignedVote(...)` - Create unsigned governance vote

### Signature Management

- `prepareSignPayload(base64Tx)` - Get message bytes to sign
- `applySignature(...)` - Apply signature to transaction
- `verifyAndSerialize(base64Tx)` - Verify and serialize transaction

### BLE Transport

- `pushInbound(dataBase64)` - Push received BLE data
- `nextOutbound({maxLen})` - Get next frame to send
- `tick()` - Periodic protocol tick
- `metrics()` - Get transport metrics
- `clearTransaction(txId)` - Clear transaction from buffers

### Fragmentation

- `fragment(txBytes, {maxPayload})` - Fragment transaction for BLE
- `fragmentTransaction(txBytesBase64)` - Fragment signed transaction
- `reconstructTransaction(fragments)` - Reconstruct from fragments
- `getFragmentationStats(txBytesBase64)` - Get fragmentation statistics

### Offline Transactions

- `prepareOfflineBundle(...)` - Prepare offline bundle
- `createOfflineTransaction(...)` - Create offline transaction
- `submitOfflineTransaction(...)` - Submit offline transaction
- `getAvailableNonce()` - Get available nonce account
- `refreshOfflineBundle()` - Refresh cached nonce data

### MWA Support

- `createUnsignedOfflineTransaction(...)` - Create unsigned offline transaction
- `getRequiredSigners(unsignedTx)` - Get required signers
- `getTransactionMessageToSign(unsignedTx)` - Get message bytes for signing

### Queue Management

- `pushOutboundTransaction(...)` - Push transaction to outbound queue
- `getReceivedQueueSize()` - Get received queue size
- `nextReceivedTransaction()` - Get next received transaction
- `markTransactionSubmitted(txBase64)` - Mark transaction as submitted

### Utilities

- `cleanupStaleFragments()` - Cleanup stale fragments
- `cleanupExpired()` - Cleanup expired items
- `saveQueues()` - Save queues to disk
- `clearAllQueues()` - Clear all queues

## Configuration

### SdkConfig

```dart
SdkConfig(
  version: 1,                    // SDK version
  rpcUrl: 'https://...',          // Optional: Solana RPC URL
  enableLogging: true,            // Enable SDK logging
  logLevel: 'info',               // Log level: 'debug', 'info', 'warn', 'error'
  storageDirectory: '/path',      // Optional: Custom storage directory
)
```

## Platform Support

- ✅ **Android**: Full support (API 21+)
- ❌ **iOS**: Not yet supported (native iOS SDK required)

## Requirements

- Android 5.0 (API 21) or higher
- Bluetooth Low Energy (BLE) hardware support
- For Android 12+ (API 31+): Runtime permission handling required

## Troubleshooting

### Common Issues and Solutions

#### 1. "RPC client not initialized" Error

**Problem**: Transaction creation fails with RPC client error.

**Cause**: SDK initialized without RPC URL.

**Solution**: Always initialize with an RPC URL:

```dart
final config = SdkConfig(
  rpcUrl: 'https://api.devnet.solana.com',  // REQUIRED for transactions
  enableLogging: true,
);
await PollinetSdk.initialize(config: config);
```

#### 2. No BLE Activity / Not Scanning

**Problem**: SDK initialized but no BLE scanning/advertising happening.

**Cause**: Missing periodic `tick()` calls.

**Solution**: The BLE protocol REQUIRES a 1-second tick:

```dart
Timer.periodic(const Duration(seconds: 1), (_) async {
  await PollinetSdk.tick();
});
```

#### 3. Permission Denied (Android 12+)

**Problem**: Permissions denied on Android 12+.

**Cause**: BLE permissions not requested at runtime.

**Solution**: Request permissions before SDK initialization:

```dart
final result = await PollinetSdk.requestPermissions();
final allGranted = result.values.every((s) => s == 'granted');

if (!allGranted) {
  // Show user why permissions are needed
  print('BLE permissions required for offline transaction mesh');
}
```

#### 4. "SDK not initialized" Error

**Problem**: `NOT_INITIALIZED` error when calling SDK methods.

**Cause**: Methods called before `initialize()`.

**Solution**: Always initialize first:

```dart
// CORRECT order:
await PollinetSdk.initialize(config: config);
await PollinetSdk.tick();  // ✅ Works

// WRONG order:
await PollinetSdk.tick();  // ❌ Throws NOT_INITIALIZED
await PollinetSdk.initialize(config: config);
```

#### 5. Build Error: "minSdkVersion 28 cannot be smaller than version 29"

**Problem**: App minSdk is lower than plugin requirement.

**Cause**: Plugin requires Android 10+ (API 29).

**Solution**: Update your `android/app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        minSdk = 29  // Change from 21/23/28 to 29
    }
}
```

#### 6. Transaction Creation Fails

**Problem**: Transaction creation throws error.

**Solutions**:
- ✅ Verify public keys are valid base58 Solana addresses (44 characters)
- ✅ Ensure amounts are in lamports (1 SOL = 1,000,000,000 lamports)
- ✅ Confirm fee payer has sufficient balance
- ✅ Check RPC URL is accessible
- ✅ Verify sender/recipient addresses are correct

```dart
// Correct format:
final tx = await PollinetSdk.createUnsignedTransaction(
  request: TransactionRequest(
    sender: 'Abc123...XYZ',      // 44-char base58 string
    recipient: 'Def456...ABC',   // 44-char base58 string
    feePayer: 'Ghi789...DEF',    // 44-char base58 string
    amount: 1000000,             // 0.001 SOL in lamports
  ),
);
```

#### 7. MissingPluginException

**Problem**: `MissingPluginException: No implementation found for method X`

**Cause**: Flutter didn't recognize the plugin.

**Solutions**:
1. Run `flutter clean`
2. Run `flutter pub get`
3. Rebuild the app: `flutter run`
4. If still failing, check `pubspec.yaml` has correct path
5. Verify plugin's `pubspec.yaml` has correct registration:

```yaml
flutter:
  plugin:
    platforms:
      android:
        package: xyz.pollinet.sdk.flutter
        pluginClass: PollinetPlugin
```

#### 8. Gradle Build Fails

**Problem**: Android build fails with Gradle errors.

**Solutions**:

1. **Check Kotlin version** (should be 2.1.0):
   ```kotlin
   // android/settings.gradle or settings.gradle.kts
   id("org.jetbrains.kotlin.android") version "2.1.0"
   ```

2. **Check AGP version** (should be 8.2.2+):
   ```kotlin
   id("com.android.application") version "8.2.2"
   ```

3. **Clean and rebuild**:
   ```bash
   cd android
   ./gradlew clean
   cd ..
   flutter clean
   flutter pub get
   flutter run
   ```

---

## Best Practices

### 1. Always Use RPC Configuration

```dart
// ❌ BAD: No RPC
await PollinetSdk.initialize();

// ✅ GOOD: With RPC
await PollinetSdk.initialize(
  config: SdkConfig(rpcUrl: 'https://api.devnet.solana.com'),
);
```

### 2. Always Start BLE Tick After Init

```dart
// Initialize SDK
await PollinetSdk.initialize(config: config);

// Start tick immediately after
_bleTickTimer = Timer.periodic(
  const Duration(seconds: 1),
  (_) async => await PollinetSdk.tick(),
);
```

### 3. Handle Permissions Gracefully

```dart
final granted = await _requestPermissions();
if (!granted) {
  // Show user-friendly message
  showDialog(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Bluetooth Permissions Required'),
      content: Text(
        'Pollinet needs Bluetooth permissions to relay transactions '
        'through the mesh network. This enables offline transaction '
        'propagation.'
      ),
      actions: [
        TextButton(
          onPressed: () => _requestPermissions(),
          child: Text('Grant Permission'),
        ),
      ],
    ),
  );
}
```

### 4. Always Clean Up

```dart
@override
void dispose() {
  // Stop tick timer
  _bleTickTimer?.cancel();
  
  // Shutdown SDK
  PollinetSdk.shutdown();
  
  super.dispose();
}
```

### 5. Use Try-Catch for All SDK Calls

```dart
try {
  final metrics = await PollinetSdk.metrics();
  print('Metrics: $metrics');
} on PollinetException catch (e) {
  print('Error ${e.code}: ${e.message}');
  // Handle error appropriately
} catch (e) {
  print('Unexpected error: $e');
}
```

---

## Complete Working Example

See the [example app](./example) for a complete working implementation with:
- ✅ Permission handling
- ✅ SDK initialization with RPC
- ✅ BLE protocol tick
- ✅ Transaction creation
- ✅ Nonce account creation
- ✅ Metrics display
- ✅ Battery optimization
- ✅ Comprehensive logging
- ✅ Clean shutdown

Run the example:
```bash
cd example
flutter run
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

[Add your license information here]

## Additional Information

For more information about Pollinet, visit:
- [Pollinet Website](https://pollinet.xyz)
- [Documentation](https://docs.pollinet.xyz)
- [GitHub Repository](https://github.com/pollinet)

For issues and support, please file an issue on GitHub.
