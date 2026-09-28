package community.intelladb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ListTableModel;
import community.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

/** Reusable results grid (used by the console, table-data tabs and the AI panel). */
public final class ResultsPanel extends JPanel {

    private final Project project;
    private ListTableModel<Object[]> model = new ListTableModel<>();
    private final TableView<Object[]> table = new TableView<>(model);
    private final JBLabel info = new JBLabel("Run a query to see results here", SwingConstants.LEFT);

    public ResultsPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setShowGrid(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setToolTipText("Double-click a cell to view the full value");
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull MouseEvent e) {
                if (e.getClickCount() == 2) {
                    viewSelectedCell();
                }
            }
        });
        info.setBorder(JBUI.Borders.empty(4, 8));

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(info, BorderLayout.SOUTH);
    }

    /** Opens the full cell value; JSON is pretty-printed. */
    private void viewSelectedCell() {
        int row = table.getSelectedRow();
        int column = table.getSelectedColumn();
        if (row < 0 || column < 0 || row >= model.getRowCount() || column >= model.getColumnCount()) {
            return;
        }
        Object[] rowData = model.getItem(row);
        Object cell = rowData != null && rowData.length > column ? rowData[column] : null;
        if (cell == null) {
            return; // NULL has nothing more to show
        }
        String columnName = model.getColumnName(column);
        new CellValueDialog(project, columnName, String.valueOf(cell)).show();
    }

    public void showRunning() {
        info.setText("Running…");
        // Link foreground (theme-aware), not raw blue — which is unreadable in dark themes.
        info.setForeground(com.intellij.ui.JBColor.namedColor("Link.activeForeground",
                new com.intellij.ui.JBColor(new java.awt.Color(0x2470B8), new java.awt.Color(0x6F9FCC))));
    }

    public void showMessage(@NotNull String message) {
        clearGrid();
        info.setText(message);
        info.setForeground(JBUI.CurrentTheme.Label.foreground());
    }

    public void showResult(@NotNull SqlResult result) {
        switch (result.kind) {
            case ROWS -> {
                setGrid(result);
                String note = result.truncated ? " (showing first " + SqlResult.MAX_ROWS + ")" : "";
                info.setText(result.rows.size() + " row" + (result.rows.size() == 1 ? "" : "s") + note
                        + "   ·   " + result.durationMs + " ms");
                info.setForeground(JBUI.CurrentTheme.Label.foreground());
            }
            case UPDATE_COUNT -> {
                clearGrid();
                info.setText("Statement completed   ·   " + result.updateCount + " row(s) affected   ·   "
                        + result.durationMs + " ms");
                info.setForeground(JBUI.CurrentTheme.Label.foreground());
            }
            case MESSAGE -> {
                clearGrid();
                info.setText("OK   ·   " + result.durationMs + " ms");
                info.setForeground(JBUI.CurrentTheme.Label.foreground());
            }
            case ERROR -> {
                clearGrid();
                info.setText("Error: " + result.text);
                info.setForeground(JBUI.CurrentTheme.Label.errorForeground());
            }
        }
    }

    private void setGrid(@NotNull SqlResult result) {
        ColumnInfo<Object[], String>[] columns = new ColumnInfo[result.columns.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = new ValueColumnInfo(i, result.columns.get(i));
        }
        model = new ListTableModel<>(columns, new ArrayList<>(result.rows));
        table.setModelAndUpdateColumns(model);
        int width = Math.max(120, table.getWidth() / Math.max(1, columns.length));
        for (int i = 0; i < columns.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? Math.max(180, width) : width);
        }
    }

    private void clearGrid() {
        model = new ListTableModel<>();
        table.setModelAndUpdateColumns(model);
    }

    private static final class ValueColumnInfo extends ColumnInfo<Object[], String> {
        private final int index;

        ValueColumnInfo(int index, @NotNull String title) {
            super(title);
            this.index = index;
        }

        @Override
        public @Nullable String valueOf(@NotNull Object[] row) {
            Object cell = row.length > index ? row[index] : null;
            return cell == null ? "NULL" : String.valueOf(cell);
        }
    }
}
