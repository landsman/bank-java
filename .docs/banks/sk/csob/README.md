# How ČSOB Slovakia's e-mail behaves

**Not seen as a real signed e-mail yet (2026-11-10).** The parser follows
[tomaj/bank-mails-parser](https://github.com/tomaj/bank-mails-parser/tree/master/tests/fixtures/csob-sk)'s 16 fixtures
and [remp2020's raw e-mail](https://github.com/remp2020/crm-payments-module/blob/master/docs/sk_csob.eml) (both MIT,
2019). **That e-mail carries no DKIM signature of the bank** (only Gmail's own ARC headers), and `csob.sk` is not
DNSSEC-signed (checked 2026-11-10 through 1.1.1.1). So no key is pinned, and even with one `withDns()` would refuse every
ČSOB SK e-mail until a real one shows that ČSOB SK signs, and under which domain. See
[#22](https://github.com/landsman/bank-java/issues/22).

## What it carries

Subject `ČSOB Info 24 - Avízo`, **windows-1250**, base64:

```
dovoľujeme si Vám oznámiť, že dňa 6.9.2019 bola na účte SK13 7500 0000 0040 1942 5381 PETIT PRESS, A.S. zaúčtovaná suma SEPA platobného príkazu:
suma:                    +1150,00 EUR
z účtu:                  SK91 7500 0000 0030 2072 9058
banka:                   CEKOSKBX
detaily platby:
názov protiúčtu:         NOVAK JOZEF
referencia platiteľa:    /VS212049000/SS964/KS0308
informácia pre príjemcu: Maroko 2019

Zostatok na účte po zaúčtovaní sumy platobnej operácie: +14050,70 EUR
```

- **Accounts are IBANs**, read with `BankAccount.parseIban` and checked against both modulo 97 and modulo 11.
- **Only incoming payments**: no outgoing sample has been seen, so an amount with a minus is refused.
- **The symbols come from the payer's reference** (`/VS…/SS…/KS…`), text the payer wrote: match on the amount as well.
  `NOTPROVIDED` means none. The template prints the line every time, so it is required exactly once, and it has to
  follow the counter-party's name directly; a reference forged in the name makes a second.
- **Past `informácia pre príjemcu`**, the payer's message, no labelled line may follow, and a second booking sentence is
  refused: tomaj's attack fixtures put a forged account or a whole forged transaction there.
- **No transaction id**; the balance has no time. No thousands separator in the samples (`+1150,00`).
- tomaj's multi-transaction fixture is synthetic, so one transaction per e-mail is read.
