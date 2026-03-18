plugins {
    id("java-library")
    id("org.allaymc.gradle.plugin") version "0.2.1"
}

group = "org.allaymc.netallay"
description = "NetEase PyRpc Communication API for AllayMC - Similar to NukkitMaster"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allay {
    api = "0.26.0"
    apiOnly = false

    plugin {
        entrance = ".NetAllay"
        authors += "YiRanKuma"
        website = "https://github.com/AllayMC/NetAllay"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.34")
    compileOnly("org.allaymc:protocol-extension:0.1.6")
    compileOnly("com.google.code.gson:gson:2.10.1")
    implementation("org.msgpack:msgpack-core:0.9.8")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
}
