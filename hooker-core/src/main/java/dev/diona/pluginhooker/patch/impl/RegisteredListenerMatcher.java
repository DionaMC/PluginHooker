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
import java.util.Arrays;

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

    private static final ClassLoader PLATFORM_CLASS_LOADER = ClassLoader.getPlatformClassLoader();
    private static final ClassFile DEFAULT_CLASS_FILE = ClassFile.of();
    private static final BytePattern CALL_EVENT_TYPE_PATTERN = new BytePattern(CALL_EVENT_TYPE);
    private static final BytePattern CALL_EVENT_NAME_PATTERN = new BytePattern(CALL_EVENT);

    private final LoaderClassSet verifiedPassthroughClasses = new LoaderClassSet();

    void reset() {
        verifiedPassthroughClasses.clear();
    }

    static boolean isCallEvent(MethodModel method) {
        return method.methodName().equalsString(CALL_EVENT) && method.methodType().equalsString(CALL_EVENT_TYPE);
    }

    boolean matchesClassFile(ClassLoader loader, String internalClassName, byte[] classfileBuffer) {
        if (loader == null || loader == PLATFORM_CLASS_LOADER || isNonCandidateClass(internalClassName)) {
            return false;
        }
        // Any class overriding void callEvent(Event) must contain its UTF-8 descriptor and method name in the
        // constant pool; check the 27-byte descriptor first via Boyer-Moore-Horspool to skip ~27 bytes per step
        if (!CALL_EVENT_TYPE_PATTERN.matchesIn(classfileBuffer) || !CALL_EVENT_NAME_PATTERN.matchesIn(classfileBuffer)) {
            return false;
        }
        ClassModel model = DEFAULT_CLASS_FILE.parse(classfileBuffer);
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

    private static boolean isNonCandidateClass(String internalName) {
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/")
                || internalName.startsWith("net/minecraft/")
                || internalName.startsWith("com/mojang/")
                || internalName.startsWith("io/netty/")
                || internalName.startsWith("com/google/")
                || internalName.startsWith("it/unimi/")
                || internalName.startsWith("org/apache/")
                || internalName.startsWith("org/slf4j/")
                || internalName.startsWith("org/objectweb/asm/")
                || internalName.startsWith("dev/diona/pluginhooker/");
    }

    private static final class BytePattern {
        private final byte[] pattern;
        private final int[] shift;

        BytePattern(String ascii) {
            this.pattern = ascii.getBytes(StandardCharsets.UTF_8);
            this.shift = new int[256];
            int len = pattern.length;
            Arrays.fill(shift, len);
            for (int i = 0; i < len - 1; i++) {
                shift[pattern[i] & 0xFF] = len - 1 - i;
            }
        }

        boolean matchesIn(byte[] data) {
            byte[] pat = this.pattern;
            int m = pat.length;
            int last = m - 1;
            int limit = data.length - m;
            if (limit < 0) {
                return false;
            }
            byte lastByte = pat[last];
            byte firstByte = pat[0];
            int[] shiftTable = this.shift;
            int i = 0;
            while (i <= limit) {
                byte b = data[i + last];
                if (b == lastByte && data[i] == firstByte) {
                    int j = 1;
                    while (j < last && data[i + j] == pat[j]) {
                        j++;
                    }
                    if (j == last) {
                        return true;
                    }
                }
                i += shiftTable[b & 0xFF];
            }
            return false;
        }
    }
}
