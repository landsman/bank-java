# Roadmap

A working document: what is still unknown and what this library does next.

## Current state (2026-11-10)

- **Not released.** `0.1.0-SNAPSHOT` only, built by CI on Java 21 and 25; no release workflow yet.
- **Reads Air Bank**: incoming and outgoing transfers with their symbols, message and the balance after them, from
  three real e-mails of a personal account
  ([air-bank.md](../air-bank.md)).
- **Verifies DKIM** on Apache James jDKIM 0.5 before a `Transaction` is handed out ([verification](../verification.md));
  both real e-mails verify against Air Bank's published key and fail once their amount is changed.
- **38 tests**: the parser on the three fixtures and its refusals, account checksums, the verifier on messages signed
  in the test, reading end to end, and hostile input that must end in `BankNotificationException` and nothing else.
- **Trusts only Air Bank's key**: validated through DNSSEC from the root and pinned in the code, checked daily against
  DNS by `keys.yml` ([verification](../verification.md#whose-key-it-is)).
- **Tested as a caller** on Spring Boot 3.5.16 and 4.1.2 and from Kotlin ([testing](../../testing/README.md)).
- **Scanned**: Semgrep and a dependency scan weekly and on every pull request; Dependabot proposes bumps.
- **Typed**: `BankAccount` with the modulo 11 check, `Money` and `PaymentSymbols` as in `moneta-api-java`, one sealed
  `BankNotificationException`.

## Next

- **Releasing**: a release workflow and `make release` as in `moneta-api-java`, then `0.1.0`.
- **A jDKIM release with the parsing fixes**: Dependabot proposes it; then raise the 64 KiB limit.
- **More Air Bank samples**, one issue each: [a business account](https://github.com/landsman/bank-java/issues/2),
  [1 000 CZK and more](https://github.com/landsman/bank-java/issues/5),
  [a card payment](https://github.com/landsman/bank-java/issues/6),
  [instant against ordinary](https://github.com/landsman/bank-java/issues/7),
  [the lowest amount notified](https://github.com/landsman/bank-java/issues/8).

## Later

- **Other banks' e-mails**, each from one real message.
- **Banks with an API** (Fio, ...) as modules of their own, returning the same kind of transaction.
- **Framework modules** as in `moneta-api-java`, when a caller needs one.
