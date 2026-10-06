package io.github.originosfcmguardian.buildlogic;

import org.junit.Test;
import org.objectweb.asm.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PairingCallRedirectTest {
    private byte[] fixture(String className, String method, String methodDesc, int opcode,
                           String owner, String call, String callDesc, int count, boolean unrelated) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        addMethod(writer, method, methodDesc, opcode, owner, call, callDesc, count);
        if (unrelated) addMethod(writer, "unrelated", PairingCallRedirect.TARGET_DESCRIPTOR,
            Opcodes.INVOKESTATIC, PairingCallRedirect.OLD_OWNER, PairingCallRedirect.EXPORT_METHOD,
            PairingCallRedirect.EXPORT_DESCRIPTOR, 1);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void addMethod(ClassWriter writer, String name, String descriptor, int opcode,
                           String owner, String call, String callDescriptor, int count) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, name, descriptor, null, null);
        method.visitCode();
        for (int i = 0; i < count; i++) {
            method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL); method.visitIntInsn(Opcodes.BIPUSH, 64);
            method.visitMethodInsn(opcode, owner, call, callDescriptor, false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(4, 1); method.visitEnd();
    }

    private byte[] redirect(byte[] input) {
        ClassWriter output = new ClassWriter(0);
        new ClassReader(input).accept(new PairingCallRedirect(output), 0);
        return output.toByteArray();
    }

    @Test public void redirectsOneExactCallAndLeavesOtherMethodsAndDescriptorAlone() {
        byte[] output = redirect(fixture(PairingCallRedirect.TARGET_CLASS, PairingCallRedirect.TARGET_METHOD,
            PairingCallRedirect.TARGET_DESCRIPTOR, Opcodes.INVOKESTATIC, PairingCallRedirect.OLD_OWNER,
            PairingCallRedirect.EXPORT_METHOD, PairingCallRedirect.EXPORT_DESCRIPTOR, 1, true));
        List<String> calls = new ArrayList<>();
        new ClassReader(output).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String call, String desc, boolean isInterface) {
                        assertEquals(Opcodes.INVOKESTATIC, opcode);
                        assertEquals(PairingCallRedirect.EXPORT_METHOD, call);
                        assertEquals(PairingCallRedirect.EXPORT_DESCRIPTOR, desc);
                        assertFalse(isInterface);
                        calls.add(name + ":" + owner);
                    }
                };
            }
        }, 0);
        assertEquals(List.of(PairingCallRedirect.TARGET_METHOD + ":" + PairingCallRedirect.BRIDGE_OWNER,
            "unrelated:" + PairingCallRedirect.OLD_OWNER), calls);
    }

    @Test public void rejectsEveryNonExactMatchInsteadOfSilentlyApplyingIt() {
        for (int changed = 0; changed < 7; changed++) {
            final int which = changed;
            assertThrows(IllegalStateException.class, () -> redirect(fixture(
                which == 0 ? "other/Class" : PairingCallRedirect.TARGET_CLASS,
                which == 1 ? "otherMethod" : PairingCallRedirect.TARGET_METHOD,
                which == 2 ? "()Ljava/lang/Object;" : PairingCallRedirect.TARGET_DESCRIPTOR,
                which == 3 ? Opcodes.INVOKEVIRTUAL : Opcodes.INVOKESTATIC,
                which == 4 ? "other/Owner" : PairingCallRedirect.OLD_OWNER,
                which == 5 ? "otherCall" : PairingCallRedirect.EXPORT_METHOD,
                which == 6 ? "(Ljavax/net/ssl/SSLSocket;Ljava/lang/String;[BI)Ljava/lang/Object;" : PairingCallRedirect.EXPORT_DESCRIPTOR,
                1, false)));
        }
    }

    @Test public void rejectsAbsentAndDuplicateCalls() {
        for (int count : new int[] {0, 2}) assertThrows(IllegalStateException.class, () -> redirect(fixture(
            PairingCallRedirect.TARGET_CLASS, PairingCallRedirect.TARGET_METHOD, PairingCallRedirect.TARGET_DESCRIPTOR,
            Opcodes.INVOKESTATIC, PairingCallRedirect.OLD_OWNER, PairingCallRedirect.EXPORT_METHOD,
            PairingCallRedirect.EXPORT_DESCRIPTOR, count, false)));
    }
}
