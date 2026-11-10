plugins {
    `java-library`
}

description = "Verifies the DKIM signature of a bank's notification e-mail before its transaction is trusted"

dependencies {
    implementation("org.jspecify:jspecify:1.0.1")
    // Apache James jDKIM. No jDKIM type leaves this module, so replacing it stays a change inside it.
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
    manifest { attributes("Automatic-Module-Name" to "cz.insuit.bank.notification.dkim") }
}
