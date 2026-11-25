# How Česká spořitelna's e-mail behaves

**Not seen as a real e-mail yet (2026-11-25).** The parser follows the wording in
[Joey7500/Mobile-Finance-App](https://github.com/Joey7500/Mobile-Finance-App/blob/main/core/domain/src/test/kotlin/io/github/joey7500/vaultflow/domain/ingest/PaymentDetailsParserTest.kt)
(Apache-2.0), copied there from real e-mails with every number and name replaced. A second project, written from one
real incoming and one real outgoing transfer (AGPL, so read and not copied), names some lines differently; the parser
takes both. No key is pinned: `withDns()` accepts any key DNSSEC proves `csas.cz` published (DS 31090, algorithm 13);
see [#15](https://github.com/landsman/bank-java/issues/15).

## What it carries

Subject `Přišla platba` or `Odešla platba`, from `ceskasporitelna@csas.cz`, **HTML only** as far as public code shows.
The library turns it into lines ([usage](../../../usage.md#the-raw-message)); both sources show one `Label: value` per
line:

```
z účtu 1234567899/0800 odešla platba ve výši 1 400,00 Kč.
Na účtu zůstává k dispozici 19 880,40 Kč (včetně kontokorentu 1 000,00 Kč).
Vaše Česká spořitelna
Informace o transakci
Datum a čas platby: 20. 11. 2026 10:12
Směr platby: odchozí
Z účtu: 1234567899/0800
Na účet: 9876543211/0100
Částka v měně transakce: 1 400,00 Kč
Částka v měně účtu: 1 400,00 Kč
Konstantní symbol: 0308
Zpráva pro příjemce: padel 3. 12.
```

- **The direction is stated twice**, `přišla` / `odešla` in the sentence and `Směr platby`, and has to agree. The
  sentence's `na účet` / `z účtu` has to fit its verb.
- **The account and the amount are stated twice too**: the sentence's against `Na účet` (incoming) or `Z účtu`
  (outgoing), and against `Částka v měně účtu`, when those lines are there.
- **A space between thousands**, a no-break space in the HTML (`1&nbsp;400,00 Kč`).
- **The balance** is read from `Na účtu zůstává k dispozici`, seen only on outgoing payments; it includes an overdraft,
  as the bank says. Worded otherwise, it is left out.
- **No transaction id**: deduplicate on a signed `messageId()`.

What the sources disagree on, and the parser takes either of:

| Line | Joey7500 | The other project |
|---|---|---|
| incoming verb | `přišla` | `právě dorazila` |
| counter-party's name | `Název protiúčtu` | `Název protistrany` |
| accounts on an incoming payment | `Z účtu`, `Na účet` | `Číslo účtu protistrany`, `Číslo účtu` |

**Refused**: card payments (`Platba kartou`, the account without its bank code and no transaction block) and the
daily balance (`Zůstatek na účtu`). Neither has the opening sentence.

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/cs): Informační zprávy → Příchozí platby →
Aktivovat; outgoing payments are activated separately, George Business works the same way. **One address takes every
message the bank sends**: setting ours replaces the account holder's own, unless they forward, which may break DKIM.
