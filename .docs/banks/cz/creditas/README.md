# How Creditas's e-mail behaves

**Not seen as a real e-mail yet (2026-11-10).** The parser follows the sanitised samples in
[radekhulan/myinvoice](https://github.com/radekhulan/myinvoice/blob/master/api/tests/Unit/Bank/CreditasBankEmailNoticeParserTest.php)
(MIT). Those samples were at least partly typed: their counter-account fails the modulo 11 check. No key is pinned, so
`withDns()` refuses every Creditas e-mail as `Unverified`, naming the key DNS gave; see
[#20](https://github.com/landsman/bank-java/issues/20).

## What it carries

Subject `Notifikace o změně na účtu`, from `info@creditas.cz`. A sentence, then a list whose items are sometimes one to a
line and sometimes joined on one line:

```
zůstatek na účtu 1000000005/2250 se zvýšil o částku 1 000,00 CZK (Příchozí úhrada). Disponibilní zůstatek 23.06.2026 18:43 je 97 163,53 CZK.

Detail platby:
- změna na účtu: 1000000005/2250
- účet protistrany: 1900000007 - banka protistrany: 0800 - datum: 23.06.2026 18:43
- částka: 1 000,00 CZK
- VS: 123456 - disponibilní zůstatek: 97 163,53 CZK
```

- **The account and the amount are stated twice**, in the sentence and the list, and have to agree.
- **The kind is in parentheses.** `Příchozí úhrada` is `TRANSFER`; any other is `GENERAL`, and an incoming kind on a
  balance that went down is refused.
- **`Blokace` is refused**: a card hold, not a booked payment. Read as a payment, it would count twice once booked.
- **No transaction id, no counter-party name, no message.** The balance has a time, so `balance()` is set.

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/creditas): the notification goes to the authorisation
e-mail, changed with an SMS code; Správa → Spravovat notifikace → Nová notifikace o změně na účtu → Změna zůstatku o
více než 1 Kč. **The new Creditas One app has no e-mail notifications**, and accounts are being moved to it.
