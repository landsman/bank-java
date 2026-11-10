# How Fio banka's Czech e-mail behaves

Fio banka in Czechia, `fio.cz`. Its Slovak e-mails (`fio.sk`) are not read yet, see
[#10](https://github.com/landsman/bank-java/issues/10).

From ten real notifications of August to November 2026 on a personal account: incoming transfers (instant, within Fio,
from another bank), outgoing transfers, card payments in Czechia and abroad, and a direct debit. The test fixtures are
six of them, anonymised; the card payment keeps its merchant.

## Watch out for

- **Every movement sends one**, money in and money out, card payments and direct debits included.
- **The account number has no bank code**: `zůstatek účtu 1234567899`. It is Fio's, so the library adds `/2010`.
- **Thousands are separated by a plain space**: `93 000,00 CZK`.
- **No booking date of its own.** The sentence gives the date and time the balance changed; `bookedOn()` is that date
  and `balance().asOf()` that moment. A card payment arrives when it is settled, a day or so after the purchase
  (bought on 4.11, notified on 5.11 at 05:56).
- **A card payment names no account**: `counterAccount()` is `null`. Its variable symbol is `9133` on every card payment
  seen, Fio's own value rather than the merchant's, and the message is the purchase: merchant, address, date, amount.
- **`Uživatelský symbol`** repeats the payer's name or the message. The library does not read it.
- **`Konstantní symbol: 0`** comes as it is; `0` is not taken for "none".
- **The account holder picks one of three formats** when setting the notification up ([setup](../../../setup.md#fio-banka-cz)).
  This page is about the long one; the other two carry no transaction id. A fourth shape has arrived too, subject
  `Fio banka - prijem na konte` (2026-08-18): account, amount, symbols, message, balance and counter-account on six
  lines, but **no transaction id and no currency**, and it matches none of the form's previews, so it may be an older
  setting. All of them are `Unparsed`: without an id, the same e-mail arriving twice would count twice.

## The message

- `From: automat@fio.cz`, `Message-ID` from `sap.private.fio.cz`, not signed.
- `DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=fio.cz; s=20160512; h=Date:From:To:Subject;`, no `l=`,
  so the whole body and the subject are signed. The key at `20160512._domainkey.fio.cz` is RSA 2048-bit, fingerprint
  `g66SWhDk9AKwpAE7Gli7fGccyAq37YMXhmoTUv5Rx6c=` (the same through 1.1.1.1 and 8.8.8.8, 2026-11-10), pinned in
  `FioCz.KEY_FINGERPRINTS`. `fio.cz` is DNSSEC-signed (RSASHA1) and so is the key record. SPF passes, DMARC is
  `p=REJECT`.
- `text/plain; charset=UTF-8`, quoted-printable, CRLF line ends, Czech, one part. Most lines end in a space.
- Under a minute from the balance changing to the `Date` header (18:29 to 18:29:45), except at night: a card payment
  settled at 05:56 was sent at 08:16, because with *online notifications* off Fio holds them between 00:00 and 8:00.

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
- `ID pokynu:` has to follow `Protistrana:` directly, so a line break in the counter-party's name cannot add lines
  of its own.
- A balance in a format not seen yet (an overdraft, perhaps) leaves `balance()` `null`; the payment is still read.

## Samples

Every kind of e-mail Fio can send, and whether a real one has been read. ✅ read, 🚫 seen and refused, ❔ not seen yet,
👁 seen only as a preview in the settings form. A ❔ needs one raw `.eml`, exported as the original, never forwarded; see
[development](../../../development.md#adding-a-bank). Parser, tests and fixtures are in `modules/bank-notifications/src/banks/fio-cz/`.

### What the account holder chooses

The settings form ([setup](../../../setup.md#fio-banka-cz)) decides the shape of every e-mail that follows.

| Choice | Option | Sample |
|---|---|---|
| Channel | e-mail | ✅ |
| | SMS | ❔ not signed, so a hint at most: [#12](https://github.com/landsman/bank-java/issues/12) |
| | push to Fio's app | n/a: no third party can read it |
| Format | long, `Fio banka - příjem na účtu` | ✅ |
| | short, `Nový příjem na účtu`: account, amount, date, counter-party, balance, type | 🚫 no transaction id |
| | one sentence, `Nový příjem na účtu`, no counter-party | 👁 |
| | `Fio banka - prijem na konte`, six lines | 🚫 no transaction id, no currency; seen 2026-08-18, it matches none of the three previews |
| Direction | all, incoming, outgoing | ✅ both directions; it decides which e-mails come, not their shape |
| Language | Czech | ✅ |
| | English | 👁 [#11](https://github.com/landsman/bank-java/issues/11) |
| Online notifications | on | ✅ |
| | off: held between 00:00 and 8:00 | ✅ same e-mail, sent later |
| Filters | amount, counter-account, bank code, variable symbol | n/a: they decide which e-mails come, not their shape |

### The account

| Account | Sample |
|---|---|
| Personal, CZK | ✅ |
| Business | ❔ |
| Fio konto, the savings account, CZK or EUR only | ❔ its money leaves only by transfer to an account at Fio, and interest is credited to it, so expect transfers within Fio and interest |
| Foreign currency | ❔ per currency, below; [#13](https://github.com/landsman/bank-java/issues/13) |
| Balance in overdraft | ❔ |

### Currencies

Fio opens a personal current account in 14 currencies (the account form, 2026-11-10). Each one sends its own
notification, and the amount is read with the currency it names. What a sample has to show: that the currency code is
printed as on a CZK account, and how many decimals the amount carries.

| Currency | Decimals in ISO 4217 | Sample |
|---|:---:|---|
| CZK | 2 | ✅ |
| EUR | 2 | ❔ an account exists to ask for one |
| USD, GBP, CHF, PLN, AUD, CAD, DKK, NOK, SEK | 2 | ❔ |
| HUF | 2, though forints are not divided in practice | ❔ whether Fio prints `,00` |
| JPY | 0 | ❔ the parser expects two decimals today, so `1 000 JPY` would be `Unparsed` |
| RUB | 2 | ❔ |

### The movement (`Typ pohybu`)

| Movement | Direction | Sample |
|---|---|---|
| `Bezhotovostní příjem`, from another bank | in | ✅ `incoming-transfer.eml` |
| `Okamžitá příchozí platba` | in | ✅ `incoming-instant.eml` |
| `Příjem převodem uvnitř banky` | in | ✅ read, no fixture |
| `Bezhotovostní platba`, to another bank | out | ✅ `outgoing-transfer.eml` |
| `Platba kartou`, in Czechia and abroad | out | ✅ `card.eml` |
| `Inkaso` | out | ✅ `direct-debit.eml` |
| Outgoing instant payment | out | ❔ |
| Transfer within Fio | out | ❔ |
| Standing order | out | ❔ |
| Card refund | in | ❔ |
| Cash withdrawal, ATM | out | ❔ |
| Cash deposit | in | ❔ |
| Foreign payment, SEPA or SWIFT | in, out | ❔ |
| Between the holder's own accounts | in, out | ❔ |
| Fee, interest | in, out | ❔ |

The names of the ❔ rows are descriptions, not Fio's wording: `Typ pohybu` is read from the sample. A movement whose
name is not in the table above `type()` as `GENERAL` and is still read; the sample is what turns it into `TRANSFER`,
`CARD` or `DIRECT_DEBIT`.

### Fields

| Field | Sample |
|---|---|
| Variable and constant symbol | ✅ |
| Specific symbol | ❔ |
| Counter-party without a name | ✅ `outgoing-transfer.eml` |
| A message with a line break | ❔ |

### Other notifications

Fio sends more than movements. These say nothing about a payment, so `read` does not make a `Transaction` of them:
their subject matches no movement template and they are `Unparsed`. Recorded so a sample is recognised when one comes.

**Balance** (*Přidat nové oznámení o zůstatku*), three kinds: the balance itself, the balance falling below an amount,
the balance rising above an amount. Each in the same three formats as a movement, from the form's preview:

| Format | Preview | Sample |
|---|---|---|
| long, `Fio banka - zůstatek na účtu` | `Dobrý den, stav účtu 2800201001 přesáhl 1.1.2025 v 14:20 hlídanou částku 950 000 CZK, aktuální zůstatek je 1 000 000 CZK.` | 👁 |
| short, `Zůstatek na účtu` | `Účet 2800201001 přesáhl hlídanou částku: 950 000 CZK, zůstatek 1.1.2025 v 14:20 je: 1 000 000 CZK` | 👁 |
| one sentence | `Účet 2800201001 přesáhl hlídanou částku. Aktuální zůstatek 1 000 000 CZK.` | 👁 |

The previews show the rise above an amount only; the wording of the other two is not known.
