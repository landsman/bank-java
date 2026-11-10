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

## Another bank

Not supported yet; see [what it covers](../README.md#what-it-covers). Adding one starts from one of its real e-mails,
see [development](development.md#adding-a-bank).
