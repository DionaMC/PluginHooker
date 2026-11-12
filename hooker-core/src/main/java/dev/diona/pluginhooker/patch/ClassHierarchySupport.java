package dev.diona.pluginhooker.patch;

import com.google.common.collect.MapMaker;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-{@link ClassLoader} hierarchy resolution and superclass lookup for {@link ClassFile} transformations.
 * Uses weak {@link ClassLoader} references in both map keys and resolver closures so third-party
 * {@code PluginClassLoader}s are never pinned in Metaspace after plugin unload.
 */
public final class ClassHierarchySupport {

    private static final ClassFile DEFAULT_CLASS_FILE = ClassFile.of();
    private static final String OBJECT_INTERNAL_NAME = "java/lang/Object";
    private static final String NO_SUPER = "";
    private static final int MAX_HIERARCHY_DEPTH = 32;

    private record LoaderCache(
            ClassHierarchyResolver resolver,
            ConcurrentMap<String, String> superNames
    ) {
    }

    private static final ConcurrentMap<ClassLoader, LoaderCache> LOADER_CACHE =
            new MapMaker().weakKeys().makeMap();

    private ClassHierarchySupport() {
    }

    public static void clear() {
        LOADER_CACHE.clear();
    }

    /**
     * Builds a {@link ClassHierarchyResolver} for transforming {@code model}. Queries for {@code model.thisClass()}
     * are answered directly from {@code model} so in-memory loaders without {@code .class} resources never trigger
     * {@link ClassCircularityError} via {@link Class#forName} mid-{@code defineClass}.
     */
    public static ClassHierarchyResolver resolverFor(ClassLoader loader, ClassModel model) {
        LoaderCache cache = getLoaderCache(loader);
        String selfInternalName = model.thisClass().asInternalName();
        model.superclass().ifPresent(sup -> cache.superNames().putIfAbsent(selfInternalName, sup.asInternalName()));

        ClassDesc selfDesc = model.thisClass().asSymbol();
        ClassHierarchyResolver.ClassHierarchyInfo selfInfo = isInterface(model)
                ? ClassHierarchyResolver.ClassHierarchyInfo.ofInterface()
                : ClassHierarchyResolver.ClassHierarchyInfo.ofClass(
                        model.superclass().map(ClassEntry::asSymbol).orElse(ConstantDescs.CD_Object)
                );
        ClassHierarchyResolver baseResolver = cache.resolver();
        return desc -> selfDesc.equals(desc) ? selfInfo : baseResolver.getClassInfo(desc);
    }

    /**
     * Checks whether {@code directSuperInternalName} equals {@code targetInternalName} or inherits from it.
     */
    public static boolean isSubclassOf(ClassLoader loader, String directSuperInternalName, String targetInternalName) {
        String current = directSuperInternalName;
        for (int depth = 0; depth < MAX_HIERARCHY_DEPTH && current != null && !OBJECT_INTERNAL_NAME.equals(current); depth++) {
            if (targetInternalName.equals(current)) {
                return true;
            }
            current = resolveSuperInternalName(loader, current);
        }
        return false;
    }

    private static String resolveSuperInternalName(ClassLoader loader, String internalClassName) {
        if (internalClassName == null || OBJECT_INTERNAL_NAME.equals(internalClassName)) {
            return null;
        }
        ClassLoader effectiveLoader = effectiveLoader(loader);
        LoaderCache cache = getLoaderCache(effectiveLoader);
        String cached = cache.superNames().get(internalClassName);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resolved = loadSuperInternalName(effectiveLoader, internalClassName);
        cache.superNames().putIfAbsent(internalClassName, resolved != null ? resolved : NO_SUPER);
        return resolved;
    }

    private static String loadSuperInternalName(ClassLoader loader, String internalClassName) {
        try (InputStream in = loader.getResourceAsStream(internalClassName + ".class")) {
            if (in != null) {
                ClassModel model = DEFAULT_CLASS_FILE.parse(in.readAllBytes());
                return isInterface(model) ? null : model.superclass().map(ClassEntry::asInternalName).orElse(null);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> clazz = Class.forName(internalClassName.replace('/', '.'), false, loader);
            if (clazz.isInterface()) {
                return null;
            }
            Class<?> superclass = clazz.getSuperclass();
            return superclass != null ? superclass.getName().replace('.', '/') : null;
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private static LoaderCache getLoaderCache(ClassLoader loader) {
        return LOADER_CACHE.computeIfAbsent(effectiveLoader(loader), ClassHierarchySupport::createLoaderCache);
    }

    private static LoaderCache createLoaderCache(ClassLoader cl) {
        WeakReference<ClassLoader> loaderRef = new WeakReference<>(cl);
        ClassHierarchyResolver resourceResolver = ClassHierarchyResolver.ofResourceParsing(desc -> {
            ClassLoader target = loaderRef.get();
            if (target == null || !desc.isClassOrInterface()) {
                return null;
            }
            return target.getResourceAsStream(descriptorToInternalName(desc) + ".class");
        });
        ClassHierarchyResolver classLoadingResolver = desc -> {
            ClassLoader target = loaderRef.get();
            if (target == null || !desc.isClassOrInterface()) {
                return null;
            }
            if (ConstantDescs.CD_Object.equals(desc)) {
                return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(null);
            }
            String binaryName = descriptorToInternalName(desc).replace('/', '.');
            try {
                Class<?> clazz = Class.forName(binaryName, false, target);
                if (clazz.isInterface()) {
                    return ClassHierarchyResolver.ClassHierarchyInfo.ofInterface();
                }
                Class<?> superclass = clazz.getSuperclass();
                return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(
                        superclass != null ? superclass.describeConstable().orElse(ConstantDescs.CD_Object) : null
                );
            } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
                return null;
            }
        };
        ClassHierarchyResolver resolver = resourceResolver
                .orElse(classLoadingResolver)
                .orElse(ClassHierarchyResolver.defaultResolver())
                .orElse(desc -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object))
                .cached(ConcurrentHashMap::new);
        return new LoaderCache(resolver, new ConcurrentHashMap<>());
    }

    private static ClassLoader effectiveLoader(ClassLoader loader) {
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    private static String descriptorToInternalName(ClassDesc desc) {
        String descriptor = desc.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
    }

    private static boolean isInterface(ClassModel model) {
        return (model.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0;
    }
}
