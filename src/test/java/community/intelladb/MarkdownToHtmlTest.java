package community.intelladb;

import community.intelladb.ai.MarkdownToHtml;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownToHtmlTest {

    @Test
    void inlineFormatting() {
        assertEquals("<b>x</b> and <i>y</i>", MarkdownToHtml.inlineToHtml("**x** and *y*"));
        assertEquals("<code>public.customers</code>", MarkdownToHtml.inlineToHtml("`public.customers`"));
        assertEquals("<s>gone</s>", MarkdownToHtml.inlineToHtml("~~gone~~"));
    }

    @Test
    void escapesHtmlBeforeFormatting() {
        assertEquals("<b>a &amp; b</b>", MarkdownToHtml.inlineToHtml("**a & b**"));
        assertEquals("1 &lt; 2", MarkdownToHtml.inlineToHtml("1 < 2"));
    }

    @Test
    void linksBecomeAnchors() {
        String html = MarkdownToHtml.inlineToHtml("see [the docs](https://example.com/x) now");
        assertEquals("see <a href=\"https://example.com/x\">the docs</a> now", html);
    }

    @Test
    void headingsBecomeBoldParagraphs() {
        List<MarkdownToHtml.Block> blocks = MarkdownToHtml.split("## Summary\n\nbody text");
        assertEquals(2, blocks.size());
        assertEquals(new MarkdownToHtml.Paragraph("<b>Summary</b>"), blocks.get(0));
        assertEquals(new MarkdownToHtml.Paragraph("body text"), blocks.get(1));
    }

    @Test
    void bulletListsGroupIntoOneUl() {
        List<MarkdownToHtml.Block> blocks = MarkdownToHtml.split("- **customers** — records\n- **orders** — purchases\n\ntail");
        assertEquals(2, blocks.size());
        assertEquals(new MarkdownToHtml.Paragraph(
                "<ul><li><b>customers</b> — records</li><li><b>orders</b> — purchases</li></ul>"),
                blocks.get(0));
        assertEquals(new MarkdownToHtml.Paragraph("tail"), blocks.get(1));
    }

    @Test
    void fencedCodeBlocksAreExtracted() {
        List<MarkdownToHtml.Block> blocks = MarkdownToHtml.split(
                "before\n```sql\nSELECT 1;\n```\nafter");
        assertEquals(3, blocks.size());
        assertEquals(new MarkdownToHtml.Paragraph("before"), blocks.get(0));
        assertEquals(new MarkdownToHtml.Code("SELECT 1;\n"), blocks.get(1));
        assertEquals(new MarkdownToHtml.Paragraph("after"), blocks.get(2));
    }

    @Test
    void unclosedFenceStillEmitsCode() {
        List<MarkdownToHtml.Block> blocks = MarkdownToHtml.split("```\nSELECT 2;");
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0) instanceof MarkdownToHtml.Code);
        assertEquals("SELECT 2;\n", ((MarkdownToHtml.Code) blocks.get(0)).text());
    }

    @Test
    void paragraphsJoinConsecutiveLines() {
        List<MarkdownToHtml.Block> blocks = MarkdownToHtml.split("line one\nline two");
        assertEquals(1, blocks.size());
        assertEquals(new MarkdownToHtml.Paragraph("line one<br>line two"), blocks.get(0));
    }
}
