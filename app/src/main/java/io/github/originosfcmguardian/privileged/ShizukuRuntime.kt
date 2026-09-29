package io.github.originosfcmguardian.privileged

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuRuntime(
    private val context: Context,
    val executor: ShizukuExecutor = ShizukuExecutor(context),
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val _status = MutableStateFlow(
        ShizukuStatusSnapshot(ShizukuStatus.ERROR, "正在读取 Shizuku 状态"),
    )
    val status: StateFlow<ShizukuStatusSnapshot> = _status.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        executor.invalidate()
        refresh()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE && grantResult != PackageManager.PERMISSION_GRANTED) {
                executor.cleanupBestEffort()
            }
            refresh()
        }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.openSession()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun refresh() {
        val installed = isManagerInstalled()
        val snapshot = runCatching {
            val binderAlive = Shizuku.pingBinder()
            val serverUid = if (binderAlive) Shizuku.getUid() else null
            val granted = binderAlive &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val state = classifyShizukuStatus(installed, binderAlive, serverUid, granted)
            ShizukuStatusSnapshot(state, state.detail(serverUid))
        }.getOrElse { error ->
            ShizukuStatusSnapshot(
                ShizukuStatus.ERROR,
                "Shizuku 状态读取失败：${error.message?.take(120) ?: error.javaClass.simpleName}",
            )
        }
        _status.value = snapshot
    }

    fun requestOrOpen() {
        when (_status.value.status) {
            ShizukuStatus.PERMISSION_REQUIRED -> runCatching {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }.onFailure { refresh() }

            ShizukuStatus.NOT_RUNNING -> openShizukuManager()
            ShizukuStatus.NOT_INSTALLED -> openShizukuDownloadPage()
            else -> refresh()
        }
    }

    override fun close() {
        if (!started.compareAndSet(true, false)) return
        executor.closePermanently()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    private fun isManagerInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun openShizukuManager() {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun openShizukuDownloadPage() {
        val intent = Intent(Intent.ACTION_VIEW, SHIZUKU_DOWNLOAD_URL.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun ShizukuStatus.detail(serverUid: Int?): String = when (this) {
        ShizukuStatus.NOT_INSTALLED -> "未安装；可打开官方 Shizuku 下载页"
        ShizukuStatus.NOT_RUNNING -> "已安装，但服务未启动"
        ShizukuStatus.PERMISSION_REQUIRED -> "服务已启动，等待 Guardian 授权"
        ShizukuStatus.READY_SHELL -> "已连接 shell（UID 2000）"
        ShizukuStatus.READY_ROOT -> "检测到 Root 模式（UID 0）；本项目明确不使用 Root"
        ShizukuStatus.ERROR -> "服务身份异常（UID ${serverUid ?: "未知"}）"
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 41_730
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }
}
