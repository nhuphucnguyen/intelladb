import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.phucngu.intelladb"
version = "0.1.0"

java {
    // The 2026.2 platform is built with Java 25; plugin compilation must match.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Bundled into the plugin ZIP so users need no driver install.
    implementation("org.postgresql:postgresql:42.7.4")
    // MySQL is reached with MariaDB Connector/J (LGPL-2.1), which speaks the MySQL protocol;
    // MySQL's own Connector/J is GPL and cannot be bundled into this plugin.
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // IDE-level tests (BasePlatformTestCase) are JUnit 3/4 style; the vintage engine runs them on the JUnit Platform.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    intellijPlatform {
        // Since 2025.3 IDEA is a single unified distribution (free without Ultimate license);
        // intellijIdeaCommunity artifacts are discontinued.
        intellijIdea("2026.2")
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 25
    }
}
