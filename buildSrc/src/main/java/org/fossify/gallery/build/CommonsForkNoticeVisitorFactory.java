package org.fossify.gallery.build;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Replaces Commons' hardcoded fork warning without changing the app's package identity. */
public abstract class CommonsForkNoticeVisitorFactory
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override
    public boolean isInstrumentable(ClassData classData) {
        return classData.getClassName().equals("org.fossify.commons.extensions.ActivityKt");
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return new CommonsForkNoticeClassVisitor(nextClassVisitor);
    }
}

/** Commons 7.1.3 has no override hook; keep this adapter limited to its one warning method. */
class CommonsForkNoticeClassVisitor extends ClassVisitor {
    static final String ORIGINAL_DESCRIPTOR = "(Lorg/fossify/commons/activities/BaseSimpleActivity;)V";
    static final String NOTICE_OWNER = "org/fossify/gallery/dialogs/ForkNoticeDialog";
    static final String NOTICE_DESCRIPTOR = "(Landroid/app/Activity;)V";
    private boolean replaced;

    CommonsForkNoticeClassVisitor(ClassVisitor delegate) {
        super(Opcodes.ASM9, delegate);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!name.equals("showModdedAppWarning") || !descriptor.equals(ORIGINAL_DESCRIPTOR)) {
            return method;
        }
        if ((access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("Commons fork warning is no longer static; review the adapter");
        }
        replaced = true;
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, NOTICE_OWNER, "show", NOTICE_DESCRIPTOR, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        // Returning null discards the original warning and its Google Play callback.
        return null;
    }

    @Override
    public void visitEnd() {
        if (!replaced) {
            throw new IllegalStateException("Commons fork warning changed; review the adapter before shipping");
        }
        super.visitEnd();
    }
}
