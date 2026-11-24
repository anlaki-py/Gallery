package org.fossify.gallery.build;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.Assert.*;

public class CommonsForkNoticeClassVisitorTest {
    @Test
    public void replacesWarningAndStoreRedirectButPreservesOtherMethods() {
        ClassWriter result = new ClassWriter(0);
        new ClassReader(originalClass(CommonsForkNoticeClassVisitor.ORIGINAL_DESCRIPTOR))
                .accept(new CommonsForkNoticeClassVisitor(result), 0);
        List<String> calls = new ArrayList<>();
        List<Object> constants = new ArrayList<>();
        new ClassReader(result.toByteArray()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        calls.add(name + ":" + owner + "." + method + desc);
                        assertEquals(Opcodes.INVOKESTATIC, opcode);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        constants.add(value);
                    }
                };
            }
        }, 0);
        assertEquals(List.of("showModdedAppWarning:"
                + CommonsForkNoticeClassVisitor.NOTICE_OWNER + ".show"
                + CommonsForkNoticeClassVisitor.NOTICE_DESCRIPTOR), calls);
        assertEquals(List.of("unchanged"), constants);
    }

    @Test
    public void rejectsChangedUpstreamMethodInsteadOfSilentlyKeepingTheOldWarning() {
        try {
            new ClassReader(originalClass("(Landroid/app/Activity;)V"))
                    .accept(new CommonsForkNoticeClassVisitor(new ClassWriter(0)), 0);
            fail("A changed Commons API must stop the build");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Commons fork warning changed"));
        }
    }

    private byte[] originalClass(String descriptor) {
        ClassWriter original = new ClassWriter(0);
        original.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "org/fossify/commons/extensions/ActivityKt",
                null, "java/lang/Object", null);
        MethodVisitor warning = original.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "showModdedAppWarning", descriptor, null, null);
        warning.visitCode();
        warning.visitLdcInsn("You are using a fake version of the app");
        warning.visitInsn(Opcodes.POP);
        warning.visitVarInsn(Opcodes.ALOAD, 0);
        warning.visitMethodInsn(Opcodes.INVOKESTATIC, "example/StoreRedirect", "open", descriptor, false);
        warning.visitInsn(Opcodes.RETURN);
        warning.visitMaxs(1, 1);
        warning.visitEnd();
        MethodVisitor other = original.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "unrelated", "()Ljava/lang/String;", null, null);
        other.visitCode();
        other.visitLdcInsn("unchanged");
        other.visitInsn(Opcodes.ARETURN);
        other.visitMaxs(1, 0);
        other.visitEnd();
        original.visitEnd();
        return original.toByteArray();
    }
}
