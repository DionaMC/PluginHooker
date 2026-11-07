package dev.diona.pluginhooker.patch;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ConstantDescs;

/**
 * Base abstraction for a bytecode patch using the JDK 25 {@link ClassFile} API.
 */
public abstract class ClassPatch {

    /**
     * Checks whether this patch is enabled in the configuration.
     *
     * @return true if enabled
     */
    public abstract boolean isEnabled();

    /**
     * Checks whether this patch applies to the given internal class name (e.g. {@code org/bukkit/plugin/RegisteredListener}).
     *
     * @param internalClassName the JVM internal class name (slash-separated)
     * @return true if this patch targets the class
     */
    public abstract boolean matchesClass(String internalClassName);

    /**
     * Creates the {@link ClassTransform} for the matched class.
     *
     * @param internalClassName the JVM internal class name
     * @param classModel        the parsed class model
     * @return the class transform to apply
     */
    protected abstract ClassTransform createTransform(String internalClassName, ClassModel classModel);

    /**
     * Transforms the raw classfile bytes.
     *
     * @param loader            the defining class loader
     * @param internalClassName the JVM internal class name
     * @param classfileBuffer   the original class bytes
     * @return the transformed class bytes
     */
    public final byte[] transform(ClassLoader loader, String internalClassName, byte[] classfileBuffer) {
        ClassLoader effectiveLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        ClassHierarchyResolver resolver = ClassHierarchyResolver.ofResourceParsing(effectiveLoader)
                .orElse(ClassHierarchyResolver.ofClassLoading(effectiveLoader))
                .orElse(ClassHierarchyResolver.defaultResolver())
                .orElse(desc -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object))
                .cached();

        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
        ClassModel model = cf.parse(classfileBuffer);
        ClassTransform transform = createTransform(internalClassName, model);
        return cf.transformClass(model, transform);
    }
}
