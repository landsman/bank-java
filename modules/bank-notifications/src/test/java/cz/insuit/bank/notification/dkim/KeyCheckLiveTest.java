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
        Set<String> pinned =
                Objects.requireNonNull(BankNotifications.pinnedKeys().get("airbank.cz"));
        List<String> records = dns.getRecords("dns/txt", "ab", "airbank.cz");

        assertFalse(records.isEmpty(), "no key at ab._domainkey.airbank.cz");
        for (String record : records) {
            String fingerprint = DkimVerifier.fingerprint(publicKey(record));
            assertTrue(
                    pinned.contains(fingerprint),
                    "Air Bank publishes a key that is not pinned: " + fingerprint + " (" + record + ")");
        }
    }

    @Test
    void refusesABogusAnswer() {
        // A zone kept deliberately broken for testing validators.
        assertThrows(PermFailException.class, () -> dns.getRecords("dns/txt", "x", "dnssec-failed.org"));
    }

    @Test
    void refusesAnUnsignedZone() {
        // google.com is not DNSSEC-signed; its DKIM key would come back, unproven.
        assertThrows(PermFailException.class, () -> dns.getRecords("dns/txt", "20230601", "google.com"));
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
