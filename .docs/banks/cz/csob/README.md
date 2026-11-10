# How ČSOB's e-mail behaves

**Not seen as a real e-mail yet (2026-11-10).** The parser reads ČSOB's **CEB** notification from the fixtures of
[tomaj/bank-mails-parser](https://github.com/tomaj/bank-mails-parser/tree/master/tests/fixtures/csob-cz) (MIT, 43 of
them, 2018 to 2022), whose accounts fail the modulo 11 check. ČSOB signed as `d=csob.cz; s=alpha` in a 2020 e-mail
([remp2020's csob.eml](https://github.com/remp2020/crm-payments-module/blob/master/docs/csob.eml)); that key is gone, so
none is pinned and `withDns()` refuses every ČSOB e-mail as `Unverified`. See
[#16](https://github.com/landsman/bank-java/issues/16).

The newer notification of ČSOB's own internet banking (labels on their own lines, `Parametry platby`, as in
[myinvoice's sample](https://github.com/radekhulan/myinvoice/blob/master/api/tests/Unit/Bank/CsobBankEmailNoticeParserTest.php))
is not read yet.

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

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/csob): in CEB, Nastavení → CEB info → Účty a platby →
Nová notifikace → Zaúčtování platby → Všechny pohyby.
