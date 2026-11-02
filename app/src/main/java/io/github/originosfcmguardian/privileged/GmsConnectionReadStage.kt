package io.github.originosfcmguardian.privileged

/** Bounded diagnostic categories only; never exception text or an installation identity. */
enum class GmsConnectionReadStage {
    INTERACTIVE_ADMISSION, READ_SESSION, AVAILABILITY, BIND, SERVICE_IDENTITY, OBSERVATION, RESPONSE,
}
