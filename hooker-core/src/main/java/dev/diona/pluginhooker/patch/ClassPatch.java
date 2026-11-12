package dev.diona.pluginhooker.patch;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;

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
     * Checks whether this patch applies to a class its name does not identify, such as a subclass of a target. Only
     * asked for classes {@link #matchesClass} rejects.
     *
     * @param loader            the defining class loader
     * @param internalClassName the JVM internal class name
     * @param classfileBuffer   the class bytes
     * @return true if this patch targets the class
     */
    public boolean matchesClassFile(ClassLoader loader, String internalClassName, byte[] classfileBuffer) {
        return false;
    }

    /**
     * Checks whether an already loaded class may be one {@link #matchesClassFile} accepts, so that it is retransformed.
     *
     * @param loadedClass the loaded class
     * @return true if the class should be offered to the transformer
     */
    public boolean matchesLoadedClass(Class<?> loadedClass) {
        return false;
    }

    /**
     * Clears any runtime or inspection state held by this patch on instrumentation shutdown.
     */
    public void reset() {
    }

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
        ClassModel model = ClassFile.of().parse(classfileBuffer);
        ClassHierarchyResolver resolver = ClassHierarchySupport.resolverFor(loader, model);
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
        return cf.transformClass(model, createTransform(internalClassName, model));
    }
}
