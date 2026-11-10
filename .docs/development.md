# Development

`make build` checks formatting, compiles with Error Prone and NullAway, runs the tests and checks declared dependencies;
`make` lists the targets. A JDK 21 or newer is enough. `make format` fixes formatting.

It also fails when a module declares a dependency it does not use, or uses one it only gets transitively. What to
change is in `build/reports/dependency-analysis/build-health-report.txt`.

What ships is in [modules/](../modules): `bank-notifications`, with the e-mail parsers in `cz.insuit.bank.notification`
and the DKIM verification in `cz.insuit.bank.notification.dkim`. Projects that use it the way a caller's build would, on
Spring Boot 3 and 4 and from Kotlin, are in [testing/](../testing/README.md).

`make lock` rewrites `modules/bank-notifications/gradle.lockfile` after a dependency changes; the weekly vulnerability
scan in `security.yml` reads it, and the build fails when it drifts from what ships.

## Adding a bank

1. **Get a real e-mail**: the raw `.eml`, exported as "show original" or "raw source", never forwarded, which rewrites
   the headers. An incoming payment with a variable symbol and a message is the most useful first one.
2. **Verify its signature** against the bank's key in DNS before anything else, e.g.
   `uvx --from dkimpy==1.1.8 --with dnspython dkimverify < message.eml`. Note the `d=`, the selector, the key size,
   and that there is no `l=`.
3. **Anonymise it into a fixture** under `modules/bank-notifications/src/test/resources/<bank>/`: keep the headers from
   `Date:` on, drop the transport headers and the signature, replace accounts, names, balance, transaction codes and
   the recipient (`bruce.wayne@gothba.org`), and keep everything else byte for byte, line ends and invisible characters
   included. `.gitattributes` keeps git from touching `*.eml`.
4. **Write the parser** beside `AirBank`: lines read by label, the direction stated twice and required to agree,
   nothing guessed. A test per refusal, not only per success.
5. **Pin its key**: the fingerprint `DkimVerifier.fingerprint` gives for the key in DNS, read through two resolvers, in
   the bank's `KEY_FINGERPRINTS`, its domain in `BankNotifications.pinnedKeys()`, and a check in `KeyCheckLiveTest`.
   Its domain has to be DNSSEC-signed, or `withDns()` refuses every e-mail from it.
6. **Document it** in [setup](setup.md), a page like [air-bank.md](air-bank.md), and the table in the README.

The real e-mail stays out of the repository: an anonymised message no longer matches its signature, so the tests sign
the fixture with a key of their own.

## A bank rotates its key

`keys.yml` opens an issue. Read the new fingerprint from the failure, confirm it through a second resolver
(`dig +short TXT <selector>._domainkey.<domain> @8.8.8.8`, then the `p=` value Base64-decoded and SHA-256'd), add it to
the bank's `KEY_FINGERPRINTS` beside the old one, and release. Drop the old one once DNS no longer publishes it.

## Releasing

Not set up yet: there is no release workflow, and nothing is on GitHub Packages (2026-11-10). It will follow
`moneta-api-java`: a tag `vX.Y.Z` publishes that version.
