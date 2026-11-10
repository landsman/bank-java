package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankNotificationException.TemporaryFailure;
import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.BankNotificationException.Unverified;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
    void doesNotTrustAnUnsignedOne() throws Exception {
        assertThrows(Unverified.class, () -> notifications().read(fixture("incoming.eml")));
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

        assertThrows(Unparsed.class, () -> noLookup.read(other));
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

    private static byte[] signed(String name) throws Exception {
        byte[] message = fixture(name);
        String header = new DKIMSigner(
                        "v=1; a=rsa-sha256; c=relaxed/relaxed; d=airbank.cz; s=ab; h=Date:To:From:Subject; bh=; b=;",
                        key.getPrivate())
                .sign(new ByteArrayInputStream(message));
        byte[] prefix = (header + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + message.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(message, 0, signed, prefix.length, message.length);
        return signed;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = BankNotificationsTest.class.getResourceAsStream("/airbank/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }
}
