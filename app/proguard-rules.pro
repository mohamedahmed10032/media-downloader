# ---- General ----
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Kotlin ----
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# ---- Coroutines ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Hilt / Dagger ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# ---- Room ----
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }

# ---- Retrofit / OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-keepattributes Exceptions

# ---- Jsoup ----
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---- Media3 / ExoPlayer ----
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# ---- Models kept for serialization / reflection safety ----
-keep class com.melmeligy.mediadownloader.domain.model.** { *; }
-keep class com.melmeligy.mediadownloader.data.remote.dto.** { *; }

# ---- WorkManager custom workers ----
-keep class * extends androidx.work.ListenableWorker { *; }

# ---- Media interception layer ----
# R8 must never rename or strip methods called from injected JavaScript.
-keepclassmembers class com.melmeligy.mediadownloader.intercept.BlobBridgeInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.melmeligy.mediadownloader.intercept.MediaStreamPayload { *; }
-keep class com.melmeligy.mediadownloader.intercept.StreamType { *; }

# ---- DASH manifest parsing (javax.xml on Android) ----
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**
