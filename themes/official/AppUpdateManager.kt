package com.xingshi.ai.feature.settings.ui.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xingshi.ai.network.AppUpdateClient
import com.xingshi.ai.network.AppUpdateFetchResult
import com.xingshi.ai.network.AppUpdateManifest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

object AppUpdateManager {

    enum class Status {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        CHECK_FAILED,
        DOWNLOADING,
        DOWNLOAD_FAILED,
        READY_TO_INSTALL,
        INSTALL_FAILED
    }

    data class UiState(
        val currentVersionName: String = "",
        val currentVersionCode: Long = 0L,
        val status: Status = Status.IDLE,
        val remoteVersionName: String = "",
        val remoteVersionCode: Long = 0L,
        val releaseNotes: String = "",
        val sizeBytes: Long = 0L,
        val progress: Float? = null,
        val detail: String = "",
        val readyApkPath: String = ""
    )

    private const val PREFS = "app_update"
    private const val PENDING_VERSION = "pending_version"
    private const val READY_PATH = "ready_path"
    private const val DIR_NAME = "app_update"

    private val stateRef = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = stateRef.asStateFlow()

    private var manifest: AppUpdateManifest? = null
    private val downloadLock = Mutex()

    fun installedVersion(context: Context): Pair<String, Long> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName.orEmpty()
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        name to code
    }.getOrDefault("" to 0L)

    fun refreshInstalled(context: Context) {
        val (name, code) = installedVersion(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getLong(PENDING_VERSION, 0L)
        val readyPath = prefs.getString(READY_PATH, "").orEmpty()
        val readyFile = readyPath.takeIf { it.isNotBlank() }?.let(::File)
        val installedNow = pending > 0L && code >= pending
        if (installedNow) {
            runCatching { File(context.filesDir, DIR_NAME).deleteRecursively() }
            prefs.edit().remove(PENDING_VERSION).remove(READY_PATH).apply()
            stateRef.value = UiState(
                currentVersionName = name,
                currentVersionCode = code,
                status = Status.UP_TO_DATE,
                remoteVersionName = stateRef.value.remoteVersionName,
                remoteVersionCode = stateRef.value.remoteVersionCode
            )
            return
        }
        val restored = if (readyFile != null && readyFile.isFile) {
            stateRef.value.copy(
                currentVersionName = name,
                currentVersionCode = code,
                status = Status.READY_TO_INSTALL,
                progress = null,
                readyApkPath = readyFile.absolutePath
            )
        } else {
            stateRef.value.copy(currentVersionName = name, currentVersionCode = code)
        }
        stateRef.value = restored
    }

    suspend fun check(context: Context) {
        refreshInstalled(context)
        val current = stateRef.value.currentVersionCode
        stateRef.value = stateRef.value.copy(status = Status.CHECKING, detail = "", progress = null)
        try {
        when (val result = AppUpdateClient.fetchManifest(com.xingshi.ai.plugins.DownloadSources.APP_CATALOG_URL)) {
            is AppUpdateFetchResult.Failure -> {
                stateRef.value = stateRef.value.copy(status = Status.CHECK_FAILED, detail = result.code)
            }
            is AppUpdateFetchResult.Success -> {
                val remote = result.manifest
                manifest = remote
                stateRef.value = if (remote.versionCode > current) {
                    stateRef.value.copy(
                        status = Status.AVAILABLE,
                        remoteVersionName = remote.versionName,
                        remoteVersionCode = remote.versionCode,
                        releaseNotes = remote.releaseNotes,
                        sizeBytes = remote.sizeBytes,
                        detail = ""
                    )
                } else {
                    stateRef.value.copy(status = Status.UP_TO_DATE, detail = "")
                }
            }
        }
        } catch (e: CancellationException) {
            stateRef.value = stateRef.value.copy(status = Status.IDLE, detail = "", progress = null)
            throw e
        }
    }

    suspend fun download(context: Context) {
        val remote = manifest ?: return
        if (stateRef.value.status == Status.DOWNLOADING) return
        if (!downloadLock.tryLock()) return
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "xingshi-${remote.versionCode}.apk")
        val temp = File(dir, "xingshi-${remote.versionCode}.apk.tmp")
        try {
            stateRef.value = stateRef.value.copy(status = Status.DOWNLOADING, progress = 0f, detail = "")
            val outcome = AppUpdateClient.download(remote.apkUrl, temp) { received, total ->
                stateRef.value = stateRef.value.copy(
                    progress = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else null
                )
            }
            if (outcome.isFailure) {
                runCatching { temp.delete() }
                if (!currentCoroutineContext().isActive) {
                    stateRef.value = stateRef.value.copy(status = Status.AVAILABLE, progress = null, detail = "")
                    return
                }
                stateRef.value = stateRef.value.copy(
                    status = Status.DOWNLOAD_FAILED,
                    progress = null,
                    detail = (outcome.exceptionOrNull() as? AppUpdateClient.UpdateDownloadException)?.code ?: "network"
                )
                return
            }
            val verification = verify(context, remote, temp)
            if (verification != null) {
                runCatching { temp.delete() }
                stateRef.value = stateRef.value.copy(
                    status = Status.DOWNLOAD_FAILED,
                    progress = null,
                    detail = verification
                )
                return
            }
            if (target.exists()) target.delete()
            val moved = runCatching { temp.renameTo(target) }.getOrDefault(false)
            if (!moved) {
                runCatching { temp.delete() }
                stateRef.value = stateRef.value.copy(status = Status.DOWNLOAD_FAILED, progress = null, detail = "save")
                return
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(PENDING_VERSION, remote.versionCode)
                .putString(READY_PATH, target.absolutePath)
                .apply()
            stateRef.value = stateRef.value.copy(
                status = Status.READY_TO_INSTALL,
                progress = null,
                detail = "",
                readyApkPath = target.absolutePath
            )
        } catch (e: CancellationException) {
            runCatching { temp.delete() }
            stateRef.value = stateRef.value.copy(
                status = Status.AVAILABLE,
                progress = null,
                detail = ""
            )
            throw e
        } catch (e: Exception) {
            runCatching { temp.delete() }
            stateRef.value = stateRef.value.copy(
                status = Status.DOWNLOAD_FAILED,
                progress = null,
                detail = "operation_failed"
            )
        } finally {
            downloadLock.unlock()
        }
    }

    fun install(context: Context, apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.isFile) {
            stateRef.value = stateRef.value.copy(status = Status.INSTALL_FAILED, detail = "missing_file")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            openInstallPermission(context)
            stateRef.value = stateRef.value.copy(
                status = Status.READY_TO_INSTALL,
                detail = "needs_permission",
                readyApkPath = file.absolutePath
            )
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.xingshi.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            stateRef.value = stateRef.value.copy(status = Status.INSTALL_FAILED, detail = "no_installer")
            false
        }
    }

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    suspend fun cleanupOnStart(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getLong(PENDING_VERSION, 0L)
        val (_, installed) = installedVersion(context)
        val dir = File(context.filesDir, DIR_NAME)
        if (pending > 0L && installed >= pending) {
            runCatching { dir.deleteRecursively() }
            prefs.edit().remove(PENDING_VERSION).remove(READY_PATH).apply()
            return@withContext
        }
        val ready = prefs.getString(READY_PATH, "").orEmpty()
        if (ready.isNotBlank() && !File(ready).isFile) {
            prefs.edit().remove(READY_PATH).apply()
        }
        if (!dir.exists()) return@withContext
        dir.listFiles()?.forEach { file ->
            val keep = (pending > 0L && file.name == "xingshi-$pending.apk") ||
                (ready.isNotBlank() && file.absolutePath == ready)
            if (!keep) runCatching { file.delete() }
        }
    }

    private suspend fun verify(context: Context, remote: AppUpdateManifest, file: File): String? =
        withContext(Dispatchers.IO) {
            if (file.length() <= 0L) return@withContext "empty"
            if (remote.sizeBytes > 0L && file.length() != remote.sizeBytes) return@withContext "size"
            if (remote.sha256.length != 64 ||
                !AppUpdateClient.sha256(file).equals(remote.sha256, ignoreCase = true)
            ) {
                return@withContext "sha"
            }
            val pm = context.packageManager
            val archiveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            @Suppress("DEPRECATION")
            val archive = pm.getPackageArchiveInfo(file.absolutePath, archiveFlags or PackageManager.GET_META_DATA)
                ?: return@withContext "invalid_apk"
            if (!archive.packageName.equals(context.packageName, ignoreCase = false)) {
                return@withContext "package"
            }
            val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archive.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archive.versionCode.toLong()
            }
            if (archiveCode != remote.versionCode) return@withContext "version"
            val archiveMinSdk = archive.applicationInfo?.minSdkVersion ?: 0
            if (archiveMinSdk > Build.VERSION.SDK_INT || remote.minSdk > Build.VERSION.SDK_INT) {
                return@withContext "min_sdk"
            }
            val installed = pm.getPackageInfo(context.packageName, archiveFlags)
            val installedSignatures = signatures(installed)
            val archiveSignatures = signatures(archive)
            if (installedSignatures.isEmpty() || archiveSignatures.isEmpty()) {
                return@withContext "signature_unreadable"
            }
            if (installedSignatures.intersect(archiveSignatures).isEmpty()) {
                return@withContext "signature"
            }
            null
        }

    private fun signatures(info: PackageInfo): Set<String> = runCatching {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return@runCatching emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        certificates.orEmpty().map {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
    }.getOrDefault(emptySet())
}
