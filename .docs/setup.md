# Setting up a bank

The library reads the e-mail a bank sends when money moves on an account. Somebody has to switch that e-mail on, in
their own internet banking, and point it at an address your application receives. That is the account holder, not
the developer: no API key, no contract with the bank, no PSD2 licence.

**Send it straight to your address, not through a mailbox forward.** The bank's DKIM signature is what makes the
e-mail trustworthy, and a forward can break it. Every bank below lets the notification go to any address.

## Air Bank

On a computer, not in the My Air app ([Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/air) says the same):

1. Internet banking → **Účty a karty** → the account.
2. **Možnosti** → **Info o dění na účtu**.
3. Tick **Jiný kontakt** and enter your address.
4. Under the balance change, choose e-mail and the lowest amount. A 1 CZK payment produced an e-mail
   (2026-11-10); whether anything below that does is not known.
5. **Pokračovat**.

No guide mentions a confirmation of the address. Send yourself a small payment and check that `read` returns a
`Transaction` for it.

It is a balance-change notification: outgoing payments arrive as well, as `Direction.OUTGOING`.

## Fio banka CZ

Fio sends an e-mail for every movement, money in and out, card payments and direct debits included. In internet
banking, Nastavení → Oznámení → Uživatelská oznámení → Pohyby → Přidat oznámení opens **Přidat nové oznámení o pohybu na účtu** (*Add a new account transaction notification* in
English); its fields, as both interfaces showed them on 2026-11-10:

1. **Číslo účtu** (*Account number*): the account. One notification per account; a CZK and a EUR account each need
   their own.
2. **Kam posílat** (*Send to*): **E-mail**, not SMS or push; see [#12](https://github.com/landsman/bank-java/issues/12)
   for why only the e-mail can be trusted.
3. **Vyberte kontaktní e-mail**: **Jiný email**, then type your address. Any address is accepted; no confirmation step
   is described ([Fakturoid's guide](https://www.fakturoid.cz/podpora/parovani/fio), read 2026-11-10).
4. **Vše / Příchozí / Odchozí**: which direction. **Vše** sends outgoing payments too; **Příchozí** is enough for
   matching payments.
5. **Částka**, **Platba je z/na účet**, **Kód banky**, **Variabilní symbol**: filters. Leave them empty, so every
   payment is sent; Fakturoid's guide puts `0,01` in **Částka** to the same end.
6. **Informace v oznámení** (*Notification format*): three choices, each shown with a preview. **Choose the middle
   one, `Fio banka - příjem na účtu`**, the preview starting `Dobrý den, zůstatek účtu … se … zvýšil o`, with an
   `ID pokynu`. The first, `Nový příjem na účtu` in one line with the counter-party, and the third, one sentence
   without it, carry no transaction id, and the library cannot read them.
7. **Posílat online** (*Online notifications*): keep it on. Off, Fio holds notifications between 00:00 and 8:00, so a payment at
   night is announced in the morning.

Check that `read` returns a `Transaction` for a small payment.

## MONETA

See [moneta.md](banks/cz/moneta/README.md#setting-it-up): Upozornění - Info Servis, e-mail for incoming and outgoing payments, only
from 100 Kč. Not verified yet: no real MONETA e-mail has been read.

## Creditas

See [creditas.md](banks/cz/creditas/README.md#setting-it-up). Not verified yet: no real Creditas e-mail has been read.

## Raiffeisenbank

See [raiffeisenbank.md](banks/cz/raiffeisenbank/README.md#setting-it-up). Not verified yet: no real Raiffeisenbank e-mail has been read.

## UniCredit Bank

See [unicredit](banks/cz/unicredit/README.md#setting-it-up). Not verified yet: no real UniCredit e-mail has been read.

## ČSOB

See [csob](banks/cz/csob/README.md#setting-it-up). Not verified yet: no real ČSOB e-mail has been read.

## Another bank

Not supported yet; see [banks](banks/README.md). Adding one starts from one of its real e-mails,
see [development](development.md#adding-a-bank).
