package dev.diona.pluginhooker.patch;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/**
 * Reusable bytecode transformation operators built on the JDK 25 {@link java.lang.classfile} API.
 */
public final class CodeTransforms {

    public static final ClassDesc BRIDGE_DESC = ClassDesc.of("dev.diona.pluginhooker.bridge.HookBridge");

    public static final MethodTypeDesc GUARD_MTD = MethodTypeDesc.ofDescriptor(
            "(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
    );

    public static final MethodTypeDesc FILTER_MTD = MethodTypeDesc.ofDescriptor(
            "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;"
    );

    public static final MethodTypeDesc NOTIFY_MTD = MethodTypeDesc.ofDescriptor(
            "(ILjava/lang/Object;Ljava/lang/Object;)V"
    );

    private CodeTransforms() {
    }

    /**
     * Specifies how to push a single Object argument onto the operand stack.
     */
    @FunctionalInterface
    public interface ArgSpec {
        void emit(CodeBuilder cb);

        static ArgSpec nullConst() {
            return CodeBuilder::aconst_null;
        }

        static ArgSpec thisRef() {
            return cb -> cb.aload(0);
        }

        static ArgSpec aload(int slot) {
            return cb -> cb.aload(slot);
        }

        static ArgSpec param(int paramIndex) {
            return cb -> cb.aload(cb.parameterSlot(paramIndex));
        }

        static ArgSpec getThisField(ClassDesc owner, String fieldName, ClassDesc fieldType) {
            return cb -> {
                cb.aload(0);
                cb.getfield(owner, fieldName, fieldType);
            };
        }
    }

    /**
     * Injects a guard check at the start of a void-returning method:
     * {@code if (HookBridge.checkGuard(hookId, arg0, arg1, arg2)) return;}
     */
    public static CodeTransform guardVoidEntry(int hookId, ArgSpec arg0, ArgSpec arg1, ArgSpec arg2) {
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cb) {
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                arg2.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "checkGuard", GUARD_MTD);
                Label continueLabel = cb.newLabel();
                cb.ifeq(continueLabel);
                cb.return_();
                cb.labelBinding(continueLabel);
            }

            @Override
            public void accept(CodeBuilder cb, CodeElement element) {
                cb.with(element);
            }
        };
    }

    /**
     * Injects a guard check at the start of a boolean-returning method:
     * {@code if (HookBridge.checkGuard(hookId, arg0, arg1, arg2)) return false;}
     */
    public static CodeTransform guardBooleanEntry(int hookId, ArgSpec arg0, ArgSpec arg1, ArgSpec arg2) {
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cb) {
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                arg2.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "checkGuard", GUARD_MTD);
                Label continueLabel = cb.newLabel();
                cb.ifeq(continueLabel);
                cb.iconst_0();
                cb.ireturn();
                cb.labelBinding(continueLabel);
            }

            @Override
            public void accept(CodeBuilder cb, CodeElement element) {
                cb.with(element);
            }
        };
    }

    /**
     * Intercepts all reference returns ({@code areturn}) and passes the return value through
     * {@code HookBridge.filterReturn(returnValue, hookId, receiver)}.
     */
    public static CodeTransform filterObjectReturn(int hookId, ClassDesc expectedReturnType, ArgSpec receiver) {
        return (cb, element) -> {
            if (element instanceof ReturnInstruction ret && ret.opcode() == Opcode.ARETURN) {
                cb.ldc(hookId);
                receiver.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "filterReturn", FILTER_MTD);
                if (!ConstantDescs.CD_Object.equals(expectedReturnType)) {
                    cb.checkcast(expectedReturnType);
                }
                cb.areturn();
            } else {
                cb.with(element);
            }
        };
    }

    /**
     * Injects a notification call {@code HookBridge.onNotify(hookId, arg0, arg1)} at method entry.
     */
    public static CodeTransform notifyAtEntry(int hookId, ArgSpec arg0, ArgSpec arg1) {
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cb) {
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "onNotify", NOTIFY_MTD);
            }

            @Override
            public void accept(CodeBuilder cb, CodeElement element) {
                cb.with(element);
            }
        };
    }

    /**
     * Injects a notification call {@code HookBridge.onNotify(hookId, arg0, arg1)} right before every return instruction.
     */
    public static CodeTransform notifyBeforeReturn(int hookId, ArgSpec arg0, ArgSpec arg1) {
        return (cb, element) -> {
            if (element instanceof ReturnInstruction) {
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "onNotify", NOTIFY_MTD);
            }
            cb.with(element);
        };
    }

    /**
     * Replaces a specific {@code invokeinterface} instruction with a static bridge call.
     */
    public static CodeTransform replaceInterfaceCall(
            String targetOwnerInternalName,
            String targetMethodName,
            String targetDescriptor,
            String bridgeMethodName,
            MethodTypeDesc bridgeDescriptor
    ) {
        return (cb, element) -> {
            if (element instanceof InvokeInstruction inv
                    && inv.opcode() == Opcode.INVOKEINTERFACE
                    && inv.owner().asInternalName().equals(targetOwnerInternalName)
                    && inv.name().equalsString(targetMethodName)
                    && inv.type().equalsString(targetDescriptor)) {
                cb.invokestatic(BRIDGE_DESC, bridgeMethodName, bridgeDescriptor);
            } else {
                cb.with(element);
            }
        };
    }
}
