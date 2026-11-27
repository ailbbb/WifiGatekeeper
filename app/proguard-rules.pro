# Keep Room generated code and entities.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# OkHttp / JSoup reflection safety.
-dontwarn okhttp3.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# Tink (androidx.security:security-crypto) resolves algorithm classes by name.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.errorprone.annotations.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**

# kotlinx.coroutines internals accessed reflectively by the service/worker glue.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**

# Keep crash-relevant line numbers.
-keepattributes SourceFile,LineNumberTable
