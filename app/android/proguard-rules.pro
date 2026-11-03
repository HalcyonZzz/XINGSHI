
-repackageclasses
-renamesourcefileattribute ""
-classobfuscationdictionary proguard-dictionary.txt
-packageobfuscationdictionary proguard-dictionary.txt

-keep class kotlin.Metadata { *; }
-dontwarn kotlin.metadata.**
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Exceptions,Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

-keep class com.xingshi.ai.common.SecureLog { *; }

-keep class com.xingshi.ai.domain.ServiceRegistry { *; }
-keepclassmembers class com.xingshi.ai.domain.ServiceRegistry {
    public static <methods>;
}

-keep class com.xingshi.ai.feature.notification.** { *; }

-keep class com.xingshi.ai.push.PushManager { *; }

-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xingshi.ai.**$$serializer { *; }
-keepclassmembers class com.xingshi.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.xingshi.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

-keep class com.xingshi.ai.XingShiApplication { *; }
-keep class com.xingshi.ai.MainActivity { *; }
-keep,allowobfuscation class * extends android.app.Service { *; }
-keep,allowobfuscation class * extends android.content.BroadcastReceiver { *; }
-keep,allowobfuscation class * extends androidx.work.Worker { *; }
-keep,allowobfuscation class * extends androidx.work.CoroutineWorker { *; }

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep,allowobfuscation class com.xingshi.ai.database.AppDatabase { *; }
-keep,allowobfuscation @androidx.room.Entity class com.xingshi.ai.database.model.** { *; }
-keep,allowobfuscation interface com.xingshi.ai.database.dao.** { *; }
-keep class androidx.room.** { *; }
-keep @androidx.room.Dao interface *
-keepclassmembers,allowobfuscation class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*


-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

-keep class com.github.wechat.ilink.sdk.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil.**
-dontwarn androidx.work.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.security.crypto.**

-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-dontwarn javax.crypto.**

-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

-keep class com.xingshi.ai.feature.wechat.** { *; }
-keep class com.xingshi.ai.feature.wechat.data.** { *; }
-keep class com.xingshi.ai.feature.wechat.service.** { *; }

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.xingshi.ai.R$* { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class com.heytap.msp.push.** { *; }
-keep class com.coloros.mcs.** { *; }
-keep class com.vivo.push.** { *; }
-keep class com.xiaomi.mipush.sdk.** { *; }
-keep class com.xiaomi.push.** { *; }
-keep class com.huawei.hms.push.** { *; }
-dontwarn com.heytap.msp.push.**
-dontwarn com.coloros.mcs.**
-dontwarn com.vivo.push.**
-dontwarn com.xiaomi.mipush.sdk.**
-dontwarn com.xiaomi.push.**
-keep class com.xingshi.ai.MainActivity { *; }
-keep class com.xingshi.ai.XingShiApplication { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }

-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepattributes *Annotation*
-keep class androidx.work.impl.** { *; }
-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }

-dontwarn com.huawei.hms.**
-dontwarn com.huawei.android.os.**
-dontwarn com.huawei.hianalytics.**
-dontwarn com.huawei.libcore.io.**
-dontwarn org.apache.commons.codec.**


-dontwarn com.google.errorprone.annotations.**

-keep class com.xingshi.ai.plugins.api.** { *; }
-keep class com.xingshi.ai.plugins.ui.** { *; }
-keep class com.xingshi.ai.theme.framework.** { *; }

-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
