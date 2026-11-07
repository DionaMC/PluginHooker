plugins {
    `maven-publish`
}

java {
    withSourcesJar()
}

dependencies {
    compileOnly("org.spigotmc:spigot:1.8.8-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "hooker-api"
        }
    }
}
