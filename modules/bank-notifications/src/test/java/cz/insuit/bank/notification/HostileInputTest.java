package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Anyone can mail the address these e-mails come to, so whatever arrives must end in a transaction or a
 * {@link BankNotificationException}, never in another exception that would take the caller's mail handling down. The
 * seed is fixed, so a failure here fails the same way again.
 */
class HostileInputTest {

    @Test
    void throwsNothingButItsOwnException() throws IOException {
        BankNotifications notifications =
                BankNotifications.with((selector, domain) -> List.of("v=DKIM1; k=rsa; p=AAAA"));
        for (byte[] input : hostileInputs()) {
            try {
                notifications.read(input);
            } catch (BankNotificationException expected) {
                // the only way out
            } catch (RuntimeException | StackOverflowError e) {
                fail("threw " + e + " for: " + preview(input), e);
            }
        }
    }

    private static List<byte[]> hostileInputs() throws IOException {
        List<byte[]> inputs = new ArrayList<>();
        byte[] incoming = fixture("incoming.eml");
        byte[] outgoing = fixture("outgoing.eml");
        Random random = new Random(20261110);

        inputs.add(new byte[0]);
        inputs.add(bytes("\r\n\r\n"));
        inputs.add(bytes("From: Air Bank<info@airbank.cz>\r\n"));
        inputs.add(
                bytes("From: Air Bank<info@airbank.cz>\r\nContent-Type: multipart/mixed; boundary=x\r\n\r\n--x\r\n"));
        inputs.add(
                bytes("From: Air Bank<info@airbank.cz>\r\nContent-Transfer-Encoding: base64\r\n\r\n!!!not base64!!!"));
        inputs.add(bytes("From: Air Bank<info@airbank.cz>\r\nContent-Type: text/plain; charset=nonsense\r\n\r\nx"));
        inputs.add(bytes("From: =?utf-8?Q?broken\r\n\r\nx"));
        inputs.add(bytes("From: a@airbank.cz, b@airbank.cz\r\n\r\nx"));
        inputs.add(bytes("From: Air Bank<info@airbank.cz>\r\n\r\n" + "zůstatek na účtu ".repeat(3000) + "číslo"));
        inputs.add(bytes("From: Air Bank<info@airbank.cz>\r\nX: " + "a".repeat(60_000) + "\r\n\r\nx"));
        inputs.add(bytes("DKIM-Signature: v=1; a=rsa-sha256; d=airbank.cz; s=ab; b=; bh=;\r\n" + text(incoming)));
        inputs.add(bytes("DKIM-Signature: " + "x=;".repeat(10_000) + "\r\n" + text(incoming)));

        byte[] invalidUtf8 = Arrays.copyOf(incoming, incoming.length + 3);
        invalidUtf8[incoming.length] = (byte) 0xC3;
        invalidUtf8[incoming.length + 1] = (byte) 0x28;
        invalidUtf8[incoming.length + 2] = (byte) 0xFF;
        inputs.add(invalidUtf8);

        for (byte[] fixture : List.of(incoming, outgoing)) {
            // Cut short at every 50th byte: a header, the blank line, a field, a number, each broken off.
            for (int length = 0; length < fixture.length; length += 50) {
                inputs.add(Arrays.copyOf(fixture, length));
            }
            // A few bytes flipped at random, a hundred times over.
            for (int round = 0; round < 100; round++) {
                byte[] flipped = fixture.clone();
                for (int i = 0; i < 4; i++) {
                    flipped[random.nextInt(flipped.length)] = (byte) random.nextInt(256);
                }
                inputs.add(flipped);
            }
        }
        for (int round = 0; round < 100; round++) {
            byte[] noise = new byte[random.nextInt(2048)];
            random.nextBytes(noise);
            inputs.add(noise);
        }
        return inputs;
    }

    private static String preview(byte[] input) {
        String text = new String(input, StandardCharsets.UTF_8);
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = HostileInputTest.class.getResourceAsStream("/airbank/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
