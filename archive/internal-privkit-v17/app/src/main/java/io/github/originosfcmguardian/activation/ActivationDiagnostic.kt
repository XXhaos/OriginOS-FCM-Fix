package io.github.originosfcmguardian.activation

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Only observed public call boundaries, never inferred network handshake internals. */
enum class ActivationStage {
    PREFLIGHT, WIRELESS_PREPARE, PAIRING_DISCOVERY, PAIRING_REQUEST,
    CONNECT_DISCOVERY, START_REQUEST, IDENTITY_BEFORE, CLEANUP, IDENTITY_AFTER,
}

enum class ActivationFailureKind {
    TIMEOUT, CANCELLED, SECURITY, CONNECT, TLS, IO, INCOMPATIBLE, PRECONDITION, UNKNOWN,
}

/** Enums only: no exception, class name, message, port, key, code or protocol payload retained. */
data class ActivationDiagnostic(val stage: ActivationStage, val kind: ActivationFailureKind) {
    val code: String get() = "${stage.name}/${kind.name}"
    val guidance: String get() = when (kind) {
        ActivationFailureKind.TIMEOUT -> when (stage) {
            ActivationStage.PAIRING_DISCOVERY -> "未发现配对端口。重新主动开始通知配对，并打开系统配对码对话框。"
            ActivationStage.CONNECT_DISCOVERY -> "未发现连接端口。核对无线调试主页面，手动填写连接端口后重试。"
            ActivationStage.IDENTITY_BEFORE, ActivationStage.IDENTITY_AFTER -> "身份核验超时，当前服务不可用。请手动重新启动并核验。"
            ActivationStage.CLEANUP -> "无线清理超时。请手动关闭无线调试并确认。"
            ActivationStage.START_REQUEST -> "启动接口超时。核对无线调试主页面及连接端口后重试。"
            else -> "操作超时。保持系统配对对话框开启，核对对应端口后重试。"
        }
        ActivationFailureKind.CANCELLED -> "操作已取消；迟到结果不会采用。"
        ActivationFailureKind.SECURITY -> "系统拒绝此接口。检查一次性授权与无线调试条件。"
        ActivationFailureKind.CONNECT -> "连接请求未建立。核对无线调试状态和对应端口。"
        ActivationFailureKind.TLS -> "安全连接接口失败。重新打开系统配对对话框并核对对应端口。"
        ActivationFailureKind.IO -> "接口读写失败。请按当前阶段检查条件后重试。"
        ActivationFailureKind.INCOMPATIBLE -> "内置依赖接口未能链接，当前操作未完成。"
        ActivationFailureKind.PRECONDITION -> "前置条件或事务状态未满足，请按页面提示处理。"
        ActivationFailureKind.UNKNOWN -> "此阶段未通过。核对一次性授权、无线调试和对应端口。"
    }
}

internal fun activationDiagnostic(stage: ActivationStage, failure: Throwable): ActivationDiagnostic {
    val seen = IdentityHashMap<Throwable, Boolean>()
    val chain = ArrayList<Throwable>(8)
    var current: Throwable? = failure
    repeat(8) {
        val item = current ?: return@repeat
        if (seen.put(item, true) != null) { current = null; return@repeat }
        chain += item
        current = runCatching { item.cause }.getOrNull()
    }
    val kind = when {
        chain.any { it is TimeoutCancellationException || it is SocketTimeoutException } -> ActivationFailureKind.TIMEOUT
        chain.any { it is CancellationException } -> ActivationFailureKind.CANCELLED
        chain.any { it is LinkageError } -> ActivationFailureKind.INCOMPATIBLE
        chain.any { it is SecurityException } -> ActivationFailureKind.SECURITY
        chain.any { it is SSLException } -> ActivationFailureKind.TLS
        chain.any { it is ConnectException } -> ActivationFailureKind.CONNECT
        chain.any { it is IOException } -> ActivationFailureKind.IO
        else -> ActivationFailureKind.UNKNOWN
    }
    return ActivationDiagnostic(stage, kind)
}

/** Cleanup cannot erase the stage of the primary operation failure. */
internal fun primaryActivationDiagnostic(
    operation: ActivationDiagnostic?, cleanup: ActivationDiagnostic?,
): ActivationDiagnostic? = operation ?: cleanup

/** Also guards completion of a cancelled generation, which no longer passes accepts(). */
internal fun activationDiagnosticBelongsToOperation(activeTicket: Long?, resultTicket: Long): Boolean =
    activeTicket == resultTicket
