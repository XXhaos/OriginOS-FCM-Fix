package io.github.originosfcmguardian.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public abstract class VerifyPinnedPairingArtifact extends DefaultTask {
    public static final String SHA256 = "3f4cdf2d11340b813edfefa7005399a07f860898a967a48b3fa8ccc2f61d2cbe";
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getAarFile();
    @OutputFile public abstract RegularFileProperty getVerifiedArtifact();

    @TaskAction public void verify() throws Exception {
        var aar = getAarFile().get().getAsFile();
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(aar.toPath())));
        if (!SHA256.equals(actual)) throw new IllegalStateException("Priv Kit 0.16.1 AAR hash mismatch; redirect not applied");
        int classes = 0;
        try (ZipFile archive = new ZipFile(aar)) {
            var entry = archive.getEntry("classes.jar");
            if (entry == null) throw new IllegalStateException("Pinned pairing classes missing");
            try (var input = archive.getInputStream(entry);
                 var jar = new ZipInputStream(new ByteArrayInputStream(input.readAllBytes()))) {
                for (var item = jar.getNextEntry(); item != null; item = jar.getNextEntry()) {
                    if ((PairingCallRedirect.TARGET_CLASS + ".class").equals(item.getName())) {
                        classes++;
                        new ClassReader(jar.readAllBytes()).accept(new PairingCallRedirect(new ClassVisitor(Opcodes.ASM9) {}), 0);
                    }
                }
            }
        }
        if (classes != 1) throw new IllegalStateException("Pinned pairing target class must exist exactly once");
        var marker = getVerifiedArtifact().get().getAsFile().toPath();
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, SHA256 + "\n");
    }
}
