package community.intelladb.ai;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the markdown subset LLM answers actually use into pieces the chat can render:
 * paragraphs as Swing HTML (bold, italic, inline code, links, headings, lists) and
 * fenced code blocks kept as raw text. Deliberately small and dependency-free.
 */
public final class MarkdownToHtml {

    /** A rendered piece of an answer. */
    public sealed interface Block permits Paragraph, Code {
    }

    /** Inner HTML for one paragraph/list/heading; the panel wraps it in &lt;html&gt;. */
    public record Paragraph(@NotNull String html) implements Block {
    }

    /** Raw content of a fenced code block (fence markers removed). */
    public record Code(@NotNull String text) implements Block {
    }

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*([^*\\s][^*]*?)\\*(?!\\*)");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<![A-Za-z0-9_])_([^_\n]+)_(?![A-Za-z0-9_])");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~(.+?)~~");
    private static final Pattern HEADING = Pattern.compile("#{1,6}\\s+(.*)");

    private MarkdownToHtml() {
    }

    /**
     * Splits an answer into paragraphs (inner HTML, lines already joined) and code
     * blocks, in order. Blank lines separate paragraphs; consecutive bullet lines
     * become one &lt;ul&gt;.
     */
    public static @NotNull List<Block> split(@NotNull String markdown) {
        List<Block> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<String> bullets = new ArrayList<>();
        boolean inFence = false;
        StringBuilder code = new StringBuilder();

        for (String line : markdown.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inFence) {
                    blocks.add(new Code(code.toString()));
                    code.setLength(0);
                } else {
                    flushParagraph(paragraph, blocks);
                    flushBullets(bullets, blocks);
                }
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                code.append(line).append('\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, blocks);
                flushBullets(bullets, blocks);
                continue;
            }
            if (isBullet(trimmed)) {
                flushParagraph(paragraph, blocks);
                bullets.add(trimmed.substring(2).trim());
                continue;
            }
            flushBullets(bullets, blocks);
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flushParagraph(paragraph, blocks);
                blocks.add(new Paragraph("<b>" + inlineToHtml(heading.group(1)) + "</b>"));
                continue;
            }
            paragraph.add(trimmed);
        }
        if (inFence && code.length() > 0) {
            blocks.add(new Code(code.toString()));
        }
        flushParagraph(paragraph, blocks);
        flushBullets(bullets, blocks);
        return blocks;
    }

    /** Escapes HTML, then applies inline markdown: links, bold, italic, code, strike. */
    public static @NotNull String inlineToHtml(@NotNull String raw) {
        String s = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        s = replaceAll(s, INLINE_CODE, m -> "<code>" + m.group(1) + "</code>");
        s = replaceAll(s, LINK, m -> "<a href=\"" + m.group(2) + "\">" + m.group(1) + "</a>");
        s = replaceAll(s, BOLD, m -> "<b>" + (m.group(1) != null ? m.group(1) : m.group(2)) + "</b>");
        s = replaceAll(s, ITALIC_STAR, m -> "<i>" + m.group(1) + "</i>");
        s = replaceAll(s, ITALIC_UNDERSCORE, m -> "<i>" + m.group(1) + "</i>");
        s = replaceAll(s, STRIKETHROUGH, m -> "<s>" + m.group(1) + "</s>");
        return s;
    }

    private static void flushParagraph(@NotNull List<String> lines, @NotNull List<Block> out) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                html.append("<br>");
            }
            html.append(inlineToHtml(lines.get(i)));
        }
        out.add(new Paragraph(html.toString()));
        lines.clear();
    }

    private static void flushBullets(@NotNull List<String> bullets, @NotNull List<Block> out) {
        if (bullets.isEmpty()) {
            return;
        }
        StringBuilder html = new StringBuilder("<ul>");
        for (String bullet : bullets) {
            html.append("<li>").append(inlineToHtml(bullet)).append("</li>");
        }
        html.append("</ul>");
        out.add(new Paragraph(html.toString()));
        bullets.clear();
    }

    private static boolean isBullet(@NotNull String trimmed) {
        return trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ");
    }

    private static @NotNull String replaceAll(@NotNull String s, @NotNull Pattern pattern,
                                              @NotNull java.util.function.Function<Matcher, String> toReplacement) {
        Matcher matcher = pattern.matcher(s);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(toReplacement.apply(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
