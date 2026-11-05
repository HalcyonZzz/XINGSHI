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
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }

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

# 通道插件跨包的最小宿主能力：插件按原名解析这些类型与成员，R8 不得改名/裁剪。
# 实现（plugins/framework/host）随宿主混淆，插件不引用实现类。
-keep class com.xingshi.ai.plugins.api.PluginHost { *; }
-keep class com.xingshi.ai.plugins.api.PluginHostServices { *; }
-keep class com.xingshi.ai.plugins.api.PluginCompanionRef { *; }

# 外部插件编译期依赖 Kotlin 运行时与协程；R8 必须保留原名与成员，否则插件链接失败
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# 通道插件配置页共享的 Compose/lifecycle ABI：插件 ZIP 只带自己的代码，
# 页面在宿主进程内组合，因此这些被插件引用的类型与成员必须保留原名（精确到最后引用者）。
-keep class androidx.compose.foundation.BackgroundKt { *; }
-keep class androidx.compose.foundation.BorderStroke { *; }
-keep class androidx.compose.foundation.ClickableKt { *; }
-keep class androidx.compose.foundation.ImageKt { *; }
-keep class androidx.compose.foundation.ScrollKt { *; }
-keep class androidx.compose.foundation.ScrollState { *; }
-keep class androidx.compose.foundation.gestures.FlingBehavior { *; }
-keep class androidx.compose.foundation.interaction.MutableInteractionSource { *; }
-keep class androidx.compose.foundation.layout.Arrangement$Horizontal { *; }
-keep class androidx.compose.foundation.layout.Arrangement$HorizontalOrVertical { *; }
-keep class androidx.compose.foundation.layout.Arrangement$Vertical { *; }
-keep class androidx.compose.foundation.layout.Arrangement { *; }
-keep class androidx.compose.foundation.layout.BoxKt { *; }
-keep class androidx.compose.foundation.layout.BoxScope { *; }
-keep class androidx.compose.foundation.layout.BoxScopeInstance { *; }
-keep class androidx.compose.foundation.layout.ColumnKt { *; }
-keep class androidx.compose.foundation.layout.ColumnScope { *; }
-keep class androidx.compose.foundation.layout.ColumnScopeInstance { *; }
-keep class androidx.compose.foundation.layout.PaddingKt { *; }
-keep class androidx.compose.foundation.layout.PaddingValues { *; }
-keep class androidx.compose.foundation.layout.RowKt { *; }
-keep class androidx.compose.foundation.layout.RowScope { *; }
-keep class androidx.compose.foundation.layout.RowScopeInstance { *; }
-keep class androidx.compose.foundation.layout.SizeKt { *; }
-keep class androidx.compose.foundation.layout.SpacerKt { *; }
-keep class androidx.compose.foundation.layout.WindowInsets { *; }
-keep class androidx.compose.foundation.shape.RoundedCornerShape { *; }
-keep class androidx.compose.foundation.shape.RoundedCornerShapeKt { *; }
-keep class androidx.compose.foundation.text.KeyboardActions { *; }
-keep class androidx.compose.foundation.text.KeyboardOptions { *; }
-keep class androidx.compose.material.icons.Icons$AutoMirrored$Filled { *; }
-keep class androidx.compose.material.icons.Icons$Filled { *; }
-keep class androidx.compose.material.icons.Icons$Outlined { *; }
-keep class androidx.compose.material.icons.automirrored.filled.ArrowBackKt { *; }
-keep class androidx.compose.material.icons.filled.CheckCircleKt { *; }
-keep class androidx.compose.material.icons.filled.QrCodeKt { *; }
-keep class androidx.compose.material.icons.outlined.DeleteKt { *; }
-keep class androidx.compose.material.icons.outlined.EditKt { *; }
-keep class androidx.compose.material.icons.outlined.LinkKt { *; }
-keep class androidx.compose.material.icons.outlined.MessageKt { *; }
-keep class androidx.compose.material.icons.outlined.WarningKt { *; }
-keep class androidx.compose.material3.AndroidAlertDialog_androidKt { *; }
-keep class androidx.compose.material3.AppBarKt { *; }
-keep class androidx.compose.material3.ButtonColors { *; }
-keep class androidx.compose.material3.ButtonElevation { *; }
-keep class androidx.compose.material3.ButtonKt { *; }
-keep class androidx.compose.material3.ChipKt { *; }
-keep class androidx.compose.material3.ColorScheme { *; }
-keep class androidx.compose.material3.DividerKt { *; }
-keep class androidx.compose.material3.FilterChipDefaults { *; }
-keep class androidx.compose.material3.IconButtonColors { *; }
-keep class androidx.compose.material3.IconButtonKt { *; }
-keep class androidx.compose.material3.IconKt { *; }
-keep class androidx.compose.material3.MaterialTheme { *; }
-keep class androidx.compose.material3.OutlinedTextFieldKt { *; }
-keep class androidx.compose.material3.ProgressIndicatorKt { *; }
-keep class androidx.compose.material3.ScaffoldKt { *; }
-keep class androidx.compose.material3.SelectableChipColors { *; }
-keep class androidx.compose.material3.SelectableChipElevation { *; }
-keep class androidx.compose.material3.SwitchColors { *; }
-keep class androidx.compose.material3.SwitchKt { *; }
-keep class androidx.compose.material3.TextFieldColors { *; }
-keep class androidx.compose.material3.TextKt { *; }
-keep class androidx.compose.material3.TopAppBarColors { *; }
-keep class androidx.compose.material3.TopAppBarDefaults { *; }
-keep class androidx.compose.material3.TopAppBarScrollBehavior { *; }
-keep class androidx.compose.runtime.Applier { *; }
-keep class androidx.compose.runtime.ComposablesKt { *; }
-keep class androidx.compose.runtime.Composer$Companion { *; }
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.CompositionLocal { *; }
-keep class androidx.compose.runtime.CompositionLocalMap { *; }
-keep class androidx.compose.runtime.EffectsKt { *; }
-keep class androidx.compose.runtime.MutableState { *; }
-keep class androidx.compose.runtime.ProvidableCompositionLocal { *; }
-keep class androidx.compose.runtime.RecomposeScopeImplKt { *; }
-keep class androidx.compose.runtime.ScopeUpdateScope { *; }
-keep class androidx.compose.runtime.SnapshotMutationPolicy { *; }
-keep class androidx.compose.runtime.SnapshotStateKt { *; }
-keep class androidx.compose.runtime.State { *; }
-keep class androidx.compose.runtime.Updater { *; }
-keep class androidx.compose.runtime.internal.ComposableLambda { *; }
-keep class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
-keep class androidx.compose.ui.Alignment$Companion { *; }
-keep class androidx.compose.ui.Alignment$Horizontal { *; }
-keep class androidx.compose.ui.Alignment$Vertical { *; }
-keep class androidx.compose.ui.Alignment { *; }
-keep class androidx.compose.ui.ComposedModifierKt { *; }
-keep class androidx.compose.ui.Modifier$Companion { *; }
-keep class androidx.compose.ui.Modifier { *; }
-keep class androidx.compose.ui.draw.ClipKt { *; }
-keep class androidx.compose.ui.graphics.AndroidImageBitmap_androidKt { *; }
-keep class androidx.compose.ui.graphics.Color$Companion { *; }
-keep class androidx.compose.ui.graphics.Color { *; }
-keep class androidx.compose.ui.graphics.ColorFilter { *; }
-keep class androidx.compose.ui.graphics.ColorKt { *; }
-keep class androidx.compose.ui.graphics.ImageBitmap { *; }
-keep class androidx.compose.ui.graphics.Shape { *; }
-keep class androidx.compose.ui.graphics.vector.ImageVector { *; }
-keep class androidx.compose.ui.layout.ContentScale { *; }
-keep class androidx.compose.ui.layout.MeasurePolicy { *; }
-keep class androidx.compose.ui.node.ComposeUiNode$Companion { *; }
-keep class androidx.compose.ui.node.ComposeUiNode { *; }
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-keep class androidx.compose.ui.semantics.Role { *; }
-keep class androidx.compose.ui.text.TextStyle { *; }
-keep class androidx.compose.ui.text.font.FontFamily { *; }
-keep class androidx.compose.ui.text.font.FontStyle { *; }
-keep class androidx.compose.ui.text.font.FontWeight$Companion { *; }
-keep class androidx.compose.ui.text.font.FontWeight { *; }
-keep class androidx.compose.ui.text.input.VisualTransformation { *; }
-keep class androidx.compose.ui.text.style.TextAlign$Companion { *; }
-keep class androidx.compose.ui.text.style.TextAlign { *; }
-keep class androidx.compose.ui.text.style.TextDecoration { *; }
-keep class androidx.compose.ui.unit.Dp { *; }
-keep class androidx.compose.ui.unit.TextUnitKt { *; }
-keep class androidx.compose.ui.window.DialogProperties { *; }
-keep class androidx.lifecycle.HasDefaultViewModelProviderFactory { *; }
-keep class androidx.lifecycle.ViewModel { *; }
-keep class androidx.lifecycle.ViewModelKt { *; }
-keep class androidx.lifecycle.ViewModelProvider$Factory { *; }
-keep class androidx.lifecycle.ViewModelStoreOwner { *; }
-keep class androidx.lifecycle.viewmodel.CreationExtras$Empty { *; }
-keep class androidx.lifecycle.viewmodel.CreationExtras { *; }
-keep class androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner { *; }
-keep class androidx.lifecycle.viewmodel.compose.ViewModelKt { *; }
