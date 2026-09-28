package dev.phucngu.intelladb;

import dev.phucngu.intelladb.util.JsonText;
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

    @Test
    void sortsPropertiesAscendingAtEveryLevel() {
        String json = "{\"b\":1,\"a\":{\"z\":1,\"Y\":2},\"C\":[{\"q\":1,\"p\":2}]}";
        assertEquals("{\n  \"a\": {\n    \"Y\": 2,\n    \"z\": 1\n  },\n  \"b\": 1,\n"
                        + "  \"C\": [\n    {\n      \"p\": 2,\n      \"q\": 1\n    }\n  ]\n}",
                JsonText.prettyIfJson(json, JsonText.KeyOrder.ASCENDING));
    }

    @Test
    void sortsPropertiesDescendingAndKeepsArrayOrder() {
        String json = "[3,1,{\"a\":null,\"b\":true}]";
        assertEquals("[\n  3,\n  1,\n  {\n    \"b\": true,\n    \"a\": null\n  }\n]",
                JsonText.prettyIfJson(json, JsonText.KeyOrder.DESCENDING));
    }

    @Test
    void originalOrderIsUnchanged() {
        assertEquals("{\n  \"b\": 1,\n  \"a\": 2\n}",
                JsonText.prettyIfJson("{\"b\":1,\"a\":2}", JsonText.KeyOrder.ORIGINAL));
    }
}
