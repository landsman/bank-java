# How ČSOB's e-mail behaves

**Not seen as a real e-mail yet (2026-11-10).** The parser reads ČSOB's **CEB** notification from the fixtures of
[tomaj/bank-mails-parser](https://github.com/tomaj/bank-mails-parser/tree/master/tests/fixtures/csob-cz) (MIT, 43 of
them, 2018 to 2022), whose accounts fail the modulo 11 check. ČSOB signed as `d=csob.cz; s=alpha` in a 2020 e-mail
([remp2020's csob.eml](https://github.com/remp2020/crm-payments-module/blob/master/docs/csob.eml)); that key is gone, so
none is pinned and `withDns()` refuses every ČSOB e-mail as `Unverified`. See
[#16](https://github.com/landsman/bank-java/issues/16).

ČSOB's own internet banking sends another template, **"Moje info - Avízo"**, read from
[myinvoice's samples](https://github.com/radekhulan/myinvoice/blob/master/api/tests/Unit/Bank/CsobBankEmailNoticeParserTest.php)
(MIT); see [below](#moje-info---avízo).

## What it carries

Subject `CEB Info: Zaúčtování platby`. One block per booked transaction, **several in one e-mail at times**:

```
Dne 25.9.2018 byla na účtu 123456789 zaúčtovaná transakce typu: Došlá platba.

Název smlouvy: CRM International a.s.
Číslo smlouvy: 87654321
Majitel smlouvy: Shmelina a.s.
Účet: 123456789, CZK, CRM INTERNATION
Částka: +1 234,56 CZK
Účet protistrany: 1122334455/9999
Název protistrany: Capi Hnizdo a.s.
Variabilní symbol: 23456789
Konstantní symbol: 3456

Zůstatek na účtu po zaúčtování transakce: +1 234 567,89 CZK.
```

- **Read them all with `readAll()`.** `read()` refuses an e-mail with several, rather than return one of them.
- **The balances chain**: with several blocks, each balance has to be the previous one plus the block's amount, and
  every block needs its balance. A block forged in a payer's message would take the real block's balance away or break
  the chain; without balances it could not be told apart, so such an e-mail is refused. tomaj's parser names that case
  a known limitation.
- **In each block** the sentence's account is the `Účet` line's (`/0300` is added), the amount's currency is the
  account's, and the sign is the direction. The kinds read: `transakce typu: Došlá platba`, `Došlá úhrada`,
  `Příchozí úhrada` (`okamžitá` too), and `transakce platební kartou` (no counter-account, `Z účtu: /`); an incoming kind
  with a minus is refused. A SEPA transfer (`byl na účtu … zaúčtovaný SEPA převod`), a foreign or a cash one is a
  sentence of its own, with an IBAN for the counter-party, and is `Unparsed` for now.
- **After `Zpráva příjemci`**, the payer's text, no labelled line may follow.
- **No transaction id**, and the balance has no time, so `balance()` is `null`. Deduplicate on a signed `messageId()`
  (ČSOB signed `message-id` in 2020) and the position in `readAll()`'s list.

## Moje info - Avízo

The subject picks it. One transaction, the labels on lines of their own and each value under its label:

```
dne 31.5.2026 byla na účtu 123456789 zaúčtována transakce typu: Odchozí úhrada okamžitá.

Parametry platby
Účet
123456789/0300
Účet protistrany
35-4544230267/0100
Datum účtování
31.5.2026
Částka
-10 000,00 CZK
Variabilní symbol
0009002567593
Konstantní symbol
1120
Zůstatek na účtu po zaúčtování transakce: +0,00 CZK.
```

- **The direction is stated twice**, `Příchozí` / `Odchozí` in the kind and the amount's sign, and has to agree.
- **A symbol may be padded with zeros to 13 digits**; only the zeros beyond ten are cut, so `0000002567593` is
  `0002567593`. A longer symbol that is not zero padding is refused.
- **The line under `Název protistrany` is the name; the one after it has to be `Datum účtování`**, so a line break in
  the name cannot add lines.
- No transaction id, no message label seen; the balance has no time.

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/csob): in CEB, Nastavení → CEB info → Účty a platby →
Nová notifikace → Zaúčtování platby → Všechny pohyby.
