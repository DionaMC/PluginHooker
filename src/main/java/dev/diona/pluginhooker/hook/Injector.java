package dev.diona.pluginhooker.hook;

import dev.diona.pluginhooker.PluginHooker;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;

public abstract class Injector {

    protected static final ClassPool classPool = ClassPool.getDefault();

    static {
        classPool.appendClassPath(new LoaderClassPath(PluginHooker.class.getClassLoader()));
    }

    protected Class<?> neighbor;

    protected CtClass targetClass;

    @Getter
    protected final String targetClassName;

    @Getter
    protected final String classNameWithoutPackage;


    public Injector(String targetClassName, String neighborName) {
        this.targetClassName = targetClassName;
        // split the class name
        String[] className = this.getTargetClassName().split("\\.");
        // get the class name without the package
        classNameWithoutPackage = className[className.length - 1];

        PluginHooker.getConfigManager().loadConfig(this);

        if (!this.canHook()) return;

        try {
            this.initClassPath();
            this.neighbor = Class.forName(neighborName);
            this.targetClass = classPool.get(targetClassName);
            this.hookClass();
        } catch (Exception e) {
            e.printStackTrace();
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

    protected abstract void initClassPath();

}
