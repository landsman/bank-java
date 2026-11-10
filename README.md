# bank-java

Java libraries for learning that money moved on a Czech bank account: who paid, how much, with which variable symbol.
Java 21, no framework required, nullability in JSpecify.

| Module | Package | Reads |
|---|---|---|
| `bank-notifications` | `cz.insuit.bank.notification` | the e-mail a bank sends on every payment, for banks without an API |
| `bank-notifications-dkim` | `cz.insuit.bank.notification.dkim` | whether that e-mail really comes from the bank |

Banks with an API of their own are a module each, as they come.

## bank-notifications

Most Czech banks can e-mail every incoming and outgoing payment. Point that e-mail at an address you receive, and hand
each raw message to the parser:

```java
switch (BankNotifications.parse(rawMessage)) {
    case Transaction t -> ... // t.direction(), t.amountMinor(), t.variableSymbol(), t.transactionId()
    case Unparsed u -> ...    // keep the message and look at u.reason(): a bank changed its template
}
```

**Verify before you trust.** Anyone can send an e-mail that looks like a bank's, so the parser trusts nothing.
`bank-notifications-dkim` does the first check:

```java
DkimVerifier dkim = DkimVerifier.withDns(); // or DkimVerifier.with(yourLookup), thread-safe, keep one
if (BankNotifications.parse(raw) instanceof Transaction t
        && dkim.verify(raw, t.signedBy()) instanceof DkimResult.Verified) {
    // genuine
}
```

It verifies the DKIM signature over the raw bytes, never an `Authentication-Results` header that arrived with the
message, and counts it only when `d=` is the bank's domain, the whole body is signed (no `l=`), the key is not in testing
mode and an RSA key has at least 1024 bits (`minimumKeyBits` raises it). A key lookup that fails is a
`TemporaryFailure`, not a `Failed`. It runs on [Apache James jDKIM](https://github.com/apache/james-jdkim) 0.5, and
refuses messages over 64 KiB until a jDKIM release carries the linear-time parsing fixes made upstream on 2026-11-05.

The other two checks stay with you:

- check that `Transaction.account()` is the account you expect, or a genuine notification about somebody else's
  account counts as yours;
- deduplicate on `Transaction.transactionId()`.

### Banks

| Bank | Sender | Notes |
|---|---|---|
| Air Bank | `info@airbank.cz`, DKIM `d=airbank.cz` | Internet banking on a computer: Účty a karty → account → Možnosti → Info o dění na účtu → "Jiný kontakt" takes any address, no mailbox forward needed. Incoming and outgoing transfers. Constant and specific symbols, the incoming message and amounts of 1 000 and more are not read yet: no sample has shown them. |

A new bank starts from one of its real e-mails, anonymised, as a test fixture.

## Development

`make build` checks formatting, compiles with Error Prone and NullAway, runs the tests and checks declared dependencies.
`make format` fixes formatting.
