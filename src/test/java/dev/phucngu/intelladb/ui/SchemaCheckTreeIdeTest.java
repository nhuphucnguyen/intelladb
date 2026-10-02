package dev.phucngu.intelladb.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JTree;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;

/** The connection dialog's schema picker, clicked like a user would (on the checkbox). */
public class SchemaCheckTreeIdeTest extends BasePlatformTestCase {

    private SchemaCheckTree picker;
    private JTree tree;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        picker = new SchemaCheckTree();
        picker.add("shop", new SchemaCheckTree.Item("shop.public", "public", false), true);
        picker.add("shop", new SchemaCheckTree.Item("shop.sales", "sales", false), false);
        picker.add("hr", new SchemaCheckTree.Item("hr.staff", "staff", false), true);
        picker.reload();
        tree = (JTree) picker.component();
        tree.setSize(400, 400);
    }

    public void testSchemasHangOffTheirDatabase() {
        assertEquals(5, tree.getRowCount()); // shop, public, sales, hr, staff — expanded
        assertEquals(3, picker.schemaCount());
        assertEquals(List.of("shop.public", "hr.staff"), picker.checked());
    }

    public void testCheckingADatabaseChecksItsSchemas() {
        clickCheckbox(0); // shop: partly checked → all
        assertEquals(List.of("shop.public", "shop.sales", "hr.staff"), picker.checked());
    }

    public void testDisabledIgnoresClicks() {
        picker.setEnabled(false);
        clickCheckbox(1);
        assertEquals(List.of("shop.public", "hr.staff"), picker.checked());
    }

    private void clickCheckbox(int row) {
        Rectangle r = tree.getRowBounds(row);
        int x = r.x + 8;
        int y = r.y + r.height / 2;
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            tree.dispatchEvent(new MouseEvent(tree, id, System.currentTimeMillis(), 0, x, y, 1, false,
                    MouseEvent.BUTTON1));
        }
    }
}
