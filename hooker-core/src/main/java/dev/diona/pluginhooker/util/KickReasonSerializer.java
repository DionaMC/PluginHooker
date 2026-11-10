package dev.diona.pluginhooker.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Lock-free {@link ClassValue}-backed serializer that extracts human-readable kick/disconnect reasons
 * from {@link String}, Adventure {@code Component}, Mojang {@code Component}, legacy {@code IChatBaseComponent},
 * and 1.21+ {@code DisconnectionDetails}.
 */
public final class KickReasonSerializer {

    private static final ClassValue<Optional<MethodHandle>> REASON_STRING_EXTRACTOR = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> reasonClass) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            // 1. Adventure Component -> LegacyComponentSerializer.legacySection().serialize(component)
            try {
                ClassLoader loader = reasonClass.getClassLoader();
                Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component", false, loader);
                if (componentClass.isAssignableFrom(reasonClass)) {
                    Class<?> serializerClass = Class.forName(
                            "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer",
                            true,
                            loader
                    );
                    Object legacySectionInstance = serializerClass.getMethod("legacySection").invoke(null);
                    Method serializeMethod = serializerClass.getMethod("serialize", componentClass);
                    return Optional.of(
                            lookup.unreflect(serializeMethod)
                                    .bindTo(legacySectionInstance)
                                    .asType(MethodType.methodType(String.class, Object.class))
                    );
                }
            } catch (Throwable ignored) {
            }

            // 2. Mojang 1.21+ DisconnectionDetails -> details.reason().getString()
            if (reasonClass.getSimpleName().equals("DisconnectionDetails")) {
                try {
                    Method reasonMethod = reasonClass.getMethod("reason");
                    Method getStringMethod = reasonMethod.getReturnType().getMethod("getString");
                    MethodHandle reasonMh = lookup.unreflect(reasonMethod);
                    MethodHandle strMh = lookup.unreflect(getStringMethod);
                    return Optional.of(
                            MethodHandles.filterReturnValue(reasonMh, strMh)
                                    .asType(MethodType.methodType(String.class, Object.class))
                    );
                } catch (Throwable ignored) {
                }
            }

            // 3. Mojang Component#getString() or Legacy IChatBaseComponent#getText()
            for (String methodName : List.of("getString", "getText")) {
                try {
                    Method m = reasonClass.getMethod(methodName);
                    if (m.getReturnType() == String.class) {
                        return Optional.of(
                                lookup.unreflect(m).asType(MethodType.methodType(String.class, Object.class))
                        );
                    }
                } catch (Throwable ignored) {
                }
            }
            return Optional.empty();
        }
    };

    private KickReasonSerializer() {
    }

    public static String serialize(Object reasonObj) {
        if (reasonObj == null) {
            return "";
        }
        if (reasonObj instanceof String str) {
            return str;
        }
        Optional<MethodHandle> extractor = REASON_STRING_EXTRACTOR.get(reasonObj.getClass());
        if (extractor.isPresent()) {
            try {
                return (String) extractor.get().invokeExact(reasonObj);
            } catch (Throwable ignored) {
            }
        }
        return reasonObj.toString();
    }
}
