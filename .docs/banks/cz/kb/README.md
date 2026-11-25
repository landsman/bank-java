# How Komerční banka's KB+ e-mail behaves

Komerční banka in Czechia, the KB+ internet banking, sender `servis@kbinfo.cz`. KB's other sender, `kb.cz` (MojeBanka
and MojeBanka Business), is not read: no real e-mail of it has been seen. Requested in
[#14](https://github.com/landsman/bank-java/issues/14).

From two real incoming payments on a personal account (2026-11-07 and 2026-11-25) and a notice about deposit
protection (2026-11-03). The test fixtures are those three, anonymised.

## Watch out for

- **Incoming payments only.** KB+ offers no notification of money leaving the account, nor of card payments.
- **Only to the main contact e-mail**, never to an address of your choice: the account holder's mailbox forwards it
  by a filter ([setup](../../../setup.md#komerční-banka)).
- **No transaction id and no balance.** Deduplicate on `messageId()`, which KB signs.
- **No counter-party name.** The `Z účtu` cell holds a `<br>` before the account, perhaps where a name would go; a
  name there would make the e-mail `Unparsed` rather than misread.
- **About 90 KB**, one HTML part with its styles inline, which is why the size limit moved
  ([verification](../../../verification.md#the-size-limit)).
- **Every value is stated twice**, a Czech block and then the same in English.
- **No-break spaces**: between the thousands (`1 700,00 Kč`, U+00A0) and between some words (`na Váš účet`,
  `Chcete s něčím poradit?`). The preheader is a run of U+2007 U+FEFF U+034F.
- **Empty fields keep their label** and leave the value cell empty: `Zpráva pro příjemce`, the three symbols. In both
  real e-mails all four were empty, even where the payer set a symbol of their own (Fio's `uživatelský symbol` is
  not passed on).

## The message

- `DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=kbinfo.cz; s=scph0521; i=@kbinfo.cz;
  h=Content-Type:To:Message-ID:Date:Subject:From:From:To:Cc:Subject:Feedback-Id`, no `l=`, so the whole body, the
  subject and the `Message-ID` are signed, and `From` is oversigned. `Content-Transfer-Encoding` is not signed, which
  the library's one-copy rule covers. The key at `scph0521._domainkey.kbinfo.cz` is RSA 1024-bit, fingerprint
  `eMt7sZv6hImbWc6czAVHyTbua9n1wrFfAB9Mv8+3n6g=` (the same through 1.1.1.1 and 8.8.8.8, 2026-11-25), pinned in
  `KomercniBanka.KEY_FINGERPRINTS`. `kbinfo.cz` is DNSSEC-signed, and so is the key record.
- Sent through SparkPost; `Message-ID` from `sparkpost`, `Reply-To: kbplus@kb.cz`. SPF passes, DMARC is
  `p=QUARANTINE`.
- `text/html; charset="UTF-8"`, quoted-printable, one part, no text part, no attachment.

## The body

Subject `Servisní zpráva: Přijali jsme platbu na Váš účet`. As [lines](../../../development.md#adding-a-bank), one per
cell, an empty cell giving none:

```text
For English scroll down
Přijali jsme platbu 1 700,00 Kč na Váš účet
Splatnost:  07. 11. 2026
Z účtu
9876543211/2010
Na účet
1234567899/0100
Zpráva pro příjemce
Variabilní symbol
Konstantní symbol
Specifický symbol
Chcete s něčím poradit?
…
We have received the payment 1,700.00 CZK to your account
Due date:  11-07-2026
From account
9876543211/2010
To account
1234567899/0100
Message for beneficiary
Variable symbol
Constant symbol
Specific symbol
We are here to help
…
```

The English amount has a comma between thousands and a dot before the hundredths; its date puts the month first.

## How the parser reads it

- The direction is stated three times, in the signed subject and in both headings; anything but the incoming wording
  is `Unparsed`.
- The amount, the due date, both accounts, the message and each symbol are read from both blocks and have to agree,
  or the e-mail is `Unparsed`.
- A label followed by another label is that field being absent. The line that ends each block (`Chcete s něčím
  poradit?`, `We are here to help`) counts as a label, so an empty specific symbol never takes it for a value.
- After `Zpráva pro příjemce`, the payer's text, only the symbols and the block's end may follow. The message sits in
  one cell, so a line break the payer typed stays inside it.
- `bookedOn()` is `Splatnost`; `type()` is `TRANSFER`; `account()` is `Na účet` and `counterAccount()` is `Z účtu`.
- Only CZK has been seen; any other currency is `Unparsed`.

## Setting it up

See [setup](../../../setup.md#komerční-banka): KB+ → Nastavení → Oznámení → Příchozí platby → e-mail, then a
mailbox filter on `servis@kbinfo.cz` forwarding to your address. Verified by forwarding from Gmail to Seznam.cz
through an automatic filter (2026-11-25, DKIM still passing). A business account in KB+ gets no payment
notifications.

## Samples

✅ read, 🚫 seen and refused, ❔ not seen yet. Parser, tests and fixtures are in
`modules/bank-notifications/src/banks/cz/kb/`.

| E-mail | Sample |
|---|---|
| Incoming payment, CZK, personal account | ✅ `incoming.eml` (1 700,00 Kč), `incoming-small.eml` (20,00 Kč) |
| Incoming payment with a variable symbol and a message | ❔ the tests put them into the empty cells; a real one is still wanted |
| Forwarded by a Gmail filter | ✅ read the same, no fixture (its forwarding headers are private) |
| Incoming payment in another currency | ❔ |
| Servisní zpráva: Informace o pojištění vkladů | 🚫 `NOT_A_PAYMENT`, `deposit-protection-notice.eml` |
| Other `Servisní zpráva` notices | ❔ `Unparsed` until seen |
| MojeBanka, `kb.cz` | ❔ no parser |
