package community.intelladb.ui;

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
import java.util.ArrayList;

/** Reusable results grid (used by the console, table-data tabs and the AI panel). */
public final class ResultsPanel extends JPanel {

    private ListTableModel<Object[]> model = new ListTableModel<>();
    private final TableView<Object[]> table = new TableView<>(model);
    private final JBLabel info = new JBLabel("Run a query to see results here", SwingConstants.LEFT);

    public ResultsPanel() {
        super(new BorderLayout());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setShowGrid(true);
        table.getTableHeader().setReorderingAllowed(false);
        info.setBorder(JBUI.Borders.empty(4, 8));

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(info, BorderLayout.SOUTH);
    }

    public void showRunning() {
        info.setText("Running…");
        info.setForeground(com.intellij.ui.JBColor.BLUE);
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
