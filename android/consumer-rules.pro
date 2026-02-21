# Keep BleService and its inner classes (LocalBinder) for service binding
-keepclassmembers class xyz.pollinet.sdk.BleService$LocalBinder {
    public *;
}

# Keep PolliNetSDK and all serializable data classes used across the FFI bridge
-keep class xyz.pollinet.sdk.PolliNetSDK { *; }
-keep class xyz.pollinet.sdk.SdkConfig { *; }
-keep class xyz.pollinet.sdk.BleService { *; }
-keep class xyz.pollinet.sdk.Priority { *; }
-keep class xyz.pollinet.sdk.ConfirmationStatus { *; }
-keep class xyz.pollinet.sdk.ConfirmationStatus$* { *; }
-keep class xyz.pollinet.sdk.Confirmation { *; }
-keep class xyz.pollinet.sdk.FragmentFFI { *; }
-keep class xyz.pollinet.sdk.FragmentData { *; }
