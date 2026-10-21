# Native engine proguard rules
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.pocketserver.nativeengine.** { *; }
