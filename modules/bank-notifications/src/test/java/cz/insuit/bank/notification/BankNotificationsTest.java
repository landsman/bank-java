package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.insuit.bank.notification.BankNotificationException.TemporaryFailure;
import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.BankNotificationException.Unverified;
import cz.insuit.bank.notification.dkim.DkimVerifier;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.apache.james.jdkim.DKIMSigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reading goes through the DKIM check. The anonymised fixtures carry no signature of their own, so each test signs one
 * the way Air Bank does, with a key made for the test.
 */
class BankNotificationsTest {

    @Test
    void readsASignedNotification() throws Exception {
        Transaction transaction = notifications().read(signed("incoming.eml"));

        assertEquals("100000000001", transaction.transactionId());
    }

    @Test
    void readsABankThatPinsNoKeyWithAnyKeyItsDomainPublishes() throws Exception {
        // MONETA is read from public samples and pins nothing, so withDns() takes whatever key DNSSEC proves it
        // published.
        Transaction transaction =
                pinned("moneta.cz").read(signed(fixture("/moneta/incoming-synthetic.eml"), "moneta.cz"));

        assertEquals("moneta.cz", transaction.signedBy());
    }

    @Test
    void readsKomercniBankasHtmlEmailOverTheOldSizeLimit() throws Exception {
        // About 86 KB, one HTML part; KB signs its Message-ID, and with no transaction id that is what deduplicates.
        byte[] message = fixture("/kb-cz/incoming.eml");
        String record = "v=DKIM1; k=rsa; p="
                + Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        BankNotifications kb = BankNotifications.trustingKeysFrom((selector, domain) ->
                selector.equals("ab") && domain.equals("kbinfo.cz") ? List.of(record) : List.of());

        Transaction transaction = kb.read(signed(message, "kbinfo.cz", "Content-Type:To:Message-ID:Date:Subject:From"));

        assertTrue(message.length > 64 * 1024, String.valueOf(message.length));
        assertEquals("<00.00.00000.00000000@i-00000000000000000.mta3vrest.sd.prd.sparkpost>", transaction.messageId());
    }

    @Test
    void refusesAKeyThatABankWhichPinsDoesNotName() throws Exception {
        Unverified refused =
                assertThrows(Unverified.class, () -> pinned("airbank.cz").read(signed("incoming.eml")));

        assertTrue(String.valueOf(refused.getMessage()).contains("not one of the keys pinned"), refused.getMessage());
    }

    @Test
    void doesNotTrustAnUnsignedOne() throws Exception {
        assertThrows(Unverified.class, () -> notifications().read(fixture("/airbank/incoming.eml")));
    }

    @Test
    void doesNotTrustOneChangedAfterSigning() throws Exception {
        byte[] changed = new String(signed("incoming.eml"), StandardCharsets.UTF_8)
                .replace("Variabilní symbol: 1337", "Variabilní symbol: 4242")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(Unverified.class, () -> notifications().read(changed));
    }

    @Test
    void waitsWhenTheKeyCannotBeLookedUp() throws Exception {
        BankNotifications unreachable = BankNotifications.trustingKeysFrom((selector, domain) -> {
            throw new IOException("resolver timed out");
        });

        byte[] signed = signed("incoming.eml");

        assertThrows(TemporaryFailure.class, () -> unreachable.read(signed));
    }

    @Test
    void leavesAnUnreadableMessageUnparsedWithoutLookingUpAKey() throws Exception {
        BankNotifications noLookup = BankNotifications.trustingKeysFrom((selector, domain) -> {
            throw new AssertionError("looked up a key for a message it could not read");
        });

        byte[] other = "From: someone@example.com\r\n\r\nhi".getBytes(StandardCharsets.UTF_8);

        assertEquals(
                Unparsed.Reason.NOT_A_BANK_EMAIL,
                assertThrows(Unparsed.class, () -> noLookup.read(other)).reason());
    }

    @Test
    void takesAnUnreadableFromForNoBankRatherThanForTheBankWriting() {
        BankNotifications noLookup = BankNotifications.trustingKeysFrom((selector, domain) -> {
            throw new AssertionError("looked up a key for a message that names no bank");
        });
        // An unclosed quote: Jakarta Mail refuses the From, so the sender is never known.
        byte[] broken = String.join(
                        "\r\n",
                        "From: \"Air Bank <info@airbank.cz>",
                        "To: me@example.com",
                        "Subject: hello",
                        "",
                        "Nothing a parser knows.",
                        "")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(
                Unparsed.Reason.NOT_A_BANK_EMAIL,
                assertThrows(Unparsed.class, () -> noLookup.read(broken)).reason());
    }

    @Test
    void doesNotTrustAMessageTooLargeToVerify() {
        BankNotifications noLookup = BankNotifications.trustingKeysFrom((selector, domain) -> {
            throw new AssertionError("looked up a key for a message too large to verify");
        });
        byte[] padded = new byte[DkimVerifier.MAX_MESSAGE_BYTES + 1];
        byte[] head = unreadable();
        System.arraycopy(head, 0, padded, 0, head.length);

        assertThrows(Unverified.class, () -> noLookup.read(padded));
    }

    @Test
    void doesNotTrustAHeaderTooLargeToVerify() throws Exception {
        BankNotifications noLookup = BankNotifications.trustingKeysFrom((selector, domain) -> {
            throw new AssertionError("looked up a key for a header too large to verify");
        });
        String padding = "X-Padding: " + "x".repeat(70) + "\r\n";
        byte[] padded = prepended(
                padding.repeat(DkimVerifier.MAX_HEADER_BYTES / padding.length()).strip(), signed("incoming.eml"));

        assertThrows(Unverified.class, () -> noLookup.read(padded));
    }

    @Test
    void takesALargeMessageFromAnyoneElseForNoBank() {
        BankNotifications noLookup = BankNotifications.trustingKeysFrom((selector, domain) -> {
            throw new AssertionError("looked up a key for a message from no bank");
        });
        byte[] padded = new byte[DkimVerifier.MAX_MESSAGE_BYTES + 1];
        byte[] head = "From: shop@example.com\r\nSubject: news\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, padded, 0, head.length);

        assertEquals(
                Unparsed.Reason.NOT_A_BANK_EMAIL,
                assertThrows(Unparsed.class, () -> noLookup.read(padded)).reason());
    }

    @Test
    void doesNotTrustASecondMessageIdAddedAboveASignedOne() throws Exception {
        byte[] replayed = prepended(
                "Message-ID: <forged@attacker.example>", signed("incoming.eml", "Date:To:From:Subject:Message-ID"));

        assertThrows(Unverified.class, () -> notifications().read(replayed));
    }

    @Test
    void doesNotTrustASecondSubjectAddedAboveASignedOne() throws Exception {
        byte[] retitled = prepended("Subject: something else", signed("incoming.eml"));

        assertThrows(Unverified.class, () -> notifications().read(retitled));
    }

    private static byte[] prepended(String header, byte[] message) {
        byte[] line = (header + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[line.length + message.length];
        System.arraycopy(line, 0, out, 0, line.length);
        System.arraycopy(message, 0, out, line.length, message.length);
        return out;
    }

    @Test
    void doesNotTrustAnUnsignedMessageThatClaimsABankWhateverItsBodySays() {
        assertThrows(Unverified.class, () -> notifications().read(unreadable()));
        assertThrows(Unverified.class, () -> notifications().readAll(unreadable()));
    }

    @Test
    void leavesASignedButUnreadableMessageUnparsed() throws Exception {
        assertThrows(Unparsed.class, () -> notifications().read(signed(unreadable(), "airbank.cz")));
    }

    @Test
    void neverLooksUpAKeyForADomainTheMessageChose() throws Exception {
        List<String> looked = new ArrayList<>();
        BankNotifications lookingUp = BankNotifications.trustingKeysFrom((selector, domain) -> {
            looked.add(domain);
            throw new IOException("resolver timed out");
        });

        byte[] foreign = signed(fixture("/airbank/incoming.eml"), "attacker.example");

        assertThrows(Unverified.class, () -> lookingUp.read(foreign));
        assertEquals(List.of(), looked);
    }

    private static KeyPair key;

    @BeforeAll
    static void makeKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        key = generator.generateKeyPair();
    }

    private static BankNotifications notifications() {
        String record = "v=DKIM1; k=rsa; p="
                + Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        return BankNotifications.trustingKeysFrom((selector, domain) ->
                selector.equals("ab") && domain.equals("airbank.cz") ? List.of(record) : List.of());
    }

    @Test
    void keepsTheMessageIdOnlyWhenTheSignatureCoversIt() throws Exception {
        // Air Bank signs Date, To, From and Subject: an unsigned Message-ID could be changed on a copy of the e-mail.
        assertNull(notifications().read(signed("incoming.eml")).messageId());
        assertEquals(
                "<0000@localhost.localdomain>",
                notifications()
                        .read(signed("incoming.eml", "Date:To:From:Subject:Message-ID"))
                        .messageId());
    }

    @Test
    void doesNotTrustASubjectTheSignatureLeavesOut() {
        // Refused for Air Bank too, which reads only the body: for Fio, KB, ČSOB, MONETA and Tatra the subject picks
        // the template.
        assertThrows(
                BankNotificationException.Unverified.class,
                () -> notifications().read(signed("incoming.eml", "Date:To:From")));
    }

    /** As {@link BankNotifications#withDns()}, pins included, but with the test's key published for {@code domain}. */
    private static BankNotifications pinned(String domain) {
        String record = "v=DKIM1; k=rsa; p="
                + Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        return BankNotifications.pinning(DkimVerifier.with(
                (selector, name) -> selector.equals("ab") && name.equals(domain) ? List.of(record) : List.of()));
    }

    private static byte[] signed(String name) throws Exception {
        return signed(name, "Date:To:From:Subject");
    }

    private static byte[] signed(String name, String headers) throws Exception {
        return signed(fixture("/airbank/" + name), "airbank.cz", headers);
    }

    private static byte[] signed(byte[] message, String domain) throws Exception {
        return signed(message, domain, "Date:To:From:Subject");
    }

    /** What anyone can send to the receiving address: a bank's sender domain over a body no parser understands. */
    private static byte[] unreadable() {
        return "From: Bank <info@airbank.cz>\r\nTo: me@example.com\r\nSubject: hello\r\nDate: Mon, 1 Jan 2024 10:00:00 +0100\r\n\r\nNothing a parser knows.\r\n"
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] signed(byte[] message, String domain, String headers) throws Exception {
        String header = new DKIMSigner(
                        "v=1; a=rsa-sha256; c=relaxed/relaxed; d=" + domain + "; s=ab; h=" + headers + "; bh=; b=;",
                        key.getPrivate())
                .sign(new ByteArrayInputStream(message));
        byte[] prefix = (header + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + message.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(message, 0, signed, prefix.length, message.length);
        return signed;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = BankNotificationsTest.class.getResourceAsStream(name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }
}
