plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    api(project(":hooker-api"))

    compileOnly("org.spigotmc:spigot:1.8.8-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")

    implementation("net.bytebuddy:byte-buddy-agent:1.18.17")
    implementation("org.bstats:bstats-bukkit:3.2.1")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    test {
        compileClasspath += main.get().compileClasspath
        runtimeClasspath += main.get().compileClasspath
    }
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    shadowJar {
        archiveBaseName.set("PluginHooker")
        archiveClassifier.set("")
        relocate("net.bytebuddy.agent", "dev.diona.pluginhooker.libs.bytebuddy.agent")
        relocate("org.bstats", "dev.diona.pluginhooker.libs.bstats")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }

    val verifyPatches = register<JavaExec>("verifyPatches") {
        group = "verification"
        classpath = sourceSets["main"].compileClasspath + sourceSets["test"].runtimeClasspath
        mainClass.set("dev.diona.pluginhooker.PatchVerifier")
        val paperCacheDir = providers.gradleProperty("paperServerDir")
            .orElse(layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/pluginhooker-paper/26.3") }).map { it.asFile.absolutePath })
        jvmArgs(
            "-XX:+EnableDynamicAgentLoading",
            "-Dpaper.cache.dir=${paperCacheDir.get()}"
        )
    }

    check {
        dependsOn(verifyPatches)
    }
}
