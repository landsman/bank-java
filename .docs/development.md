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

Each bank has a folder of its own, `modules/bank-notifications/src/banks/<country>/<bank>/`, and its documentation is
`.docs/banks/<country>/<bank>/README.md`, with a row in [banks](banks/README.md). The country is a folder (`cz`, later
`sk` and others), the same in both places:

```text
src/banks/cz/fio/
  java/cz/insuit/bank/notification/FioCz.java        the parser
  test/cz/insuit/bank/notification/FioCzTest.java    its tests
  fixtures/fio-cz/*.eml                              its anonymised e-mails, read as /fio-cz/<name>.eml
```

The fixtures' own folder keeps the country in its name (`fio-cz`): every bank's fixtures share one classpath, so Fio
in Slovakia needs `fio-sk`, not a second `fio`. Gradle picks every bank folder up as another source root. Each is in the
package `cz.insuit.bank.notification`, like the rest, so the parser stays package-private and out of the public API: a
folder is not a package.

1. **Get a real e-mail**: the raw `.eml`, exported as "show original" or "raw source", never forwarded, which rewrites
   the headers. An incoming payment with a variable symbol and a message is the most useful first one.
2. **Verify its signature** against the bank's key in DNS before anything else, e.g.
   `uvx --from dkimpy==1.1.8 --with dnspython dkimverify < message.eml`. Note the `d=`, the selector, the key size,
   and that there is no `l=`.
3. **Anonymise it into a fixture** under `src/banks/<country>/<bank>/fixtures/<bank>-<country>/` (or the name the bank's
   existing fixtures use): keep the headers from
   `Date:` on, drop the transport headers and the signature, replace accounts, names, balance, transaction codes and
   the recipient (`bruce.wayne@gothba.org`), and keep everything else byte for byte, line ends and invisible characters
   included. `.gitattributes` keeps git from touching `*.eml`.
4. **Write the parser** in `src/banks/<country>/<bank>/java/`, with its test in `src/banks/<country>/<bank>/test/`, as `AirBank` and `FioCz` are:
   a class implementing `Bank`, listed in `BankNotifications.BANKS`, reading the body through `NotificationLines`
   (one-off lines, labelled lines, the guard against a forged line after a payer's message). What stays the bank's
   own: its patterns, the amount format, the movement types; lines read by label, the direction stated twice and required to agree,
   nothing guessed. A test per refusal, not only per success. A parser gets the `text/plain` part, or for an HTML-only
   bank its HTML as lines (`MailBody.lines`: one line per cell, `<br>` and block), so it never sees markup. **Lines** are split by `String.lines()`, on CR and LF
   only: a NEL, U+2028 or NBSP in a payer's text stays inside its line, so compile the labelled-line pattern with
   `DOTALL`, or such a message is dropped. The separator tests in `AirBankTest` and `FioCzTest` show both: a symbol
   behind any separator is never taken, and a message keeps what it holds.
5. **Pin its key**: the fingerprint `DkimVerifier.fingerprint` gives for the key in DNS, read through two resolvers, in
   the bank's `KEY_FINGERPRINTS` (returned by `keyFingerprints()`, which `pinnedKeys()` lists), and a check in
   `KeyCheckLiveTest`.
   Its domain has to be DNSSEC-signed, or `withDns()` refuses every e-mail from it.
6. **Document it** in [setup](setup.md), a folder like [banks/cz/airbank](banks/cz/airbank/README.md), and its row in [banks](banks/README.md).

The real e-mail stays out of the repository: an anonymised message no longer matches its signature, so the tests sign
the fixture with a key of their own.

## A bank rotates its key

`keys.yml` opens an issue. Read the new fingerprint from the failure, confirm it through a second resolver
(`dig +short TXT <selector>._domainkey.<domain> @8.8.8.8`, then the `p=` value Base64-decoded and SHA-256'd), add it to
the bank's `KEY_FINGERPRINTS` beside the old one, and release. Drop the old one once DNS no longer publishes it.

## Releasing

A release is made by hand, and only a tag makes one: merging to `main` publishes nothing. A tag `vX.Y.Z` on `main`
releases `X.Y.Z`. `make release` does all of what follows: it asks for the tag, suggesting the next patch (type
`v0.1.0` for the first one), refuses one that exists or a checkout that is not a clean `origin/main`, asks once
more, then tags, waits for the publish and writes the release page, a pre-release while it is 0.x. By hand:

    git switch main && git pull --ff-only              # the commit to release, with CI green on it
    git tag -a v0.1.0 -m "v0.1.0"                      # annotated, so it records who tagged it and when
    git push origin v0.1.0                             # this push starts the release

[release.yml](../.github/workflows/release.yml) then builds, tests and publishes the jar to GitHub Packages
(`gh run watch` follows it). Once it is green, write the release page:

    gh release create v0.1.0 --verify-tag --generate-notes --prerelease

A version, once published, cannot be published again; the next fix is the next tag. `bin/release.sh` and its test,
`bin/release.test.sh`, are the same as in `moneta-api-java`; `make scripts-test` runs shellcheck and the test.
