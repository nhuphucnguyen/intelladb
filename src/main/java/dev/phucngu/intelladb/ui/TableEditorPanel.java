package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.JBTabsFactory;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.TabsListener;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.util.List;

/**
 * A table's editor tab: its rows beside its DDL — IntelliJ's DATA_EDITOR / CODE_EDITOR
 * faces of one object. The DDL is regenerated each time its tab is selected, so it
 * reflects the catalog's current view of the table.
 */
final class TableEditorPanel extends javax.swing.JPanel implements Disposable {

    private final Project project;
    private final DbExplorerPanel explorer;
    private final TableRef table;
    private final Document ddlDocument = EditorFactory.getInstance().createDocument("");
    private final EditorEx ddlViewer;

    TableEditorPanel(@NotNull Project project, @NotNull DbExplorerPanel explorer,
                     @NotNull TableRef table, @NotNull JComponent data) {
        super(new BorderLayout());
        this.project = project;
        this.explorer = explorer;
        this.table = table;
        this.ddlViewer = (EditorEx) EditorFactory.getInstance().createViewer(ddlDocument, project);

        JBTabs tabs = JBTabsFactory.createTabs(project, this);
        tabs.addTab(new TabInfo(data).setText("Data").setIcon(AllIcons.Actions.Preview));
        TabInfo ddlTab =
                new TabInfo(ddlViewer.getComponent()).setText("DDL").setIcon(AllIcons.Actions.EditSource);
        tabs.addTab(ddlTab);
        tabs.addListener(new TabsListener() {
            @Override
            public void selectionChanged(@Nullable TabInfo oldSelection, @Nullable TabInfo newSelection) {
                if (newSelection == ddlTab) {
                    refreshDdl();
                }
            }
        });
        add(tabs.getComponent(), BorderLayout.CENTER);
    }

    /** Regenerates the CREATE statement from the live catalog (falling back to the tree's snapshot). */
    private void refreshDdl() {
        String ddl = table.config().dialect().describeSchema(new SchemaCatalog(List.of(
                new SchemaCatalog.Schema(table.schema(), List.of(currentMeta())))));
        if (ddl.equals(ddlDocument.getText())) {
            return;
        }
        ddlDocument.setReadOnly(false);
        ApplicationManager.getApplication().runWriteAction(() -> ddlDocument.setText(ddl));
        ddlDocument.setReadOnly(true);
    }

    /** The table's live metadata when the catalog has it, else the snapshot the tree opened with. */
    private @NotNull TableMeta currentMeta() {
        DbSession session = explorer.sessionOf(table.config());
        SchemaCatalog catalog = session == null ? null : session.catalog();
        if (catalog != null) {
            for (SchemaCatalog.Schema schema : catalog.forDatabase(table.database()).schemas()) {
                if (!schema.name().equals(table.schema())) {
                    continue;
                }
                for (TableMeta.Kind kind : TableMeta.Kind.values()) {
                    for (TableMeta meta : schema.tablesOf(kind)) {
                        if (meta.name.equals(table.name())) {
                            return meta;
                        }
                    }
                }
            }
        }
        return table.meta();
    }

    @Override
    public void dispose() {
        EditorFactory.getInstance().releaseEditor(ddlViewer);
    }
}
