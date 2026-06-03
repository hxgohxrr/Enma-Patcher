-keep class com.enmapatcher.** { *; }
-keep class brut.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn brut.**
-dontwarn com.android.apksig.**
-dontwarn org.bouncycastle.**
-dontwarn com.google.j2objc.annotations.**





-dontoptimize


-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod


-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** $serializer;
    *** Companion;
    *** INSTANCE;
}


-keepclassmembernames class kotlinx.** { volatile <fields>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}