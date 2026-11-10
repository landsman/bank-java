rootProject.name = "bank-java"

// One module per way of learning that a payment arrived. A bank's API client (Fio, ...) joins here as its own module.
include(":modules:bank-notifications")

// Callers the library promises to work for, each on its own classpath; see testing/README.md.
include(
    // Gradle would add the parent anyway; listed so Dependabot reads its build file too.
    ":testing:spring-boot",
    ":testing:spring-boot:3.5.16",
    ":testing:spring-boot:4.1.2",
    ":testing:kotlin",
)
