# ================================================================
# XingShi ProGuard/R8 Rules — Production Release
# ================================================================

# -overloadaggressively  # REMOVED: breaks Kotlin metadata → R8 produces invalid bytecode → SIGABRT
# -mergeinterfacesaggressively  # REMOVED: aggressive interface merging corrupts class hierarchy → SIGABRT on initWeChat
# -allowaccessmodification      # REMOVED: conservative — interacts badly with Kotlin inline/companion access
-repackageclasses
-renamesourcefileattribute ""
-classobfuscationdictionary proguard-dictionary.txt
-packageobfuscationdictionary proguard-dictionary.txt

# Kotlin metadata: R8 must be able to parse these for correct optimization
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.metadata.**
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Exceptions,Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# Strip Android logging in release builds.
# EXCEPTION: SecureLog.critical() uses Log.wtf — keep the class intact.
-keep class com.xingshi.ai.common.SecureLog { *; }

# 🔒 ServiceRegistry: reflection-based register()/get() — must survive obfuscation
-keep class com.xingshi.ai.domain.ServiceRegistry { *; }
-keepclassmembers class com.xingshi.ai.domain.ServiceRegistry {
    public static <methods>;
}

# 🔒 feature:notification: CompanionKeepAliveService + BootReceiver declared in AndroidManifest
-keep class com.xingshi.ai.feature.notification.** { *; }

# 🔒 Push: PushManager imported in XingShiApplication, routes to vendor Push SDKs
-keep class com.xingshi.ai.push.PushManager { *; }

# kotlinx.serialization: preserve serializers for reflection-based adapter lookup
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

# Android manifest entry points must stay loadable by class name.
# R8 updates manifest class references when obfuscation renames them.
-keep class com.xingshi.ai.XingShiApplication { *; }
-keep class com.xingshi.ai.MainActivity { *; }
-keep,allowobfuscation class * extends android.app.Service { *; }
-keep,allowobfuscation class * extends android.content.BroadcastReceiver { *; }
-keep,allowobfuscation class * extends androidx.work.Worker { *; }
-keep,allowobfuscation class * extends androidx.work.CoroutineWorker { *; }

# Prevent R8 from stripping System.loadLibrary calls in static initializers.
# Even with -keep, R8 may optimize away side-effect-free-looking code paths
# that include loadLibrary. This directive preserves all native method stubs.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Room: keep annotations and generated metadata, but allow class/interface names to be obfuscated.
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

# Kotlin serialization: DTO obfuscation is safe because:
# - Custom serializers use string-based key lookups (no reflection on field names)
# - Auto-generated serializers respect @SerialName annotations for wire names
# - R8 preserves annotations via -keepattributes
# Class names, property names, and method names may all be obfuscated freely.

# Compose runtime needs annotations, not app composable names.
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Third-party SDKs that use reflection/native loading.
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

# Crypto: keep JCA classes intact for AES-256-GCM encrypted API calls
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-dontwarn javax.crypto.**

# ViewModel constructors are created reflectively by AndroidX factories; allow
# class/method obfuscation while preserving constructors.
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# WeChat module — keep initialization path intact; R8 aggressive optimizations
# (mergeinterfacesaggressively/overloadaggressively) corrupt static initializers
# in these classes, causing SIGABRT on startup (XingShiApplication.initWeChat).
-keep class com.xingshi.ai.feature.wechat.** { *; }
-keep class com.xingshi.ai.feature.wechat.data.** { *; }
-keep class com.xingshi.ai.feature.wechat.service.** { *; }

# Android framework conventions.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.xingshi.ai.R$* { *; }

# Enum converters need value lookup; names are persisted in Room values.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ================================================================
# Vendor Push SDKs (OPPO / vivo / Xiaomi / Huawei)
# ================================================================
# OPPO / vivo / Xiaomi SDKs are bundled as local aars and accessed via reflection.
# Keep their class names and members so Class.forName / getMethod work in release builds.
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
# Keep MainActivity (manifest LAUNCHER → must be resolvable by ClassLoader)
-keep class com.xingshi.ai.MainActivity { *; }
-keep class com.xingshi.ai.XingShiApplication { *; }

# Keep all native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep AndroidX runtime
-keep class androidx.work.WorkManager { *; }
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep class androidx.work.impl.WorkManagerInitializer { *; }

# ═══════════════════════════════════════════════════════════════
# WorkManager — preserve reflection-based initialization
# ═══════════════════════════════════════════════════════════════
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

# 插件与作者主题的扩展 ABI：运行时由宿主 ClassLoader 解析，类名不能被混淆/裁剪
-keep class com.xingshi.ai.plugins.api.** { *; }
-keep class com.xingshi.ai.plugins.ui.** { *; }
-keep class com.xingshi.ai.theme.framework.** { *; }

# 外部插件编译期依赖 Kotlin 运行时与协程；R8 必须保留原名与成员，否则插件链接失败
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
