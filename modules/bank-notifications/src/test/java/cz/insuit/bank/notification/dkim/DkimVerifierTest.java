package cz.insuit.bank.notification.dkim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.james.jdkim.DKIMSigner;
import org.apache.james.jdkim.exceptions.FailException;
import org.junit.jupiter.api.Test;

/**
 * Signs an anonymised Air Bank notification with a key made for the test, so the verifier is exercised end to end
 * offline. The real notifications were verified the same way against Air Bank's published key before this was written;
 * they cannot be fixtures here, because anonymising a signed message breaks its signature.
 */
class DkimVerifierTest {

    @Test
    void verifiesTheBanksSignature() throws Exception {
        KeyPair key = rsa(1024);

        DkimResult result = verifierFor(key).verify(signed(MESSAGE, key, ""), "airbank.cz");

        assertEquals(new DkimResult.Verified("airbank.cz", "ab", Set.of("date", "to", "from", "subject")), result);
    }

    @Test
    void failsWhenTheBodyWasChanged() throws Exception {
        KeyPair key = rsa(1024);
        byte[] changed = new String(signed(MESSAGE, key, ""), StandardCharsets.UTF_8)
                .replace("Částka: 1,00 CZK", "Částka: 9,00 CZK")
                .getBytes(StandardCharsets.UTF_8);

        assertInstanceOf(DkimResult.Failed.class, verifierFor(key).verify(changed, "airbank.cz"));
    }

    @Test
    void failsWhenAnotherDomainSigned() throws Exception {
        KeyPair key = rsa(1024);

        DkimResult result = verifierFor(key).verify(signed(MESSAGE, key, ""), "fio.cz");

        assertInstanceOf(DkimResult.Failed.class, result);
    }

    @Test
    void failsWithoutASignature() {
        DkimResult result = DkimVerifier.with((s, d) -> List.of()).verify(bytes(MESSAGE), "airbank.cz");

        assertInstanceOf(DkimResult.Failed.class, result);
    }

    @Test
    void failsWhenOnlyPartOfTheBodyIsSigned() throws Exception {
        KeyPair key = rsa(1024);

        DkimResult result = verifierFor(key).verify(signed(MESSAGE, key, "l=10;"), "airbank.cz");

        assertInstanceOf(DkimResult.Failed.class, result);
        assertTrue(((DkimResult.Failed) result).reason().contains("first 10"), result.toString());
    }

    /**
     * jDKIM 0.5 refuses a signature without {@code from} in {@code h=} ("From field not signed", in
     * SignatureRecordImpl.validate), and its signer refuses to make one, so the test signs by hand. It pins the
     * refusal against a jDKIM upgrade.
     */
    @Test
    void failsWhenFromIsNotSigned() throws Exception {
        KeyPair key = rsa(1024);

        DkimResult result = verifierFor(key).verify(signedWithoutFrom(MESSAGE, key), "airbank.cz");

        assertInstanceOf(DkimResult.Failed.class, result);
    }

    @Test
    void failsOnAKeyInTestingMode() throws Exception {
        KeyPair key = rsa(1024);
        DkimVerifier verifier = DkimVerifier.with((s, d) -> List.of(record(key) + "; t=y"));

        assertInstanceOf(DkimResult.Failed.class, verifier.verify(signed(MESSAGE, key, ""), "airbank.cz"));
    }

    @Test
    void failsOnAKeyShorterThanTheMinimum() throws Exception {
        KeyPair key = rsa(1024);
        DkimVerifier verifier = verifierFor(key).minimumKeyBits(2048);

        DkimResult result = verifier.verify(signed(MESSAGE, key, ""), "airbank.cz");

        assertInstanceOf(DkimResult.Failed.class, result);
        assertTrue(((DkimResult.Failed) result).reason().contains("under 2048"), result.toString());
    }

    @Test
    void acceptsAPinnedKey() throws Exception {
        KeyPair key = rsa(1024);
        DkimVerifier verifier =
                verifierFor(key).pinnedKeys("airbank.cz", Set.of(DkimVerifier.fingerprint(key.getPublic())));

        assertEquals(
                new DkimResult.Verified("airbank.cz", "ab", Set.of("date", "to", "from", "subject")),
                verifier.verify(signed(MESSAGE, key, ""), "airbank.cz"));
    }

    @Test
    void refusesAValidKeyThatIsNotPinned() throws Exception {
        KeyPair forged = rsa(1024);
        DkimVerifier verifier = verifierFor(forged)
                .pinnedKeys(
                        "airbank.cz", Set.of(DkimVerifier.fingerprint(rsa(1024).getPublic())));

        DkimResult result = verifier.verify(signed(MESSAGE, forged, ""), "airbank.cz");

        assertInstanceOf(DkimResult.Failed.class, result);
        assertTrue(((DkimResult.Failed) result).reason().contains("not one of the keys pinned"), result.toString());
    }

    @Test
    void refusesEveryKeyOfABankWithNonePinnedYetAndNamesTheOneDnsGave() throws Exception {
        KeyPair key = rsa(1024);
        DkimVerifier verifier = verifierFor(key).pinnedKeys("airbank.cz", Set.of());

        DkimResult result = verifier.verify(signed(MESSAGE, key, ""), "airbank.cz");

        assertInstanceOf(DkimResult.Failed.class, result);
        String reason = ((DkimResult.Failed) result).reason();
        assertTrue(reason.contains("no key is pinned"), reason);
        assertTrue(reason.contains(DkimVerifier.fingerprint(key.getPublic())), reason);
    }

    @Test
    void isTemporaryWhenTheKeyCannotBeLookedUp() throws Exception {
        KeyPair key = rsa(1024);
        DkimVerifier verifier = DkimVerifier.with((s, d) -> {
            throw new IOException("resolver timed out");
        });

        assertInstanceOf(DkimResult.TemporaryFailure.class, verifier.verify(signed(MESSAGE, key, ""), "airbank.cz"));
    }

    @Test
    void refusesAMessageOverTheSizeLimit() throws Exception {
        KeyPair key = rsa(1024);
        byte[] huge = new byte[DkimVerifier.MAX_MESSAGE_BYTES + 1];

        assertInstanceOf(DkimResult.Failed.class, verifierFor(key).verify(huge, "airbank.cz"));
    }

    private static final String MESSAGE = String.join(
            "\r\n",
            "Date: Tue, 10 Nov 2026 14:40:05 +0100",
            "To: bruce.wayne@gotham.org",
            "From: Air Bank<info@airbank.cz>",
            "Subject: =?utf-8?Q?Zv=C3=BD=C5=A1en=C3=AD_z=C5=AFstatku_na_=C3=BA=C4=8Dtu_Hlavni?=",
            "MIME-Version: 1.0",
            "Content-Transfer-Encoding: 8bit",
            "Content-Type: text/plain; charset=\"utf-8\"",
            "",
            "zůstatek na účtu Hlavni číslo 1234567890/3030 se zvýšil o částku 1,00 CZK.",
            "Příchozí úhrada z účtu Novák, Jan číslo 9876543210/2010",
            "Částka: 1,00 CZK",
            "Datum zaúčtování: 10.11.2026",
            "Variabilní symbol: 1337",
            "Kód transakce: 100000000001",
            "");

    private static DkimVerifier verifierFor(KeyPair key) {
        return DkimVerifier.with((selector, domain) ->
                selector.equals("ab") && domain.equals("airbank.cz") ? List.of(record(key)) : List.of());
    }

    /** Signs like Air Bank does: relaxed/relaxed, rsa-sha256, Date:To:From:Subject. */
    private static byte[] signed(String message, KeyPair key, String extraTags) throws IOException, FailException {
        String template = "v=1; a=rsa-sha256; c=relaxed/relaxed; d=airbank.cz; s=ab; h=Date:To:From:Subject; "
                + extraTags + " bh=; b=;";
        String header = new DKIMSigner(template, key.getPrivate()).sign(new ByteArrayInputStream(bytes(message)));
        return bytes(header + "\r\n" + message);
    }

    /** A relaxed/relaxed rsa-sha256 signature over Date:To:Subject, written out by hand as RFC 6376 section 3.7 says. */
    private static byte[] signedWithoutFrom(String message, KeyPair key) throws GeneralSecurityException {
        String body = message.substring(message.indexOf("\r\n\r\n") + 4);
        String relaxedBody =
                body.replaceAll("[ \t]+\r\n", "\r\n").replaceAll("[ \t]+", " ").replaceAll("(\r\n)+$", "");
        String bodyHash = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes(relaxedBody + "\r\n")));
        String unsigned =
                "v=1; a=rsa-sha256; c=relaxed/relaxed; d=airbank.cz; s=ab; h=Date:To:Subject; bh=" + bodyHash + "; b=";
        StringBuilder data = new StringBuilder();
        for (String name : List.of("Date", "To", "Subject")) {
            String line = message.lines()
                    .filter(l -> l.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    .findFirst()
                    .orElseThrow();
            data.append(name.toLowerCase(Locale.ROOT))
                    .append(':')
                    .append(line.substring(name.length() + 1)
                            .replaceAll("[ \t]+", " ")
                            .strip())
                    .append("\r\n");
        }
        data.append("dkim-signature:").append(unsigned);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(bytes(data.toString()));
        String b = Base64.getEncoder().encodeToString(signature.sign());
        return bytes("DKIM-Signature: " + unsigned + b + "\r\n" + message);
    }

    private static String record(KeyPair key) {
        return "v=DKIM1; k=rsa; p="
                + Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }

    private static KeyPair rsa(int bits) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
