package cz.insuit.bank.notification.dkim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.insuit.bank.notification.BankNotifications;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.james.jdkim.exceptions.PermFailException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Against live DNS, so not part of {@code make build}: {@code make check-keys}, run daily by
 * {@code .github/workflows/keys.yml}. It fails when a bank publishes a key this release does not pin, when DNSSEC
 * validation stops working (a root key rollover the trust anchors miss), or when it stops refusing what it must refuse.
 */
@Tag("live")
class KeyCheckLiveTest {

    private final DnssecKeyLookup dns = DnssecKeyLookup.usingSystemResolvers();

    @Test
    void airBankPublishesOnlyPinnedKeys() throws Exception {
        assertPublishesOnlyPinnedKeys("ab", "airbank.cz");
    }

    @Test
    void fioPublishesOnlyPinnedKeys() throws Exception {
        assertPublishesOnlyPinnedKeys("20160512", "fio.cz");
    }

    @Test
    void komercniBankaPublishesOnlyPinnedKeys() throws Exception {
        assertPublishesOnlyPinnedKeys("scph0521", "kbinfo.cz");
    }

    private void assertPublishesOnlyPinnedKeys(String selector, String domain) throws Exception {
        Set<String> pinned =
                Objects.requireNonNull(BankNotifications.pinnedKeys().get(domain), domain);
        List<String> records = dns.getRecords("dns/txt", selector, domain);

        assertFalse(records.isEmpty(), "no key at " + selector + "._domainkey." + domain);
        for (String record : records) {
            String fingerprint = DkimVerifier.fingerprint(publicKey(record));
            assertTrue(
                    pinned.contains(fingerprint),
                    domain + " publishes a key that is not pinned: " + fingerprint + " (" + record + ")");
        }
    }

    // Each refusal is pinned to its kind and carries the validator's reason. A broken resolver fails every validation,
    // so the two unsigned zones then go red here instead of passing for the wrong reason.
    @Test
    void refusesABogusAnswer() {
        // A zone kept deliberately broken for testing validators.
        PermFailException refused =
                assertThrows(PermFailException.class, () -> dns.getRecords("dns/txt", "x", "dnssec-failed.org"));
        assertRefusedAs("DNSSEC validation of x._domainkey.dnssec-failed.org failed: ", refused);
    }

    @Test
    void stillRefusesCsobSlovakia() {
        // ČSOB SK pins no key, so DNSSEC alone would decide; csob.sk is not signed, so its e-mails stay Unverified.
        PermFailException refused =
                assertThrows(PermFailException.class, () -> dns.getRecords("dns/txt", "x", "csob.sk"));
        assertRefusedAs("the answer for x._domainkey.csob.sk is not DNSSEC-secure: ", refused);
    }

    @Test
    void refusesAnUnsignedZone() {
        // google.com is not DNSSEC-signed; its DKIM key would come back, unproven.
        PermFailException refused =
                assertThrows(PermFailException.class, () -> dns.getRecords("dns/txt", "20230601", "google.com"));
        assertRefusedAs("the answer for 20230601._domainkey.google.com is not DNSSEC-secure: ", refused);
    }

    private static void assertRefusedAs(String prefix, PermFailException refused) {
        String message = Objects.requireNonNull(refused.getMessage());
        assertTrue(message.startsWith(prefix) && message.length() > prefix.length(), message);
    }

    private static PublicKey publicKey(String record) throws Exception {
        Matcher p = Pattern.compile("(?:^|;)\\s*p=([^;]+)").matcher(record);
        if (!p.find()) {
            throw new AssertionError("no p= in " + record);
        }
        byte[] der = Base64.getDecoder().decode(p.group(1).replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }
}
