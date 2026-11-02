package io.github.originosfcmguardian.update

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AppUpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, PREPARING_INSTALL, INSTALLING, FAILED }
internal data class AppUpdateUiState(val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val update: AvailableAppUpdate? = null, val percent: Int = 0, val message: String? = null,
    val launch: AppUpdateLaunch? = null) {
    val busy get() = phase in setOf(AppUpdatePhase.CHECKING, AppUpdatePhase.DOWNLOADING,
        AppUpdatePhase.PREPARING_INSTALL, AppUpdatePhase.INSTALLING)
}
internal data class AppUpdateLaunch(val sequence: Long, val intent: Intent, val permission: Boolean)

/** Activity-retained, event-driven UI work. No update work is scheduled at app or daemon startup. */
internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GitHubUpdateRepository(application)
    private val mutableState = MutableStateFlow(AppUpdateUiState())
    val state = mutableState.asStateFlow()
    private var sequence = 0L

    fun check() {
        if (state.value.busy) return
        mutableState.value = AppUpdateUiState(AppUpdatePhase.CHECKING)
        viewModelScope.launch {
            try {
                val available = withContext(Dispatchers.IO) { repository.check() }
                mutableState.value = if (available == null) AppUpdateUiState(AppUpdatePhase.CURRENT,
                    message = "已是最新版本") else AppUpdateUiState(AppUpdatePhase.AVAILABLE, available)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.value = AppUpdateUiState(AppUpdatePhase.FAILED,
                message = "检查失败，请稍后重试") }
        }
    }

    fun download() {
        val current = state.value
        val update = current.update ?: return
        if (current.phase != AppUpdatePhase.AVAILABLE) return
        mutableState.value = current.copy(phase = AppUpdatePhase.DOWNLOADING, percent = 0, message = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val coroutine = coroutineContext
                    repository.download(update, { coroutine.ensureActive() }) { percent ->
                        mutableState.update { it.copy(percent = percent) }
                    }
                }
                mutableState.update { it.copy(phase = AppUpdatePhase.READY, message = "下载完成") }
                prepareInstall(allowPermissionRequest = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableState.update { it.copy(phase = AppUpdatePhase.AVAILABLE,
                message = "下载或校验失败，请重试") } }
        }
    }

    fun prepareInstall(allowPermissionRequest: Boolean = true) {
        val current = state.value
        val update = current.update ?: return
        if (current.phase != AppUpdatePhase.READY) return
        mutableState.value = current.copy(phase = AppUpdatePhase.PREPARING_INSTALL, launch = null)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val apk = withContext(Dispatchers.IO) { repository.validatedApk(update) }
                val decision = updateInstallDecision(apkValid = true,
                    permissionGranted = app.packageManager.canRequestPackageInstalls(), allowPermissionRequest)
                val request = when (decision.launch) {
                    UpdateInstallLaunch.SOURCE_PERMISSION -> AppUpdateLaunch(++sequence,
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri()),
                        permission = true)
                    UpdateInstallLaunch.INSTALLER -> {
                        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.clipData = ClipData.newRawUri("Guardian update", uri)
                        AppUpdateLaunch(++sequence, intent, permission = false)
                    }
                    UpdateInstallLaunch.NONE -> null
                }
                mutableState.update { it.copy(phase = decision.phase, message = decision.message, launch = request) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                val decision = updateInstallDecision(apkValid = false, permissionGranted = false, allowPermissionRequest)
                mutableState.update { it.copy(phase = decision.phase, launch = null, message = decision.message) }
            }
        }
    }

    fun consumeLaunch(sequence: Long) {
        mutableState.update { if (it.launch?.sequence == sequence) it.copy(launch = null) else it }
    }

    fun permissionReturned() {
        if (state.value.phase == AppUpdatePhase.READY) prepareInstall(allowPermissionRequest = false)
    }

    fun installerReturned() {
        mutableState.update(::updateInstallerReturned)
    }

    fun launchFailed() {
        mutableState.update { it.copy(phase = AppUpdatePhase.READY, launch = null, message = "无法打开安装页面，请重试") }
    }
}
