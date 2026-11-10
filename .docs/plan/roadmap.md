# Roadmap

A working document: what is still unknown and what this library does next.

## Current state (2026-11-10)

- **Not released.** `0.1.0-SNAPSHOT` only, built by CI on Java 21 and 25; no release workflow yet.
- **Reads Air Bank**: incoming and outgoing transfers, from two real e-mails of a personal account
  ([air-bank.md](../air-bank.md)).
- **Verifies DKIM** on Apache James jDKIM 0.5 before a `Transaction` is handed out ([verification](../verification.md));
  both real e-mails verify against Air Bank's published key and fail once their amount is changed.
- **23 tests**: the parser on the two fixtures and its refusals, the verifier on messages signed in the test, and
  reading end to end.

## Next

- **Releasing**: a release workflow and `make release` as in `moneta-api-java`, then `0.1.0`.
- **Dependabot and a vulnerability scan**, so a jDKIM release with the parsing fixes is noticed; then raise the 64 KiB
  limit.
- **More Air Bank samples**: a business account, constant and specific symbols, an incoming message, 1 000 CZK and more,
  a card payment, an instant transfer.

## Later

- **Other banks' e-mails**, each from one real message.
- **Banks with an API** (Fio, ...) as modules of their own, returning the same kind of transaction.
- **Framework modules** as in `moneta-api-java`, when a caller needs one.
