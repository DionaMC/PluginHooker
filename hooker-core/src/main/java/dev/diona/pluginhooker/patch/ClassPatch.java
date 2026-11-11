package dev.diona.pluginhooker.patch;

import com.google.common.collect.MapMaker;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ConstantDescs;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Base abstraction for a bytecode patch using the JDK 25 {@link ClassFile} API.
 */
public abstract class ClassPatch {

    private static final ConcurrentMap<ClassLoader, ClassHierarchyResolver> RESOLVER_CACHE =
            new MapMaker().weakKeys().makeMap();

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
     * @param internalClassName the JVM internal class name
     * @param classfileBuffer   the class bytes
     * @return true if this patch targets the class
     */
    public boolean matchesClassFile(String internalClassName, byte[] classfileBuffer) {
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
        ClassHierarchyResolver resolver = getHierarchyResolver(loader);
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
        ClassModel model = cf.parse(classfileBuffer);
        ClassTransform transform = createTransform(internalClassName, model);
        return cf.transformClass(model, transform);
    }

    private static ClassHierarchyResolver getHierarchyResolver(ClassLoader loader) {
        ClassLoader effectiveLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        return RESOLVER_CACHE.computeIfAbsent(effectiveLoader, cl ->
                ClassHierarchyResolver.ofResourceParsing(cl)
                        .orElse(ClassHierarchyResolver.ofClassLoading(cl))
                        .orElse(ClassHierarchyResolver.defaultResolver())
                        .orElse(desc -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object))
                        .cached(ConcurrentHashMap::new)
        );
    }
}
