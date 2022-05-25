package io.github.dionatestserver.pluginhooker.utils;

import javassist.CtMethod;

public class ClassUtils {

    public static CtMethod getMethodByName(CtMethod[] methods, String targetName) {
        for (CtMethod method : methods) {
            if (method.getName().equals(targetName))
                return method;
        }
        return null;
    }

    public static CtMethod getMethodBySignature(CtMethod[] methods, String signature) {
        for (CtMethod method : methods) {
            if (method.getSignature().equals(signature))
                return method;
        }
        return null;
    }
}
