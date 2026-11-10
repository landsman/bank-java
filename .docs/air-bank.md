# How Air Bank's e-mail behaves

From three real notifications of 2026-11-10 on a personal account, 1 CZK transfers: an incoming one without a
message, an outgoing one with a message, and an incoming one with a message and all three symbols. The test fixtures
are those e-mails, anonymised.

## Watch out for

- **It is a balance-change notification**, so outgoing payments arrive too. Their template differs in four places,
  below.
- **Optional lines appear only when the payment has them**: the symbols and the message. The parser reads labelled
  lines, never positions.
- **The message has a different label each way**: `Zpráva pro příjemce:` on an incoming payment,
  `Zpráva pro plátce:` on an outgoing one. `message()` holds either.
- **The subject carries the account's nickname**, whatever the client named it. The parser does not read the subject.
- **The outgoing transaction code ends in a U+FEFF**, an invisible character, before the line break.
- **The amount uses a decimal comma** (`1,00 CZK`). How 1 000 and more is written has not been seen; until it is, such
  an e-mail is `Unparsed`, not guessed at.
- **The balance after the payment and its time** are in the first sentence, to the minute, in Prague time.
  `balance()` is `null` when it is not in the format seen so far (1 000 and more, an overdraft): the payment itself is
  still read.

## The message

- `From: Air Bank<info@airbank.cz>`, relayed by `mx10.airbank.cz`, `X-Mailer: PHPMailer 5.1`.
- `DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=airbank.cz; s=ab; h=Date:To:From:Subject;`, no `l=`, so
  the whole body is signed. The key at `ab._domainkey.airbank.cz` is RSA 1024-bit, fingerprint
  `M1mJ01eTgtt/3Jw9syH2YdwTWzPzYeST8Hfk/dfZg6E=` (the same through 1.1.1.1 and 8.8.8.8, 2026-11-10), pinned in
  `AirBank.KEY_FINGERPRINTS`. `airbank.cz` is DNSSEC-signed and so is the key record. SPF passes, DMARC is
  `p=QUARANTINE`.
- `Message-ID` is `<hex@localhost.localdomain>` and not signed: no use for deduplication. The transaction code is.
- `text/plain; charset="utf-8"`, `8bit`, CRLF line ends, Czech, one part.
- About a minute from the balance changing to the `Date` header (14:39 to 14:40:05, 14:41 to 14:42:04).

## The body

Incoming, subject `Zvýšení zůstatku na účtu <nickname>`, footer `132/22/01`:

```
zůstatek na účtu <nickname> číslo <account>/3030 se zvýšil o částku 1,00 CZK. Dostupný zůstatek k 10.11.2026 v 14:39 je <balance> CZK.
Příchozí úhrada z účtu <Surname, Name> číslo <counter-account>/<bank code>
Částka: 1,00 CZK
Datum zaúčtování: 10.11.2026
Variabilní symbol: 1337
Konstantní symbol: 1234
Specifický symbol: 123456789
Zpráva pro příjemce: <text>
Kód transakce: <12 digits>
```

The symbols and the message appear only when the payment has them.

Outgoing, subject `Snížení zůstatku na účtu <nickname>`, footer `134/22/01`:

```
zůstatek na účtu <nickname> číslo <account>/3030 se snížil o částku 1,00 CZK. Dostupný zůstatek k 10.11.2026 v 14:41 je <balance> CZK.
Odchozí úhrada na účet číslo <counter-account>/<bank code>
Částka: 1,00 CZK
Datum zaúčtování: 10.11.2026
Variabilní symbol: 7331
Zpráva pro plátce: <text>
Kód transakce: <12 digits><U+FEFF>
```

## How the parser reads it

- The direction is stated twice, `se zvýšil` / `se snížil` and `Příchozí úhrada z účtu` / `Odchozí úhrada na účet`. They
  have to agree, or the message is `Unparsed`: a template change shows up as a refusal, not as a misread payment.
- The amount in the sentence has to equal the `Částka:` line.
- A labelled line that appears twice makes the message `Unparsed`.
- After the message, the only free text a payer controls, only `Kód transakce:` may follow, so a message cannot smuggle
  in a symbol of its own.
- Each symbol is 1 to 10 digits, leading zeros kept.
- Both kinds of line say "úhrada", a transfer, so `type()` is `TRANSFER`.

## Not seen yet

A business account's template; 1 000 CZK and more, as an amount or a balance; an overdraft; a card payment; an instant against an ordinary transfer; anything below
1 CZK. Each needs one real e-mail.
