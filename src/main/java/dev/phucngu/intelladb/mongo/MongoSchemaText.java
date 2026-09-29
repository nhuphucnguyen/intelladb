package dev.phucngu.intelladb.mongo;

import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A MongoDB catalog as shell text (Copy DDL, the AI assistant's schema): per collection a
 * comment with its sampled fields — {@code ?} marks one not every document has — then the
 * commands that create it and its indexes.
 */
final class MongoSchemaText {

    private MongoSchemaText() {
    }

    static @NotNull String describe(@NotNull SchemaCatalog catalog) {
        StringBuilder sb = new StringBuilder();
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("// database ").append(schema.name()).append('\n');
            String db = "db.getSiblingDB(" + MongoValues.shell(schema.name()) + ")";
            for (TableMeta table : schema.tables()) {
                sb.append("// ").append(table.name).append(": {");
                String separator = "";
                for (ColumnMeta column : table.columns) {
                    sb.append(separator).append(column.name).append(column.nullable ? "?" : "").append(": ")
                            .append(column.typeName);
                    separator = ", ";
                }
                sb.append('}');
                if (!table.remarks.isEmpty()) {
                    sb.append("  (").append(table.remarks).append(')');
                }
                sb.append('\n');
                if (table.isView()) {
                    String source = table.remarks.startsWith("view on ") ? table.remarks.substring(8) : "?";
                    sb.append(db).append(".createView(").append(MongoValues.shell(table.name)).append(", ")
                            .append(MongoValues.shell(source)).append(", [])\n");
                    continue;
                }
                sb.append(db).append(".createCollection(").append(MongoValues.shell(table.name)).append(")\n");
                for (TableMeta.Index index : table.indexes) {
                    Map<String, Object> keys = new LinkedHashMap<>();
                    for (String field : index.columns()) {
                        keys.put(field.replaceAll(" desc$| \\(.*\\)$", ""), direction(field));
                    }
                    Map<String, Object> options = new LinkedHashMap<>();
                    options.put("name", index.name());
                    if (index.unique()) {
                        options.put("unique", true);
                    }
                    sb.append(db).append(".getCollection(").append(MongoValues.shell(table.name)).append(").createIndex(")
                            .append(MongoValues.shell(keys)).append(", ").append(MongoValues.shell(options)).append(")\n");
                }
            }
        }
        return sb.toString();
    }

    /** "age desc" → -1, "bio (text)" → "text", "name" → 1: how the tree shows an index key. */
    private static @NotNull Object direction(@NotNull String shown) {
        if (shown.endsWith(" desc")) {
            return -1;
        }
        int open = shown.lastIndexOf(" (");
        return open > 0 && shown.endsWith(")") ? shown.substring(open + 2, shown.length() - 1) : 1;
    }
}
