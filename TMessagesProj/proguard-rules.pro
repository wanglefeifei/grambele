-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}
-keep class org.telegram.** { *; }
-keep class com.coremedia.** { *; }
-dontwarn com.coremedia.**
-dontwarn org.telegram.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.common.cache.**
-dontwarn com.google.common.primitives.**
-dontwarn com.googlecode.mp4parser.**

-dontwarn com.google.firebase.**
-dontwarn org.apache.http.**


# Use -keep to explicitly keep any other classes shrinking would remove
-dontoptimize
-dontobfuscate