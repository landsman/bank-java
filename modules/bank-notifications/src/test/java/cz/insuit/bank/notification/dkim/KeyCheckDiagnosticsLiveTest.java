package cz.insuit.bank.notification.dkim;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedFlags;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;
import org.xbill.DNS.dnssec.ValidatingResolver;

/** Temporary: prints what the runner's resolvers hand back, to find why the key check fails there. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "KEYS_DIAG", matches = "1")
class KeyCheckDiagnosticsLiveTest {

    private static final String[][] QUERIES = {
        {".", "DNSKEY"},
        {"cz.", "DS"},
        {"cz.", "DNSKEY"},
        {"fio.cz.", "DS"},
        {"fio.cz.", "DNSKEY"},
        {"airbank.cz.", "DS"},
        {"airbank.cz.", "DNSKEY"},
        {"kbinfo.cz.", "DS"},
        {"kbinfo.cz.", "DNSKEY"},
        {"_domainkey.fio.cz.", "TXT"},
        {"20160512._domainkey.fio.cz.", "TXT"},
        {"ab._domainkey.airbank.cz.", "TXT"},
        {"scph0521._domainkey.kbinfo.cz.", "TXT"},
    };

    private static final String[] KEYS = {
        "20160512._domainkey.fio.cz.", "ab._domainkey.airbank.cz.", "scph0521._domainkey.kbinfo.cz."
    };

    @Test
    void printWhatTheResolversSee() throws Exception {
        List<InetSocketAddress> system = ResolverConfig.getCurrentConfig().servers();
        System.out.println("DIAG system resolvers: " + system);
        List<InetSocketAddress> servers = new ArrayList<>(system);
        servers.add(new InetSocketAddress("1.1.1.1", 53));
        for (InetSocketAddress server : servers) {
            for (String[] q : QUERIES) {
                System.out.println("DIAG raw " + server + " " + q[0] + " " + q[1] + ": " + raw(server, q[0], q[1]));
            }
        }
        for (String upstreamName : new String[] {"system", "1.1.1.1"}) {
            ExtendedResolver upstream =
                    upstreamName.equals("system") ? new ExtendedResolver() : new ExtendedResolver(new String[] {
                        upstreamName
                    });
            upstream.setTimeout(Duration.ofSeconds(5));
            ValidatingResolver validating = new ValidatingResolver(upstream);
            validating.setAddReasonToAdditional(true);
            validating.loadTrustAnchors(new ByteArrayInputStream(
                    DnssecKeyLookup.ROOT_TRUST_ANCHORS.getBytes(StandardCharsets.US_ASCII)));
            for (String key : KEYS) {
                long start = System.nanoTime();
                String out;
                try {
                    Message response = validating.send(
                            Message.newQuery(Record.newRecord(Name.fromString(key), Type.TXT, DClass.IN)));
                    out = Rcode.string(response.getRcode()) + " AD="
                            + response.getHeader().getFlag(Flags.AD) + "\n" + response;
                } catch (Exception e) {
                    out = "threw " + e;
                }
                System.out.println("DIAG validating via " + upstreamName + " " + key + " in "
                        + (System.nanoTime() - start) / 1_000_000 + " ms: " + out);
            }
        }
    }

    private static String raw(InetSocketAddress server, String name, String type) {
        long start = System.nanoTime();
        try {
            SimpleResolver resolver = new SimpleResolver(server);
            resolver.setTimeout(Duration.ofSeconds(5));
            resolver.setEDNS(0, 1232, ExtendedFlags.DO, List.of());
            Message query = Message.newQuery(Record.newRecord(Name.fromString(name), Type.value(type), DClass.IN));
            query.getHeader().setFlag(Flags.CD);
            Message response = resolver.send(query);
            long rrsigs = response.getSection(Section.ANSWER).stream()
                    .filter(r -> r instanceof RRSIGRecord)
                    .count();
            long records = response.getSection(Section.ANSWER).size() - rrsigs;
            OPTRecord opt = response.getOPT();
            return Rcode.string(response.getRcode()) + " flags=[" + response.getHeader().printFlags() + "] answer="
                    + records + " rrsig=" + rrsigs + " authority="
                    + response.getSection(Section.AUTHORITY).size() + " bytes=" + response.numBytes() + " opt="
                    + (opt == null
                            ? "none"
                            : "payload " + opt.getPayloadSize() + " do=" + ((opt.getFlags() & ExtendedFlags.DO) != 0)
                                    + " " + opt.getOptions())
                    + " in " + (System.nanoTime() - start) / 1_000_000 + " ms";
        } catch (Exception e) {
            return "threw " + e + " in " + (System.nanoTime() - start) / 1_000_000 + " ms";
        }
    }
}
