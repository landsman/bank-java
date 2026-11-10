// Everything a Spring Boot version shares; each version's folder only imports its BOM, which then picks the Jakarta
// Mail, Angus, SLF4J and commons-io versions the library runs on, as it would in an application.
subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    dependencies {
        "testImplementation"(project(":modules:bank-notifications"))
        // Signs the message with a key made for the test; not managed by Spring Boot.
        "testImplementation"("org.apache.james.jdkim:apache-jdkim-library:0.5")
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    configure<SourceSetContainer> {
        named("test") { java.srcDir("../shared/src/test/java") }
    }
}
