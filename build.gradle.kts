plugins {
    kotlin("jvm") version "2.2.20"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
}

tasks {
    processResources {
        filesMatching("*.yml") {
            expand(project.properties)
            expand(extra.properties)
        }
    }

    register<Jar>("paperJar") {
        from(sourceSets["main"].output)
        archiveClassifier.set("")
        archiveVersion.set("")

        doLast {
            copy {
                from(archiveFile)
                val runPlugins = File(rootDir, "server/plugins/")
                into(runPlugins)
            }
        }
    }
}