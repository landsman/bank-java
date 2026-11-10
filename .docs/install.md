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

None are published: only tags are, the first `0.1.0` on 2026-11-10
([release](https://github.com/landsman/bank-java/releases/tag/v0.1.0), a pre-release while it is 0.x). To try `main`
before its next release, `make publish-local` in a checkout installs the jar into `~/.m2` as the next `-SNAPSHOT`, where
`mavenLocal()` finds it.
