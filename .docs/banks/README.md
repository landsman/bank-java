# Banks

Which banks the library reads, and what it reads from each. Every bank has a page of its own with the template, the
setup and what has not been seen yet, in a folder per country: `cz/<bank>/`, and `sk/<bank>/` once a Slovak bank is
read; another country is another folder. This page only says where each one stands, and changes with every bank's pull
request.

A bank is in one of three states:

- **Verified**: read from real e-mails, its DKIM key pinned and checked daily. `read()` returns a `Transaction`.
- **Samples only**: the template follows public code ([radekhulan/myinvoice](https://github.com/radekhulan/myinvoice),
  MIT), not a real e-mail, and no key is pinned. `read()` refuses every e-mail from it as `Unverified`, naming the key
  DNS gave, until a real e-mail confirms the template and that key.
- **Not yet**: no parser.

## Czechia

| Bank | State | Reads | Leaves out | Details |
|---|---|---|---|---|
| Air Bank | verified | transfers in and out; symbols, message, balance | amounts and balances of 1 000 and more ([#5](https://github.com/landsman/bank-java/issues/5)), cards, direct debits | [airbank](cz/airbank/README.md) |
| Fio banka | verified | transfers in and out, card payments, direct debits; symbols, message, balance; thousands | other currencies ([#13](https://github.com/landsman/bank-java/issues/13)), English ([#11](https://github.com/landsman/bank-java/issues/11)); its short format is refused | [fio](cz/fio/README.md) |
| MONETA | samples only | incoming and outgoing; counter-account, symbol from the description | own account (masked), transaction id, balance; payments under 100 Kč are never sent | [moneta](cz/moneta/README.md) |
| Creditas | samples only | incoming and outgoing; counter-account, symbol, balance | transaction id, name, message; card holds refused | [creditas](cz/creditas/README.md) |
| Raiffeisenbank | samples only | transfers and card payments, from HTML; names, symbols, message, balance | transaction id | [raiffeisenbank](cz/raiffeisenbank/README.md) |
| UniCredit Bank | samples only | incoming, from HTML; counter-account and name, symbols, message | transaction id, balance, type; how an outgoing payment writes its direction | [unicredit](cz/unicredit/README.md) |
| ČSOB | samples only | CEB: incoming transfers and card payments, several in one e-mail (`readAll`); names, symbols, message | transaction id, balance time; SEPA, foreign and cash payments; its own internet banking's newer notification | [csob](cz/csob/README.md) |
| Komerční banka | not yet | | | [#14](https://github.com/landsman/bank-java/issues/14) |
| Česká spořitelna | not yet | | | [#15](https://github.com/landsman/bank-java/issues/15) |
| Citfin | not yet | | | [#21](https://github.com/landsman/bank-java/issues/21) |
| Partners Banka | not yet | | sends no e-mail at all | [#25](https://github.com/landsman/bank-java/issues/25) |

## Slovakia

| Bank | State | Reads | Leaves out | Details |
|---|---|---|---|---|
| Fio banka | not yet | | | [#10](https://github.com/landsman/bank-java/issues/10) |
| ČSOB | not yet | | | [#22](https://github.com/landsman/bank-java/issues/22) |
| Slovenská sporiteľňa | not yet | | | [#23](https://github.com/landsman/bank-java/issues/23) |
| Tatra banka | not yet | | key known, still published | [#24](https://github.com/landsman/bank-java/issues/24) |

What a missing transaction id means for deduplication, and a masked account for knowing whose payment it is:
[using the library](../usage.md#two-checks-stay-with-you). A bank that is not yet read has an issue collecting what is
known and which samples are needed; an e-mail from it is `Unparsed` with `reason()` `NOT_A_BANK_EMAIL`.
