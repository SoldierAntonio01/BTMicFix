# Add project specific ProGuard rules here.
# Keep Shizuku AIDL interfaces
-keep class com.btmicfix.IPrivilegedService { *; }
-keep class com.btmicfix.IPrivilegedService$Stub { *; }
-keep class com.btmicfix.IPrivilegedService$Stub$Proxy { *; }
-keep class com.btmicfix.shizuku.PrivilegedServiceImpl { *; }

# Keep Shizuku provider
-keep class rikka.shizuku.** { *; }
