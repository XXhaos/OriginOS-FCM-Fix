package io.github.originosfcmguardian.buildlogic;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** One pinned callsite only. Stack, arguments, provider and protocol stay unchanged. */
public final class PairingCallRedirect extends ClassVisitor {
    public static final String TARGET_CLASS = "priv/kit/core/adb/PrivilegeAdbPairingClient";
    public static final String TARGET_METHOD = "setupTlsConnection";
    public static final String TARGET_DESCRIPTOR = "()Lpriv/kit/adb/crypto/pairing/PrivilegeAdbPairingCryptoContext;";
    public static final String OLD_OWNER = "com/android/org/conscrypt/Conscrypt";
    public static final String BRIDGE_OWNER = "io/github/originosfcmguardian/activation/PairingTlsExporterBridge";
    public static final String EXPORT_METHOD = "exportKeyingMaterial";
    public static final String EXPORT_DESCRIPTOR = "(Ljavax/net/ssl/SSLSocket;Ljava/lang/String;[BI)[B";
    private boolean targetClass;
    private int replacements;

    public PairingCallRedirect(ClassVisitor next) { super(Opcodes.ASM9, next); }

    @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
        targetClass = TARGET_CLASS.equals(name);
        super.visit(version, access, name, signature, parent, interfaces);
    }

    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!targetClass || !TARGET_METHOD.equals(name) || !TARGET_DESCRIPTOR.equals(descriptor)) return next;
        return new MethodVisitor(Opcodes.ASM9, next) {
            @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                if (opcode == Opcodes.INVOKESTATIC && !isInterface && OLD_OWNER.equals(owner) &&
                        EXPORT_METHOD.equals(method) && EXPORT_DESCRIPTOR.equals(desc)) {
                    replacements++;
                    super.visitMethodInsn(opcode, BRIDGE_OWNER, method, desc, false);
                } else super.visitMethodInsn(opcode, owner, method, desc, isInterface);
            }
        };
    }

    @Override public void visitEnd() {
        if (!targetClass || replacements != 1) throw new IllegalStateException("Pinned pairing callsite must match exactly once");
        super.visitEnd();
    }
}
