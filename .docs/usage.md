# Using the library

**It reads Air Bank's e-mails, and only those so far.** Air Bank sends one for every payment on an account once the
account holder [switches it on](setup.md#air-bank); `read` turns it into a `Transaction`. An e-mail from any other bank
comes back `Unparsed` with the reason `no parser for sender domain <domain>`, until that bank is added.

| Bank | Recognised by | Reads | Details |
|---|---|---|---|
| Air Bank | `From: info@airbank.cz`, signed by `airbank.cz` | incoming and outgoing transfers | [how its e-mail behaves](air-bank.md) |

```java
BankNotifications notifications = BankNotifications.withDns(); // thread-safe, keep one

switch (notifications.read(rawMessage)) {
    case Transaction t -> ...      // genuine
    case Unverified u -> ...       // no valid signature of the bank
    case TemporaryFailure f -> ... // the bank's key could not be looked up
    case Unparsed u -> ...         // not a template the library knows
}
```

## The raw message

Hand `read` the message **exactly as it arrived**: the bytes your SMTP server, Cloudflare Email Worker or inbound
provider received, headers included. A message re-serialised by a mail library (headers refolded, CRLF turned into LF,
a body decoded) no longer matches its signature and comes back `Unverified`, every time.

Store those bytes before reading them. A template the library does not know yet comes back `Unparsed`, and a later
release can read the stored message again.

Messages over 64 KiB are `Unparsed` without being looked at; a bank's notification is around 5 KiB. See
[verification](verification.md#the-size-limit).

## The four results

| Result | Means | Do |
|---|---|---|
| `Transaction` | the bank sent it, and this is what it says | the two checks below, then act on it |
| `Unverified` | no valid DKIM signature of the bank; `reason()` says why | do not act on it; anyone can send an e-mail that looks real |
| `TemporaryFailure` | the bank's key could not be looked up | keep the message, read it again later |
| `Unparsed` | not an e-mail the library can read: another bank's, a template that changed, or not a bank's at all | keep it; `reason()` says which |

## A transaction

| Field | Holds |
|---|---|
| `signedBy()` | the bank's domain whose signature verified: `airbank.cz` |
| `direction()` | `INCOMING` or `OUTGOING` |
| `type()` | `TRANSFER`, `CARD`, `DIRECT_DEBIT`, or `GENERAL` when the e-mail does not say |
| `account()` | the account the e-mail is about, `number/bankCode` |
| `counterAccount()` | the payer of an incoming payment, the recipient of an outgoing one |
| `counterName()` | the other side's name, when the bank prints it; `null` otherwise |
| `amountMinor()`, `currency()` | the amount in minor units (haléře) and its ISO 4217 code, always positive |
| `variableSymbol()` | the variable symbol, or `null` |
| `message()` | the payment's message, when the bank prints it; `null` otherwise |
| `bookedOn()` | the booking date the bank states |
| `transactionId()` | the bank's own id of the transaction |

## Two checks stay with you

**Compare `account()` with the account you expect.** A valid signature proves the bank sent the e-mail, not that it is
about *your* account: a genuine notification of somebody else's account, sent to your address, verifies just as well.
If each customer has their own address, compare against that customer's account.

**Deduplicate on `transactionId()`**, per bank. The same e-mail can arrive twice, and two real payments can share an
amount, a variable symbol and a date.

## Your own key lookup

`BankNotifications.with(lookup)` takes a `KeyLookup` instead of DNS: a resolver with its own timeouts, or a recorded key
in a test. It returns the TXT records of `<selector>._domainkey.<domain>`, and throws `IOException` when the lookup could
not be made, which becomes a `TemporaryFailure`.

```java
BankNotifications notifications = BankNotifications.with((selector, domain) -> myResolver.txt(selector + "._domainkey." + domain));
```

## Testing your code

Your tests should not reach DNS. Sign a message with a key made in the test and hand `with` its public half; this
repository's `BankNotificationsTest` does exactly that with jDKIM's `DKIMSigner`.
