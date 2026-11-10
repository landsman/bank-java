# How Raiffeisenbank's e-mail behaves

**Not seen as a real e-mail yet (2026-11-10).** The parser follows the sanitised samples in
[radekhulan/myinvoice](https://github.com/radekhulan/myinvoice/blob/master/api/tests/Unit/Bank/RaiffeisenbankEmailNoticeParserTest.php)
(MIT). Those are text a mail library made of the HTML, and at least partly typed: their accounts fail the modulo 11
check. No key is pinned, so `withDns()` refuses every Raiffeisenbank e-mail as `Unverified`, naming the key DNS gave;
see [#17](https://github.com/landsman/bank-java/issues/17).

## What it carries

Subject `Pohyb na účtě`, from `info@rb.cz`, **HTML only** as far as public code shows. The library turns it into one line
per cell ([usage](../../../usage.md#the-raw-message)); the labels have no colon and the value is on the next line:

```
na účtě byla provedena následující příchozí platba:
Datum a čas
01. 06. 2026 10:15
Na účet
123456789/5500Firma Test s.r.o.
Částka v měně účtu
+1.234,56 CZK
Z účtu
987654321/5500Plátce Demo s.r.o.
Variabilní symbol
2606001
Konstantní symbol
308
Zpráva pro příjemce
Faktura 2606001
Disponibilní zůstatek po pohybu
+99.999,99 CZK
```

- **The direction is stated twice**, `příchozí` / `odchozí` in the sentence and the amount's sign, and has to agree.
- **A dot separates thousands** (`+1.234,56`).
- **The account and the holder's name are glued** (`123456789/5500Firma Test s.r.o.`): the account is read from the
  start, the rest is the name. Which line is whose account depends on the direction: incoming `Na účet` is yours,
  outgoing `Z účtu`, a card payment `Účet`.
- **A card payment** (`odchozí karetní transakce`) has no counter-account; its `Detaily` (the merchant) is the message.
- **No transaction id**. The balance has the time of the movement, so `balance()` is set.

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/rb): Nastavení → Upozornění Informuj mě → Nové upozornění;
Směr pohybu Příchozí (or Všechny), Částka od 0, **Včetně zůstatku** for the balance.
