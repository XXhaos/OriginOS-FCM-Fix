package io.github.originosfcmguardian.privileged

internal object IpcContract {
    const val NO_UID_ARGUMENT = -1

    const val KEY_EXIT_CODE = "exit_code"
    const val KEY_STDOUT = "stdout"
    const val KEY_STDERR = "stderr"
    const val KEY_TIMED_OUT = "timed_out"
    const val KEY_OUTPUT_TRUNCATED = "output_truncated"

    const val EXIT_INVALID_REQUEST = 64
    const val EXIT_PERMISSION_DENIED = 126
    const val EXIT_TIMED_OUT = 124
    const val EXIT_CANCELLED = 130
    const val EXIT_BRIDGE_ERROR = 125
}
