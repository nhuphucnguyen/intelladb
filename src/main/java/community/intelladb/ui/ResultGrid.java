package community.intelladb.ui;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.icons.AllIcons;
import community.intelladb.IntellaDbIcons;
import community.intelladb.util.JsonText;
import community.intelladb.connection.SqlResult;
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
 * full value.
 */
final class ResultGrid extends JBTable {

    private static final int MAX_CELL_CHARS = 1000;
    private static final int MIN_COLUMN_WIDTH = 50;
    private static final int MAX_COLUMN_WIDTH = 360;
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int2", "int4", "int8", "smallint", "integer", "bigint", "serial", "bigserial", "smallserial",
            "numeric", "decimal", "float4", "float8", "real", "double precision", "money", "oid");

    private static final int CELL_PADDING = 6;
    /** JSON cells longer than this are not parsed for the icon (they can still be opened). */
    private static final int MAX_JSON_SNIFF = 1_000_000;

    private enum JsonKind { NONE, OBJECT, ARRAY }

    private final Project project;
    private final RowHeader rowHeader = new RowHeader();
    /** Per-cell JSON detection, computed lazily once per result (key: modelRow * columns + modelColumn). */
    private final java.util.Map<Long, JsonKind> jsonCells = new java.util.HashMap<>();
    private GridModel model = new GridModel(List.of(), List.of(), List.of());

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
        applyEditorFont();
        getSelectionModel().addListSelectionListener(e -> rowHeader.repaint());
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int column = columnAtPoint(e.getPoint());
                if (e.getClickCount() == 2 || (e.getClickCount() == 1 && onJsonIcon(e.getPoint()))) {
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
    }

    /** Wraps the grid in a scroll pane whose row header is the frozen row-number gutter. */
    @NotNull JBScrollPane wrapInScrollPane() {
        JBScrollPane scroll = new JBScrollPane(this);
        scroll.setRowHeaderView(rowHeader);
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    void setResult(@Nullable SqlResult result) {
        model = result == null
                ? new GridModel(List.of(), List.of(), List.of())
                : new GridModel(result.columns, result.columnTypes, result.rows);
        jsonCells.clear();
        setModel(model);
        TableRowSorter<GridModel> sorter = new TableRowSorter<>(model);
        for (int c = 0; c < model.getColumnCount(); c++) {
            sorter.setComparator(c, ResultGrid::compareValues);
        }
        sorter.addRowSorterListener(e -> rowHeader.refresh());
        setRowSorter(sorter);
        installHeaderRenderer();
        fitColumns();
        rowHeader.refresh();
    }

    // ------------------------------------------------------------------ row access

    /** All rows in the order shown (after sorting). */
    @NotNull List<Object[]> rowsInViewOrder() {
        List<Object[]> rows = new java.util.ArrayList<>(getRowCount());
        for (int view = 0; view < getRowCount(); view++) {
            rows.add(model.rows.get(convertRowIndexToModel(view)));
        }
        return rows;
    }

    /** Rows touched by the selection (any selected cell counts), in the order shown. */
    @NotNull List<Object[]> selectedRowsInViewOrder() {
        int[] selected = getSelectedRows();
        java.util.Arrays.sort(selected);
        List<Object[]> rows = new java.util.ArrayList<>(selected.length);
        for (int view : selected) {
            rows.add(model.rows.get(convertRowIndexToModel(view)));
        }
        return rows;
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
     * The "open in viewer" affordance of a JSON cell: a neutral expand icon at the cell's
     * right edge — not a {} glyph, which read as part of the value right before its own "{".
     */
    private static @Nullable javax.swing.Icon jsonIcon(@NotNull JsonKind kind, boolean hovered) {
        if (kind == JsonKind.NONE) {
            return null;
        }
        return hovered ? AllIcons.General.ExpandComponentHover : AllIcons.General.ExpandComponent;
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

    /** Is the point on the expand icon of a JSON cell? A click there opens the JSON viewer. */
    private boolean onJsonIcon(@NotNull java.awt.Point point) {
        int row = rowAtPoint(point);
        int column = columnAtPoint(point);
        if (row < 0 || column < 0) {
            return false;
        }
        javax.swing.Icon icon = jsonIcon(jsonKind(row, column), false);
        if (icon == null) {
            return false;
        }
        java.awt.Rectangle cell = getCellRect(row, column, false);
        int iconStart = cell.x + cell.width - JBUI.scale(CELL_PADDING) - icon.getIconWidth();
        return point.x >= iconStart - JBUI.scale(3);
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

    private boolean isNumericColumn(int modelColumn) {
        String type = model.type(modelColumn).toLowerCase();
        if (NUMERIC_TYPES.contains(type)) {
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
        /** Painted at the right edge (see {@link #paintComponent}); null for non-JSON cells. */
        private @Nullable javax.swing.Icon trailingIcon;

        @Override
        protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value, boolean selected,
                                             boolean hasFocus, int row, int column) {
            setFont(table.getFont());
            boolean numeric = isNumericColumn(table.convertColumnIndexToModel(column));
            setTextAlign(numeric ? SwingConstants.RIGHT : SwingConstants.LEFT);
            trailingIcon = value == null ? null
                    : jsonIcon(jsonKind(row, column), row == hoveredRow && column == hoveredColumn);
            // Reserve the icon's strip on the right so the text never runs under it.
            int right = CELL_PADDING + (trailingIcon == null ? 0
                    : JBUI.unscale(trailingIcon.getIconWidth()) + 4);
            setBorder(JBUI.Borders.empty(0, CELL_PADDING, 0, right));
            if (value == null) {
                append("<null>", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            } else {
                append(display(value), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            javax.swing.Icon icon = trailingIcon;
            if (icon != null) {
                int x = getWidth() - JBUI.scale(CELL_PADDING) - icon.getIconWidth();
                int y = (getHeight() - icon.getIconHeight()) / 2;
                icon.paintIcon(this, g, x, y);
            }
        }
    }

    /** Keeps the platform header look (sort arrows) and adds the column icon + type tooltip. */
    private void installHeaderRenderer() {
        TableCellRenderer base = getTableHeader().getDefaultRenderer();
        for (int c = 0; c < getColumnModel().getColumnCount(); c++) {
            TableColumn column = getColumnModel().getColumn(c);
            int modelIndex = column.getModelIndex();
            column.setHeaderRenderer((table, value, selected, focus, row, col) -> {
                Component component = base.getTableCellRendererComponent(table, value, selected, focus, row, col);
                if (component instanceof JLabel label) {
                    label.setIcon(IntellaDbIcons.COLUMN);
                    label.setHorizontalAlignment(SwingConstants.LEFT);
                    String type = model.type(modelIndex);
                    label.setToolTipText(type.isEmpty() ? String.valueOf(value) : value + " : " + type);
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
        if (value != null) { // NULL has nothing more to show
            new CellValueDialog(project, getColumnName(viewColumn), String.valueOf(value)).show();
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
            return rows.size();
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
            Object[] values = rows.get(row);
            return column < values.length ? values[column] : null;
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
