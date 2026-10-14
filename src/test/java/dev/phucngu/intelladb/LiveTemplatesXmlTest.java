package dev.phucngu.intelladb;

import dev.phucngu.intelladb.sql.IntellaSqlTemplateContext;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped SQL live-template set: every IntelliJ database-tools verb ({@code sel} …
 * {@code col}) is there, and every template is scoped to IntellaSQL so it never fires in a
 * Java editor.
 */
class LiveTemplatesXmlTest {

    @Test
    void theStatementTemplatesAreAllThere() throws Exception {
        Document doc = document();
        assertEquals("Intella DB", doc.getDocumentElement().getAttribute("group"));
        Set<String> names = new LinkedHashSet<>();
        var templates = doc.getElementsByTagName("template");
        for (int i = 0; i < templates.getLength(); i++) {
            Element template = (Element) templates.item(i);
            names.add(template.getAttribute("name"));
            assertTrue(template.getAttribute("description").length() > 3,
                    template.getAttribute("name") + " needs a description");
            assertTrue(template.getAttribute("value").contains("$END$"),
                    template.getAttribute("name") + " needs an end marker");
            assertTrue(template.getElementsByTagName("context").getLength() == 1
                            && ((Element) template.getElementsByTagName("context").item(0))
                            .getElementsByTagName("option").getLength() >= 1,
                    template.getAttribute("name") + " needs a context");
        }
        assertEquals(Set.of("sel", "selw", "selc", "lim", "ins", "upd", "del", "tab", "view", "col"), names);
    }

    @Test
    void everyTemplateIsScopedToIntellaSql() throws Exception {
        Document doc = document();
        var templates = doc.getElementsByTagName("template");
        for (int i = 0; i < templates.getLength(); i++) {
            Element template = (Element) templates.item(i);
            var options = template.getElementsByTagName("option");
            boolean scoped = false;
            for (int j = 0; j < options.getLength(); j++) {
                Element option = (Element) options.item(j);
                if (IntellaSqlTemplateContext.ID.equals(option.getAttribute("name"))
                        && "true".equals(option.getAttribute("value"))) {
                    scoped = true;
                }
            }
            assertTrue(scoped, template.getAttribute("name") + " must apply in Intella SQL only");
        }
    }

    private static Document document() throws Exception {
        try (InputStream in = LiveTemplatesXmlTest.class.getResourceAsStream("/liveTemplates/IntellaDB.xml")) {
            assertNotNull(in, "liveTemplates/IntellaDB.xml must ship with the plugin");
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
    }
}
