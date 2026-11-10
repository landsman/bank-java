# How MONETA's e-mail behaves

**Not seen as a real e-mail yet (2026-11-10).** The parser follows the sanitised samples in
[radekhulan/myinvoice](https://github.com/radekhulan/myinvoice/blob/master/api/tests/Unit/Bank/MonetaBankEmailNoticeParserTest.php)
(MIT), whose MONETA parser real users run. No key is pinned, so `withDns()` refuses every MONETA e-mail as `Unverified`,
with the fingerprint of the key DNS gave in the message. The first real e-mail confirms the template and that key;
see [#18](https://github.com/landsman/bank-java/issues/18).

## What it carries, and what it does not

Subject `Přišly peníze` or `Odešly peníze`, from `infoservis@moneta.cz`, the label on one line and its value on the next:

```
Přišly peníze

Účet:
238***891

Datum:
01.07.2026

Částka:
35 000,00 Kč

Popis:
OKAMŽITÁ ÚHRADA, DOM-AVIZO:202600009 FIRMA DEMO S.R.O., VS: 202600009

Od:
264555317/0300

Disponibilní zůstatek:
64 831,42 Kč

Účetní zůstatek:
64 831,42 Kč
```

- **The receiving account is masked** (`238***891`): `account()` is `null`. Which merchant it belongs to has to come from
  the address it was sent to.
- **No transaction id**: `transactionId()` is `null`. A second delivery of the same e-mail is told apart only by a
  signed `messageId()`, if MONETA signs it, which a real e-mail will show.
- **The variable symbol is inside `Popis`**, as `VS: 202600009`, and `Popis` may hold the payer's own text. Two `VS:`
  there are refused; one is taken, so **match on the amount as well**.
- **The balance has a date but no time**, so `balance()` is `null`.
- **`type()` is `GENERAL`**: the description says `OKAMŽITÁ ÚHRADA` and the like, but no mapping is built without samples.
- Only `Kč` amounts are read; another currency is refused.

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/moneta): Nastavení → Upozornění - Info Servis →
Přidat upozornění → Účty; E-mail for Příchozí and Odchozí platba. **MONETA notifies only from 100 Kč**, so smaller
payments send nothing. MONETA also has a free API with a token the account holder issues, without that floor:
[moneta-api-java](https://github.com/landsman/moneta-api-java).
