# --- Kotlin ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class **$WhenMappings {
    <fields>;
}

# --- Moshi ---
-keep class com.orbitai.data.api.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    *** Companion;
    @com.squareup.moshi.Json @kotlin.jvm.JvmField *;
}

# --- Retrofit ---
-keep,allowobfuscation interface com.orbitai.data.api.**.*Service
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Shizuku ---
-keep class dev.rikka.shizuku.** { *; }
-keep interface dev.rikka.shizuku.** { *; }

# --- Model / Entity classes (keep for serialization and DB) ---
-keep class com.orbitai.data.local.entity.** { *; }
-keep class com.orbitai.domain.models.** { *; }

# --- Compose ---
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Tink (via androidx.security-crypto) references errorprone/j2objc annotations
# that exist only at compile time; R8 full-mode treats them as missing classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
