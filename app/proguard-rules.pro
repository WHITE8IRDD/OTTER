-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.tukaani.xz.**
-keepclassmembers class * { native <methods>; }

-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

-keep class com.vidgrab.app.data.model.** { *; }

-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

-dontwarn kotlinx.coroutines.**
