package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.patch.ClassHierarchySupport;
import dev.diona.pluginhooker.util.LoaderClassSet;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

/**
 * Identifies direct and multi-level {@code RegisteredListener} subclasses that override {@code callEvent(Event)}
 * without purely delegating to {@code super.callEvent(event)}.
 */
final class RegisteredListenerMatcher {

    static final String TARGET_CLASS = "org/bukkit/plugin/RegisteredListener";
    private static final String TARGET_CLASS_DOT = "org.bukkit.plugin.RegisteredListener";
    private static final String TIMED_TARGET_CLASS = "org/bukkit/plugin/TimedRegisteredListener";
    private static final String EVENT_CLASS = "org/bukkit/event/Event";
    private static final String EVENT_CLASS_DOT = "org.bukkit.event.Event";

    private static final String CALL_EVENT = "callEvent";
    private static final String CALL_EVENT_TYPE = "(Lorg/bukkit/event/Event;)V";
    private static final byte[] CALL_EVENT_UTF8 = CALL_EVENT.getBytes(StandardCharsets.UTF_8);
    private static final byte[] CALL_EVENT_TYPE_UTF8 = CALL_EVENT_TYPE.getBytes(StandardCharsets.UTF_8);

    private final LoaderClassSet verifiedPassthroughClasses = new LoaderClassSet();

    void reset() {
        verifiedPassthroughClasses.clear();
    }

    static boolean isCallEvent(MethodModel method) {
        return method.methodName().equalsString(CALL_EVENT) && method.methodType().equalsString(CALL_EVENT_TYPE);
    }

    boolean matchesClassFile(ClassLoader loader, String internalClassName, byte[] classfileBuffer) {
        // Any class overriding void callEvent(Event) must contain its UTF-8 method name and descriptor in the
        // constant pool, regardless of how many intermediate superclasses sit between it and RegisteredListener
        if (!containsBytes(classfileBuffer, CALL_EVENT_UTF8) || !containsBytes(classfileBuffer, CALL_EVENT_TYPE_UTF8)) {
            return false;
        }
        ClassModel model = ClassFile.of().parse(classfileBuffer);
        if ((model.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0) {
            return false;
        }
        String directSuper = model.superclass().map(ClassEntry::asInternalName).orElse(null);
        if (directSuper == null || "java/lang/Object".equals(directSuper)) {
            return false;
        }
        CodeModel callEventCode = findConcreteCallEventCode(model);
        if (callEventCode == null || !ClassHierarchySupport.isSubclassOf(loader, directSuper, TARGET_CLASS)) {
            return false;
        }

        if (isPureSuperCallEvent(callEventCode, directSuper) || isSafeBukkitTimedDelegate(internalClassName, callEventCode)) {
            verifiedPassthroughClasses.add(loader, internalClassName);
            return false;
        }
        verifiedPassthroughClasses.remove(loader, internalClassName);
        return true;
    }

    boolean matchesLoadedClass(Class<?> loadedClass) {
        for (Class<?> s = loadedClass.getSuperclass(); s != null && s != Object.class; s = s.getSuperclass()) {
            if (TARGET_CLASS_DOT.equals(s.getName())) {
                String internalName = loadedClass.getName().replace('.', '/');
                return !verifiedPassthroughClasses.contains(loadedClass.getClassLoader(), internalName)
                        && declaresConcreteCallEvent(loadedClass);
            }
        }
        return false;
    }

    private static CodeModel findConcreteCallEventCode(ClassModel model) {
        for (MethodModel method : model.methods()) {
            if (isCallEvent(method) && method.code().isPresent()) {
                return method.code().orElseThrow();
            }
        }
        return null;
    }

    private static boolean declaresConcreteCallEvent(Class<?> loadedClass) {
        try {
            for (var method : loadedClass.getDeclaredMethods()) {
                if (CALL_EVENT.equals(method.getName())
                        && method.getReturnType() == void.class
                        && method.getParameterCount() == 1
                        && EVENT_CLASS_DOT.equals(method.getParameterTypes()[0].getName())
                        && !Modifier.isAbstract(method.getModifiers())) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError | SecurityException ignored) {
            return true;
        }
    }

    /**
     * Checks if {@code callEvent(Event)} is strictly {@code super.callEvent(event); return;} with no pre/post logic,
     * branches, or exception handlers. Because a blocked base {@code callEvent} returns normally, any instructions
     * before or after {@code super.callEvent} (or in sibling branches) would otherwise execute unsandboxed.
     */
    private static boolean isPureSuperCallEvent(CodeModel code, String directSuperInternalName) {
        int step = 0;
        for (CodeElement element : code) {
            if (element instanceof ExceptionCatch) {
                return false;
            }
            if (!(element instanceof Instruction instruction)) {
                continue;
            }
            boolean ok = switch (step) {
                case 0 -> instruction instanceof LoadInstruction load
                        && load.typeKind() == TypeKind.REFERENCE
                        && load.slot() == 0;
                case 1 -> instruction instanceof LoadInstruction load
                        && load.typeKind() == TypeKind.REFERENCE
                        && load.slot() == 1;
                case 2 -> instruction instanceof InvokeInstruction invoke
                        && invoke.opcode() == Opcode.INVOKESPECIAL
                        && invoke.name().equalsString(CALL_EVENT)
                        && invoke.type().equalsString(CALL_EVENT_TYPE)
                        && (invoke.owner().asInternalName().equals(directSuperInternalName)
                        || invoke.owner().asInternalName().equals(TARGET_CLASS));
                case 3 -> instruction instanceof ReturnInstruction ret
                        && ret.opcode() == Opcode.RETURN;
                default -> false;
            };
            if (!ok) {
                return false;
            }
            step++;
        }
        return step == 4;
    }

    /**
     * Verifies Bukkit's {@code TimedRegisteredListener#callEvent(Event)} only touches its own timing fields,
     * {@code java.lang.*}, and {@code Event#isAsynchronous()} while delegating dispatch to
     * {@code RegisteredListener#callEvent(Event)}.
     */
    private static boolean isSafeBukkitTimedDelegate(String internalClassName, CodeModel code) {
        if (!TIMED_TARGET_CLASS.equals(internalClassName)) {
            return false;
        }
        boolean callsSuper = false;
        for (CodeElement element : code) {
            if (element instanceof ExceptionCatch || element instanceof InvokeDynamicInstruction) {
                return false;
            }
            if (element instanceof FieldInstruction field
                    && (field.opcode() == Opcode.PUTSTATIC
                    || field.opcode() == Opcode.PUTFIELD && !field.owner().asInternalName().equals(TIMED_TARGET_CLASS))) {
                return false;
            }
            if (element instanceof InvokeInstruction invoke) {
                String owner = invoke.owner().asInternalName();
                if (invoke.opcode() == Opcode.INVOKESPECIAL
                        && owner.equals(TARGET_CLASS)
                        && invoke.name().equalsString(CALL_EVENT)
                        && invoke.type().equalsString(CALL_EVENT_TYPE)) {
                    callsSuper = true;
                } else if (!owner.startsWith("java/lang/")
                        && !owner.equals(TIMED_TARGET_CLASS)
                        && !(owner.equals(EVENT_CLASS) && invoke.name().equalsString("isAsynchronous"))) {
                    return false;
                }
            }
        }
        return callsSuper;
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
