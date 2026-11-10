package example

import cz.insuit.bank.notification.BankNotificationException
import cz.insuit.bank.notification.BankNotifications
import cz.insuit.bank.notification.Transaction
import org.apache.james.jdkim.DKIMSigner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * What a Kotlin caller gets: a `when` over the exception needs no `else`, because Kotlin reads the Java sealed class as
 * sealed, and a nullable component arrives as a nullable type, from JSpecify.
 */
class KotlinCallerTest {
    @Test
    fun whenOverTheExceptionIsExhaustive() {
        val unsigned = BankNotifications.trustingKeysFrom { _, _ -> emptyList() }

        val outcome =
            try {
                unsigned.read(MESSAGE.toByteArray())
                "read"
            } catch (e: BankNotificationException) {
                // No else branch: adding a subtype would stop this from compiling.
                when (e) {
                    is BankNotificationException.Unverified -> "unverified"
                    is BankNotificationException.TemporaryFailure -> "later"
                    is BankNotificationException.Unparsed -> "unparsed"
                }
            }

        assertEquals("unverified", outcome)
    }

    @Test
    fun nullableComponentsAreNullable() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val record = "v=DKIM1; k=rsa; p=" + Base64.getEncoder().encodeToString(key.public.encoded)
        val header =
            DKIMSigner(
                "v=1; a=rsa-sha256; c=relaxed/relaxed; d=airbank.cz; s=ab; h=Date:To:From:Subject; bh=; b=;",
                key.private,
            ).sign(MESSAGE.toByteArray().inputStream())

        val t: Transaction = BankNotifications.trustingKeysFrom { _, _ -> listOf(record) }.read("$header\r\n$MESSAGE".toByteArray())

        // message is String? in Kotlin: assigning it to a String would not compile.
        val message: String? = t.message()
        assertNull(message)
        assertEquals("Novák, Jan", t.counterName() ?: "nobody")
    }

    private companion object {
        val MESSAGE =
            listOf(
                "Date: Tue, 10 Nov 2026 14:40:05 +0100",
                "To: bruce.wayne@gothba.org",
                "From: Air Bank<info@airbank.cz>",
                "MIME-Version: 1.0",
                "Content-Transfer-Encoding: 8bit",
                "Content-Type: text/plain; charset=\"utf-8\"",
                "",
                "zůstatek na účtu Hlavni číslo 1234567899/3030 se zvýšil o částku 1,00 CZK. Dostupný zůstatek je 5,00 CZK.",
                "Příchozí úhrada z účtu Novák, Jan číslo 9876543211/2010",
                "Částka: 1,00 CZK",
                "Datum zaúčtování: 10.11.2026",
                "Variabilní symbol: 1337",
                "Kód transakce: 100000000001",
                "",
            ).joinToString("\r\n")
    }
}
