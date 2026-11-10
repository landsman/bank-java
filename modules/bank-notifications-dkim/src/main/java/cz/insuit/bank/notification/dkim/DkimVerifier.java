package cz.insuit.bank.notification.dkim;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.james.jdkim.DKIMVerifier;
import org.apache.james.jdkim.api.PublicKeyRecordRetriever;
import org.apache.james.jdkim.api.Result;
import org.apache.james.jdkim.api.SignatureRecord;
import org.apache.james.jdkim.exceptions.FailException;
import org.apache.james.jdkim.exceptions.PermFailException;
import org.apache.james.jdkim.exceptions.TempFailException;
import org.apache.james.jdkim.impl.DNSPublicKeyRecordRetriever;
import org.apache.james.jdkim.tagvalue.PublicKeyRecordImpl;
import org.jspecify.annotations.Nullable;

/**
 * Checks that an e-mail carries a valid DKIM signature (RFC 6376) of the domain you expect, over the raw bytes as
 * received.
 *
 * <p>A signature counts only when its {@code d=} is the expected domain, it covers the whole body (no {@code l=}), its
 * key is not in testing mode ({@code t=y}) and an RSA key has at least {@link #minimumKeyBits(int)} bits. Thread-safe
 * and reusable.
 *
 * <pre>{@code
 * DkimVerifier dkim = DkimVerifier.withDns();
 * if (BankNotifications.parse(raw) instanceof Transaction t
 *         && dkim.verify(raw, t.signedBy()) instanceof DkimResult.Verified) {
 *     // genuine; still compare t.account() with the account you expect
 * }
 * }</pre>
 */
public final class DkimVerifier {

    /** Looks keys up in DNS. */
    public static DkimVerifier withDns() {
        return new DkimVerifier(new DNSPublicKeyRecordRetriever(), DEFAULT_MINIMUM_KEY_BITS);
    }

    /**
     * Looks keys up with your own lookup: a resolver with its own timeouts, or a recorded key in a test.
     *
     * @param lookup where the key records come from
     */
    public static DkimVerifier with(KeyLookup lookup) {
        PublicKeyRecordRetriever retriever = (method, selector, domain) -> {
            try {
                return lookup.txtRecords(selector.toString(), domain.toString());
            } catch (IOException e) {
                throw new TempFailException("key lookup failed: " + e.getMessage());
            }
        };
        return new DkimVerifier(retriever, DEFAULT_MINIMUM_KEY_BITS);
    }

    /**
     * Returns a verifier that refuses RSA keys shorter than {@code bits}. The default is 1024, the shortest RFC 8301
     * still lets a verifier accept and what Air Bank signs with.
     *
     * @param bits the shortest RSA modulus accepted
     */
    public DkimVerifier minimumKeyBits(int bits) {
        return new DkimVerifier(keys, bits);
    }

    /**
     * Verifies one raw message against the domain that has to have signed it.
     *
     * @param rawMessage the message exactly as received, headers included; not re-serialised by a mail library
     * @param domain the domain that must have signed it, e.g. {@code Transaction.signedBy()}
     * @return verified, failed, or temporarily undecidable
     */
    public DkimResult verify(byte[] rawMessage, String domain) {
        if (rawMessage.length > MAX_MESSAGE_BYTES) {
            return new DkimResult.Failed(
                    "message of " + rawMessage.length + " bytes is over the " + MAX_MESSAGE_BYTES + " byte limit");
        }
        // One jDKIM verifier per message: it keeps its results on the instance.
        DKIMVerifier verifier = new DKIMVerifier(checked(keys, minimumKeyBits));
        try {
            verifier.verify(new ByteArrayInputStream(rawMessage));
        } catch (FailException e) {
            // Every signature failed; getResults() says why, one result each.
        } catch (IOException e) {
            return new DkimResult.Failed("not a readable message: " + e.getMessage());
        }

        String expected = domain.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        @Nullable String temporary = null;
        for (Result result : verifier.getResults()) {
            SignatureRecord signature = result.getRecord();
            if (result.getResultType() == Result.Type.NONE || signature == null) {
                reasons.add("no DKIM-Signature");
                continue;
            }
            String signer = signature.getDToken().toString().toLowerCase(Locale.ROOT);
            if (!signer.equals(expected)) {
                reasons.add("d=" + signer + " is not " + expected);
            } else if (result.isSuccess() && signature.getBodyHashLimit() == WHOLE_BODY) {
                return new DkimResult.Verified(signer, signature.getSelector().toString());
            } else if (result.isSuccess()) {
                reasons.add("d=" + signer + " signs only the first " + signature.getBodyHashLimit() + " body bytes");
            } else if (result.getResultType() == Result.Type.TEMPERROR) {
                temporary = "d=" + signer + ": " + result.getErrorMessage();
            } else {
                reasons.add("d=" + signer + ": " + result.getErrorMessage());
            }
        }
        if (temporary != null) {
            return new DkimResult.TemporaryFailure(temporary);
        }
        return new DkimResult.Failed(reasons.isEmpty() ? "no DKIM-Signature" : String.join("; ", reasons));
    }

    // ponytail: jDKIM 0.5 has parsing that is not linear in the input, fixed upstream on 2026-11-05 and not released
    // yet. Anyone can mail the address these come to, so the size is capped before jDKIM sees the message. A bank
    // notification is around 5 KiB; raise the cap once a jDKIM release carries those fixes.
    /** The largest message read, in bytes; see the comment above. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private static final int DEFAULT_MINIMUM_KEY_BITS = 1024;

    // SignatureRecord.getBodyHashLimit() when the signature has no l= tag.
    private static final int WHOLE_BODY = -1;

    private final PublicKeyRecordRetriever keys;
    private final int minimumKeyBits;

    private DkimVerifier(PublicKeyRecordRetriever keys, int minimumKeyBits) {
        this.keys = keys;
        this.minimumKeyBits = minimumKeyBits;
    }

    /** Refuses, as a permanent failure, a key in testing mode or an RSA key that is too short. */
    private static PublicKeyRecordRetriever checked(PublicKeyRecordRetriever keys, int minimumKeyBits) {
        return (method, selector, domain) -> {
            List<String> records = keys.getRecords(method, selector, domain);
            for (String record : records) {
                PublicKeyRecordImpl parsed;
                PublicKey key;
                try {
                    parsed = new PublicKeyRecordImpl(record);
                    key = parsed.getPublicKey();
                } catch (RuntimeException e) {
                    continue; // a malformed record; jDKIM reports it in its own words
                }
                String name = selector + "._domainkey." + domain;
                if (parsed.isTesting()) {
                    throw new PermFailException("key " + name + " is in testing mode");
                }
                if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() < minimumKeyBits) {
                    throw new PermFailException(
                            "key " + name + " has " + rsa.getModulus().bitLength() + " bits, under " + minimumKeyBits);
                }
            }
            return records;
        };
    }
}
