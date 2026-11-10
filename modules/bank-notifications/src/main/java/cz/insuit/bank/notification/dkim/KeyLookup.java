package cz.insuit.bank.notification.dkim;

import java.io.IOException;
import java.util.List;

/** Finds the DKIM public key records of a domain: the TXT records of {@code <selector>._domainkey.<domain>}. */
@FunctionalInterface
public interface KeyLookup {

    /**
     * Returns the TXT records, each joined into one string.
     *
     * @param selector the {@code s=} of the signature
     * @param domain the {@code d=} of the signature
     * @return the records, empty when there are none
     * @throws IOException when the lookup could not be made; the result is then temporary, not a failure
     */
    List<String> txtRecords(String selector, String domain) throws IOException;
}
