# Keep Room generated code and entities.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# OkHttp / JSoup reflection safety.
-dontwarn okhttp3.**
-dontwarn org.slf4j.**

# Keep crash-relevant line numbers.
-keepattributes SourceFile,LineNumberTable
