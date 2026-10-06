package io.github.originosfcmguardian.buildlogic;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.objectweb.asm.ClassVisitor;
import java.io.IOException;
import java.nio.file.Files;

public abstract class PairingExporterVisitorFactory implements AsmClassVisitorFactory<PairingExporterVisitorFactory.Parameters> {
    public interface Parameters extends InstrumentationParameters {
        @InputFile @PathSensitive(PathSensitivity.NONE) RegularFileProperty getVerifiedArtifact();
    }
    @Override public boolean isInstrumentable(ClassData data) {
        return PairingCallRedirect.TARGET_CLASS.replace('/', '.').equals(data.getClassName());
    }
    @Override public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        try {
            String verified = Files.readString(getParameters().get().getVerifiedArtifact().get().getAsFile().toPath()).trim();
            if (!VerifyPinnedPairingArtifact.SHA256.equals(verified)) throw new IllegalStateException("Pinned pairing artifact verification missing");
        } catch (IOException failure) { throw new IllegalStateException("Pinned pairing artifact verification missing", failure); }
        return new PairingCallRedirect(next);
    }
}
