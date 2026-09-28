package dev.phucngu.intelladb;

import dev.phucngu.intelladb.util.ResultExporter;
import dev.phucngu.intelladb.util.ResultExporter.Format;
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

    @Test
    void transposeWritesOneLinePerColumn() {
        ResultExporter.Options transpose = new ResultExporter.Options(true, null);
        assertEquals("id,1,2\nName,Ann,O'Brien\n",
                ResultExporter.export(Format.CSV, List.of("id", "Name"),
                        List.<Object[]>of(new Object[]{1, "Ann"}, new Object[]{2, "O'Brien"}), null, transpose));
        assertEquals("| column | 1 |\n| --- | --- |\n| id | 1 |\n",
                ResultExporter.export(Format.MARKDOWN, List.of("id"), List.<Object[]>of(new Object[]{1}), null, transpose));
    }

    @Test
    void transposeIsIgnoredForRowOrientedFormats() {
        ResultExporter.Options transpose = new ResultExporter.Options(true, null);
        assertEquals("INSERT INTO t (id) VALUES (1);\n",
                ResultExporter.export(Format.SQL_INSERTS, List.of("id"), List.<Object[]>of(new Object[]{1}), "t", transpose));
    }

    @Test
    void ddlIsWrittenBeforeInserts() {
        ResultExporter.Options ddl = new ResultExporter.Options(false, "CREATE TABLE t (\n    id int4\n)\n");
        assertEquals("CREATE TABLE t (\n    id int4\n);\n\nINSERT INTO t (id) VALUES (1);\n",
                ResultExporter.export(Format.SQL_INSERTS, List.of("id"), List.<Object[]>of(new Object[]{1}), "t", ddl));
    }
}
