# bank-java

Java libraries for learning that money moved on a Czech bank account: who paid, how much, with which variable symbol.
Java 21, no framework required, nullability in JSpecify.

## Install

One dependency. It brings the DKIM verification with it, at the same version.

```kotlin
// build.gradle.kts; the jars are in this repository's GitHub Packages
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/landsman/bank-java")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull
            password = providers.environmentVariable("GITHUB_TOKEN").orNull // a token with read:packages
        }
    }
}
dependencies {
    implementation("cz.insuit:bank-notifications:0.1.0")
}
```

## Use

Most Czech banks can e-mail every incoming and outgoing payment. Point that e-mail at an address you receive, and hand
each raw message, exactly as it arrived, to `read`:

```java
BankNotifications notifications = BankNotifications.withDns(); // thread-safe, keep one

switch (notifications.read(rawMessage)) {
    case Transaction t -> ...      // genuine: t.direction(), t.amountMinor(), t.variableSymbol(), t.transactionId()
    case Unverified u -> ...       // no valid signature of the bank: anyone can send an e-mail that looks real
    case TemporaryFailure f -> ... // the bank's key could not be looked up: keep the message, read it again later
    case Unparsed u -> ...         // keep the message and look at u.reason(): a bank changed its template
}
```

A `Transaction` is only handed out once the message's DKIM signature verified, over the raw bytes and never from an
`Authentication-Results` header that came with it: `d=` is the bank's domain, the whole body is signed (no `l=`), the
key is not in testing mode and an RSA key has at least 1024 bits. Two checks stay with you:

- compare `t.account()` with the account you expect, or a genuine notification about somebody else's account counts as
  yours;
- deduplicate on `t.transactionId()`.

`BankNotifications.with(lookup)` takes your own key lookup instead of DNS: a resolver with its own timeouts, or a
recorded key in a test.

## Modules

| Module | Package | Does |
|---|---|---|
| `bank-notifications` | `cz.insuit.bank.notification` | reads the bank's e-mail; the one to depend on |
| `bank-notifications-dkim` | `cz.insuit.bank.notification.dkim` | verifies its DKIM signature, on [Apache James jDKIM](https://github.com/apache/james-jdkim) 0.5; comes with the first |

Messages over 64 KiB are refused until a jDKIM release carries the linear-time parsing fixes made upstream on
2026-11-05. Banks with an API of their own will be a module each.

## Banks

| Bank | Sender | Notes |
|---|---|---|
| Air Bank | `info@airbank.cz`, DKIM `d=airbank.cz` | Internet banking on a computer: Účty a karty → account → Možnosti → Info o dění na účtu → "Jiný kontakt" takes any address, no mailbox forward needed. Incoming and outgoing transfers. Constant and specific symbols, the incoming message and amounts of 1 000 and more are not read yet: no sample has shown them. |

A new bank starts from one of its real e-mails, anonymised, as a test fixture.

## Development

`make build` checks formatting, compiles with Error Prone and NullAway, runs the tests and checks declared dependencies.
`make format` fixes formatting.
