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
    // DNSSEC validation of the key lookups; jDKIM brings it too, at this version.
    implementation("dnsjava:dnsjava:3.6.3")
    // HTML-only notifications (Raiffeisenbank, UniCredit) are turned into lines through jsoup's HTML5 parser; 1.23.2 is
    // the first without CVE-2026-75140. Only the HTML parser is used, never the XML one.
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// One folder per bank, src/banks/<country>/<bank>/: its parser in java/, its tests in test/, its anonymised e-mails in
// fixtures/. Every folder is in the same package as the rest, so a parser stays package-private; a folder is not a package.
val banks =
    file("src/banks")
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .flatMap { country -> country.listFiles { file -> file.isDirectory }.orEmpty().toList() }
        .sortedBy { it.path }

sourceSets {
    main { java.srcDirs(banks.map { it.resolve("java") }) }
    test {
        java.srcDirs(banks.map { it.resolve("test") })
        resources.srcDirs(banks.map { it.resolve("fixtures") })
    }
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

// Live DNS stays out of make build; make check-keys runs it, and keys.yml does so daily.
tasks.test {
    useJUnitPlatform { excludeTags("live") }
}

val checkKeys =
    tasks.register<Test>("checkKeys") {
        description = "Checks the banks' DKIM keys in live DNS against the pinned ones, through DNSSEC."
        group = "verification"
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("live") }
        outputs.upToDateWhen { false }
    }
