-keep class com.enmapatcher.** { *; }
-keep class brut.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn brut.**
-dontwarn com.android.apksig.**
-dontwarn org.bouncycastle.**
-dontwarn com.google.j2objc.annotations.**

# Kotlin 2.1.0 + AGP 8.3.2: R8 cannot parse Kotlin metadata for this Kotlin version.
# Without metadata, R8 misidentifies coroutine state machines and Compose internals
# as dead/optimizable code, silently breaking them at runtime (blank screen / crashes).
# Disabling optimizations keeps shrinking + obfuscation while bypassing the metadata issue.
-dontoptimize

# Preserve Kotlin @Metadata — required for coroutines, serialization, reflection
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# kotlinx.serialization — keep generated $serializer companion for @Serializable classes
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** $serializer;
    *** Companion;
    *** INSTANCE;
}

# kotlinx.coroutines — volatile fields need to survive R8
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}