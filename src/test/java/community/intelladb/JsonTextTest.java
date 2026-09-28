package community.intelladb;

import community.intelladb.util.JsonText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTextTest {

    @Test
    void detectsJsonObjectsAndArrays() {
        assertTrue(JsonText.isJson("{\"a\": 1}"));
        assertTrue(JsonText.isJson("  [1, 2, 3]  "));
        assertFalse(JsonText.isJson("just a string"));
        assertFalse(JsonText.isJson("42"));
        assertFalse(JsonText.isJson(""));
        assertFalse(JsonText.isJson("{not json}"));
    }

    @Test
    void prettyPrintsJson() {
        String pretty = JsonText.prettyIfJson("{\"name\":\"Grace\",\"tags\":[\"pioneer\",\"admiral\"]}");
        assertEquals("{\n  \"name\": \"Grace\",\n  \"tags\": [\n    \"pioneer\",\n    \"admiral\"\n  ]\n}", pretty);
    }

    @Test
    void leavesNonJsonUntouched() {
        assertEquals("hello world", JsonText.prettyIfJson("hello world"));
        assertEquals("{unclosed", JsonText.prettyIfJson("{unclosed"));
    }

    @Test
    void prettyPrintsNestedJson() {
        String pretty = JsonText.prettyIfJson("{\"a\":{\"b\":[1,2]}}");
        assertTrue(pretty.contains("  \"a\": {"));
        assertTrue(pretty.contains("    \"b\": ["));
    }
}
