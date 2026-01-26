# Pollinet SDK Example

A simple example application demonstrating how to use the Pollinet Flutter SDK.

## Features Demonstrated

- ✅ Permission checking and requesting (Android 12+)
- ✅ SDK initialization
- ✅ Version checking
- ✅ Transport metrics retrieval
- ✅ SDK shutdown

## Getting Started

### Prerequisites

- Flutter SDK 3.10.7 or higher
- Android device with Bluetooth Low Energy support
- Android 5.0 (API 21) or higher

### Running the Example

1. Navigate to the example directory:

```bash
cd example
```

2. Install dependencies:

```bash
flutter pub get
```

3. Run on a connected Android device:

```bash
flutter run
```

## Usage Flow

1. **Check Permissions**: The app automatically checks permission status on startup
2. **Request Permissions**: Tap "Request" to request BLE permissions (required on Android 12+)
3. **Initialize SDK**: Once permissions are granted, tap "Initialize SDK"
4. **Get Metrics**: After initialization, tap "Get Metrics" to view transport statistics
5. **Shutdown**: Tap "Shutdown SDK" when done

## What You'll See

- **Status Card**: Shows current SDK status and version
- **Permissions Card**: Displays permission status for all required BLE permissions
- **SDK Actions**: Buttons to initialize, get metrics, and shutdown the SDK
- **Metrics Display**: Shows transport metrics when available (fragments buffered, transactions complete, etc.)

## Notes

- This is a minimal example focusing on basic SDK operations
- For more advanced features (transaction creation, BLE transport, etc.), see the main README
- The example requires BLE permissions to function properly
- On Android 12+, you'll see a system permission dialog when requesting permissions
