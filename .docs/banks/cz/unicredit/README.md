# How UniCredit Bank's e-mail behaves

**Not seen as a real e-mail yet (2026-11-10).** The parser follows the one sanitised sample in
[radekhulan/myinvoice](https://github.com/radekhulan/myinvoice/blob/master/api/tests/Unit/Bank/UnicreditBankEmailNoticeParserTest.php)
(MIT), whose accounts fail the modulo 11 check. No key is pinned, so `withDns()` refuses every UniCredit e-mail as
`Unverified`; see [#19](https://github.com/landsman/bank-java/issues/19).

**Before a key is pinned, a real outgoing e-mail has to show how it writes its direction.** The parser takes the
amount's sign, as myinvoice does: none is money in, a minus is money out. The sample is incoming. If an outgoing
payment came without a minus, a refund to a customer, with the invoice's amount and symbol, would pay that invoice
again.

## What it carries

Subject `Informace o pohybu na účtu`, from `unicreditbank@unicreditgroup.cz` (`unicreditgroup.cz` is DNSSEC-signed;
`unicreditbank.cz` is not), **HTML** with `<br/>` between lines:

```
na Vašem účtu č. 123456789 (CZK) Test právě proběhla transakce s následujícími údaji:

Částka: 20,546.00 CZK
Číslo účtu protistrany: 0-987654321/0600
Název účtu protistrany: PLÁTCE DEMO s.r.o.
Variabilní symbol: 0002026017
Specifický symbol:
Detaily transakce: Faktura 2026017
Datum: 25.05.2026 13:02:31
```

- **The account has no bank code**: `/2700` is added. Its currency, in parentheses, has to be the amount's. The
  account's nickname (`Test`) sits before `právě`.
- **Amounts are written the English way**: a comma between thousands, a dot before the decimals.
- **Every label is printed, an empty one too**, so the `Variabilní symbol` line is required exactly once: a forged one,
  smuggled in through the counter-party's name, makes a second.
- **No transaction id, no balance**, no movement type (`type()` is `GENERAL`).

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/unicredit): Nastavení → Notifikace → Vytvořit notifikace
pro transakce; Všechny transakce over 1 Kč.
