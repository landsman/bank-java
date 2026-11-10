package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankAccount;
import cz.insuit.bank.notification.BankNotificationException;
import cz.insuit.bank.notification.BankNotifications;
import cz.insuit.bank.notification.Transaction;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.apache.james.jdkim.DKIMSigner;
import org.junit.jupiter.api.Test;

/**
 * Reads and verifies a signed Air Bank notification on the classpath this Spring Boot version's BOM resolves: its
 * Jakarta Mail and Angus parse the message, its SLF4J serves dnsjava and jDKIM.
 */
class ReadsOnSpringBootTest {

    @Test
    void readsAndVerifies() throws Exception {
        KeyPair key = rsa();
        BankNotifications notifications =
                BankNotifications.trustingKeysFrom((selector, domain) -> List.of(record(key)));

        Transaction t = notifications.read(signed(key));

        assertEquals(BankAccount.parse("1234567899/3030", BankAccount.Country.CZ), t.account());
        assertEquals(new BigDecimal("1.00"), t.amount().amount());
        assertEquals("1337", t.symbols().variable());
    }

    @Test
    void refusesAnUnsignedOne() throws Exception {
        BankNotifications notifications = BankNotifications.trustingKeysFrom((selector, domain) -> List.of());

        assertThrows(BankNotificationException.Unverified.class, () -> notifications.read(bytes(MESSAGE)));
    }

    static final String MESSAGE = String.join(
            "\r\n",
            "Date: Tue, 10 Nov 2026 14:40:05 +0100",
            "To: bruce.wayne@gothba.org",
            "From: Air Bank<info@airbank.cz>",
            "Subject: =?utf-8?Q?Zv=C3=BD=C5=A1en=C3=AD_z=C5=AFstatku_na_=C3=BA=C4=8Dtu_Hlavni?=",
            "MIME-Version: 1.0",
            "Content-Transfer-Encoding: 8bit",
            "Content-Type: text/plain; charset=\"utf-8\"",
            "",
            "zůstatek na účtu Hlavni číslo 1234567899/3030 se zvýšil o částku 1,00 CZK. Dostupný zůstatek k 10.11.2026"
                    + " v 14:39 je 512,30 CZK.",
            "Příchozí úhrada z účtu Novák, Jan číslo 9876543211/2010",
            "Částka: 1,00 CZK",
            "Datum zaúčtování: 10.11.2026",
            "Variabilní symbol: 1337",
            "Kód transakce: 100000000001",
            "");

    static byte[] signed(KeyPair key) throws Exception {
        String header = new DKIMSigner(
                        "v=1; a=rsa-sha256; c=relaxed/relaxed; d=airbank.cz; s=ab; h=Date:To:From:Subject; bh=; b=;",
                        key.getPrivate())
                .sign(new ByteArrayInputStream(bytes(MESSAGE)));
        return bytes(header + "\r\n" + MESSAGE);
    }

    static String record(KeyPair key) {
        return "v=DKIM1; k=rsa; p="
                + Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }

    static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
