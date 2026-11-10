# Using the library

Each bank sends an e-mail for every payment on an account once the account holder [switches it on](setup.md); `read`
turns it into a `Transaction`. Which banks it reads, and what it reads from each: [banks](banks/README.md). An e-mail from any
other bank is `Unparsed` with the message `no parser for sender domain <domain>`.

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

One e-mail can report several transactions (ČSOB's does): `readAll(rawMessage)` returns them all, in order, and
`read` refuses such an e-mail rather than return one of them. For any other bank `readAll` returns a list of one.

## The raw message

Hand `read` the message **exactly as it arrived**: the bytes your SMTP server, Cloudflare Email Worker or inbound
provider received, headers included. A message re-serialised by a mail library (headers refolded, CRLF turned into LF,
a body decoded) no longer matches its signature and is `Unverified`, every time.

The library reads the `text/plain` part when there is one, and the `text/html` part only when there is not: some banks
send HTML only. That HTML becomes lines at every `<br>`, table cell, row and block, through
[jsoup](https://jsoup.org/), never at whitespace inside text, so a payer's own line break cannot start a line.

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

**`message()` and `counterName()` are the payer's text, untrusted.** A valid signature proves the bank sent the e-mail,
not that what the payer typed is harmless: it can hold `'; DROP TABLE`, `<script>` or anything else. The library hands it
out as it was written, as plain text: an HTML e-mail's markup is gone and its entities decoded, but nothing is escaped
or removed, because it cannot know where the text will go and a changed message could stop matching an invoice. So
stop injection where the text is used:

- **SQL**: bind it as a parameter (JDBC `PreparedStatement`, JPA, jOOQ), never concatenate it into a query.
- **HTML**: let the template escape it (Thymeleaf `th:text`, Vaadin `setText`); never `setHtml` or `th:utext` with it.
- **Logs and shells**: keep it out of log patterns and command lines; `toString()` already leaves it out.

The same holds for the symbols, which are digits only, and the account numbers, which are checked, only less so.

`BankAccount` is a Czech or Slovak account, its `country()` with `[prefix-]number/bankCode`, without leading zeros, so
the same account is equal however it was written: its local form and its IBAN compare equal. It checks the modulo 11
rule both countries share, and an IBAN's modulo 97 digits, so an account number misread from an e-mail is refused, not
handed out. To compare against yours:

| You have | Read it with |
|---|---|
| `19-2000145399/0800`, Czech | `BankAccount.parse("19-2000145399/0800", BankAccount.Country.CZ)` |
| `4019425381/7500`, Slovak | `BankAccount.parse("4019425381/7500", BankAccount.Country.SK)` |
| `CZ65 0800 0000 1920 0014 5399` | `BankAccount.parseIban("CZ65 0800 0000 1920 0014 5399")`, with or without spaces; the IBAN says the country |

A local form does not say its country, so `parse` takes it: there is no default. `iban()` writes the IBAN back,
`toString()` the local form. The same digits under a Czech and a Slovak bank code are two
accounts. `Money` and `PaymentSymbols` have the same shape as in `moneta-api-java`.

## Two checks stay with you

**Compare `account()` with the account you expect.** A valid signature proves the bank sent the e-mail, not that it is
about *your* account: a genuine notification of somebody else's account, sent to your address, verifies just as well.
If each customer has their own address, compare against that customer's account.

**Deduplicate on `transactionId()`**, per bank, or on a signed `messageId()` where a bank gives no transaction id,
together with the position in `readAll()`'s list when one e-mail reports several transactions (ČSOB does).
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
