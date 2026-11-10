# bank-java

**Know the moment a customer pays you, without a bank API.**

Czech banks can send an e-mail for every payment that moves on an account. This library turns that e-mail into a
payment your application can act on: who paid, how much, with which variable symbol. Match it to an invoice or an
order and mark it paid, automatically, about a minute after the money lands.

- **No contract with the bank, no API key, no PSD2 licence.** The account holder switches the e-mail on in their own
  internet banking and sends it to an address your application receives.
- **A forged e-mail is never taken for a payment.** Only an e-mail the bank itself signed gets through; anything else
  is refused, with no setting to forget. See [how verification works](.docs/verification.md).
- **Money in and money out.** Incoming payments to match, outgoing ones to see the account move.
- **Air Bank first**, more banks to follow.

```mermaid
flowchart TB
    payer["Customer pays"] --> bank["Bank e-mails<br/>the payment"]
    bank --> lib["bank-java checks<br/>the bank signed it"]
    lib --> app["Your app marks<br/>the order paid"]
```

For Java 21+ and Kotlin, one jar, no framework required.

## What it covers

✅ read, ⬜ not yet, ❔ no real e-mail has shown it yet. Details in [how Air Bank's e-mail behaves](.docs/air-bank.md).

| Feature | Air Bank e-mail | This library |
|---|:---:|:---:|
| Incoming payment | ✅ | ✅ |
| Outgoing payment | ✅ | ✅ |
| Card payment | ❔ | ⬜ |
| Direct debit | ❔ | ⬜ |
| Amount | ✅ | ✅ below 1 000 so far |
| Who paid: name and account | ✅ | ✅ |
| Variable symbol | ✅ | ✅ |
| Message for the recipient | ✅ | ✅ |
| Date and transaction id | ✅ | ✅ |
| Balance after the payment | ✅ | ✅ below 1 000 so far |
| Proven to come from the bank | ✅ | ✅ |
| Other banks | n/a | ⬜ |

## Docs

| Read | For |
|---|---|
| [Setting up a bank](.docs/setup.md) | what the account holder clicks in their internet banking |
| [Installing](.docs/install.md) | adding the library to a Gradle or Maven project |
| [Using the library](.docs/usage.md) | reading an e-mail, the failures, a payment's fields, the two checks left to you |
| [How verification works](.docs/verification.md) | why a forged e-mail cannot pass |
| [How Air Bank's e-mail behaves](.docs/air-bank.md) | what the bank sends and what has not been seen yet |
| [Roadmap](.docs/plan/roadmap.md) | what is next |
| [Development](.docs/development.md) | building it, adding a bank |
| [llms.txt](llms.txt) | for a coding assistant adding the library to a project |

Licence: [Apache 2.0](LICENSE)
