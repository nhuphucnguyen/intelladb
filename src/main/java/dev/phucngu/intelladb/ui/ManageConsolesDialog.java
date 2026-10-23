package dev.phucngu.intelladb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import dev.phucngu.intelladb.connection.ConnectionManager;
import dev.phucngu.intelladb.connection.DbConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Every saved console of every connection in one table — what is left over, when it was
 * last used, what it holds — to reopen the useful ones and delete the rest.
 */
public final class ManageConsolesDialog extends DialogWrapper {

    /** The "Show" filter: everything, only empty consoles, or the ones idle for a while. */
    private enum Filter {
        ALL("All consoles", 0), EMPTY("Empty consoles", 0),
        WEEK("Not used for 7 days", 7), MONTH("Not used for 30 days", 30), QUARTER("Not used for 90 days", 90);

        final String label;
        final int days;

        Filter(String label, int days) {
            this.label = label;
            this.days = days;
        }

        boolean accepts(@NotNull ConsoleStore.ConsoleState console) {
            return switch (this) {
                case ALL -> true;
                case EMPTY -> ConsoleStore.isEmpty(console.sql);
                // Consoles saved before use was tracked count as idle.
                default -> console.lastUsed < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String ALL_CONNECTIONS = "All connections";

    private final Project project;
    private final DbExplorerPanel explorer;
    private final ConsoleStore store;
    private final ConnectionManager manager;
    private final ComboBox<Object> connectionCombo = new ComboBox<>();
    private final ComboBox<Filter> filterCombo = new ComboBox<>(Filter.values());
    private final Model model = new Model();
    private final JBTable table = new JBTable(model);
    private final JBTextArea preview = new JBTextArea();
    private final JButton openButton = new JButton("Open");
    private final JButton deleteButton = new JButton("Delete…");
    private final JBLabel summary = new JBLabel();

    /** {@code connectionId} narrows the list to that connection's consoles at first. */
    public ManageConsolesDialog(@NotNull Project project, @NotNull DbExplorerPanel explorer,
                                @Nullable String connectionId) {
        super(project);
        this.project = project;
        this.explorer = explorer;
        this.store = ConsoleStore.getInstance(project);
        this.manager = ConnectionManager.getInstance(project);
        setTitle("Consoles");
        connectionCombo.addItem(ALL_CONNECTIONS);
        DbConfig selected = null;
        for (DbConfig config : manager.configs()) {
            connectionCombo.addItem(new ConnectionItem(config));
            if (config.id.equals(connectionId)) {
                selected = config;
            }
        }
        if (selected != null) {
            for (int i = 0; i < connectionCombo.getItemCount(); i++) {
                if (connectionCombo.getItemAt(i) instanceof ConnectionItem item && item.config == selected) {
                    connectionCombo.setSelectedIndex(i);
                }
            }
        }
        init();
        reload();
    }

    /** A connection in the combo, shown by name. */
    private record ConnectionItem(DbConfig config) {
        @Override
        public String toString() {
            return config.name.isEmpty() ? config.describe() : config.name;
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        filters.add(new JBLabel("Connection:"));
        filters.add(connectionCombo);
        filters.add(new JBLabel("Show:"));
        filters.add(filterCombo);
        connectionCombo.addActionListener(e -> reload());
        filterCombo.addActionListener(e -> reload());

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setShowGrid(false);
        TableRowSorter<Model> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(Model.LAST_USED, javax.swing.SortOrder.DESCENDING)));
        table.getColumnModel().getColumn(Model.LAST_USED).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                long millis = value instanceof Long l ? l : 0;
                setText(millis > 0 ? DateFormatUtil.formatPrettyDateTime(millis) : "—");
            }
        });
        table.getColumnModel().getColumn(Model.CONNECTION).setPreferredWidth(JBUI.scale(150));
        table.getColumnModel().getColumn(Model.NAME).setPreferredWidth(JBUI.scale(130));
        table.getColumnModel().getColumn(Model.SCOPE).setPreferredWidth(JBUI.scale(110));
        table.getColumnModel().getColumn(Model.LAST_USED).setPreferredWidth(JBUI.scale(120));
        table.getColumnModel().getColumn(Model.LINES).setPreferredWidth(JBUI.scale(50));
        table.getColumnModel().getColumn(Model.OPEN).setPreferredWidth(JBUI.scale(50));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectionChanged();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) {
                    openSelected();
                }
            }
        });
        new TableSpeedSearch(table);

        preview.setEditable(false);
        preview.setFont(UIUtil.getFontWithFallback(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN,
                UIUtil.getLabelFont().getSize()));
        preview.setBorder(JBUI.Borders.empty(4, 6));

        JBSplitter splitter = new JBSplitter(true, "IntellaDb.ManageConsoles.splitter", 0.55f);
        splitter.setFirstComponent(new JBScrollPane(table));
        splitter.setSecondComponent(new JBScrollPane(preview));

        openButton.addActionListener(e -> openSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        buttons.add(openButton);
        buttons.add(deleteButton);
        buttons.add(summary);
        summary.setForeground(UIUtil.getContextHelpForeground());

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(filters, BorderLayout.NORTH);
        panel.add(splitter, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setPreferredSize(JBUI.size(760, 480));
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        getOKAction().putValue(Action.NAME, "Close");
        return new Action[]{getOKAction()};
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return table;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "IntellaDb.ManageConsoles";
    }

    /** Re-reads the store with the current filters, keeping the selection where it can. */
    private void reload() {
        List<String> keep = selectedIds();
        String connectionId = connectionCombo.getSelectedItem() instanceof ConnectionItem item ? item.config.id : null;
        Filter filter = (Filter) filterCombo.getSelectedItem();
        List<ConsoleStore.ConsoleState> rows = new ArrayList<>();
        for (ConsoleStore.ConsoleState console : store.all()) {
            if ((connectionId == null || console.connectionId.equals(connectionId))
                    && (filter == null || filter.accepts(console))) {
                rows.add(console);
            }
        }
        model.setRows(rows);
        for (int i = 0; i < rows.size(); i++) {
            if (keep.contains(rows.get(i).id)) {
                int view = table.convertRowIndexToView(i);
                table.getSelectionModel().addSelectionInterval(view, view);
            }
        }
        if (table.getSelectedRowCount() == 0 && table.getRowCount() > 0) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        }
        selectionChanged();
    }

    private @NotNull List<ConsoleStore.ConsoleState> selected() {
        List<ConsoleStore.ConsoleState> consoles = new ArrayList<>();
        for (int view : table.getSelectedRows()) {
            consoles.add(model.rows.get(table.convertRowIndexToModel(view)));
        }
        return consoles;
    }

    private @NotNull List<String> selectedIds() {
        return selected().stream().map(c -> c.id).toList();
    }

    private void selectionChanged() {
        List<ConsoleStore.ConsoleState> consoles = selected();
        if (consoles.size() == 1) {
            preview.setText(consoles.get(0).sql == null ? "" : consoles.get(0).sql);
            preview.setCaretPosition(0);
        } else {
            preview.setText(consoles.isEmpty() ? "" : consoles.size() + " consoles selected");
        }
        openButton.setEnabled(!consoles.isEmpty()
                && consoles.stream().allMatch(c -> manager.findConfig(c.connectionId) != null));
        deleteButton.setEnabled(!consoles.isEmpty());
        summary.setText(model.rows.size() + " of " + store.all().size() + " consoles");
    }

    private void openSelected() {
        List<String> ids = selectedIds();
        if (ids.isEmpty() || !openButton.isEnabled()) {
            return;
        }
        close(OK_EXIT_CODE);
        ids.forEach(explorer::openConsoleById);
    }

    private void deleteSelected() {
        List<ConsoleStore.ConsoleState> consoles = selected();
        if (consoles.isEmpty()) {
            return;
        }
        if (!consoles.stream().allMatch(c -> ConsoleStore.isEmpty(c.sql))) {
            String what = consoles.size() == 1 ? "console '" + consoles.get(0).name + "'" : consoles.size() + " consoles";
            int answer = Messages.showYesNoDialog(getContentPanel(),
                    "Delete " + what + "? Its SQL is removed and open tabs close.",
                    "Delete Console", Messages.getQuestionIcon());
            if (answer != Messages.YES) {
                return;
            }
        }
        explorer.deleteConsoles(consoles.stream().map(c -> c.id).toList());
        reload();
    }

    /** The table rows: console states, read live (they are edited in place by the store). */
    private final class Model extends AbstractTableModel {
        static final int CONNECTION = 0, NAME = 1, SCOPE = 2, LAST_USED = 3, LINES = 4, OPEN = 5;
        private static final String[] COLUMNS = {"Connection", "Console", "Schema", "Last used", "Lines", "Open"};

        List<ConsoleStore.ConsoleState> rows = List.of();

        void setRows(@NotNull List<ConsoleStore.ConsoleState> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case LAST_USED -> Long.class;
                case LINES -> Integer.class;
                case OPEN -> Boolean.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            ConsoleStore.ConsoleState console = rows.get(row);
            return switch (column) {
                case CONNECTION -> {
                    DbConfig config = manager.findConfig(console.connectionId);
                    yield config == null ? "(deleted connection)" : config.name.isEmpty() ? config.describe() : config.name;
                }
                case NAME -> console.name;
                case SCOPE -> ConsoleStore.scope(console);
                case LAST_USED -> console.lastUsed;
                case LINES -> ConsoleStore.isEmpty(console.sql) ? 0 : (int) console.sql.strip().lines().count();
                case OPEN -> explorer.isConsoleOpen(console.id);
                default -> "";
            };
        }
    }
}
