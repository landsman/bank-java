package cz.insuit.bank.notification.dkim;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.james.jdkim.api.PublicKeyRecordRetriever;
import org.apache.james.jdkim.exceptions.PermFailException;
import org.apache.james.jdkim.exceptions.TempFailException;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;
import org.xbill.DNS.dnssec.ValidatingResolver;

/**
 * Looks a DKIM key up in DNS and validates the answer with DNSSEC itself, from the root's trust anchors down, instead of
 * trusting whatever resolver the machine has. A key whose answer is not proven secure is refused: an unsigned zone, a
 * forged answer and a resolver that strips the signatures all end the same way.
 *
 * <p>The system's resolvers still carry the queries; they only have to pass the DNSSEC records through, which nearly
 * every recursive resolver does. This is the lookup {@link DkimVerifier#withDns()} uses.
 */
final class DnssecKeyLookup implements PublicKeyRecordRetriever {

    /**
     * Returns the TXT records at {@code <selector>._domainkey.<domain>}, each joined into one string.
     *
     * @throws TempFailException when DNS could not be reached
     * @throws PermFailException when the answer is not DNSSEC-secure, or there is no such name
     */
    @Override
    public List<String> getRecords(CharSequence method, CharSequence selector, CharSequence domain)
            throws TempFailException, PermFailException {
        String name = selector + "._domainkey." + domain;
        Message response;
        try {
            Record question = Record.newRecord(Name.fromString(name + "."), Type.TXT, DClass.IN);
            response = resolver.send(Message.newQuery(question));
        } catch (TextParseException e) {
            throw new PermFailException("not a DNS name: " + name);
        } catch (IOException e) {
            throw new TempFailException("DNS lookup of " + name + " failed: " + e.getMessage());
        }
        int rcode = response.getRcode();
        if (rcode == Rcode.SERVFAIL) {
            // ValidatingResolver answers SERVFAIL for a bogus answer as well as for a broken upstream; neither is a
            // key.
            throw new PermFailException("DNSSEC validation of " + name + " failed");
        }
        if (!response.getHeader().getFlag(Flags.AD)) {
            throw new PermFailException("the answer for " + name + " is not DNSSEC-secure");
        }
        if (rcode != Rcode.NOERROR) {
            throw new PermFailException("no key at " + name + ": " + Rcode.string(rcode));
        }
        List<String> records = new ArrayList<>();
        for (Record record : response.getSection(Section.ANSWER)) {
            if (record instanceof TXTRecord txt) {
                records.add(String.join("", txt.getStrings()));
            }
        }
        return records;
    }

    /** Sends the queries through the machine's own resolvers and validates every answer here. */
    static DnssecKeyLookup usingSystemResolvers() {
        ExtendedResolver upstream = new ExtendedResolver();
        upstream.setTimeout(TIMEOUT);
        ValidatingResolver validating = new ValidatingResolver(upstream);
        try {
            validating.loadTrustAnchors(
                    new ByteArrayInputStream(ROOT_TRUST_ANCHORS.getBytes(StandardCharsets.US_ASCII)));
        } catch (IOException e) {
            throw new UncheckedIOException("the built-in root trust anchors did not load", e);
        }
        return new DnssecKeyLookup(validating);
    }

    // The root zone's key-signing keys, from https://data.iana.org/root-anchors/root-anchors.xml (read 2026-11-10):
    // KSK-2017 and KSK-2024, both valid with no end date. The daily key check (.github/workflows/keys.yml) validates a
    // real chain with them, so a root rollover this list misses fails there before it fails a caller.
    static final String ROOT_TRUST_ANCHORS = """
            . IN DS 20326 8 2 E06D44B80B8F1D39A95C0B0D7C65D08458E880409BBC683457104237C7F8EC8D
            . IN DS 38696 8 2 683D2D0ACB8C9B712A1948B27F741219298D0A450D612C483AF444A4C0FB2B16
            """;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ValidatingResolver resolver;

    private DnssecKeyLookup(ValidatingResolver resolver) {
        this.resolver = resolver;
    }
}
