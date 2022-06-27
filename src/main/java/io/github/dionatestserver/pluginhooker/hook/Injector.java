package io.github.dionatestserver.pluginhooker.hook;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.util.proxy.DefineClassHelper;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.AbstractList;

public abstract class Injector {

    protected static final ClassPool classPool = ClassPool.getDefault();

    protected final String targetClass;

    protected final Class<?> neighbor;


    public Injector(String targetClass, Class<?> neighbor) {
        this.targetClass = targetClass;
        this.neighbor = neighbor;

        classPool.appendClassPath(new LoaderClassPath(DionaPluginHooker.class.getClassLoader()));
    }

    public boolean isTargetClassDefined() {
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            AbstractList<Class<?>> classes = (AbstractList) classesField.get(neighbor.getClassLoader());
            return classes.stream().anyMatch(clazz -> clazz.getName().equals(targetClass));
        } catch (Exception e) {
            return false;
        }
    }

    public void predefineClass() {
        try {
            CtClass hookedClass = this.generateHookedClass();

            DefineClassHelper.toClass(targetClass, neighbor, neighbor.getClassLoader(), null, hookedClass.toBytecode());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void redefineClass(Instrumentation instrumentation) {
        try {
            CtClass hookedClass = this.generateHookedClass();

            instrumentation.redefineClasses(new ClassDefinition(Class.forName(targetClass), hookedClass.toBytecode()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public abstract CtClass generateHookedClass();

    public abstract boolean canHook();
}
