package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.jspecify.annotations.Nullable;

/**
 * The text a bank's parser reads: the {@code text/plain} part when there is one, else the {@code text/html} part
 * turned into lines. Every parser then sees lines, whichever the bank sent.
 */
final class MailBody {

    static String text(Part message) throws MessagingException, IOException {
        String plain = find(message, "text/plain", 0);
        if (plain != null) return plain;
        String html = find(message, "text/html", 0);
        if (html != null) return lines(html);
        throw new Unparsed("neither a text/plain nor a text/html part");
    }

    /**
     * An HTML body as lines: a new line starts at a {@code <br>}, a table cell, a row and every block, and nowhere
     * else. Whitespace inside text collapses to one space, as a browser shows it, so text the payer wrote cannot start a
     * line of its own; only markup can. The DOM is walked without recursion, so deep nesting cannot overflow the stack.
     */
    static String lines(String html) {
        StringBuilder out = new StringBuilder();
        Element body = Jsoup.parse(html).body();
        // Text a mail client does not show is no part of the notice; script and style are never text to jsoup anyway.
        body.select("script, style, template, noscript").remove();
        NodeTraversor.traverse(
                new NodeVisitor() {
                    @Override
                    public void head(Node node, int depth) {
                        if (node instanceof TextNode text) {
                            out.append(WHITESPACE.matcher(text.getWholeText()).replaceAll(" "));
                        } else if (node instanceof Element element && BREAKS.contains(element.normalName())) {
                            out.append('\n');
                        }
                    }

                    @Override
                    public void tail(Node node, int depth) {
                        if (node instanceof Element element && BREAKS.contains(element.normalName())) {
                            out.append('\n');
                        }
                    }
                },
                body);
        StringBuilder lines = new StringBuilder();
        for (String line : out.toString().lines().toList()) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) lines.append(stripped).append('\n');
        }
        return lines.toString();
    }

    /** The first part of that type, depth first, at most a few multipart levels down. */
    private static @Nullable String find(Part part, String type, int depth) throws MessagingException, IOException {
        if (part.isMimeType(type)) {
            return part.getContent() instanceof String text ? text : null;
        }
        if (depth < MAX_DEPTH && part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = find(child, type, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ASCII whitespace only: an NBSP or a U+2028 stays a character, never a line break.
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\n\\r\\f\\u000b]+");
    private static final Set<String> BREAKS = Set.of(
            "br", "p", "div", "table", "tr", "td", "th", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "dd",
            "dt");
    private static final int MAX_DEPTH = 3;

    private MailBody() {}
}
