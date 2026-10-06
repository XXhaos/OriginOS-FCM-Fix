package io.github.originosfcmguardian.activation

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Modifier
import javax.net.ssl.SSLSocket

/** Opt-in availability diagnostic only, not a successful pairing/handshake assertion. */
class PairingApiAvailabilityDeviceTest {
    private enum class Result {
        AVAILABLE, NOT_ATTEMPTED, SIGNATURE_MISMATCH,
        CLASS_MISSING, METHOD_MISSING, SECURITY_DENIED,
        CLASS_LINKAGE, METHOD_LINKAGE, ACCESS_LINKAGE, NATIVE_LINKAGE,
        INITIALIZATION_LINKAGE, OTHER_LINKAGE, OTHER_FAILURE,
    }

    @Test fun reportFixedExporterAvailabilityWithoutInvokingIt() {
        assumeTrue("Explicit pairing API probe opt-in required",
            InstrumentationRegistry.getArguments().getString("guardianPairingApiProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loader = instrumentation.targetContext.classLoader
        // Exactly these two known API owners; no discovery, socket, key, settings or pairing calls.
        for ((label, name) in listOf(
            "CONSCRYPT" to "com.android.org.conscrypt.Conscrypt",
            "SSLSOCKETS" to "android.net.ssl.SSLSockets",
        )) {
            var type: Class<*>? = null
            val noInit = observe {
                type = Class.forName(name, false, loader)
                Result.AVAILABLE
            }
            val loaded = type
            val init = if (loaded == null) Result.NOT_ATTEMPTED else observe {
                Class.forName(name, true, loader)
                Result.AVAILABLE
            }
            val signature = if (loaded == null) Result.NOT_ATTEMPTED else observe {
                val method = loaded.getMethod("exportKeyingMaterial", SSLSocket::class.java,
                    String::class.java, ByteArray::class.java, Integer.TYPE)
                if (Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers) &&
                    method.returnType == ByteArray::class.java) Result.AVAILABLE
                else Result.SIGNATURE_MISMATCH
            }
            // Fixed labels/enums only. Never print Throwable, message, stack, class names or values.
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "$label/NO_INIT=$noInit $label/INIT=$init $label/SIGNATURE=$signature\n")
            })
        }
    }

    private fun observe(check: () -> Result): Result = try {
        check()
    } catch (_: ClassNotFoundException) { Result.CLASS_MISSING
    } catch (_: NoSuchMethodException) { Result.METHOD_MISSING
    } catch (_: SecurityException) { Result.SECURITY_DENIED
    } catch (_: NoClassDefFoundError) { Result.CLASS_LINKAGE
    } catch (_: NoSuchMethodError) { Result.METHOD_LINKAGE
    } catch (_: IllegalAccessError) { Result.ACCESS_LINKAGE
    } catch (_: UnsatisfiedLinkError) { Result.NATIVE_LINKAGE
    } catch (_: ExceptionInInitializerError) { Result.INITIALIZATION_LINKAGE
    } catch (_: LinkageError) { Result.OTHER_LINKAGE
    } catch (_: Throwable) { Result.OTHER_FAILURE }
}
