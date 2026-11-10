# How Fio's e-mail behaves

From ten real notifications of August to November 2026 on a personal account: incoming transfers (instant, within Fio,
from another bank), outgoing transfers, card payments in Czechia and abroad, and a direct debit. The test fixtures are
six of them, anonymised; the card payment keeps its merchant.

## Watch out for

- **Every movement sends one**, money in and money out, card payments and direct debits included.
- **The account number has no bank code**: `zůstatek účtu 2700284437`. It is Fio's, so the library adds `/2010`.
- **Thousands are separated by a plain space**: `93 000,00 CZK`.
- **No booking date of its own.** The sentence gives the date and time the balance changed; `bookedOn()` is that date
  and `balance().asOf()` that moment. A card payment arrives when it is settled, a day or so after the purchase
  (bought on 4.11, notified on 5.11 at 05:56).
- **A card payment names no account**: `counterAccount()` is `null`. Its variable symbol is `9133` on every card payment
  seen, Fio's own value rather than the merchant's, and the message is the purchase: merchant, address, date, amount.
- **`Uživatelský symbol`** repeats the payer's name or the message. The library does not read it.
- **`Konstantní symbol: 0`** comes as it is; `0` is not taken for "none".
- **A short template exists too**, subject `Fio banka - prijem na konte` (seen 2026-08-18): account, amount, symbols,
  message, balance and counter-account on six lines, but **no transaction id and no currency**. It is `Unparsed`:
  without an id, the same e-mail arriving twice would count twice. Which setting in Fio sends it is not known.

## The message

- `From: automat@fio.cz`, `Message-ID` from `sap.private.fio.cz`, not signed.
- `DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=fio.cz; s=20160512; h=Date:From:To:Subject;`, no `l=`,
  so the whole body and the subject are signed. The key at `20160512._domainkey.fio.cz` is RSA 2048-bit, fingerprint
  `g66SWhDk9AKwpAE7Gli7fGccyAq37YMXhmoTUv5Rx6c=` (the same through 1.1.1.1 and 8.8.8.8, 2026-11-10), pinned in
  `Fio.KEY_FINGERPRINTS`. `fio.cz` is DNSSEC-signed (RSASHA1) and so is the key record. SPF passes, DMARC is
  `p=REJECT`.
- `text/plain; charset=UTF-8`, quoted-printable, CRLF line ends, Czech, one part. Most lines end in a space.
- Under a minute from the balance changing to the `Date` header (18:29 to 18:29:45), except at night: a card payment
  settled at 05:56 was sent at 08:16.

## The body

Subject `Fio banka - příjem na účtu` or `Fio banka - výdaj na účtu`:

```
zůstatek účtu <account> se 11.10.2026 v 18:29 zvýšil o 1 000,00 CZK.

Další parametry
Typ pohybu: Okamžitá příchozí platba
Protistrana: <counter-account>/<bank code> (<name>)
ID pokynu: <11 digits>
Variabilní symbol: <digits>
Konstantní symbol: 0
Uživatelský symbol: <text>
Zpráva pro příjemce: <text>

Aktuální zůstatek: 16 992,68 CZK
```

Every line under `Další parametry` but `Typ pohybu` and `ID pokynu` appears only when the movement has it.

| `Typ pohybu` | `type()` |
|---|---|
| `Bezhotovostní příjem`, `Bezhotovostní platba`, `Okamžitá příchozí platba`, `Příjem převodem uvnitř banky` | `TRANSFER` |
| `Platba kartou` | `CARD` |
| `Inkaso` | `DIRECT_DEBIT` |
| anything not seen yet | `GENERAL` |

## How the parser reads it

- The direction is stated twice, in the signed subject (`příjem` / `výdaj`) and in the sentence (`zvýšil` / `snížil`).
  They have to agree, or the message is `Unparsed`.
- `ID pokynu` is the `transactionId()`; a message without it is `Unparsed`.
- A labelled line that appears twice makes the message `Unparsed`.
- After `Uživatelský symbol:` or `Zpráva pro příjemce:`, the text a payer controls, only the other of the two and
  `Aktuální zůstatek:` may follow, so a message cannot smuggle in a symbol of its own.
- A balance in a format not seen yet (an overdraft, perhaps) leaves `balance()` `null`; the payment is still read.

## Not seen yet

A business account; a foreign-currency account; an outgoing instant payment; a standing order; a cash withdrawal; an
overdraft balance; a specific symbol. Each needs one real e-mail.
