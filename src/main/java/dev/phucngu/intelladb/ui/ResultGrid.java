package dev.phucngu.intelladb.ui;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.phucngu.intelladb.IntellaDbIcons;
import dev.phucngu.intelladb.util.JsonText;
import dev.phucngu.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Data grid in the style of IntelliJ's database results: a frozen row-number gutter,
 * column headers with a column icon (type in the tooltip), {@code <null>} in grey
 * italics, right-aligned numbers, the editor font, click-to-sort headers and cell
 * selection (Ctrl/Cmd+C copies the selected cells as TSV). Clicking row numbers selects
 * whole rows (Shift+click for a range, Cmd/Ctrl+click to toggle). Double-click opens the
 * full value — or, when the result can be written back ({@link #setWritableColumns}),
 * edits the cell in place (Shift+Enter, the JSON badge and multi-line cells open the value
 * in an editable dialog instead); cells can be set to NULL
 * and rows marked deleted (struck through). Edits stay pending, highlighted, until they
 * are accepted (submitted) or reverted.
 */
final class ResultGrid extends JBTable {

    private static final int MAX_CELL_CHARS = 1000;
    private static final int MIN_COLUMN_WIDTH = 50;
    private static final int MAX_COLUMN_WIDTH = 360;
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int2", "int4", "int8", "smallint", "integer", "bigint", "serial", "bigserial", "smallserial",
            "numeric", "decimal", "float4", "float8", "real", "double precision", "money", "oid",
            // MySQL / MariaDB (bit is deliberately absent: it is a bit string)
            "tinyint", "mediumint", "int", "float", "double", "dec", "fixed");

    private static final int CELL_PADDING = 6;
    private static final int BADGE_MARGIN = 3;
    /** JSON cells longer than this are not parsed for the icon (they can still be opened). */
    private static final int MAX_JSON_SNIFF = 1_000_000;

    private enum JsonKind { NONE, OBJECT, ARRAY }

    private final Project project;
    private final RowHeader rowHeader = new RowHeader();
    /** Per-cell JSON detection, computed lazily once per result (key: modelRow * columns + modelColumn). */
    private final java.util.Map<Long, JsonKind> jsonCells = new java.util.HashMap<>();
    private GridModel model = new GridModel(List.of(), List.of(), List.of());
    /** Model indexes of the columns that are the source table's primary key. */
    private java.util.Set<Integer> keyColumns = java.util.Set.of();

    ResultGrid(@NotNull Project project) {
        this.project = project;
        setModel(model);
        setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        setCellSelectionEnabled(true);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setShowGrid(true);
        setGridColor(JBColor.border());
        getTableHeader().setReorderingAllowed(true);
        getEmptyText().setText("No rows");
        setDefaultRenderer(Object.class, new CellRenderer());
        setDefaultEditor(Object.class, new CellEditor());
        applyEditorFont();
        getSelectionModel().addListSelectionListener(e -> rowHeader.repaint());
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int column = columnAtPoint(e.getPoint());
                boolean edits = row >= 0 && column >= 0 && isCellEditable(row, column);
                if ((e.getClickCount() == 2 && !edits) || (e.getClickCount() == 1 && onJsonIcon(e.getPoint()))) {
                    viewCell(row, column);
                }
            }

            @Override
            public void mouseMoved(@NotNull MouseEvent e) {
                boolean onIcon = onJsonIcon(e.getPoint());
                setCursor(onIcon ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) : null);
                setHoveredIcon(onIcon ? rowAtPoint(e.getPoint()) : -1, onIcon ? columnAtPoint(e.getPoint()) : -1);
            }

            @Override
            public void mouseExited(@NotNull MouseEvent e) {
                setHoveredIcon(-1, -1);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.InputEvent.SHIFT_DOWN_MASK), "intelladb.viewCell");
        getActionMap().put("intelladb.viewCell", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(@NotNull java.awt.event.ActionEvent e) {
                viewCell(getSelectedRow(), getSelectedColumn());
            }
        });
    }

    /** Wraps the grid in a scroll pane whose row header is the frozen row-number gutter. */
    @NotNull JBScrollPane wrapInScrollPane() {
        scroll = new JBScrollPane(this);
        scroll.setRowHeaderView(rowHeader);
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    @Nullable private JBScrollPane scroll;
    /** True while the grid shows one row per result column (read-only). */
    private boolean transposed;
    /** Columns removed by the column chooser, restored on demand. */
    private final List<javax.swing.table.TableColumn> hiddenColumns = new java.util.ArrayList<>();

    void setResult(@Nullable SqlResult result) {
        model = result == null
                ? new GridModel(List.of(), List.of(), List.of())
                : new GridModel(result.columns, result.columnTypes, result.rows);
        jsonCells.clear();
        keyColumns = java.util.Set.of();
        setModel(model);
        model.addTableModelListener(e -> jsonCells.clear()); // an edit can make a cell (non-)JSON
        TableRowSorter<GridModel> sorter = new TableRowSorter<>(model);
        for (int c = 0; c < model.getColumnCount(); c++) {
            sorter.setComparator(c, ResultGrid::compareValues);
        }
        sorter.addRowSorterListener(e -> rowHeader.refresh());
        if (transposed) {
            setRowSorter(null);
            setModel(new TransposedModel(model));
        } else {
            setRowSorter(sorter);
        }
        hiddenColumns.clear(); // a fresh model brings fresh columns
        installHeaderRenderer();
        fitColumns();
        rowHeader.refresh();
    }

    // ------------------------------------------------------------------ view options

    /** Flips the grid so each result column is a row — a read-only view, like IntelliJ's transpose. */
    void setTransposed(boolean transpose) {
        if (transposed == transpose || model == null) {
            return;
        }
        transposed = transpose;
        if (transpose) {
            setRowSorter(null);
            setModel(new TransposedModel(model));
        } else {
            setModel(model);
            TableRowSorter<GridModel> sorter = new TableRowSorter<>(model);
            for (int c = 0; c < model.getColumnCount(); c++) {
                sorter.setComparator(c, ResultGrid::compareValues);
            }
            sorter.addRowSorterListener(e -> rowHeader.refresh());
            setRowSorter(sorter);
        }
        installHeaderRenderer();
        fitColumns();
        if (scroll != null) {
            scroll.setRowHeaderView(transposed ? null : rowHeader);
        }
        rowHeader.refresh();
    }

    boolean isTransposed() {
        return transposed;
    }

    /** Hides or restores the column (model index) — the column chooser. */
    void setColumnVisible(int modelIndex, boolean visible) {
        if (transposed) {
            return;
        }
        if (!visible) {
            for (int view = 0; view < getColumnCount(); view++) {
                if (convertColumnIndexToModel(view) == modelIndex) {
                    javax.swing.table.TableColumn column = getColumnModel().getColumn(view);
                    hiddenColumns.add(column);
                    removeColumn(column);
                    return;
                }
            }
        } else {
            java.util.Iterator<javax.swing.table.TableColumn> it = hiddenColumns.iterator();
            while (it.hasNext()) {
                javax.swing.table.TableColumn column = it.next();
                if (column.getModelIndex() == modelIndex) {
                    it.remove();
                    addColumn(column);
                    break;
                }
            }
            restoreColumnOrder();
        }
    }

    boolean isColumnVisible(int modelIndex) {
        for (int view = 0; view < getColumnCount(); view++) {
            if (convertColumnIndexToModel(view) == modelIndex) {
                return true;
            }
        }
        return false;
    }

    void showAllColumns() {
        for (javax.swing.table.TableColumn column : new java.util.ArrayList<>(hiddenColumns)) {
            addColumn(column);
        }
        hiddenColumns.clear();
        restoreColumnOrder();
    }

    /** Puts columns back in model order after re-adding hidden ones. */
    private void restoreColumnOrder() {
        for (int target = 0; target < getColumnCount(); target++) {
            for (int view = target; view < getColumnCount(); view++) {
                if (convertColumnIndexToModel(view) == target) {
                    if (view != target) {
                        moveColumn(view, target);
                    }
                    break;
                }
            }
        }
    }

    /** Clears the header-click sort order. */
    void resetSort() {
        if (getRowSorter() != null) {
            getRowSorter().setSortKeys(null);
        }
    }

    /** Whether the column (model index) holds numbers — the statistics footer aggregates these. */
    boolean isColumnNumeric(int modelIndex) {
        return isNumericColumn(modelIndex);
    }

    /**
     * The rows statistics are computed over — the selection when there is one, else every
     * row — as model-column value arrays.
     */
    @NotNull List<Object[]> statsRows() {
        int[] selected = getSelectedRows();
        List<Object[]> rows = new java.util.ArrayList<>(Math.max(1, selected.length));
        if (selected.length == 0) {
            for (int view = 0; view < getRowCount(); view++) {
                rows.add(rowValues(convertRowIndexToModel(view)));
            }
        } else {
            for (int view : selected) {
                rows.add(rowValues(convertRowIndexToModel(view)));
            }
        }
        return rows;
    }

    /** The grid flipped: one row per column of the result, read-only; column 0 names them. */
    private static final class TransposedModel extends javax.swing.table.AbstractTableModel {
        private final GridModel source;

        TransposedModel(@NotNull GridModel source) {
            this.source = source;
        }

        @Override
        public int getRowCount() {
            return source.getColumnCount();
        }

        @Override
        public int getColumnCount() {
            return source.getRowCount() + 1;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Column" : "Row " + column;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return column == 0 ? source.getColumnName(row) : source.getValueAt(column - 1, row);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }

    /** Marks the columns (model indexes) that are the source table's primary key in their headers. */
    void setKeyColumns(@NotNull java.util.Set<Integer> columns) {
        keyColumns = columns;
        getTableHeader().repaint();
    }

    // ------------------------------------------------------------------ editing

    /**
     * Makes the columns flagged in {@code writable} (by model index) editable, or none for
     * null; {@code onChange} runs whenever the set of pending edits changes.
     */
    void setWritableColumns(boolean @Nullable [] writable, @NotNull Runnable onChange) {
        model.writable = writable;
        model.onChange = onChange;
    }

    /** While locked (edits being submitted) no cell can be edited. */
    void setEditsLocked(boolean locked) {
        model.locked = locked;
    }

    /**
     * Whether loaded rows accept edits and deletion — false when they can't be found again
     * (no key in the result) while new rows can still be added and inserted.
     */
    void setLoadedRowsEditable(boolean editable) {
        model.loadedRowsEditable = editable;
    }

    boolean loadedRowsEditable() {
        return model.loadedRowsEditable;
    }

    /** Whether the selection touches a new (not yet inserted) row. */
    boolean selectionIncludesInsertedRow() {
        for (int view : getSelectedRows()) {
            if (model.isInserted(convertRowIndexToModel(view))) {
                return true;
            }
        }
        return false;
    }

    boolean hasEdits() {
        return !model.edits.isEmpty() || !model.deleted.isEmpty() || !model.inserted.isEmpty();
    }

    /** Rows appended with Add Row, waiting to be inserted by Submit. */
    int insertedRowCount() {
        return model.inserted.size();
    }

    /** The typed cells of every new row (model column → value), rows in the order added. */
    @NotNull List<java.util.Map<Integer, Object>> insertedRows() {
        List<java.util.Map<Integer, Object>> copy = new java.util.ArrayList<>();
        model.inserted.forEach(cells -> copy.add(new java.util.TreeMap<>(cells)));
        return copy;
    }

    /** Appends an empty new row at the end, ready to be typed into. */
    void addInsertedRow() {
        int index = model.inserted.size();
        model.inserted.add(new java.util.HashMap<>());
        int row = model.rows.size() + index;
        model.fireTableRowsInserted(row, row);
        rowHeader.refresh();
        model.onChange.run();
    }

    int deletedRowCount() {
        return model.deleted.size();
    }

    /** Rows marked for deletion (model indices, ascending). */
    @NotNull java.util.SortedSet<Integer> deletedRows() {
        return new java.util.TreeSet<>(model.deleted);
    }

    /** Whether the result is editable at all (rows can then be deleted and cells set to NULL). */
    boolean isWritable() {
        return !transposed && model.writable != null && !model.locked;
    }

    /** Sets every editable selected cell to NULL. */
    void setSelectedCellsNull() {
        if (!finishCellEditing()) {
            return;
        }
        for (int row : getSelectedRows()) {
            for (int column : getSelectedColumns()) {
                if (isCellSelected(row, column)
                        && model.isWritable(convertRowIndexToModel(row), convertColumnIndexToModel(column))) {
                    setValueAt(null, row, column);
                }
            }
        }
    }

    /**
     * Marks the rows touched by the selection deleted; their pending edits are dropped. A new
     * (not yet inserted) row is discarded outright instead.
     */
    void deleteSelectedRows() {
        if (!isWritable() || !finishCellEditing()) {
            return;
        }
        java.util.List<Integer> discardInserted = new java.util.ArrayList<>();
        for (int view : getSelectedRows()) {
            int row = convertRowIndexToModel(view);
            if (model.isInserted(row)) {
                discardInserted.add(row - model.rows.size());
            } else {
                model.deleted.add(row);
                model.edits.remove(row);
            }
        }
        if (!discardInserted.isEmpty()) {
            discardInserted.sort(java.util.Comparator.reverseOrder()); // descending: removing shifts the rest
            discardInserted.forEach(index -> model.inserted.remove((int) index));
            jsonCells.clear();
            model.fireTableDataChanged();
            rowHeader.refresh();
        } else {
            repaint();
            rowHeader.repaint();
        }
        model.onChange.run();
    }

    int editedCellCount() {
        return model.edits.values().stream().mapToInt(java.util.Map::size).sum();
    }

    /** Pending edits: model row → (model column → new value), rows in model order. */
    @NotNull java.util.SortedMap<Integer, java.util.Map<Integer, Object>> edits() {
        java.util.TreeMap<Integer, java.util.Map<Integer, Object>> copy = new java.util.TreeMap<>();
        model.edits.forEach((row, cells) -> copy.put(row, new java.util.TreeMap<>(cells)));
        return copy;
    }

    /** Row {@code modelRow} as loaded, without its pending edits. */
    @NotNull Object[] loadedRow(int modelRow) {
        return model.rows.get(modelRow);
    }

    /** Commits the in-progress cell edit, if any; false when the editor rejected its value. */
    boolean finishCellEditing() {
        return !isEditing() || getCellEditor().stopCellEditing();
    }

    /** The edits were written to the database: they become the loaded values, deleted rows go,
     * and inserted rows join the loaded ones with the values they were given. */
    void acceptEdits() {
        model.edits.forEach((row, cells) -> cells.forEach((column, value) -> model.rows.get(row)[column] = value));
        boolean removed = !model.deleted.isEmpty();
        new java.util.TreeSet<>(model.deleted).descendingSet().forEach(row -> model.rows.remove((int) row));
        model.deleted.clear();
        if (!model.inserted.isEmpty()) {
            for (java.util.Map<Integer, Object> cells : model.inserted) {
                Object[] values = new Object[model.getColumnCount()];
                cells.forEach((column, value) -> {
                    if (column < values.length) {
                        values[column] = value;
                    }
                });
                model.rows.add(values);
            }
            model.inserted.clear();
            removed = true;
        }
        if (removed) {
            clearSelection();
            model.fireTableDataChanged();
            rowHeader.refresh();
        }
        clearEdits();
    }

    void revertEdits() {
        if (isEditing()) {
            getCellEditor().cancelCellEditing();
        }
        clearEdits();
    }

    private void clearEdits() {
        model.edits.clear();
        model.deleted.clear();
        model.inserted.clear();
        jsonCells.clear();
        repaint();
        rowHeader.repaint();
        model.onChange.run();
    }

    private final class CellEditor extends javax.swing.DefaultCellEditor {
        CellEditor() {
            super(new com.intellij.ui.components.JBTextField());
            setClickCountToStart(2);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            Component component = super.getTableCellEditorComponent(table, value == null ? "" : String.valueOf(value),
                    selected, row, column);
            component.setFont(table.getFont());
            return component;
        }
    }

    // ------------------------------------------------------------------ row access

    /** All rows in the order shown (after sorting). */
    @NotNull List<Object[]> rowsInViewOrder() {
        List<Object[]> rows = new java.util.ArrayList<>(getRowCount());
        for (int view = 0; view < getRowCount(); view++) {
            rows.add(rowValues(convertRowIndexToModel(view)));
        }
        return rows;
    }

    /** Rows touched by the selection (any selected cell counts), in the order shown. */
    @NotNull List<Object[]> selectedRowsInViewOrder() {
        int[] selected = getSelectedRows();
        java.util.Arrays.sort(selected);
        List<Object[]> rows = new java.util.ArrayList<>(selected.length);
        for (int view : selected) {
            rows.add(rowValues(convertRowIndexToModel(view)));
        }
        return rows;
    }

    /** Row {@code modelRow} as a value array: loaded (with its edits applied by the model) or new. */
    private @NotNull Object[] rowValues(int modelRow) {
        if (model.isInserted(modelRow)) {
            Object[] values = new Object[model.getColumnCount()];
            model.inserted.get(modelRow - model.rows.size()).forEach((column, value) -> {
                if (column < values.length) {
                    values[column] = value;
                }
            });
            return values;
        }
        return model.rows.get(modelRow);
    }

    /** Selects whole rows {@code from..to} (view indices, any order), optionally adding to the selection. */
    private void selectRows(int from, int to, boolean add) {
        if (getColumnCount() == 0) {
            return;
        }
        if (add) {
            addRowSelectionInterval(from, to);
        } else {
            setRowSelectionInterval(from, to);
        }
        setColumnSelectionInterval(0, getColumnCount() - 1);
    }

    // ------------------------------------------------------------------ JSON cells

    private @NotNull JsonKind jsonKind(int viewRow, int viewColumn) {
        if (transposed) {
            return JsonKind.NONE; // badges are laid out for the normal orientation
        }
        int modelRow = convertRowIndexToModel(viewRow);
        int modelColumn = convertColumnIndexToModel(viewColumn);
        long key = (long) modelRow * Math.max(1, model.getColumnCount()) + modelColumn;
        return jsonCells.computeIfAbsent(key, k -> detectJson(model.getValueAt(modelRow, modelColumn)));
    }

    private static @NotNull JsonKind detectJson(@Nullable Object value) {
        if (!(value instanceof String text) || text.length() > MAX_JSON_SNIFF) {
            return JsonKind.NONE;
        }
        String trimmed = text.stripLeading();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("[")) || !JsonText.isJson(text)) {
            return JsonKind.NONE;
        }
        return trimmed.startsWith("{") ? JsonKind.OBJECT : JsonKind.ARRAY;
    }

    /**
     * The "open in viewer" affordance of a JSON cell: a small "JSON" badge overlaid on the
     * cell's right edge — plain text reads better than the tiny expand icon did.
     */
    private static final String JSON_BADGE = "JSON";
    private static final JBColor BADGE_BACKGROUND = new JBColor(new Color(0xDFE8F8), new Color(0x2E3F5E));
    private static final JBColor BADGE_HOVER_BACKGROUND = new JBColor(new Color(0xC2D4F2), new Color(0x3C5480));
    private static final JBColor EDITED_BACKGROUND = new JBColor(new Color(0xD5E8FF), new Color(0x2B4058));
    private static final JBColor DELETED_BACKGROUND = new JBColor(new Color(0xFBDADA), new Color(0x4A2E2E));
    private static final JBColor NEW_ROW_BACKGROUND = new JBColor(new Color(0xDFEEDA), new Color(0x2E4632));
    private static final JBColor BADGE_FOREGROUND = new JBColor(new Color(0x2A5DB0), new Color(0xA9C6F5));

    private static @NotNull Font badgeFont(@NotNull Font cellFont) {
        return cellFont.deriveFont(Font.BOLD, cellFont.getSize2D() * 0.8f);
    }

    /** Badge width (text plus horizontal insets) for the given cell font. */
    private int badgeWidth(@NotNull Font cellFont) {
        return getFontMetrics(badgeFont(cellFont)).stringWidth(JSON_BADGE) + JBUI.scale(8);
    }

    private int hoveredRow = -1;
    private int hoveredColumn = -1;

    private void setHoveredIcon(int row, int column) {
        if (row == hoveredRow && column == hoveredColumn) {
            return;
        }
        repaintCell(hoveredRow, hoveredColumn);
        hoveredRow = row;
        hoveredColumn = column;
        repaintCell(row, column);
    }

    private void repaintCell(int row, int column) {
        if (row >= 0 && column >= 0 && row < getRowCount() && column < getColumnCount()) {
            repaint(getCellRect(row, column, false));
        }
    }

    /** Is the point on the "JSON" badge of a JSON cell? A click there opens the JSON viewer. */
    private boolean onJsonIcon(@NotNull java.awt.Point point) {
        int row = rowAtPoint(point);
        int column = columnAtPoint(point);
        if (row < 0 || column < 0) {
            return false;
        }
        if (getValueAt(row, column) == null || jsonKind(row, column) == JsonKind.NONE) {
            return false;
        }
        java.awt.Rectangle cell = getCellRect(row, column, false);
        int badgeStart = cell.x + cell.width - JBUI.scale(BADGE_MARGIN) - badgeWidth(getFont());
        return point.x >= badgeStart - JBUI.scale(2);
    }

    @Override
    public String getToolTipText(@NotNull MouseEvent event) {
        return onJsonIcon(event.getPoint()) ? "Open JSON viewer" : super.getToolTipText(event);
    }

    // ------------------------------------------------------------------ rendering

    private void applyEditorFont() {
        Font font = EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
        setFont(font);
        setRowHeight(getFontMetrics(font).getHeight() + JBUI.scale(6));
        rowHeader.setFont(font);
        rowHeader.setRowHeight(getRowHeight());
    }

    /** "DECIMAL(10,2) UNSIGNED ZEROFILL" → "decimal": lower-case, without precision and modifiers. */
    static @NotNull String baseTypeName(@NotNull String type) {
        String name = type.toLowerCase().replaceAll("\\(.*?\\)", "").replace(" unsigned", "").replace(" zerofill", "");
        return name.strip();
    }

    private boolean isNumericColumn(int modelColumn) {
        if (NUMERIC_TYPES.contains(baseTypeName(model.type(modelColumn)))) {
            return true;
        }
        for (int r = 0; r < Math.min(20, model.getRowCount()); r++) {
            Object value = model.getValueAt(r, modelColumn);
            if (value != null) {
                return value instanceof Number;
            }
        }
        return false;
    }

    private static @NotNull String display(@NotNull Object value) {
        String text = String.valueOf(value);
        if (text.length() > MAX_CELL_CHARS) {
            text = text.substring(0, MAX_CELL_CHARS) + "…";
        }
        return text.replace("\r\n", "↵").replace('\n', '↵').replace('\r', '↵');
    }

    private final class CellRenderer extends ColoredTableCellRenderer {
        /** Overlay the "JSON" badge (see {@link #paintComponent}); false for non-JSON cells. */
        private boolean jsonBadge;
        private boolean badgeHovered;

        @Override
        protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value, boolean selected,
                                             boolean hasFocus, int row, int column) {
            setFont(table.getFont());
            setBorder(JBUI.Borders.empty(0, CELL_PADDING));
            boolean numeric = isNumericColumn(table.convertColumnIndexToModel(column));
            setTextAlign(numeric ? SwingConstants.RIGHT : SwingConstants.LEFT);
            jsonBadge = value != null && jsonKind(row, column) != JsonKind.NONE;
            badgeHovered = row == hoveredRow && column == hoveredColumn;
            int modelRow = table.convertRowIndexToModel(row);
            boolean deleted = model.deleted.contains(modelRow);
            boolean newRow = model.isInserted(modelRow);
            if (!selected && newRow) {
                setBackground(NEW_ROW_BACKGROUND);
            } else if (!selected && deleted) {
                setBackground(DELETED_BACKGROUND);
            } else if (!selected && model.isEdited(modelRow, table.convertColumnIndexToModel(column))) {
                setBackground(EDITED_BACKGROUND);
            }
            int strike = deleted ? SimpleTextAttributes.STYLE_STRIKEOUT : 0;
            if (value == null) {
                append("<null>", new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC | strike,
                        SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor()));
            } else {
                append(display(value), deleted
                        ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN | strike, null)
                        : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (!jsonBadge) {
                return;
            }
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                com.intellij.ide.ui.UISettings.setupAntialiasing(g2);
                Font font = badgeFont(getFont());
                java.awt.FontMetrics metrics = g2.getFontMetrics(font);
                int width = badgeWidth(getFont());
                int height = Math.min(getHeight() - JBUI.scale(4), metrics.getHeight() + JBUI.scale(2));
                int x = getWidth() - JBUI.scale(BADGE_MARGIN) - width;
                int y = (getHeight() - height) / 2;
                // Blank out the text under (and just before) the badge so it overlays cleanly.
                g2.setColor(getBackground());
                g2.fillRect(x - JBUI.scale(4), 0, getWidth() - x + JBUI.scale(4), getHeight());
                g2.setColor(badgeHovered ? BADGE_HOVER_BACKGROUND : BADGE_BACKGROUND);
                int arc = JBUI.scale(6);
                g2.fillRoundRect(x, y, width, height, arc, arc);
                g2.setColor(BADGE_FOREGROUND);
                g2.setFont(font);
                int textX = x + (width - metrics.stringWidth(JSON_BADGE)) / 2;
                int textY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(JSON_BADGE, textX, textY);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Keeps the platform header look (sort arrows) and adds the column (or key column) icon + type tooltip. */
    private void installHeaderRenderer() {
        TableCellRenderer base = getTableHeader().getDefaultRenderer();
        for (int c = 0; c < getColumnModel().getColumnCount(); c++) {
            TableColumn column = getColumnModel().getColumn(c);
            int modelIndex = column.getModelIndex();
            column.setHeaderRenderer((table, value, selected, focus, row, col) -> {
                Component component = base.getTableCellRendererComponent(table, value, selected, focus, row, col);
                if (component instanceof JLabel label) {
                    boolean key = keyColumns.contains(modelIndex);
                    label.setIcon(key ? IntellaDbIcons.PRIMARY_KEY_COLUMN : IntellaDbIcons.COLUMN);
                    label.setHorizontalAlignment(SwingConstants.LEFT);
                    String type = model.type(modelIndex);
                    label.setToolTipText((type.isEmpty() ? String.valueOf(value) : value + " : " + type)
                            + (key ? " (primary key)" : ""));
                }
                return component;
            });
        }
    }

    /** Sizes each column to its header and first rows, within sane bounds. */
    private void fitColumns() {
        FontMetrics cellMetrics = getFontMetrics(getFont());
        FontMetrics headerMetrics = getTableHeader().getFontMetrics(getTableHeader().getFont());
        int headerExtra = JBUI.scale(16 + 6 + 28); // icon + gap + sort arrow & padding
        for (int c = 0; c < getColumnModel().getColumnCount(); c++) {
            TableColumn column = getColumnModel().getColumn(c);
            int modelIndex = column.getModelIndex();
            int width = headerMetrics.stringWidth(model.getColumnName(modelIndex)) + headerExtra;
            for (int r = 0; r < Math.min(200, model.getRowCount()); r++) {
                Object value = model.getValueAt(r, modelIndex);
                String text = value == null ? "<null>" : display(value);
                width = Math.max(width, cellMetrics.stringWidth(text) + JBUI.scale(16));
                if (width >= MAX_COLUMN_WIDTH) {
                    break;
                }
            }
            column.setPreferredWidth(Math.max(JBUI.scale(MIN_COLUMN_WIDTH),
                    Math.min(JBUI.scale(MAX_COLUMN_WIDTH), width)));
        }
    }

    // ------------------------------------------------------------------ behaviour

    private void viewCell(int viewRow, int viewColumn) {
        if (viewRow < 0 || viewColumn < 0) {
            return;
        }
        Object value = getValueAt(viewRow, viewColumn);
        boolean writable = model.isWritable(convertRowIndexToModel(viewRow), convertColumnIndexToModel(viewColumn));
        if (value == null && !writable) { // a read-only NULL has nothing more to show
            return;
        }
        if (!writable) {
            new CellValueDialog(project, getColumnName(viewColumn), String.valueOf(value)).show();
            return;
        }
        if (!finishCellEditing()) {
            return;
        }
        CellValueDialog dialog = new CellValueDialog(project, getColumnName(viewColumn),
                value == null ? "" : String.valueOf(value), true);
        if (dialog.showAndGet()) {
            String edited = dialog.editedValue();
            if (edited != null) { // Apply: a pending edit, saved by Submit like any other
                setValueAt(edited, viewRow, viewColumn);
            }
        }
    }

    /** NULLs first, numbers numerically, same-type comparables naturally, else by text. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(@Nullable Object a, @Nullable Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : (a == null ? -1 : 1);
        }
        if (a instanceof Number na && b instanceof Number nb) {
            try {
                return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString()));
            } catch (NumberFormatException nonFinite) { // NaN / Infinity
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
        }
        if (a instanceof Comparable ca && a.getClass() == b.getClass()) {
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    // ------------------------------------------------------------------ models

    private static final class GridModel extends AbstractTableModel {
        private final List<String> columns;
        private final List<String> types;
        private final List<Object[]> rows;
        /** Editable columns by model index; null when the result is read-only. */
        private boolean @Nullable [] writable;
        private boolean locked;
        /** Whether loaded rows accept edits and deletion; false when only inserts are possible. */
        private boolean loadedRowsEditable = true;
        /** Pending edits: model row → (model column → new value). */
        private final java.util.Map<Integer, java.util.Map<Integer, Object>> edits = new java.util.HashMap<>();
        /** Rows marked for deletion (model indices). */
        private final java.util.Set<Integer> deleted = new java.util.HashSet<>();
        /** New rows waiting to be inserted: typed values by model column, in the order added. */
        private final List<java.util.Map<Integer, Object>> inserted = new java.util.ArrayList<>();
        private Runnable onChange = () -> {
        };

        GridModel(@NotNull List<String> columns, @NotNull List<String> types, @NotNull List<Object[]> rows) {
            this.columns = columns;
            this.types = types;
            this.rows = rows;
        }

        @NotNull String type(int column) {
            return column < types.size() ? types.get(column) : "";
        }

        @Override
        public int getRowCount() {
            return rows.size() + inserted.size();
        }

        /** A row beyond the loaded ones: appended with Add Row, not inserted yet. */
        boolean isInserted(int row) {
            return row >= rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (isInserted(row)) {
                return inserted.get(row - rows.size()).get(column);
            }
            java.util.Map<Integer, Object> edited = edits.get(row);
            if (edited != null && edited.containsKey(column)) {
                return edited.get(column);
            }
            return loadedValue(row, column);
        }

        private @Nullable Object loadedValue(int row, int column) {
            Object[] values = rows.get(row);
            return column < values.length ? values[column] : null;
        }

        boolean isEdited(int row, int column) {
            java.util.Map<Integer, Object> edited = edits.get(row);
            return edited != null && edited.containsKey(column);
        }

        /**
         * Writable column, not locked, and the value fits a one-line editor: the text field
         * would flatten line breaks, so multi-line values stay read-only here.
         */
        @Override
        public boolean isCellEditable(int row, int column) {
            if (!isWritable(row, column)) {
                return false;
            }
            Object value = getValueAt(row, column);
            return !(value instanceof String text && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0));
        }

        /** The cell can take a new value (SET NULL included), multi-line or not. */
        boolean isWritable(int row, int column) {
            return writable != null && !locked && column < writable.length && writable[column]
                    && (isInserted(row) || loadedRowsEditable && !deleted.contains(row));
        }

        /** Records an edit (null = SET NULL); typing back the loaded value (or nothing into a NULL) drops it.
         * In a new row, an emptied cell goes back to the table's default (the column is left out of the INSERT). */
        @Override
        public void setValueAt(Object value, int row, int column) {
            if (isInserted(row)) {
                java.util.Map<Integer, Object> cells = inserted.get(row - rows.size());
                if (value == null || String.valueOf(value).isEmpty()) {
                    cells.remove(column);
                } else {
                    cells.put(column, value);
                }
                fireTableCellUpdated(row, column);
                onChange.run();
                return;
            }
            Object loaded = loadedValue(row, column);
            boolean unchanged = loaded == null
                    ? value == null || String.valueOf(value).isEmpty()
                    : value != null && String.valueOf(loaded).equals(String.valueOf(value));
            if (unchanged) {
                java.util.Map<Integer, Object> edited = edits.get(row);
                if (edited != null) {
                    edited.remove(column);
                    if (edited.isEmpty()) {
                        edits.remove(row);
                    }
                }
            } else {
                edits.computeIfAbsent(row, r -> new java.util.HashMap<>()).put(column, value);
            }
            fireTableCellUpdated(row, column);
            onChange.run();
        }
    }

    /** Frozen gutter with 1-based row numbers in view order (they stay 1..n after sorting). */
    private final class RowHeader extends JBTable {
        RowHeader() {
            super(new AbstractTableModel() {
                @Override
                public int getRowCount() {
                    return ResultGrid.this.getRowCount();
                }

                @Override
                public int getColumnCount() {
                    return 1;
                }

                @Override
                public Object getValueAt(int row, int column) {
                    return row + 1;
                }
            });
            setFocusable(false);
            setRowSelectionAllowed(false);
            setShowGrid(false);
            MouseAdapter rowPicker = new MouseAdapter() {
                private int anchor = -1;

                @Override
                public void mousePressed(@NotNull MouseEvent e) {
                    int row = rowAtPoint(e.getPoint());
                    if (row < 0) {
                        return;
                    }
                    ResultGrid grid = ResultGrid.this;
                    boolean toggle = e.isMetaDown() || e.isControlDown();
                    if (e.isShiftDown() && anchor >= 0) {
                        grid.selectRows(anchor, row, false);
                    } else if (toggle && grid.isRowSelected(row)) {
                        grid.removeRowSelectionInterval(row, row);
                        anchor = row;
                    } else {
                        grid.selectRows(row, row, toggle);
                        anchor = row;
                    }
                    grid.requestFocusInWindow(); // so Ctrl/Cmd+C copies the rows
                }

                @Override
                public void mouseDragged(@NotNull MouseEvent e) {
                    int row = rowAtPoint(e.getPoint());
                    if (row >= 0 && anchor >= 0) {
                        ResultGrid.this.selectRows(anchor, row, false);
                    }
                }
            };
            addMouseListener(rowPicker);
            addMouseMotionListener(rowPicker);
            setDefaultRenderer(Object.class, new ColoredTableCellRenderer() {
                @Override
                protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value,
                                                     boolean selected, boolean hasFocus, int row, int column) {
                    setFont(table.getFont());
                    setTextAlign(SwingConstants.LEFT);
                    setBorder(JBUI.Borders.empty(0, 6));
                    boolean rowSelected = ResultGrid.this.isRowSelected(row);
                    if (rowSelected) {
                        setBackground(ResultGrid.this.getSelectionBackground());
                    }
                    append(String.valueOf(value), rowSelected
                            ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ResultGrid.this.getSelectionForeground())
                            : SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            });
        }

        void refresh() {
            ((AbstractTableModel) getModel()).fireTableDataChanged();
            int digits = String.valueOf(Math.max(1, ResultGrid.this.getRowCount())).length();
            int width = getFontMetrics(getFont()).charWidth('0') * Math.max(2, digits) + JBUI.scale(16);
            setPreferredScrollableViewportSize(new java.awt.Dimension(width, 0));
            if (getColumnModel().getColumnCount() > 0) {
                getColumnModel().getColumn(0).setPreferredWidth(width);
            }
            revalidate();
        }
    }
}
