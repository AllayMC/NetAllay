import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("org.allaymc.gradle.plugin") version "0.2.1"
}

group = "org.allaymc"
description = "适用于Allay的网易我的世界客户端通信插件"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allay {
    api = "0.26.0"
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
    compileOnly("org.projectlombok:lombok:1.18.34")
    compileOnly("org.allaymc:protocol-extension:0.1.6")
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
