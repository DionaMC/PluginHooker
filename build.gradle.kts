plugins {
    java
}

allprojects {
    group = "dev.diona.pluginhooker"
    version = "2.0.0-SNAPSHOT"

    repositories {
        exclusiveContent {
            forRepository {
                mavenLocal()
            }
            filter {
                includeGroup("org.spigotmc")
            }
        }
        exclusiveContent {
            forRepository {
                maven("https://repo.codemc.io/repository/maven-releases/")
            }
            filter {
                includeGroup("com.github.retrooper")
            }
        }
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        compileOnly("org.projectlombok:lombok:1.18.48")
        annotationProcessor("org.projectlombok:lombok:1.18.48")
        testCompileOnly("org.projectlombok:lombok:1.18.48")
        testAnnotationProcessor("org.projectlombok:lombok:1.18.48")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
