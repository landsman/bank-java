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
- **Air Bank and Fio banka in Czechia**, more banks to follow; [Fio in Slovakia](https://github.com/landsman/bank-java/issues/10) is next.

```mermaid
flowchart TB
    payer["Customer pays"] --> bank["Bank e-mails<br/>the payment"]
    bank --> lib["bank-java checks<br/>the bank signed it"]
    lib --> app["Your app marks<br/>the order paid"]
```

For Java 21+ and Kotlin, one jar, no framework required.

## Which banks

Air Bank and Fio banka CZ from real e-mails; MONETA, Creditas, Raiffeisenbank, UniCredit and ČSOB, and ČSOB Slovakia and Tatra banka, from public samples, until a real
e-mail confirms them. Each bank, and what it reads: [banks](.docs/banks/README.md).

## Docs

| Read | For |
|---|---|
| [Banks](.docs/banks/README.md) | which banks it reads, what it reads from each, and a page per bank |
| [Setting up a bank](.docs/setup.md) | what the account holder clicks in their internet banking |
| [Installing](.docs/install.md) | adding the library to a Gradle or Maven project |
| [Using the library](.docs/usage.md) | reading an e-mail, the failures, a payment's fields, the two checks left to you |
| [How verification works](.docs/verification.md) | why a forged e-mail cannot pass |
| [Roadmap](.docs/plan/roadmap.md) | what is next |
| [Development](.docs/development.md) | building it, adding a bank |
| [llms.txt](llms.txt) | for a coding assistant adding the library to a project |

Licence: [Apache 2.0](LICENSE)
