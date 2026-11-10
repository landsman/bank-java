# Testing

Projects that use the library the way a caller's build would, so a version a caller is likely to have cannot break it
unnoticed. `make build` runs them with the library's own tests, which stay in `modules/bank-notifications`.

| Project | Proves |
|---------|--------|
| `spring-boot/3.5.16` | reading and verifying works on the classpath Spring Boot 3.5's BOM resolves: SLF4J raised from the 1.7.36 dnsjava asks for to 2.0.18 |
| `spring-boot/4.1.2` | the same on Spring Boot 4.1, SLF4J 2.0.20 |
| `kotlin` | a `when` over `BankNotificationException` needs no `else`, and a nullable component is nullable in Kotlin |

Resolved on 2026-11-10: both BOMs manage Jakarta Mail 2.1.5 and Angus Mail 2.0.5, the versions the library is built
with, and leave commons-io at 2.17.0.

Each Spring Boot version is a folder whose `build.gradle.kts` imports its BOM; what they share is in
`spring-boot/build.gradle.kts`, and the test is in `spring-boot/shared`. Another version is another folder with its BOM
line, and an entry in `settings.gradle.kts`. These projects are callers: no Error Prone, no publishing.
