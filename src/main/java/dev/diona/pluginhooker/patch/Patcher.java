package dev.diona.pluginhooker.patch;

import dev.diona.pluginhooker.PluginHooker;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;

public abstract class Patcher {

    protected static final ClassPool classPool = ClassPool.getDefault();

    static {
        classPool.appendClassPath(new LoaderClassPath(PluginHooker.class.getClassLoader()));
    }

    @Getter
    protected final String targetClassName;
    @Getter
    protected final String classNameWithoutPackage;
    @Getter
    private final boolean redefineOnly;
    protected Class<?> neighbor;
    protected CtClass targetClass;

    public Patcher(String targetClassName, String neighborName, boolean redefineOnly) {
        this.targetClassName = targetClassName;
        this.redefineOnly = redefineOnly;
        // split the class name
        String[] className = this.getTargetClassName().split("\\.");
        // get the class name without the package
        classNameWithoutPackage = className[className.length - 1];

        PluginHooker.getConfigManager().loadConfig(this);

        if (!this.canPatch()) return;

        try {
            this.initClassPath();
            this.neighbor = Class.forName(neighborName);
            this.targetClass = classPool.get(targetClassName);
            this.applyPatch();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Patcher(String targetClassName, String neighborName) {
        this(targetClassName, neighborName, false);
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

    public abstract void applyPatch() throws Exception;

    public abstract boolean canPatch();

    protected abstract void initClassPath();

}
