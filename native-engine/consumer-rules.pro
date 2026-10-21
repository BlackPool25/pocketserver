# Proguard rules for native-engine
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.pocketserver.nativeengine.** { *; }
