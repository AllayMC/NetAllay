import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("org.allaymc.gradle.plugin") version "0.2.1"
}

group = "org.allaymc"
description = "适用于Allay的网易我的世界客户端通信插件"
version = "0.1.2-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allay {
    // Pinned to the last published release pair. The newer 0.15.0-SNAPSHOT server (API 0.30.0-SNAPSHOT)
    // no longer exposes Player#sendPacket(Object), which this plugin relies on to emit NetEase packets.
    api = "0.29.0"
    server = "0.14.0"
    apiOnly = false

    plugin {
        entrance = "org.allaymc.netallay.NetAllay"
        authors += "YiRanKuma"
        website = "https://github.com/AllayMC/NetAllay"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    val allayServerJar = providers.environmentVariable("ALLAY_SERVER_JAR").orNull
    if (!allayServerJar.isNullOrBlank()) {
        compileOnly(files(allayServerJar))
    }
    compileOnly("org.projectlombok:lombok:1.18.34")
    compileOnly("com.google.code.gson:gson:2.10.1")
    implementation("org.msgpack:msgpack-core:0.9.8")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        configureEach {
            options.isFork = true
        }
    }

    // We already publish sources, and generating Javadocs is noisy for this plugin.
    withType<Javadoc> {
        enabled = false
    }

    withType<Copy> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral()
    signAllPublications()

    coordinates(project.group.toString(), "net-allay", project.version.toString())

    pom {
        name.set(project.name)
        description.set(project.description.toString())
        inceptionYear.set("2026")
        url.set("https://github.com/AllayMC/NetAllay")

        scm {
            connection.set("scm:git:git://github.com/AllayMC/NetAllay.git")
            developerConnection.set("scm:git:ssh://github.com/AllayMC/NetAllay.git")
            url.set("https://github.com/AllayMC/NetAllay")
        }

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
            }
        }

        developers {
            developer {
                name.set("AllayMC Team")
                organization.set("AllayMC")
                organizationUrl.set("https://github.com/AllayMC")
            }
        }
    }
}
