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

    public void testSettersOnUnknownConsoleIdsAreIgnored() {
        ConsoleStore store = ConsoleStore.getInstance(getProject());
        store.loadState(new ConsoleStore.State());
        store.setSql("nope", "SELECT 1");
        store.setOpen("nope", true);
        assertNull(store.find("nope"));
    }
}
