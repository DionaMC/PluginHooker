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

        static ArgSpec invokeThisGetter(ClassDesc owner, String methodName, ClassDesc returnType) {
            return cb -> {
                cb.aload(0);
                cb.invokevirtual(owner, methodName, MethodTypeDesc.of(returnType));
            };
        }

        static ArgSpec callerClass() {
            ClassDesc swDesc = ClassDesc.of("java.lang.StackWalker");
            MethodTypeDesc getCallerMtd = MethodTypeDesc.of(ConstantDescs.CD_Class);
            return cb -> {
                cb.getstatic(BRIDGE_DESC, "CALLER_WALKER", swDesc);
                cb.invokevirtual(swDesc, "getCallerClass", getCallerMtd);
            };
        }
    }

    /**
     * Injects a guard check at the start of a void-returning method:
     * {@code if (HookBridge.HAS_HOOKED_PLUGINS && HookBridge.checkGuard(hookId, arg0, arg1, arg2)) return;}
     */
    public static CodeTransform guardVoidEntry(int hookId, ArgSpec arg0, ArgSpec arg1, ArgSpec arg2) {
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cb) {
                Label continueLabel = cb.newLabel();
                cb.getstatic(BRIDGE_DESC, "HAS_HOOKED_PLUGINS", ConstantDescs.CD_boolean);
                cb.ifeq(continueLabel);
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                arg2.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "checkGuard", GUARD_MTD);
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
     * {@code if (HookBridge.HAS_HOOKED_PLUGINS && HookBridge.checkGuard(hookId, arg0, arg1, arg2)) return false;}
     */
    public static CodeTransform guardBooleanEntry(int hookId, ArgSpec arg0, ArgSpec arg1, ArgSpec arg2) {
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cb) {
                Label continueLabel = cb.newLabel();
                cb.getstatic(BRIDGE_DESC, "HAS_HOOKED_PLUGINS", ConstantDescs.CD_boolean);
                cb.ifeq(continueLabel);
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                arg2.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "checkGuard", GUARD_MTD);
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
     * Injects a guard check at the start of a {@code CompletableFuture<Boolean>}-returning method (e.g. Paper's {@code teleportAsync}):
     * {@code if (HookBridge.HAS_HOOKED_PLUGINS && HookBridge.checkGuard(hookId, arg0, arg1, arg2)) return CompletableFuture.completedFuture(Boolean.FALSE);}
     */
    public static CodeTransform guardCompletableFutureBooleanEntry(int hookId, ArgSpec arg0, ArgSpec arg1, ArgSpec arg2) {
        ClassDesc cfDesc = ClassDesc.of("java.util.concurrent.CompletableFuture");
        MethodTypeDesc completedFutureMtd = MethodTypeDesc.ofDescriptor(
                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;"
        );
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cb) {
                Label continueLabel = cb.newLabel();
                cb.getstatic(BRIDGE_DESC, "HAS_HOOKED_PLUGINS", ConstantDescs.CD_boolean);
                cb.ifeq(continueLabel);
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                arg2.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "checkGuard", GUARD_MTD);
                cb.ifeq(continueLabel);
                cb.getstatic(ConstantDescs.CD_Boolean, "FALSE", ConstantDescs.CD_Boolean);
                cb.invokestatic(cfDesc, "completedFuture", completedFutureMtd);
                cb.areturn();
                cb.labelBinding(continueLabel);
            }

            @Override
            public void accept(CodeBuilder cb, CodeElement element) {
                cb.with(element);
            }
        };
    }

    /**
     * Injects a guard check at the start of Netty's 1-arg {@code AbstractChannelHandlerContext#write(Object)}
     * (returning {@code ChannelFuture}):
     * {@code if (HookBridge.checkGuard(hookId, this, msg, null)) return this.newSucceededFuture();}
     */
    public static CodeTransform guardNettyChannelFutureEntry(int hookId, ArgSpec arg0, ArgSpec arg1, ArgSpec arg2) {
        return guardNettyChannelFutureEntry(hookId, arg0, arg1, arg2, -1);
    }

    /**
     * Injects a guard check at the start of a Netty {@code AbstractChannelHandlerContext} outbound method
     * returning {@code ChannelFuture}. If {@code promiseParamIndex >= 0} and the passed {@code ChannelPromise}
     * argument is non-null, returns that promise directly; otherwise returns {@code this.newSucceededFuture()}.
     */
    public static CodeTransform guardNettyChannelFutureEntry(
            int hookId,
            ArgSpec arg0,
            ArgSpec arg1,
            ArgSpec arg2,
            int promiseParamIndex
    ) {
        ClassDesc ctxDesc = ClassDesc.of("io.netty.channel.AbstractChannelHandlerContext");
        MethodTypeDesc succeededFutureMtd = MethodTypeDesc.ofDescriptor("()Lio/netty/channel/ChannelFuture;");
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder cb) {
                Label continueLabel = cb.newLabel();
                cb.getstatic(BRIDGE_DESC, "HAS_HOOKED_PLUGINS", ConstantDescs.CD_boolean);
                cb.ifeq(continueLabel);
                cb.ldc(hookId);
                arg0.emit(cb);
                arg1.emit(cb);
                arg2.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "checkGuard", GUARD_MTD);
                cb.ifeq(continueLabel);
                if (promiseParamIndex >= 0) {
                    int promiseSlot = cb.parameterSlot(promiseParamIndex);
                    Label nullPromiseLabel = cb.newLabel();
                    cb.aload(promiseSlot);
                    cb.ifnull(nullPromiseLabel);
                    cb.aload(promiseSlot);
                    cb.areturn();
                    cb.labelBinding(nullPromiseLabel);
                }
                cb.aload(0);
                cb.invokevirtual(ctxDesc, "newSucceededFuture", succeededFutureMtd);
                cb.areturn();
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
     * {@code HookBridge.filterReturn(returnValue, hookId, receiver)} when hooked plugins are active.
     */
    public static CodeTransform filterObjectReturn(int hookId, ClassDesc expectedReturnType, ArgSpec receiver) {
        return (cb, element) -> {
            if (element instanceof ReturnInstruction ret && ret.opcode() == Opcode.ARETURN) {
                Label returnDirectlyLabel = cb.newLabel();
                cb.getstatic(BRIDGE_DESC, "HAS_HOOKED_PLUGINS", ConstantDescs.CD_boolean);
                cb.ifeq(returnDirectlyLabel);
                cb.ldc(hookId);
                receiver.emit(cb);
                cb.invokestatic(BRIDGE_DESC, "filterReturn", FILTER_MTD);
                if (!ConstantDescs.CD_Object.equals(expectedReturnType)) {
                    cb.checkcast(expectedReturnType);
                }
                cb.labelBinding(returnDirectlyLabel);
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
     * Wraps {@code PacketListener#onPacketSending(PacketEvent)} and {@code PacketListener#onPacketReceiving(PacketEvent)}
     * interface invocations inside ProtocolLib's {@code AsyncListenerHandler} with a guard check, avoiding brittle
     * reliance on compiler-generated synthetic lambda method names while executing inside {@code AsyncMarker}'s lock.
     */
    public static CodeTransform guardProtocolLibListenerCalls(int outboundHookId, int inboundHookId) {
        return (cb, element) -> {
            if (element instanceof InvokeInstruction inv
                    && inv.opcode() == Opcode.INVOKEINTERFACE
                    && inv.owner().asInternalName().equals("com/comphenix/protocol/events/PacketListener")
                    && inv.type().equalsString("(Lcom/comphenix/protocol/events/PacketEvent;)V")) {
                String methodName = inv.name().stringValue();
                int hookId = methodName.equals("onPacketSending")
                        ? outboundHookId
                        : (methodName.equals("onPacketReceiving") ? inboundHookId : -1);
                if (hookId != -1) {
                    // Stack before call: [..., listener, event]
                    Label invokeLabel = cb.newLabel();
                    Label doneLabel = cb.newLabel();
                    cb.getstatic(BRIDGE_DESC, "HAS_HOOKED_PLUGINS", ConstantDescs.CD_boolean);
                    cb.ifeq(invokeLabel);
                    cb.dup2();        // [..., listener, event, listener, event]
                    cb.swap();        // [..., listener, event, event, listener]
                    cb.ldc(hookId);   // [..., listener, event, event, listener, hookId]
                    cb.dup_x2();      // [..., listener, event, hookId, event, listener, hookId]
                    cb.pop();         // [..., listener, event, hookId, event, listener]
                    cb.aconst_null(); // [..., listener, event, hookId, event, listener, null]
                    cb.invokestatic(BRIDGE_DESC, "checkGuard", GUARD_MTD);
                    cb.ifeq(invokeLabel);
                    cb.pop2();        // blocked -> drop [listener, event]
                    cb.goto_(doneLabel);
                    cb.labelBinding(invokeLabel);
                    cb.with(element); // allowed -> invoke original PacketListener method
                    cb.labelBinding(doneLabel);
                    return;
                }
            }
            cb.with(element);
        };
    }

    /**
     * Replaces a Netty handler invocation ({@code invokeinterface} or Netty 4.2 devirtualized {@code invokevirtual})
     * inside {@code AbstractChannelHandlerContext} with a static bridge call, excluding {@code HeadContext}.
     */
    public static CodeTransform replaceNettyHandlerCall(
            String targetMethodName,
            String targetDescriptor,
            String bridgeMethodName,
            MethodTypeDesc bridgeDescriptor
    ) {
        return (cb, element) -> {
            if (element instanceof InvokeInstruction inv
                    && (inv.opcode() == Opcode.INVOKEINTERFACE || inv.opcode() == Opcode.INVOKEVIRTUAL)
                    && inv.name().equalsString(targetMethodName)
                    && inv.type().equalsString(targetDescriptor)
                    && !inv.owner().asInternalName().endsWith("HeadContext")) {
                cb.invokestatic(BRIDGE_DESC, bridgeMethodName, bridgeDescriptor);
            } else {
                cb.with(element);
            }
        };
    }

    /**
     * Wraps {@code io.netty.channel.Channel#write} and {@code writeAndFlush} invocations inside raw NMS
     * {@code Connection}/{@code NetworkManager} classes with one-shot notifications so {@code TailContext}
     * recognizes server-dispatched NMS packet writes in O(1) without {@link StackWalker#walk}.
     */
    public static CodeTransform wrapNmsChannelWriteCalls(int notifyHookId) {
        return (cb, element) -> {
            if (isChannelWriteCall(element)) {
                cb.ldc(notifyHookId);
                cb.getstatic(ConstantDescs.CD_Boolean, "TRUE", ConstantDescs.CD_Boolean);
                cb.aconst_null();
                cb.invokestatic(BRIDGE_DESC, "onNotify", NOTIFY_MTD);
                cb.with(element);
                cb.ldc(notifyHookId);
                cb.aconst_null();
                cb.aconst_null();
                cb.invokestatic(BRIDGE_DESC, "onNotify", NOTIFY_MTD);
            } else {
                cb.with(element);
            }
        };
    }

    public static boolean isChannelWriteCall(CodeElement element) {
        if (!(element instanceof InvokeInstruction inv)
                || inv.opcode() != Opcode.INVOKEINTERFACE
                || !inv.owner().asInternalName().equals("io/netty/channel/Channel")) {
            return false;
        }
        String name = inv.name().stringValue();
        return name.equals("write") || name.equals("writeAndFlush");
    }
}
