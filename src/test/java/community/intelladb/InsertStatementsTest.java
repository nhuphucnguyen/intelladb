package community.intelladb;

import com.intellij.openapi.util.TextRange;
import community.intelladb.sql.InsertStatements;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsertStatementsTest {

    private static final String LONG_INSERT = """
            INSERT INTO public.order_items (order_id, product_id, quantity, unit_price, note)
            VALUES (1, 7, 2, 279.00, 'keep; semicolon, and comma'),
                   (2, 1, 1, 129.99, 'it''s fine');
            UPDATE order_items SET quantity = 3 WHERE id = 1;
            """;

    @Test
    void parsesColumnsAndTuples() {
        List<InsertStatements.Statement> statements = InsertStatements.parse(LONG_INSERT);
        assertEquals(1, statements.size());
        InsertStatements.Statement s = statements.get(0);
        assertEquals(5, s.columns.size());
        assertEquals(2, s.tuples.size());
        s.tuples.forEach(t -> assertEquals(5, t.size()));
        assertTrue(s.isPairable());
    }

    @Test
    void caretOnColumnPairsAcrossTuples() {
        InsertStatements.Statement s = InsertStatements.parse(LONG_INSERT).get(0);
        // caret inside the 4th column (unit_price)
        TextRange unitPrice = s.columns.get(3);
        InsertStatements.Pairing pairing = InsertStatements.pairingFor(s, unitPrice.getStartOffset() + 2);
        assertNotNull(pairing);
        assertEquals(unitPrice, pairing.columnRange());
        assertEquals(2, pairing.sameSlotValues().size());
        // both tuples' 4th value slots are unit_price positions
        assertEquals(279.00, valueNumber(LONG_INSERT, pairing.sameSlotValues().get(0)));
        assertEquals(129.99, valueNumber(LONG_INSERT, pairing.sameSlotValues().get(1)));
    }

    @Test
    void caretOnValuePairsBackToColumn() {
        InsertStatements.Statement s = InsertStatements.parse(LONG_INSERT).get(0);
        TextRange secondTupleNote = s.tuples.get(1).get(4); // 'it''s fine'
        InsertStatements.Pairing pairing = InsertStatements.pairingFor(s, secondTupleNote.getStartOffset() + 3);
        assertNotNull(pairing);
        assertEquals(s.columns.get(4), pairing.columnRange());
        assertEquals(secondTupleNote, pairing.caretValueRange());
    }

    @Test
    void commasInsideStringsAndParensStayInOneValue() {
        InsertStatements.Statement s = InsertStatements.parse(LONG_INSERT).get(0);
        TextRange note = s.tuples.get(0).get(4);
        String text = LONG_INSERT.substring(note.getStartOffset(), note.getEndOffset());
        assertTrue(text.contains("semicolon, and comma"));
    }

    @Test
    void insertWithoutColumnListIsNotPairable() {
        String sql = "INSERT INTO t SELECT * FROM u;";
        List<InsertStatements.Statement> statements = InsertStatements.parse(sql);
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).columns.isEmpty());
        assertNull(InsertStatements.pairingFor(statements.get(0), 12));
    }

    @Test
    void multipleInsertStatements() {
        String sql = "INSERT INTO a (x, y) VALUES (1, 2);\nINSERT INTO b (p) VALUES (9);";
        List<InsertStatements.Statement> statements = InsertStatements.parse(sql);
        assertEquals(2, statements.size());
        assertEquals(2, statements.get(0).columns.size());
        assertEquals(1, statements.get(1).columns.size());
        // caret in second statement's column pairs only within it
        InsertStatements.Statement second = statements.get(1);
        InsertStatements.Pairing pairing = InsertStatements.pairingFor(second, second.columns.get(0).getStartOffset());
        assertNotNull(pairing);
        assertEquals(1, pairing.sameSlotValues().size());
    }

    @Test
    void functionCallsInValuesStayIntact() {
        String sql = "INSERT INTO t (a, b, c) VALUES (1, CAST('2024' AS int), UPPER('x'));";
        InsertStatements.Statement s = InsertStatements.parse(sql).get(0);
        assertEquals(3, s.tuples.get(0).size());
        TextRange b = s.tuples.get(0).get(1);
        String text = sql.substring(b.getStartOffset(), b.getEndOffset());
        assertEquals("CAST('2024' AS int)", text);
    }

    private static double valueNumber(String text, TextRange range) {
        return Double.parseDouble(text.substring(range.getStartOffset(), range.getEndOffset()));
    }
}
