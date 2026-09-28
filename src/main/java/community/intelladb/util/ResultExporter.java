package community.intelladb.util;

import community.intelladb.schema.IdentifierQuoting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Turns a result grid into text — the "data extractors" of the results toolbar
 * (CSV, TSV, JSON, SQL INSERT statements, Markdown table).
 */
public final class ResultExporter {

    public enum Format {
        CSV("CSV", "csv"),
        TSV("TSV", "tsv"),
        JSON("JSON", "json"),
        SQL_INSERTS("SQL Inserts", "sql"),
        MARKDOWN("Markdown", "md");

        public final String label;
        public final String extension;

        Format(@NotNull String label, @NotNull String extension) {
            this.label = label;
            this.extension = extension;
        }
    }

    /**
     * @param table target table for {@link Format#SQL_INSERTS}; a placeholder name is used
     *              when the result does not come from a single known table
     */
    public static @NotNull String export(@NotNull Format format, @NotNull List<String> columns,
                                         @NotNull List<Object[]> rows, @Nullable String table) {
        return switch (format) {
            case CSV -> delimited(columns, rows, ',');
            case TSV -> delimited(columns, rows, '\t');
            case JSON -> json(columns, rows);
            case SQL_INSERTS -> inserts(columns, rows, table == null || table.isBlank() ? "my_table" : table);
            case MARKDOWN -> markdown(columns, rows);
        };
    }

    private static @NotNull String delimited(@NotNull List<String> columns, @NotNull List<Object[]> rows, char sep) {
        StringBuilder out = new StringBuilder();
        appendDelimitedRow(out, columns.toArray(), sep);
        for (Object[] row : rows) {
            appendDelimitedRow(out, row, sep);
        }
        return out.toString();
    }

    private static void appendDelimitedRow(@NotNull StringBuilder out, @NotNull Object[] values, char sep) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(sep);
            }
            if (values[i] == null) {
                continue; // empty field for NULL
            }
            String text = String.valueOf(values[i]);
            boolean quote = text.indexOf(sep) >= 0 || text.indexOf('"') >= 0
                    || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
            out.append(quote ? '"' + text.replace("\"", "\"\"") + '"' : text);
        }
        out.append('\n');
    }

    private static @NotNull String json(@NotNull List<String> columns, @NotNull List<Object[]> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int r = 0; r < rows.size(); r++) {
            out.append(r == 0 ? "\n  {" : ",\n  {");
            Object[] row = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    out.append(", ");
                }
                out.append(jsonString(columns.get(c))).append(": ").append(jsonValue(c < row.length ? row[c] : null));
            }
            out.append('}');
        }
        return out.append(rows.isEmpty() ? "]\n" : "\n]\n").toString();
    }

    private static @NotNull String jsonValue(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return jsonString(String.valueOf(value));
    }

    private static @NotNull String jsonString(@NotNull String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static @NotNull String inserts(@NotNull List<String> columns, @NotNull List<Object[]> rows,
                                           @NotNull String table) {
        StringBuilder columnList = new StringBuilder();
        for (int c = 0; c < columns.size(); c++) {
            if (c > 0) {
                columnList.append(", ");
            }
            columnList.append(IdentifierQuoting.quote(columns.get(c)));
        }
        StringBuilder out = new StringBuilder();
        for (Object[] row : rows) {
            out.append("INSERT INTO ").append(table).append(" (").append(columnList).append(") VALUES (");
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    out.append(", ");
                }
                out.append(sqlLiteral(c < row.length ? row[c] : null));
            }
            out.append(");\n");
        }
        return out.toString();
    }

    private static @NotNull String sqlLiteral(@Nullable Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static @NotNull String markdown(@NotNull List<String> columns, @NotNull List<Object[]> rows) {
        StringBuilder out = new StringBuilder("|");
        for (String column : columns) {
            out.append(' ').append(markdownCell(column)).append(" |");
        }
        out.append("\n|");
        out.append(" --- |".repeat(columns.size()));
        for (Object[] row : rows) {
            out.append("\n|");
            for (int c = 0; c < columns.size(); c++) {
                Object value = c < row.length ? row[c] : null;
                out.append(' ').append(value == null ? "NULL" : markdownCell(String.valueOf(value))).append(" |");
            }
        }
        return out.append('\n').toString();
    }

    private static @NotNull String markdownCell(@NotNull String text) {
        return text.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }

    private ResultExporter() {
    }
}
