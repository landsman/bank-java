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
