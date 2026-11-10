plugins {
    `java-library`
}

description = "Transactions read from the e-mail a bank sends on every payment, for banks without an API, once its DKIM signature verified"

dependencies {
    api("org.jspecify:jspecify:1.0.1")
    // The MIME parser; no Jakarta type leaves the library, input is the raw message bytes.
    implementation("jakarta.mail:jakarta.mail-api:2.1.5")
    runtimeOnly("org.eclipse.angus:angus-mail:2.0.5")
    // Apache James jDKIM verifies the signature. No jDKIM type leaves the library, so replacing it stays inside it.
    implementation("org.apache.james.jdkim:apache-jdkim-library:0.5")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "cz.insuit.bank.notification") }
}

// Trivy reads Gradle only through gradle.lockfile. Only what ships is locked: tooling and tests stay out of the scan,
// where a finding could not be acted on anyway.
configurations.named("runtimeClasspath") {
    resolutionStrategy.activateDependencyLocking()
}

// Nothing else in the build resolves runtimeClasspath, so a stale lockfile would pass and Trivy would scan versions that
// do not ship. Resolving it here fails the build on any drift; `make lock` rewrites the file.
val verifyLocks =
    tasks.register("verifyLocks") {
        description = "Fails when gradle.lockfile no longer matches the runtime classpath."
        group = "verification"
        inputs.files(configurations.runtimeClasspath)
        doLast {}
    }

tasks.check {
    dependsOn(verifyLocks)
}
