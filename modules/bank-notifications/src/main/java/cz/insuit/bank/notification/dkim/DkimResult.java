package cz.insuit.bank.notification.dkim;

import java.util.Set;

/** Whether an e-mail carries a valid DKIM signature of the expected domain. */
public sealed interface DkimResult {

    /**
     * A signature of the expected domain verified, over the whole body.
     *
     * @param domain the signing domain, {@code d=}
     * @param selector the key's selector, {@code s=}
     * @param signedHeaders the headers the signature covers, {@code h=}, in lower case: only these are proven
     */
    record Verified(String domain, String selector, Set<String> signedHeaders) implements DkimResult {
        public Verified {
            signedHeaders = Set.copyOf(signedHeaders);
        }
    }

    /**
     * No valid signature of the expected domain. Do not trust the message.
     *
     * @param reason why, one line per signature that was looked at
     */
    record Failed(String reason) implements DkimResult {}

    /**
     * The key could not be looked up. Try again later; until then, do not trust the message.
     *
     * @param reason what failed
     */
    record TemporaryFailure(String reason) implements DkimResult {}
}
