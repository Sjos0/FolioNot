# FolioNote ProGuard / R8 Rules
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
-dontwarn androidx.room.paging.**

# WorkManager Workers instantiated via reflection
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger

# Credentials, GoogleId & Play Services Auth
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit Text Recognition
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-dontwarn com.google.mlkit.**

# PDFBox-Android & FontBox
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn org.slf4j.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**
