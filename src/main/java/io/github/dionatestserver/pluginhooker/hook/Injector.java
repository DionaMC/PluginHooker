package io.github.dionatestserver.pluginhooker.hook;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import javassist.ClassPool;
import javassist.LoaderClassPath;

public abstract class Injector {

    protected final ClassPool classPool;

    protected final String targetClass;

    public Injector(String targetClass) {
        this.classPool = ClassPool.getDefault();
        classPool.appendClassPath(new LoaderClassPath(DionaPluginHooker.class.getClassLoader()));

        this.targetClass = targetClass;
    }

    public abstract void predefineClass();

    public abstract boolean canHook();
}
