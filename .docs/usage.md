# Using the library

**It reads Air Bank's and Fio banka's Czech e-mails so far.** Each bank sends one for every payment on an account once the account
holder [switches it on](setup.md); `read` turns it into a `Transaction`. An e-mail from any other bank is `Unparsed`
with the message `no parser for sender domain <domain>`, until that bank is added.

| Bank | Recognised by | Reads | Details |
|---|---|---|---|
| Air Bank | `From: info@airbank.cz`, signed by `airbank.cz` | incoming and outgoing transfers | [how its e-mail behaves](air-bank.md) |
| MONETA | `From: infoservis@moneta.cz`, signed by `moneta.cz` | incoming and outgoing, from public samples; no key pinned, so `Unverified` until a real e-mail | [how its e-mail behaves](moneta.md) |
| Fio banka CZ | `From: automat@fio.cz`, signed by `fio.cz` | transfers in and out, card payments, direct debits | [how its e-mail behaves](fio-cz.md) |

```java
BankNotifications notifications = BankNotifications.withDns(); // thread-safe, keep one

try {
    Transaction t = notifications.read(rawMessage);   // genuine
} catch (BankNotificationException e) {
    switch (e) {
        case BankNotificationException.Unverified u -> ...       // not signed by the bank
        case BankNotificationException.TemporaryFailure f -> ... // the bank's key could not be looked up
        case BankNotificationException.Unparsed u -> ...         // not an e-mail the library can read
    }
}
```

## The raw message

Hand `read` the message **exactly as it arrived**: the bytes your SMTP server, Cloudflare Email Worker or inbound
provider received, headers included. A message re-serialised by a mail library (headers refolded, CRLF turned into LF,
a body decoded) no longer matches its signature and is `Unverified`, every time.

Store those bytes before reading them. A template the library does not know yet is `Unparsed`, and a later
release can read the stored message again.

Messages over 64 KiB are `Unparsed` without being looked at; a bank's notification is around 5 KiB. See
[verification](verification.md#the-size-limit).

## When there is no transaction

`read` returns a `Transaction` or throws a `BankNotificationException`, and nothing else, whatever the bytes. The
exception is sealed, so a `switch` over its three subtypes is exhaustive:

| Exception | Means | Do |
|---|---|---|
| `Unverified` | no valid DKIM signature of the bank | do not act on it; anyone can send an e-mail that looks real |
| `TemporaryFailure` | the bank's key could not be looked up | keep the message, read it again later |
| `Unparsed` | not an e-mail the library can read: another bank's, a template that changed, or not a bank's at all | keep it, so a later release can read it again |

`Unparsed.reason()` says why for a program, so the account holder is told only what they can fix:

| `reason()` | Means | Do |
|---|---|---|
| `NOT_A_BANK_EMAIL` | not from a bank this release reads | nothing for the account holder; another bank may be added later |
| `UNSUPPORTED_FORMAT` | a bank's notification in a format the library will not read, e.g. Fio's short one without a transaction id | show the account holder `getMessage()`: it says what to choose in their banking |
| `NOT_READ` | anything else: a changed template, a misread value, bytes that are no e-mail | keep it; a later release, or a bug report with an anonymised copy, can fix it |

Keep a `default` branch: a reason may be added in a minor version.

`getMessage()` says why, and is safe to log: it never carries the payer's name, the payment's message or the e-mail's
body, and control characters from the e-mail are replaced. The constructors are public, so your own tests can throw them
from a fake.

## A transaction

| Field | Holds |
|---|---|
| `signedBy()` | the bank's domain whose signature verified: `airbank.cz`, `fio.cz` |
| `direction()`, `incoming()` | `INCOMING` or `OUTGOING` |
| `type()` | `TRANSFER`, `CARD`, `DIRECT_DEBIT`, or `GENERAL` when the e-mail does not say |
| `account()` | the account the e-mail is about, a `BankAccount`; `null` where the bank masks it (MONETA) |
| `counterAccount()` | the payer of an incoming payment, the recipient of an outgoing one; `null` for a card payment |
| `counterName()` | the other side's name, when the bank prints it; `null` otherwise |
| `amount()` | a `Money`: `BigDecimal` and `Currency`, signed, positive in and negative out |
| `symbols()` | `PaymentSymbols`: `variable()`, `constant()`, `specific()`, each `null` when absent |
| `message()` | the payment's message, when the bank prints it; `null` otherwise |
| `bookedOn()` | the booking date the bank states |
| `transactionId()` | the bank's own id of the transaction; `null` where the bank prints none (MONETA) |
| `messageId()` | the e-mail's `Message-ID`, only when the bank's DKIM signature covers it; `null` otherwise (Air Bank and Fio do not sign it) |
| `balance()` | a `Balance`: the `available()` money right after the payment and `asOf()`, the bank's time to the minute; `null` when the bank does not state it in a form the library reads |

`toString()` leaves out the other side, the message and the balance, so logging a transaction does not log who paid,
what they wrote or how much the account holds.

`BankAccount` is `[prefix-]number/bankCode`, without leading zeros, so the same account is equal however it was written,
and it checks the Czech National Bank's modulo 11 rule: an account number misread from an e-mail is refused, not handed
out. `BankAccount.parse("19-2000145399/0800")` reads one, for comparing against yours. `Money` and `PaymentSymbols` have
the same shape as in `moneta-api-java`.

## Two checks stay with you

**Compare `account()` with the account you expect.** A valid signature proves the bank sent the e-mail, not that it is
about *your* account: a genuine notification of somebody else's account, sent to your address, verifies just as well.
If each customer has their own address, compare against that customer's account.

**Deduplicate on `transactionId()`**, per bank, or on a signed `messageId()` where a bank gives no transaction id.
Never on an unsigned `Message-ID` from your mail server: anyone holding a copy of the e-mail can change it while the
signature stays valid, and the same payment counts twice. The same e-mail can arrive twice, and two real payments can share an
amount, a variable symbol and a date.

## Keys

`withDns()` needs nothing from you: it validates the bank's key through DNSSEC and accepts only the keys this release
pins, see [whose key it is](verification.md#whose-key-it-is). The machine's resolver only has to pass DNSSEC records
through, as nearly every one does; one that strips them makes every e-mail `Unverified` with the message
`... is not DNSSEC-secure`. `BankNotifications.pinnedKeys()` lists the pinned keys, for an audit or a line at start-up.

`BankNotifications.trustingKeysFrom(lookup)` takes the keys from your `KeyLookup` and trusts them as they come, no
DNSSEC and no pinning: for tests, and for a resolver you vouch for yourself. It returns the TXT records of
`<selector>._domainkey.<domain>`, and throws `IOException` when the lookup could not be made, which becomes a
`TemporaryFailure`.

```java
BankNotifications notifications = BankNotifications.trustingKeysFrom((selector, domain) -> myResolver.txt(selector + "._domainkey." + domain));
```

## Testing your code

Your tests should not reach DNS. Sign a message with a key made in the test and hand `trustingKeysFrom` its public half; this
repository's `BankNotificationsTest` does exactly that with jDKIM's `DKIMSigner`.
