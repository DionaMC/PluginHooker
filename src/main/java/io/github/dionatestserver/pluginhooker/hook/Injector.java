package io.github.dionatestserver.pluginhooker.hook;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.AbstractList;

public abstract class Injector {

    protected static final ClassPool classPool = ClassPool.getDefault();

    protected final Class<?> neighbor;

    protected final CtClass targetClass;

    @Getter
    protected final String targetClassName;

    @Getter
    protected final String classNameWithoutPackage;


    public Injector(String targetClassName, Class<?> neighbor) {
        this.targetClassName = targetClassName;
        // split the class name
        String[] className = this.getTargetClassName().split("\\.");
        // get the class name without the package
        classNameWithoutPackage = className[className.length - 1];
        this.neighbor = neighbor;

        classPool.appendClassPath(new LoaderClassPath(DionaPluginHooker.class.getClassLoader()));

        try {
            this.targetClass = classPool.get(targetClassName);
            this.hookClass();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isTargetClassDefined() {
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            AbstractList<Class<?>> classes = (AbstractList) classesField.get(neighbor.getClassLoader());
            return classes.stream().anyMatch(clazz -> clazz.getName().equals(targetClassName));
        } catch (Exception e) {
            return false;
        }
    }

    public void predefineClass() throws Exception {
        DefineClassHelper.toClass(
                targetClassName,
                neighbor,
                neighbor.getClassLoader(),
                null,
                targetClass.toBytecode()
        );
    }

    public void redefineClass(Instrumentation instrumentation) throws Exception {
        instrumentation.redefineClasses(
                new ClassDefinition(Class.forName(targetClassName), targetClass.toBytecode())
        );
    }

    public abstract void hookClass() throws Exception;

    public abstract boolean canHook();
}
