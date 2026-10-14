package dev.phucngu.intelladb.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** The eye menu's flags, persisted per project. */
public class TreeViewOptionsIdeTest extends BasePlatformTestCase {

    public void testFreshProjectGetsTheDefaults() {
        assertEquals(TreeViewOptions.DEFAULTS, TreeViewOptions.load(getProject()));
    }

    public void testSaveRoundTrips() {
        TreeViewOptions options = new TreeViewOptions(false, false, true, false, false);
        options.save(getProject());
        assertEquals(options, TreeViewOptions.load(getProject()));
    }

    public void testSavingDefaultsStoresNothing() {
        new TreeViewOptions(false, false, true, false, false).save(getProject());
        TreeViewOptions.DEFAULTS.save(getProject());
        PropertiesComponent properties = PropertiesComponent.getInstance(getProject());
        assertFalse(properties.isValueSet("IntellaDb.ViewOptions.groupByKind"));
        assertFalse(properties.isValueSet("IntellaDb.ViewOptions.emptyGroups"));
        assertEquals(TreeViewOptions.DEFAULTS, TreeViewOptions.load(getProject()));
    }
}
