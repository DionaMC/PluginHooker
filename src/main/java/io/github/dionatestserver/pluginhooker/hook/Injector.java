package io.github.dionatestserver.pluginhooker.hook;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.AbstractList;

public abstract class Injector {

    protected static final ClassPool classPool = ClassPool.getDefault();

    @Getter
    protected final String targetClass;

    @Getter
    protected final String classNameWithoutPackage;

    protected final Class<?> neighbor;


    public Injector(String targetClass, Class<?> neighbor) {
        this.targetClass = targetClass;
        // split the class name
        String[] className = this.getTargetClass().split("\\.");
        // get the class name without the package
        classNameWithoutPackage = className[className.length - 1];
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

    public void predefineClass() throws Exception {
        CtClass hookedClass = this.generateHookedClass();

        DefineClassHelper.toClass(targetClass, neighbor, neighbor.getClassLoader(), null, hookedClass.toBytecode());
    }

    public void redefineClass(Instrumentation instrumentation) throws Exception {
        CtClass hookedClass = this.generateHookedClass();

        instrumentation.redefineClasses(new ClassDefinition(Class.forName(targetClass), hookedClass.toBytecode()));
    }

    public abstract CtClass generateHookedClass();

    public abstract boolean canHook();
}
