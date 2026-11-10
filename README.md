# bank-java

Java libraries for learning that money moved on a Czech bank account: who paid, how much, with which variable symbol.

Most Czech banks can e-mail every incoming and outgoing payment. `bank-notifications` reads that e-mail into a
transaction, and hands it out only once the bank's DKIM signature verified, so an e-mail that merely looks like the
bank's is never taken for a payment. No API key, no contract with the bank, no PSD2 licence: the account holder points
the notification at an address you receive. Air Bank first.

For Java 21+ and Kotlin, one jar, no framework required. Published on GitHub Packages, not Maven Central.

```java
try {
    Transaction t = BankNotifications.withDns().read(rawMessage); // genuine: compare t.account() with yours
} catch (BankNotificationException e) {                           // Unverified, TemporaryFailure or Unparsed
    ...
}
```

| Read | For |
|---|---|
| [Installing](.docs/install.md) | the artifact on GitHub Packages, for Gradle and Maven |
| [Setting up a bank](.docs/setup.md) | how the account holder points the bank's e-mail at your address |
| [Using the library](.docs/usage.md) | the raw message, the exceptions, a transaction's fields, the two checks left to you |
| [How verification works](.docs/verification.md) | what makes a signature count, what it does not prove, jDKIM, the size limit |
| [How Air Bank's e-mail behaves](.docs/air-bank.md) | the message, its body, what to watch out for, what has not been seen |
| [Roadmap](.docs/plan/roadmap.md) | the working plan: what is next, what is not covered |
| [Development](.docs/development.md) | build, tests, adding a bank |

## What it covers

What a bank's notification e-mail carries, seen in its real e-mails, against what this library reads. ❔ means no
sample has shown it yet, so the bank may well send it.

| Feature | Air Bank e-mail | This library |
|---|:---:|:---:|
| Incoming transfer | ✅ | ✅ |
| Outgoing transfer | ✅ | ✅ |
| Card payment | ❔ | ⬜ |
| Direct debit | ❔ | ⬜ |
| The account it is about | ✅ | ✅ |
| Counter-account | ✅ | ✅ |
| Counter-party name | ✅ incoming only | ✅ |
| Amount and currency, under 1 000 | ✅ | ✅ |
| Amount of 1 000 and more | ❔ | ⬜ |
| Variable symbol | ✅ | ✅ |
| Constant and specific symbol | ❔ | ⬜ |
| Message, outgoing | ✅ | ✅ |
| Message, incoming | ❔ | ⬜ |
| Booking date | ✅ | ✅ |
| Transaction id | ✅ | ✅ |
| Balance after the payment | ✅ | ⬜ |
| Business account | ❔ | ⬜ |
| DKIM signature over the whole body | ✅ | ✅ verified |
| Sent straight to any address, no mailbox forward | ✅ | n/a |
| Other banks | n/a | ⬜ |
| Spring Boot auto-configuration | n/a | ⬜ |

Licence: [Apache 2.0](LICENSE)
