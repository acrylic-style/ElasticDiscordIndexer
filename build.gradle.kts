plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "xyz.acrylicstyle"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-simple:2.0.0")
    implementation("co.elastic.clients:elasticsearch-java:8.14.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("dev.kord:kord-core:0.14.0")
    implementation("com.charleskorn.kaml:kaml:0.60.0")
    implementation("com.aallam.openai:openai-client:3.8.2")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    shadowJar {
        manifest {
            attributes(
                "Main-Class" to "xyz.acrylicstyle.elasticdiscordindexer.MainKt",
            )
        }
        archiveFileName.set("SpicyAzisabaBot.jar")
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

kotlin {
    jvmToolchain(21)
}
