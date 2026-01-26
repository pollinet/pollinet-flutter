# Pollinet Flutter SDK - Complete Integration Guide

**Last Updated:** January 26, 2026  
**Difficulty:** Easy (5 minutes)  
**Requirements:** Flutter 3.0+, Android 10+ device

---

## 📋 Pre-Integration Checklist

Before starting, verify you have:

- [ ] Flutter SDK 3.0.0 or higher
- [ ] Android Studio or VS Code with Flutter plugin
- [ ] Android device/emulator running Android 10+ (API 29+)
- [ ] Internet connection (for RPC)
- [ ] 5 minutes

---

## 🎯 Integration Overview

**What you'll integrate:**
1. Pollinet Flutter package
2. BLE permissions
3. SDK initialization with RPC
4. BLE protocol tick (Timer)

**Result:** Offline Solana transaction propagation via BLE mesh

---

## Step-by-Step Integration

### Step 1: Add Package (1 minute)

#### Option A: Local Path
Add to your `pubspec.yaml`:

```yaml
dependencies:
  flutter:
    sdk: flutter
  pollinet_flutter:
    path: ../pollinet-flutter  # Adjust path as needed
```

#### Option B: Git Repository
```yaml
dependencies:
  flutter:
    sdk: flutter
  pollinet_flutter:
    git:
      url: https://github.com/pollinet/pollinet-flutter.git
      ref: main
```

#### Install
```bash
flutter pub get
```

---

### Step 2: Configure Android (30 seconds)

Edit `android/app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 29  // ← CHANGE THIS! Was probably 21 or 23
        targetSdk = flutter.targetSdkVersion
        // ...
    }
}
```

**Why?** Pollinet native SDK requires Android 10+ (API 29)

---

### Step 3: Create Integration Code (3 minutes)

Create a new file or update your main app:

```dart
import 'package:flutter/material.dart';
import 'package:pollinet_flutter/pollinet_sdk.dart';
import 'dart:async';

class PollinetManager {
  Timer? _bleTickTimer;
  bool _isInitialized = false;
  
  // 1. Request Permissions
  Future<bool> requestPermissions() async {
    final status = await PollinetSdk.checkPermissions();
    final allGranted = status.values.every((s) => s == 'granted');
    
    if (!allGranted) {
      final result = await PollinetSdk.requestPermissions();
      return result.values.every((s) => s == 'granted');
    }
    
    return true;
  }
  
  // 2. Initialize SDK
  Future<bool> initialize() async {
    try {
      // Configure with RPC
      final config = SdkConfig(
        rpcUrl: 'https://api.devnet.solana.com',  // or mainnet
        enableLogging: true,
      );
      
      // Initialize
      final success = await PollinetSdk.initialize(config: config);
      
      if (success) {
        _isInitialized = true;
        _startBleProtocol();
        print('✅ Pollinet SDK initialized');
        return true;
      }
      
      return false;
    } on PollinetException catch (e) {
      print('❌ Init failed: ${e.message}');
      return false;
    }
  }
  
  // 3. Start BLE Protocol (CRITICAL!)
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
    print('✅ BLE protocol started');
  }
  
  // 4. Cleanup
  Future<void> shutdown() async {
    _bleTickTimer?.cancel();
    _bleTickTimer = null;
    
    if (_isInitialized) {
      await PollinetSdk.shutdown();
      _isInitialized = false;
      print('🛑 SDK shutdown');
    }
  }
  
  // 5. Get Metrics
  Future<void> getMetrics() async {
    try {
      final metrics = await PollinetSdk.metrics();
      print('📊 Metrics:');
      print('  Fragments: ${metrics.fragmentsBuffered}');
      print('  Transactions: ${metrics.transactionsComplete}');
      print('  Failures: ${metrics.reassemblyFailures}');
    } catch (e) {
      print('❌ Metrics error: $e');
    }
  }
  
  // 6. Create Transaction
  Future<String?> createTransaction({
    required String sender,
    required String recipient,
    required String feePayer,
    required int lamports,
  }) async {
    try {
      final tx = await PollinetSdk.createUnsignedTransaction(
        request: TransactionRequest(
          sender: sender,
          recipient: recipient,
          feePayer: feePayer,
          amount: lamports,
        ),
      );
      
      print('✅ Transaction created');
      return tx;
    } on PollinetException catch (e) {
      print('❌ TX failed: ${e.message}');
      return null;
    }
  }
}
```

---

### Step 4: Use in Your App (1 minute)

```dart
class MyApp extends StatefulWidget {
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _pollinet = PollinetManager();
  bool _ready = false;
  
  @override
  void initState() {
    super.initState();
    _setup();
  }
  
  @override
  void dispose() {
    _pollinet.shutdown();
    super.dispose();
  }
  
  Future<void> _setup() async {
    // 1. Request permissions
    final permissionsGranted = await _pollinet.requestPermissions();
    if (!permissionsGranted) {
      print('❌ Permissions denied');
      return;
    }
    
    // 2. Initialize SDK
    final initialized = await _pollinet.initialize();
    if (!initialized) {
      print('❌ SDK init failed');
      return;
    }
    
    setState(() => _ready = true);
    print('✅ Pollinet ready!');
  }
  
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: Text('Pollinet Demo')),
        body: Center(
          child: _ready
              ? Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text('✅ Pollinet SDK Ready'),
                    SizedBox(height: 20),
                    ElevatedButton(
                      onPressed: () => _pollinet.getMetrics(),
                      child: Text('Get Metrics'),
                    ),
                    ElevatedButton(
                      onPressed: _createTestTransaction,
                      child: Text('Create Transaction'),
                    ),
                  ],
                )
              : CircularProgressIndicator(),
        ),
      ),
    );
  }
  
  Future<void> _createTestTransaction() async {
    await _pollinet.createTransaction(
      sender: 'YourSenderPubkey',
      recipient: 'RecipientPubkey',
      feePayer: 'FeePayerPubkey',
      lamports: 1000000, // 0.001 SOL
    );
  }
}
```

---

## ✅ Verification

After integration, verify everything works:

### 1. Check Permissions
```dart
final status = await PollinetSdk.checkPermissions();
print(status);
// Expected: {android.permission.BLUETOOTH_SCAN: granted, ...}
```

### 2. Check SDK Version
```dart
final version = await PollinetSdk.version();
print(version);
// Expected: "0.1.0" or similar
```

### 3. Check Metrics
```dart
final metrics = await PollinetSdk.metrics();
print('Fragments: ${metrics.fragmentsBuffered}');
// Expected: 0 (initially)
```

### 4. Check Logs
Look for in device logs (logcat):
```
I/PolliNet-Rust: ✅ PolliNet SDK initialized successfully
I/PolliNet-Rust: ⏱️ HostBleTransport::tick()
```

---

## 🐛 Troubleshooting

### Issue: "MissingPluginException"

**Solution:**
```bash
flutter clean
flutter pub get
flutter run
```

### Issue: "minSdkVersion 28 cannot be smaller than 29"

**Solution:** Update `android/app/build.gradle.kts`:
```kotlin
minSdk = 29  // Change from 28 to 29
```

### Issue: "RPC client not initialized"

**Solution:** Initialize with RPC:
```dart
await PollinetSdk.initialize(
  config: SdkConfig(rpcUrl: 'https://api.devnet.solana.com'),
);
```

### Issue: No BLE activity

**Solution:** Make sure tick() is called:
```dart
Timer.periodic(const Duration(seconds: 1), (_) async {
  await PollinetSdk.tick();
});
```

### Issue: Permissions denied

**Solution:** Request at runtime:
```dart
await PollinetSdk.requestPermissions();
```

---

## 📊 Integration Checklist

After completing integration, verify:

- [ ] Package added to `pubspec.yaml`
- [ ] `flutter pub get` ran successfully
- [ ] `minSdk = 29` set in build.gradle
- [ ] Permissions requested at runtime
- [ ] SDK initialized with RPC URL
- [ ] BLE tick Timer started (1 second periodic)
- [ ] App builds without errors
- [ ] Permissions granted on device
- [ ] SDK version prints correctly
- [ ] Metrics accessible
- [ ] Device logs show tick activity

---

## 🎯 What's Next?

Now that integration is complete, you can:

1. **Create Transactions**
   ```dart
   await PollinetSdk.createUnsignedTransaction(...)
   ```

2. **Create Nonce Accounts** (for durable transactions)
   ```dart
   await PollinetSdk.createUnsignedNonceTransactions(...)
   ```

3. **Monitor Metrics**
   ```dart
   await PollinetSdk.metrics()
   ```

4. **Request Battery Exemption** (for background BLE)
   ```dart
   await PollinetSdk.requestBatteryOptimization()
   ```

---

## 📚 Complete Example

See the full working example app:
```bash
cd pollinet-flutter/example
flutter run
```

The example app demonstrates:
- ✅ Complete permission flow
- ✅ SDK initialization with RPC
- ✅ BLE protocol tick
- ✅ Transaction creation
- ✅ Nonce account creation
- ✅ Metrics display
- ✅ Battery optimization
- ✅ Comprehensive logging

---

## 🆘 Need Help?

1. **Check the README**: `pollinet-flutter/README.md`
2. **Review the example**: `pollinet-flutter/example/`
3. **Check device logs**: `adb logcat | grep PolliNet`
4. **File an issue**: GitHub Issues

---

## ✨ Summary

**Integration Time:** ~5 minutes  
**Code Added:** ~50-100 lines  
**Complexity:** Easy  

**You now have:**
- ✅ Offline Solana transaction propagation
- ✅ BLE mesh networking
- ✅ Transaction creation capabilities
- ✅ Durable transaction support (nonce accounts)
- ✅ Full metrics and monitoring

**Congratulations! 🎉 Pollinet is integrated and ready to use!**
