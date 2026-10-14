package dev.phucngu.intelladb.ui;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.TableMeta;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The text speed search matches in the explorer tree. */
class ConnectionTreeSearchableTextTest {

    private final DbConfig config = new DbConfig();

    private ConnectionTreeSearchableTextTest() {
        config.dialectId = "postgresql";
        config.name = "shop";
        config.host = "db.local";
        config.port = 5432;
        config.database = "shop";
    }

    @Test
    void nodesSearchByTheirLabels() {
        TableMeta table = new TableMeta("owner", TableMeta.Kind.TABLE,
                List.of(new ColumnMeta("id", "int4", false, "", 1, true, "")), "");
        assertEquals("shop", ConnectionTreePanel.searchableText(
                node(new ConnectionTreePanel.ConfigEntry(config))));
        assertEquals("owner", ConnectionTreePanel.searchableText(
                node(new ConnectionTreePanel.TableEntry(config, null, "public", table))));
        assertEquals("public", ConnectionTreePanel.searchableText(
                node(new ConnectionTreePanel.SchemaEntry(config, null, "public"))));
        assertEquals("tables", ConnectionTreePanel.searchableText(
                node(new ConnectionTreePanel.FolderEntry(config, ConnectionTreePanel.Folder.TABLES, "public", 15))));
    }

    @Test
    void columnsSearchByNameAndType() {
        assertEquals("id int4", ConnectionTreePanel.searchableText(
                node(new ConnectionTreePanel.ColumnEntry(config, "public", "owner", "id", "int4", true))));
    }

    @Test
    void unnamedConnectionsSearchByTheirTarget() {
        config.name = "";
        String text = ConnectionTreePanel.searchableText(node(new ConnectionTreePanel.ConfigEntry(config)));
        assertEquals(config.describe(), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("db.local"), text);
    }

    @Test
    void transientStatesSearchAsShown() {
        assertEquals("connecting…", ConnectionTreePanel.searchableText(node("connecting…")));
    }

    private static DefaultMutableTreeNode node(Object userObject) {
        return new DefaultMutableTreeNode(userObject);
    }
}
