package dev.phucngu.intelladb.sql.completion;

import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.completion.CursorContext.TableReference;
import dev.phucngu.intelladb.sql.completion.Suggestion.Kind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Turns "where the caret is" ({@link CursorContext}) and "what exists" ({@link CompletionScope})
 * into ranked {@link Suggestion}s. Filtering by the typed prefix is left to the caller
 * (the IDE's prefix matcher does it better, e.g. camel-hump matching).
 */
public final class SuggestionEngine {

    // Ranking: what the clause is about first, keywords last where they are not the point.
    private static final int JOIN_CONDITIONS = 120;
    private static final int RELATED_JOINS = 110;
    private static final int PRIMARY = 100;
    private static final int SECONDARY = 80;
    private static final int OTHER_SCHEMA = 60;
    private static final int FUNCTIONS = 50;
    private static final int BACKGROUND_KEYWORDS = 10;

    /** A table of the catalog, with the alias the statement gave it (if any). */
    private record ResolvedTable(@NotNull String schema, @NotNull TableMeta table, @Nullable String alias) {
        @NotNull String label() {
            return alias != null ? alias : table.name;
        }
    }

    private final CursorContext context;
    private final CompletionScope scope;
    private final Map<String, Suggestion> out = new LinkedHashMap<>();

    private SuggestionEngine(@NotNull CursorContext context, @NotNull CompletionScope scope) {
        this.context = context;
        this.scope = scope;
    }

    public static @NotNull List<Suggestion> suggest(@NotNull CursorContext context, @NotNull CompletionScope scope) {
        SuggestionEngine engine = new SuggestionEngine(context, scope);
        engine.collect();
        return List.copyOf(engine.out.values());
    }

    private void collect() {
        if (context.clause() == CursorContext.Clause.NONE) {
            return;
        }
        if (!context.qualifier().isEmpty()) {
            collectQualified();
            return;
        }
        switch (context.clause()) {
            case STATEMENT_START -> keywords(scope.vocabulary().statementStarts(), PRIMARY);
            case KEYWORD -> keywords(scope.vocabulary().keywords(), PRIMARY);
            case TABLE -> {
                if (joining()) {
                    relatedJoins();
                }
                tables();
                schemas();
            }
            case EXPRESSION -> {
                if (context.joinTarget() != null) {
                    joinConditions();
                }
                List<ResolvedTable> inScope = tablesInScope();
                inScope.forEach(t -> columns(t, PRIMARY));
                for (ResolvedTable t : inScope) {
                    // An alias typed in full has nothing left to complete; offered, it would also be the
                    // exact match the IDE puts first, above a join condition that starts with it.
                    if (!t.label().equalsIgnoreCase(context.prefix())) {
                        add(new Suggestion(t.label(), quote(t.label()), Kind.ALIAS,
                                t.alias() != null ? t.table().name : "", "", SECONDARY));
                    }
                }
                functions();
                keywords(scope.vocabulary().keywords(), BACKGROUND_KEYWORDS);
            }
            case INSERT_COLUMNS -> {
                ResolvedTable target = context.insertTarget() == null ? null : resolve(context.insertTarget());
                if (target != null) {
                    columns(target, PRIMARY);
                }
            }
            case TYPE -> {
                for (String type : scope.vocabulary().dataTypes()) {
                    String text = cased(type);
                    add(new Suggestion(text, text, Kind.TYPE, "", "", PRIMARY));
                }
                SchemaCatalog.Schema current = currentSchema();
                if (current != null) {
                    current.objectTypes().forEach(t -> add(new Suggestion(t.name(), quote(t.name()), Kind.TYPE,
                            t.kind(), "", SECONDARY)));
                }
            }
            default -> {
            }
        }
    }

    /** {@code x.|}: x is an alias, a table or a schema; {@code s.t.|} is a table in a schema. */
    private void collectQualified() {
        List<String> qualifier = context.qualifier();
        String last = qualifier.get(qualifier.size() - 1);
        boolean expression = context.clause() == CursorContext.Clause.EXPRESSION
                || context.clause() == CursorContext.Clause.INSERT_COLUMNS
                || context.clause() == CursorContext.Clause.KEYWORD;
        if (qualifier.size() >= 2) {
            ResolvedTable table = resolve(new TableReference(qualifier.get(qualifier.size() - 2), last, null));
            if (table != null) {
                columns(table, PRIMARY);
            }
            return;
        }
        if (expression) {
            boolean found = false;
            for (ResolvedTable t : tablesInScope()) {
                if (last.equalsIgnoreCase(t.label()) || (t.alias() == null && last.equalsIgnoreCase(t.table().name))) {
                    columns(t, PRIMARY);
                    found = true;
                }
            }
            if (!found) {
                ResolvedTable table = resolve(new TableReference(null, last, null));
                if (table != null) {
                    columns(table, PRIMARY);
                }
            }
        }
        SchemaCatalog.Schema schema = schema(last);
        if (schema != null) {
            for (TableMeta table : schema.tables()) {
                add(tableSuggestion(schema.name(), table, withAlias(quote(table.name), table.name), SECONDARY));
            }
            if (expression) {
                routines(schema);
            }
        }
    }

    private void keywords(@NotNull Collection<String> words, int priority) {
        for (String word : words) {
            String text = cased(word);
            add(new Suggestion(text, text, Kind.KEYWORD, "", "", priority));
        }
    }

    /** Tables of the current schema as plain names, others schema-qualified. */
    private void tables() {
        SchemaCatalog catalog = scope.catalog();
        if (catalog == null) {
            return;
        }
        SchemaCatalog.Schema current = currentSchema();
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            boolean isCurrent = schema == current;
            for (TableMeta table : schema.tables()) {
                add(tableSuggestion(schema.name(), table, withAlias(tableText(schema, table), table.name),
                        isCurrent ? PRIMARY : OTHER_SCHEMA));
            }
        }
    }

    // ------------------------------------------------------------------ joins & aliases

    /** One foreign key between two tables, as the column pairs of a join condition. */
    private record Relation(@NotNull List<String> newColumns, @NotNull List<String> existingColumns) {
        @NotNull String condition(@NotNull String newLabel, @NotNull String existingLabel,
                                  @NotNull UnaryOperator<String> quote) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < newColumns.size() && i < existingColumns.size(); i++) {
                if (i > 0) {
                    sb.append(" AND ");
                }
                sb.append(newLabel).append('.').append(quote.apply(newColumns.get(i))).append(" = ")
                  .append(existingLabel).append('.').append(quote.apply(existingColumns.get(i)));
            }
            return sb.toString();
        }
    }

    /**
     * Foreign keys linking {@code table} (in {@code schema}) with {@code existing}, in either
     * direction: the new table referencing it, or it referencing the new table.
     */
    private static @NotNull List<Relation> relations(@NotNull String schema, @NotNull TableMeta table,
                                                     @NotNull ResolvedTable existing) {
        List<Relation> relations = new ArrayList<>();
        for (TableMeta.ForeignKey fk : table.foreignKeys) {
            if (references(fk, existing.schema(), existing.table())) {
                relations.add(new Relation(fk.columns(), fk.refColumns()));
            }
        }
        for (TableMeta.ForeignKey fk : existing.table().foreignKeys) {
            if (references(fk, schema, table)) {
                relations.add(new Relation(fk.refColumns(), fk.columns()));
            }
        }
        return relations;
    }

    private static boolean references(@NotNull TableMeta.ForeignKey fk, @NotNull String schema,
                                      @NotNull TableMeta table) {
        return table.name.equalsIgnoreCase(fk.refTable())
                && (fk.refSchema() == null || fk.refSchema().isEmpty() || schema.equalsIgnoreCase(fk.refSchema()));
    }

    private boolean joining() {
        return "join".equals(context.keyword()) || "straight_join".equals(context.keyword());
    }

    /** {@code JOIN |}: each table with a foreign key to or from a table already in the statement, with its ON. */
    private void relatedJoins() {
        SchemaCatalog catalog = scope.catalog();
        if (catalog == null) {
            return;
        }
        for (ResolvedTable existing : tablesInScope()) {
            for (SchemaCatalog.Schema schema : catalog.schemas()) {
                for (TableMeta table : schema.tables()) {
                    for (Relation relation : relations(schema.name(), table, existing)) {
                        String alias = aliasFor(table.name);
                        String insert = tableText(schema, table) + " " + alias + " ON "
                                + relation.condition(alias, quote(existing.label()), this::quote);
                        add(new Suggestion(table.name, insert, Kind.JOIN, "", schema.name(), RELATED_JOINS));
                    }
                }
            }
        }
    }

    /** {@code JOIN t ON |}: the foreign-key conditions between t and the tables before it. */
    private void joinConditions() {
        TableReference target = context.joinTarget();
        ResolvedTable joined = target == null ? null : resolve(target);
        if (joined == null) {
            return;
        }
        int position = context.tables().indexOf(target);
        List<TableReference> earlier = position < 0 ? context.tables() : context.tables().subList(0, position);
        for (TableReference reference : earlier) {
            ResolvedTable existing = resolve(reference);
            if (existing == null) {
                continue;
            }
            for (Relation relation : relations(joined.schema(), joined.table(), existing)) {
                String condition = relation.condition(quote(joined.label()), quote(existing.label()), this::quote);
                add(new Suggestion(condition, condition, Kind.JOIN_CONDITION, "", "", JOIN_CONDITIONS));
            }
        }
    }

    /** Appends a generated alias where the clause takes one ({@code FROM users u}) and none is written yet. */
    private @NotNull String withAlias(@NotNull String tableText, @NotNull String tableName) {
        boolean aliasable = "from".equals(context.keyword()) || joining();
        if (!scope.tableAliases() || !aliasable || context.aliasFollows()
                || context.clause() != CursorContext.Clause.TABLE) {
            return tableText;
        }
        return tableText + " " + aliasFor(tableName);
    }

    /** An alias for a table new to the statement, unlike every name and alias already in it. */
    private @NotNull String aliasFor(@NotNull String tableName) {
        Set<String> taken = new HashSet<>();
        for (TableReference reference : context.tables()) {
            taken.add(reference.name().toLowerCase(Locale.ROOT));
            if (reference.alias() != null) {
                taken.add(reference.alias().toLowerCase(Locale.ROOT));
            }
        }
        return TableAliases.aliasFor(tableName, taken, scope.vocabulary().keywords());
    }

    /** The table as written from the current schema: plain name there, schema-qualified elsewhere. */
    private @NotNull String tableText(@NotNull SchemaCatalog.Schema schema, @NotNull TableMeta table) {
        return schema == currentSchema() ? quote(table.name) : quote(schema.name()) + "." + quote(table.name);
    }

    private void schemas() {
        SchemaCatalog catalog = scope.catalog();
        if (catalog == null) {
            return;
        }
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            add(new Suggestion(schema.name(), quote(schema.name()), Kind.SCHEMA, "schema", "", SECONDARY));
        }
    }

    private void columns(@NotNull ResolvedTable table, int priority) {
        for (ColumnMeta column : table.table().columns) {
            add(new Suggestion(column.name, quote(column.name), column.primaryKey ? Kind.KEY_COLUMN : Kind.COLUMN,
                    column.typeName, table.label(), priority));
        }
    }

    private void functions() {
        for (String function : scope.vocabulary().functions()) {
            String text = cased(function);
            add(new Suggestion(text, text, Kind.FUNCTION, "", "", FUNCTIONS));
        }
        SchemaCatalog.Schema current = currentSchema();
        if (current != null) {
            routines(current);
        }
    }

    private void routines(@NotNull SchemaCatalog.Schema schema) {
        for (SchemaCatalog.Routine routine : schema.routines()) {
            if (routine.kind() == SchemaCatalog.Routine.Kind.PROCEDURE) {
                continue; // CALLed, not used in expressions
            }
            add(new Suggestion(routine.name(), quote(routine.name()), Kind.ROUTINE, routine.returns(),
                    "(" + routine.arguments() + ")", FUNCTIONS + 5));
        }
    }

    private @NotNull Suggestion tableSuggestion(@NotNull String schema, @NotNull TableMeta table,
                                                @NotNull String insert, int priority) {
        return new Suggestion(table.name, insert, table.isView() ? Kind.VIEW : Kind.TABLE, "", schema, priority);
    }

    private void add(@NotNull Suggestion suggestion) {
        // One item per thing: the same column of two tables stays two items, a repeated keyword does not.
        out.putIfAbsent(suggestion.kind() + "\0" + suggestion.insertText() + "\0" + suggestion.location(), suggestion);
    }

    // ------------------------------------------------------------------ name resolution

    private @NotNull List<ResolvedTable> tablesInScope() {
        List<ResolvedTable> resolved = new ArrayList<>();
        for (TableReference reference : context.tables()) {
            ResolvedTable table = resolve(reference);
            if (table != null) {
                resolved.add(table);
            }
        }
        return resolved;
    }

    /** Finds a referenced table: in its schema if qualified, else the current schema first, then any. */
    private @Nullable ResolvedTable resolve(@NotNull TableReference reference) {
        SchemaCatalog catalog = scope.catalog();
        if (catalog == null) {
            return null;
        }
        List<SchemaCatalog.Schema> candidates = new ArrayList<>();
        if (reference.schema() != null) {
            SchemaCatalog.Schema schema = schema(reference.schema());
            if (schema != null) {
                candidates.add(schema);
            }
        } else {
            SchemaCatalog.Schema current = currentSchema();
            if (current != null) {
                candidates.add(current);
            }
            candidates.addAll(catalog.schemas());
        }
        for (SchemaCatalog.Schema schema : candidates) {
            TableMeta table = named(schema.tables(), reference.name());
            if (table != null) {
                return new ResolvedTable(schema.name(), table, reference.alias());
            }
        }
        return null;
    }

    private @Nullable SchemaCatalog.Schema currentSchema() {
        return scope.currentSchema() == null ? null : schema(scope.currentSchema());
    }

    private @Nullable SchemaCatalog.Schema schema(@NotNull String name) {
        SchemaCatalog catalog = scope.catalog();
        if (catalog == null) {
            return null;
        }
        SchemaCatalog.Schema folded = null;
        for (SchemaCatalog.Schema schema : catalog.schemas()) {
            if (schema.name().equals(name)) {
                return schema;
            }
            if (folded == null && schema.name().equalsIgnoreCase(name)) {
                folded = schema;
            }
        }
        return folded;
    }

    /** Exact match first; unquoted names are case-insensitive in practice. */
    private static @Nullable TableMeta named(@NotNull List<TableMeta> tables, @NotNull String name) {
        TableMeta folded = null;
        for (TableMeta table : tables) {
            if (table.name.equals(name)) {
                return table;
            }
            if (folded == null && table.name.equalsIgnoreCase(name)) {
                folded = table;
            }
        }
        return folded;
    }

    // ------------------------------------------------------------------ rendering

    private @NotNull String quote(@NotNull String identifier) {
        return scope.quoter().apply(identifier);
    }

    /** Keywords follow the case being typed: {@code sel} → {@code select}, otherwise upper case. */
    private @NotNull String cased(@NotNull String word) {
        String prefix = context.prefix();
        boolean lower = !prefix.isEmpty() && prefix.equals(prefix.toLowerCase(Locale.ROOT))
                && !prefix.equals(prefix.toUpperCase(Locale.ROOT));
        return lower ? word.toLowerCase(Locale.ROOT) : word.toUpperCase(Locale.ROOT);
    }
}
