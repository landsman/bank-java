# Banks

Which banks the library reads, and what it reads from each. Every bank has a page of its own with the template, the
setup and what has not been seen yet, in a folder per country: `cz/<bank>/` and `sk/<bank>/`; another country is
another folder. This page only says where each one stands, and changes with every bank's pull request.

A bank is in one of three states:

- **Verified**: read from real e-mails, its DKIM key pinned and checked daily. `read()` returns a `Transaction`.
- **Samples only**: the template follows public code ([radekhulan/myinvoice](https://github.com/radekhulan/myinvoice),
  [tomaj/bank-mails-parser](https://github.com/tomaj/bank-mails-parser), MIT; [Joey7500/Mobile-Finance-App](https://github.com/Joey7500/Mobile-Finance-App), Apache-2.0), not a real e-mail, and no key is pinned.
  `read()` returns a `Transaction` once the signature verifies with any key DNSSEC proves the bank's domain published;
  ČSOB SK, whose domain has no DNSSEC, stays `Unverified`. A real e-mail moves it to verified and pins its key.
- **Not yet**: no parser.

The tables list every bank in the central bank's register of payment codes: [ČNB](https://www.cnb.cz/cs/platebni-styk/ucty-kody-bank/)
and [NBS](https://nbs.sk/en/payments/general-information/directories-and-registers/directory-identification-codes-domestic-payment-system-in-sr/),
read on 2026-11-24. Left out are those that hold no account a payment arrives on: the central banks, building societies
(stavební spořitelny), development and export banks (Národní rozvojová banka, Česká exportní banka, SZRB, EXIMBANKA SR),
ČSOB Hypoteční banka, Štátna pokladnica, Sberbank CZ in liquidation, consumer lenders (COFIDIS, BNP Paribas Personal
Finance) and payment institutions that are not banks (Banking Circle, Comgate, Trust Pay and the like).

## Czechia

| Bank | State | Reads | Leaves out | Details |
|---|---|---|---|---|
| Air Bank | verified | transfers in and out, EUR accounts; symbols, message, balance; thousands as public samples write them | a real e-mail of 1 000 or more ([#5](https://github.com/landsman/bank-java/issues/5)), cards, direct debits; its daily balance is refused | [airbank](cz/airbank/README.md) |
| Fio banka | verified | transfers in and out, card payments, direct debits; symbols, message, balance; thousands; English, from public samples ([#11](https://github.com/landsman/bank-java/issues/11)) | other currencies ([#13](https://github.com/landsman/bank-java/issues/13)); its short format is refused | [fio](cz/fio/README.md) |
| Komerční banka | verified | KB+ incoming payments, from HTML; both accounts, symbols, message, the Czech and English blocks required to agree | transaction id, balance, counter-party name; outgoing and card payments (KB+ does not send them); MojeBanka (`kb.cz`) | [kb](cz/kb/README.md) |
| MONETA | samples only | incoming and outgoing; counter-account, symbol from the description | own account (masked), transaction id, balance; payments under 100 Kč are never sent | [moneta](cz/moneta/README.md) |
| Creditas | samples only | incoming and outgoing; counter-account, symbol, balance | transaction id, name, message; card holds refused | [creditas](cz/creditas/README.md) |
| Raiffeisenbank | samples only | transfers and card payments, from HTML; names, symbols, message, balance | transaction id | [raiffeisenbank](cz/raiffeisenbank/README.md) |
| Česká spořitelna | samples only | transfers in and out, from HTML; counter-account and name, symbols, message, balance on outgoing | transaction id; card payments and the daily balance are refused | [ceskasporitelna](cz/ceskasporitelna/README.md) |
| UniCredit Bank | samples only | incoming, from HTML; counter-account and name, symbols, message | transaction id, balance, type; how an outgoing payment writes its direction | [unicredit](cz/unicredit/README.md) |
| ČSOB | samples only | CEB: incoming transfers and card payments, several in one e-mail (`readAll`); own banking's "Moje info - Avízo", in and out; names, symbols, message | transaction id, balance time; SEPA, foreign and cash payments | [csob](cz/csob/README.md) |
| Citfin | not yet | | | [#21](https://github.com/landsman/bank-java/issues/21) |
| Partners Banka | not yet | | sends no e-mail at all | [#25](https://github.com/landsman/bank-java/issues/25) |
| mBank | not yet | | | |
| J&T BANKA | not yet | | | |
| PPF banka | not yet | | | |
| TRINITY BANK | not yet | | | |
| ING Bank | not yet | | | |
| PKO BP | not yet | | | |
| Citibank Europe | not yet | | | |
| Deutsche Bank | not yet | | | |
| COMMERZBANK | not yet | | | |
| BNP Paribas | not yet | | | |
| HSBC Continental Europe | not yet | | | |
| Všeobecná úverová banka, pobočka Praha | not yet | | | |
| Oberbank | not yet | | | |
| Volksbank Raiffeisenbank Nordoberpfalz, pobočka Cheb | not yet | | | |
| Bank of China (CEE) | not yet | | | |
| Bank of Communications | not yet | | | |
| Industrial and Commercial Bank of China | not yet | | | |
| Multitude Bank | not yet | | | |
| Artesa, spořitelní družstvo | not yet | | | |
| Peněžní dům, spořitelní družstvo | not yet | | | |

## Slovakia

| Bank | State | Reads | Leaves out | Details |
|---|---|---|---|---|
| Fio banka | not yet | | | [#10](https://github.com/landsman/bank-java/issues/10) |
| ČSOB | samples only | incoming SEPA credits; IBANs, name, symbols from the payer's reference, message | outgoing payments, transaction id; the 2019 sample was not DKIM-signed and `csob.sk` has no DNSSEC | [csob](sk/csob/README.md) |
| Slovenská sporiteľňa | not yet | | | [#23](https://github.com/landsman/bank-java/issues/23) |
| Tatra banka | samples only | credits and debits; transaction id and direction from the signed subject, IBAN, name, symbols from the payer's reference, balance | counter-account, type; its 2019 key is published but not pinned | [tatra](sk/tatra/README.md) |
| Všeobecná úverová banka (VÚB) | not yet | | only the old internet banking e-mails a movement ("Upozornenie o zrealizovanej transakcii"), the new one does not ([Redbit](https://podpora.redbit.cz/navod/parovani-plateb-vub/), 2026-11-25); the public samples are its e-shop payment confirmation, not an account notice | |
| Prima banka | not yet | | | |
| UniCredit Bank, pobočka | not yet | | | |
| mBank, pobočka | not yet | | | |
| J&T BANKA, pobočka | not yet | | | |
| Komerční banka, pobočka | not yet | | | |
| Penta Bank | not yet | | | |
| ING Bank, pobočka | not yet | | | |
| Citibank Europe, pobočka | not yet | | | |
| Commerzbank, pobočka | not yet | | | |
| Oberbank, pobočka | not yet | | | |
| BKS Bank, pobočka | not yet | | | |
| PKO BP, pobočka | not yet | | | |
| KDB Bank Europe, pobočka | not yet | | | |

What a missing transaction id means for deduplication, and a masked account for knowing whose payment it is:
[using the library](../usage.md#two-checks-stay-with-you). A bank that is not yet read gets an issue collecting what is
known and which samples are needed once someone asks for it; an e-mail from it is `Unparsed` with `reason()` `NOT_A_BANK_EMAIL`.
