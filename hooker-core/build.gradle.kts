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
}

tasks {
    processResources {
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
        failOnNoDiscoveredTests.set(false)
    }

    val verifyPatches = register<JavaExec>("verifyPatches") {
        group = "verification"
        classpath = sourceSets["main"].compileClasspath + sourceSets["test"].runtimeClasspath
        mainClass.set("dev.diona.pluginhooker.PatchVerifier")
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }

    check {
        dependsOn(verifyPatches)
    }
}
