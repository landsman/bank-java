package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MailBodyTest {

    @Test
    void putsEveryTableCellOnItsOwnLine() {
        // strip_tags would give "123456789/5500Firma s.r.o.", the account glued to the name.
        String html = "<table><tr><td>Na účet</td></tr><tr><td>123456789/5500</td><td>Firma s.r.o.</td></tr></table>";

        assertEquals("Na účet\n123456789/5500\nFirma s.r.o.\n", MailBody.lines(html));
    }

    @Test
    void breaksALineAtBrAndAtBlocks() {
        assertEquals(
                "Částka: 1,00 CZK\nVS: 1337\nKód: 9\n", MailBody.lines("Částka: 1,00 CZK<br/>VS: 1337<p>Kód: 9</p>"));
    }

    @Test
    void neverBreaksALineAtWhitespaceInsideText() {
        // A payer's line break is whitespace in HTML: it must not start a forged "VS:" line.
        assertEquals("Zpráva: platba VS: 42\n", MailBody.lines("<p>Zpráva: platba\r\nVS: 42</p>"));
    }

    @Test
    void keepsAnNbspAndAU2028AsCharacters() {
        assertEquals("a b c\n", MailBody.lines("<p>a&nbsp;b c</p>"));
    }

    @Test
    void decodesEntities() {
        assertEquals("Zpráva: A & B <x>\n", MailBody.lines("<p>Zpráva: A &amp; B &lt;x&gt;</p>"));
    }

    @Test
    void leavesOutWhatAMailClientDoesNotShow() {
        // Script and style content is never text to jsoup; a template's is, and is removed.
        assertEquals(
                "VS: 1\n",
                MailBody.lines(
                        "<p>VS: 1</p><div><style>p{}</style><script>a</script><template>VS: 42</template></div>"));
    }

    @Test
    void survivesNestingAsDeepAsTheSizeLimitAllows() {
        // 64 KiB of "<div>" is about 13 000 levels; a recursive walk would overflow the stack, which is an Error that
        // no catch of RuntimeException turns into Unparsed.
        String html = "<div>".repeat(13_000) + "VS: 1" + "</div>".repeat(13_000);

        assertEquals("VS: 1\n", MailBody.lines(html));
    }

    @Test
    void prefersThePlainPartOfAnAlternative() throws Exception {
        assertEquals("plain", MailBody.text(alternative("plain", "<p>html</p>")));
    }

    @Test
    void readsTheHtmlPartWhenThereIsNoPlainOne() throws Exception {
        MimeMessage message = message();
        message.setContent("<p>VS: 1</p>", "text/html; charset=utf-8");
        message.saveChanges();

        assertEquals("VS: 1\n", MailBody.text(message));
    }

    @Test
    void refusesAMessageWithNeitherPart() throws Exception {
        MimeMessage message = message();
        message.setContent("x", "application/octet-stream");
        message.saveChanges();

        assertThrows(Unparsed.class, () -> MailBody.text(message));
    }

    private static MimeMessage alternative(String plain, String html) throws Exception {
        MimeBodyPart text = new MimeBodyPart();
        text.setText(plain, "utf-8");
        MimeBodyPart rich = new MimeBodyPart();
        rich.setContent(html, "text/html; charset=utf-8");
        MimeMultipart parts = new MimeMultipart("alternative", rich, text);
        MimeMessage message = message();
        message.setContent(parts);
        message.saveChanges();
        return new MimeMessage(Session.getInstance(new Properties()), toStream(message));
    }

    private static InputStream toStream(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static MimeMessage message() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }
}
