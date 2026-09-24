plugins {
    java
    id("com.gradleup.shadow") version "8.3.11"
}

group = "ru.voidrp"
version = "1.0.0"

java {
    // Paper 26.2 ships Java 25 bytecode, so the plugin has to be built on 25 too.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.124-stable")
    implementation("com.google.code.gson:gson:2.14.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier.set("all")
    relocate("com.google.gson", "ru.voidrp.auth.shaded.gson")
    minimize()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
