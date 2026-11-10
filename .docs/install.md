# Installing

One jar, on this repository's [GitHub Packages](https://github.com/landsman?tab=packages&repo_name=bank-java):

| Artifact | For |
|----------|-----|
| `cz.insuit:bank-notifications` | reading a bank's notification e-mail, its DKIM verification included |

It needs Java 21 or newer and brings Jakarta Mail, Angus Mail, Apache James jDKIM, dnsjava, jsoup and JSpecify with it.

**GitHub Packages asks for a token even for a public package.** A personal access token (classic) with the
`read:packages` scope is enough; keep it out of the build file.

## Gradle

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/landsman/bank-java")
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.key").get()
        }
    }
}

dependencies {
    implementation("cz.insuit:bank-notifications:0.1.0")
}
```

with `gpr.user` and `gpr.key` in `~/.gradle/gradle.properties`, not in the project.

## Maven

```xml
<repositories>
    <repository>
        <id>github-bank-java</id>
        <url>https://maven.pkg.github.com/landsman/bank-java</url>
    </repository>
</repositories>

<dependency>
    <groupId>cz.insuit</groupId>
    <artifactId>bank-notifications</artifactId>
    <version>0.1.0</version>
</dependency>
```

and in `~/.m2/settings.xml` a `<server>` with the same `id`, your GitHub user and the token.

## Snapshots

None are published, and no release is out yet (2026-11-10). To try it now, `make publish-local` in a checkout installs
the jar into `~/.m2` as `0.1.0-SNAPSHOT`, where `mavenLocal()` finds it.
