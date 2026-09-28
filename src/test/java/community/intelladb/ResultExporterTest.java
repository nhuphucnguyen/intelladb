package community.intelladb;

import community.intelladb.util.ResultExporter;
import community.intelladb.util.ResultExporter.Format;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultExporterTest {

    private static final List<String> COLUMNS = List.of("id", "Name", "note");
    private static final List<Object[]> ROWS = List.<Object[]>of(
            new Object[]{1, "Ann", null},
            new Object[]{2L, "O'Brien", "a,\"b\"\nc"});

    @Test
    void csvQuotesSpecialValuesAndLeavesNullEmpty() {
        assertEquals("id,Name,note\n1,Ann,\n2,O'Brien,\"a,\"\"b\"\"\nc\"\n",
                ResultExporter.export(Format.CSV, COLUMNS, ROWS, null));
    }

    @Test
    void tsvOnlyQuotesWhenNeeded() {
        assertEquals("id\tName\tnote\n1\tAnn\t\n",
                ResultExporter.export(Format.TSV, COLUMNS, List.<Object[]>of(ROWS.get(0)), null));
    }

    @Test
    void jsonKeepsNumbersAndNullsTyped() {
        assertEquals("[\n  {\"id\": 1, \"Name\": \"Ann\", \"note\": null}\n]\n",
                ResultExporter.export(Format.JSON, COLUMNS, List.<Object[]>of(ROWS.get(0)), null));
        assertEquals("[]\n", ResultExporter.export(Format.JSON, COLUMNS, List.of(), null));
    }

    @Test
    void sqlInsertsEscapeQuotesAndQuoteMixedCaseColumns() {
        assertEquals("INSERT INTO public.people (id, \"Name\", note) VALUES (2, 'O''Brien', 'x');\n",
                ResultExporter.export(Format.SQL_INSERTS, COLUMNS,
                        List.<Object[]>of(new Object[]{2, "O'Brien", "x"}), "public.people"));
    }

    @Test
    void sqlInsertsFallBackToPlaceholderTable() {
        assertEquals("INSERT INTO my_table (id, \"Name\", note) VALUES (1, 'Ann', NULL);\n",
                ResultExporter.export(Format.SQL_INSERTS, COLUMNS, List.<Object[]>of(ROWS.get(0)), null));
    }

    @Test
    void markdownEscapesPipesAndNewlines() {
        assertEquals("| id | Name | note |\n| --- | --- | --- |\n| 1 | a\\|b | x<br>y |\n",
                ResultExporter.export(Format.MARKDOWN, COLUMNS,
                        List.<Object[]>of(new Object[]{1, "a|b", "x\ny"}), null));
    }
}
