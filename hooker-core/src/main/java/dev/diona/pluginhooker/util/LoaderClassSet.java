package dev.diona.pluginhooker.util;

import com.google.common.collect.MapMaker;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe set of internal class names partitioned by defining {@link ClassLoader}.
 * Holds {@link ClassLoader} keys weakly so unloaded plugin loaders can be garbage-collected,
 * while transparently supporting {@code null} (the bootstrap class loader).
 */
public final class LoaderClassSet {

    private static final Object BOOTSTRAP_LOADER_KEY = new Object();

    private final ConcurrentMap<Object, Set<String>> classesByLoader =
            new MapMaker().weakKeys().makeMap();

    public boolean contains(ClassLoader loader, String internalClassName) {
        Set<String> classes = classesByLoader.get(keyOf(loader));
        return classes != null && classes.contains(internalClassName);
    }

    public boolean add(ClassLoader loader, String internalClassName) {
        return classesByLoader
                .computeIfAbsent(keyOf(loader), k -> ConcurrentHashMap.newKeySet())
                .add(internalClassName);
    }

    public boolean remove(ClassLoader loader, String internalClassName) {
        Set<String> classes = classesByLoader.get(keyOf(loader));
        return classes != null && classes.remove(internalClassName);
    }

    public void clear() {
        classesByLoader.clear();
    }

    private static Object keyOf(ClassLoader loader) {
        return loader != null ? loader : BOOTSTRAP_LOADER_KEY;
    }
}
