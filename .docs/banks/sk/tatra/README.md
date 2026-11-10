# How Tatra banka's e-mail behaves

**Not seen as a real e-mail yet (2026-11-10).** The parser follows
[remp2020's raw e-mail](https://github.com/remp2020/crm-payments-module/blob/master/docs/tatrabanka.eml) (2019) and
[tomaj/bank-mails-parser](https://github.com/tomaj/bank-mails-parser/tree/master/tests/fixtures/tatrabanka)'s fixtures
(2015), both MIT. That e-mail was signed with selector `mta`, and `mta._domainkey.tatrabanka.sk` is still published:
RSA 2048, DNSSEC-secure, fingerprint `sOUiSq9+xyeXpczY9SzI+HLjzU4jZyfQUXGvxjtXmVc=` (2026-11-10, through 1.1.1.1 and
8.8.8.8). It is not pinned until a real e-mail confirms the template and that Tatra still signs with it, so `withDns()`
refuses every Tatra banka e-mail as `Unverified`. See [#24](https://github.com/landsman/bank-java/issues/24).

## What it carries

A B-mail from `b-mail@tatrabanka.sk`, **ISO-8859-2** without diacritics, subject `Kredit na ucte (ID=111219/320305-1)`,
`c=simple/simple` so the body is signed byte for byte:

```
11.12.2019 13:13 bol zostatok Vasho uctu SK0211000000002946123456 zvyseny o 4,00 EUR.
uctovny zostatok:                            88 888,62 EUR
aktualny zostatok:                           88 888,62 EUR
disponibilny zostatok:                       88 888,62 EUR

Popis transakcie: CCINT 1100/000000-2611123000
Referencia platitela: /VS12319670000/SS123/KS308
Ucet protistrany: Novak Jozef, Ing.
```

- **The subject carries the transaction id** (`ID=111219/320305-1`) **and the direction** (`Kredit`; `Debet` is
  assumed for money out), and it is signed. The sentence states the direction again (`zvyseny` / `znizeny`); the two
  have to agree. Another subject is refused.
- **The account is an IBAN**, read with `BankAccount.parseIban`. The sample's is anonymised and fails its check digits.
- **The symbols come from the payer's reference** (`/VS…/SS…/KS…`), text the payer wrote. One not in that form, ten
  digits at most per symbol, leaves them out instead of refusing the payment: the sample's variable symbol has eleven.
  The reference line is required, as every sample prints it.
- **`Ucet protistrany` holds the other side's name**, not an account; no counter-account is given. After it and after
  `Informacia pre prijemcu`, the payer's text, no other labelled line may follow.
- **The balance** is `disponibilny zostatok`, at the sentence's time, in Europe/Bratislava.
- `type()` is `GENERAL`: the description (`CCINT …`) is not mapped without more samples.

## Setting it up

[Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/tatra): NASTAVENIA → B-mail → VYTVORIŤ NOVÝ; Email, tick
Vklad and Výber with an amount.
