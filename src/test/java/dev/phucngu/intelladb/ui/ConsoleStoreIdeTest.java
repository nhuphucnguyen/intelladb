package dev.phucngu.intelladb.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/** Named consoles per connection, with migration from the one-console-per-connection era. */
public class ConsoleStoreIdeTest extends BasePlatformTestCase {

    public void testLegacyConsolesGetIdsAndNamesOnLoad() {
        ConsoleStore store = ConsoleStore.getInstance(getProject());
        store.loadState(new ConsoleStore.State()); // the fixture workspace carries state between methods
        ConsoleStore.State state = new ConsoleStore.State();
        ConsoleStore.ConsoleState legacy = new ConsoleStore.ConsoleState();
        legacy.connectionId = "conn-1";
        legacy.sql = "SELECT 1";
        legacy.open = true;
        state.consoles.add(legacy);
        store.loadState(state);

        ConsoleStore.ConsoleState migrated = store.find("conn-1");
        assertNotNull(migrated);
        assertEquals("console", migrated.name);
        assertEquals("SELECT 1", migrated.sql);
        assertEquals(List.of("conn-1"), store.openConsoles());
    }

    public void testAConnectionOwnsSeveralNamedConsoles() {
        ConsoleStore store = ConsoleStore.getInstance(getProject());
        store.loadState(new ConsoleStore.State()); // the fixture workspace carries state between methods
        ConsoleStore.ConsoleState first = store.create("conn-1");
        assertEquals("console", first.name);
        ConsoleStore.ConsoleState second = store.create("conn-1");
        assertEquals("console 2", second.name);
        assertEquals(2, store.ofConnection("conn-1").size());

        store.setSql(second.id, "SELECT 2");
        store.setName(second.id, "queries");
        store.setName(second.id, "  "); // a blank name changes nothing
        store.setSchema(second.id, "shop", "sales");
        store.setOpen(second.id, true);
        assertEquals(List.of(second.id), store.openConsoles());
        assertEquals("queries", store.find(second.id).name);
        assertEquals("sales", store.find(second.id).schema);

        store.rebind(second.id, "conn-2");
        // Values are frozen into locals: the fixture reloads persisted component state
        // between statements, so re-reading through the service can see a stale snapshot.
        ConsoleStore.ConsoleState rebound = store.find(second.id);
        assertEquals("conn-2", rebound.connectionId);
        assertNull(rebound.schema);
        assertEquals(1, store.ofConnection("conn-1").size());

        store.remove("conn-2");
        assertNull(store.find(second.id));
    }

    public void testDeletingFreesTheNameAndTracksUse() {
        ConsoleStore store = ConsoleStore.getInstance(getProject());
        store.loadState(new ConsoleStore.State());
        long before = System.currentTimeMillis();
        ConsoleStore.ConsoleState first = store.create("conn-1");
        ConsoleStore.ConsoleState second = store.create("conn-1");
        assertTrue(first.lastUsed >= before);

        store.delete(List.of(first.id));
        assertNull(store.find(first.id));
        assertEquals(List.of(second.id), store.ofConnection("conn-1").stream().map(c -> c.id).toList());
        // "console" is free again; "console 2" is still taken.
        String third = store.create("conn-1").name;
        assertEquals("console", third);
        String fourth = store.create("conn-1").name;
        assertEquals("console 3", fourth);

        ConsoleStore.ConsoleState idle = store.find(second.id);
        idle.lastUsed = 1;
        store.touch(second.id);
        long touched = store.find(second.id).lastUsed;
        assertTrue(touched >= before);
        store.find(second.id).lastUsed = 1;
        store.setSql(second.id, "SELECT 1");
        long edited = store.find(second.id).lastUsed;
        assertTrue(edited >= before);
    }

    public void testOnlyBlankOrHeaderOnlyConsolesAreEmpty() {
        assertTrue(ConsoleStore.isEmpty(null));
        assertTrue(ConsoleStore.isEmpty("  \n"));
        assertTrue(ConsoleStore.isEmpty("-- SQL for postgres@localhost\n"));
        assertTrue(ConsoleStore.isEmpty("// MongoDB shell for mongo@localhost, e.g. db.collection.find({})\n\n"));
        assertFalse(ConsoleStore.isEmpty("-- SQL for postgres@localhost\nSELECT 1"));
        assertFalse(ConsoleStore.isEmpty("-- my notes about the migration"));
        assertFalse(ConsoleStore.isEmpty("SELECT 1"));
    }

    public void testScopeAndPreview() {
        ConsoleStore.ConsoleState console = new ConsoleStore.ConsoleState();
        assertEquals("", ConsoleStore.scope(console));
        console.schema = "sales";
        assertEquals("sales", ConsoleStore.scope(console));
        console.database = "shop";
        assertEquals("shop.sales", ConsoleStore.scope(console));
        console.schema = null;
        assertEquals("shop", ConsoleStore.scope(console));

        assertEquals("SELECT * FROM orders", ConsoleStore.preview("-- SQL for pg@localhost\n\n  SELECT * FROM orders\nWHERE 1"));
        assertEquals("", ConsoleStore.preview("-- SQL for pg@localhost\n"));
        assertEquals("", ConsoleStore.preview(null));
    }

    public void testSettersOnUnknownConsoleIdsAreIgnored() {
        ConsoleStore store = ConsoleStore.getInstance(getProject());
        store.loadState(new ConsoleStore.State());
        store.setSql("nope", "SELECT 1");
        store.setOpen("nope", true);
        assertNull(store.find("nope"));
    }
}
