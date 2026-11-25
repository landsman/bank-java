# Payment QR codes

A QR code on an invoice or a checkout fills in the payer's transfer, so the reference this library matches on
arrives the way you wrote it rather than the way the payer typed it. Which code to print depends on the currency
and on the payer's bank: there are three, and no bank app reads all of them. Researched and checked on 2026-11-25;
what was not verified says so.

| Code | Currency | Reference it carries | Read by |
|---|---|---|---|
| [QR Platba (SPAYD)](https://qr-platba.cz/pro-vyvojare/specifikace-formatu/) | CZK | variable, specific, constant symbol (`X-VS`, `X-SS`, `X-KS`), message | every Czech bank, for CZK |
| [PAY by square](https://bysquare.com/pay-by-square/) | EUR | symbols and payer's reference | Slovak banks |
| [EPC QR](https://www.europeanpaymentscouncil.eu/document-library/guidance-documents/quick-response-code-guidelines-enable-data-capture-initiation) (GiroCode, "Zahlen mit Code") | EUR only | a creditor reference **or** a free text, no symbols | Austrian and German banks, some others |

So: SPAYD for a CZK payment, PAY by square for a Slovak payer, EPC only as an extra for a EUR payment from a
payer abroad. SPAYD's all-bank guarantee covers `CC:CZK` only; its own FAQ warns against counting on EUR or on
Slovak banks ([qr-platba.cz](https://qr-platba.cz/kontakt/)).

## What reaches the e-mail

The library reads the symbols into `symbols()` and the payer's text into `message()` ([usage](usage.md)).

- **SPAYD:** the symbols are their own fields, and a Czech bank books them as symbols. `symbols().variable()`
  is what to match on.
- **EPC QR has no symbol field and no End-to-End id.** Line 10 is a structured creditor reference (35, ISO 11649
  `RF…` may be used), line 11 a free text (140); only one of them may be filled. Neither is booked as a variable
  symbol, so match on `message()`: put one short ASCII token there (`ORDER 1234567890`), and match on the account,
  the amount and that token, never on any ten digits found in the text.
- **The Slovak SEPA convention** `/VS…/SS…/KS…` sits in the payer's reference (the End-to-End id); ČSOB SK reads its
  symbols from there ([csob](banks/sk/csob/README.md)). An EPC code cannot fill that field.
- The SEPA rulebook requires the remittance to reach the payee's bank unaltered (SCT rulebook 2.7). Whether a given
  bank's e-mail then prints an EPC reference whole has **not been seen for any bank** here yet; a real e-mail of a
  payment made from an EPC code answers it per bank.

## Which banks read EPC QR

No register exists; vendor lists ([GiroSnap](https://www.girosnap.eu/en/banks/epc),
[Billit](https://www.billit.eu/en-int/help-page/integrations/qr-codes/epc-qr-code/)) are claims, not proof.

| Country | State |
|---|---|
| Austria | the national QR is the EPC one and the banks' apps read it ([PSA](https://zv.psa.at/de/zahlungsverkehr/qr-code.html), checked); Erste/Sparkasse George, Raiffeisen Mein ELBA, Bank Austria and BAWAG document scanning a QR into a transfer |
| Germany | GiroCode is EPC; Sparkasse documents it ([page](https://www.sparkasse.de/pk/produkte/konten-und-karten/banking/ueberweisung/girocode.html)); ING, Deutsche Bank, Commerzbank document QR transfers without naming the format; VR banks, DKB, comdirect reported by vendors |
| Czechia | ČSOB Smart, George Business and Raiffeisenbank's business app document "SEPA payment by QR" but do not say whether that is EPC or SPAYD with `CC:EUR`; the rest not found. A scan test settles it. |
| Slovakia | PAY by square, a format of its own ([specification](https://portal.bysquare.com/files/bysquare-PAYspecifications-1.2.0.pdf)); EPC not found at any Slovak bank |
| Poland, Hungary | national formats of their own (the ZBP 2D code, qvik), not EPC |
| Netherlands | ING and bunq reported; ABN AMRO and Rabobank reported not to read it |
| Belgium, Finland, the Baltics | several large banks reported |
| Revolut, N26, bunq | reported to read EPC; Revolut does not read SPAYD ([Wise](https://wise.com/cz/blog/revolut-platba-qr-kodem)) |

## How fast the money arrives

The e-mail comes after the money, so this is the floor for when `read` can see a payment.

- **CZK instant (CERTIS):** seconds. Every Czech bank must receive instant payments but need not offer sending
  them; the Czech National Bank reports 99 % coverage
  ([ČNB](https://www.cnb.cz/cs/platebni-styk/certis/okamzite-platby/)).
- **SEPA:** an ordinary transfer lands the next business day; an instant one (SCT Inst) within 10 seconds
  (Regulation 260/2012, Art. 5a(4)(c)). **A QR code cannot ask for instant**: EPC's identification is `SCT`
  whatever the payer picks, and the payer's app decides.
- **Instant EUR becomes mandatory** under [Regulation (EU) 2024/886](https://eur-lex.europa.eu/eli/reg/2024/886/oj/eng),
  Art. 5a(8): euro-area banks receive since 2025-01-09 and send since 2025-10-09; Czech and other non-euro banks
  must receive by **2027-01-09** and send by **2027-07-09**, at the price of an ordinary transfer from 2027-01-09.
  Raiffeisenbank and Fio already send and receive instant EUR. CZK payments are not affected.
- When a bank sends its e-mail after the booking is up to the bank; the SEPA instant scheme leaves the
  payee's notification out of its rules.

## Generating an EPC code

    BCD
    002
    1
    SCT

    Example Merchant
    FR1420041010050500013M02606
    EUR12.30


    ORDER 1234567890

Version `002` makes the BIC (line 5) optional inside the EEA; charset `1` is UTF-8; the amount is EUR, 0.01 to
999999999.99; the whole payload at most 331 bytes, error correction level M, QR version 13 at most. Interior
empty lines stay, trailing ones go. Use the account holder's real name: the payer's bank checks it against the IBAN
(verification of payee, euro area since 2025-10-09, non-euro banks by 2027-07-09, Art. 5c). All of this checked in [EPC069-12 v3.1](https://www.europeanpaymentscouncil.eu/sites/default/files/kb/file/2024-03/EPC069-12%20v3.1%20Quick%20Response%20Code%20-%20Guidelines%20to%20Enable%20the%20Data%20Capture%20for%20the%20Initiation%20of%20an%20SCT.pdf),
which also says banks need not implement it at all.

Twelve lines and [ZXing](https://github.com/zxing/zxing) are enough; the Java libraries are
[epc-qr](https://github.com/jomu78/epc-qr) (Apache-2.0) and the `EpcQrCode` builder in
[iban4j](https://github.com/arturmkrtchyan/iban4j), whose presence in a released jar was not checked. This library
does not generate codes; it reads what arrives.
