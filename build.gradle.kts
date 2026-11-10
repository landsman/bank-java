import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("com.diffplug.spotless") version "8.11.0"
    id("com.autonomousapps.dependency-analysis") version "3.20.1"
    id("net.ltgt.errorprone") version "5.2.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.4.21" apply false
}

// The tests run on the JDK -PtestsJdk names, so CI's matrix checks the jars on each JDK they support.
val testsJdk = providers.gradleProperty("testsJdk")

// A tag vX.Y.Z releases X.Y.Z (-PreleaseVersion); anything else is the next snapshot.
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.1-SNAPSHOT")

allprojects {
    group = "cz.insuit"
    version = releaseVersion.get()

    repositories {
        mavenCentral()
    }
}

// Every project's tests run on the JDK -PtestsJdk names, testing/ included.
subprojects {
    plugins.withType<JavaPlugin> {
        val toolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            if (testsJdk.isPresent) {
                javaLauncher = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(testsJdk.get()) }
            }
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        // Built on any current JDK, usable from Java 21.
        options.release = 21
    }
}

// What ships is held to the strictest checks: every warning fails, and JSpecify is enforced by NullAway. testing/ is
// callers and stays ordinary.
subprojects {
    if (!path.startsWith(":modules:")) return@subprojects
    apply(plugin = "net.ltgt.errorprone")
    apply(plugin = "com.autonomousapps.dependency-analysis")
    dependencies {
        "errorprone"("com.google.errorprone:error_prone_core:2.52.0")
        "errorprone"("com.uber.nullaway:nullaway:0.14.3")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
        options.errorprone {
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:JSpecifyMode", "true")
            option("NullAway:OnlyNullMarked", "true")
        }
    }
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }
    // Published to this repository's GitHub Packages.
    apply(plugin = "maven-publish")
    plugins.withType<JavaLibraryPlugin> {
        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name = project.name
                        description = provider { project.description }
                        url = "https://github.com/landsman/bank-java"
                        licenses {
                            license {
                                name = "Apache License 2.0"
                                url = "https://www.apache.org/licenses/LICENSE-2.0"
                            }
                        }
                        developers {
                            developer {
                                id = "landsman"
                                name = "Michal Landsman"
                            }
                        }
                        scm {
                            url = "https://github.com/landsman/bank-java"
                            connection = "scm:git:https://github.com/landsman/bank-java.git"
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/landsman/bank-java")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}

// A dependency declared and unused ships in the POM for nothing; one used and undeclared breaks when it drops out.
dependencyAnalysis {
    structure {
        bundle("junit-jupiter") { includeGroup("org.junit.jupiter") }
    }
    issues { all { onAny { severity("fail") } } }
}

spotless {
    java {
        target("modules/*/src/**/*.java", "testing/**/src/**/*.java")
        palantirJavaFormat("2.103.0")
    }
    kotlin {
        target("testing/**/src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts", "modules/*/*.gradle.kts", "testing/**/*.gradle.kts")
        ktlint()
    }
}
