import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":modules:bank-notifications"))
    testImplementation("org.apache.james.jdkim:apache-jdkim-library:0.5")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}
