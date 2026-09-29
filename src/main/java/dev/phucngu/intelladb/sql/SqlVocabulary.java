package dev.phucngu.intelladb.sql;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The words a SQL dialect knows, for completion: keywords used inside statements, the
 * keywords a statement can start with, built-in functions and data types. All lower-case;
 * completion renders them in the case the user is typing. {@link #ANSI} is the common core
 * every dialect extends with {@link #plus}.
 */
public record SqlVocabulary(@NotNull Set<String> keywords, @NotNull Set<String> statementStarts,
                            @NotNull Set<String> functions, @NotNull Set<String> dataTypes) {

    public static final SqlVocabulary ANSI = new SqlVocabulary(
            words("all", "and", "any", "as", "asc", "between", "by", "case", "cast", "check", "constraint",
                    "cross", "current_date", "current_time", "current_timestamp", "default", "delete", "desc",
                    "distinct", "else", "end", "except", "exists", "false", "fetch", "first", "foreign", "from", "full", "group", "having", "in", "index", "inner",
                    "insert", "intersect", "into", "is", "join", "key", "left", "like", "limit", "natural",
                    "not", "null", "offset", "on", "only", "or", "order", "outer", "over", "partition",
                    "primary", "recursive", "references", "right", "rows", "select", "set", "some", "table",
                    "then", "true", "union", "unique", "update", "using", "values", "view", "when", "where",
                    "window", "with"),
            words("alter", "begin", "commit", "create", "delete", "drop", "explain", "grant", "insert",
                    "revoke", "rollback", "select", "truncate", "update", "with"),
            // current_date & co. are called without parentheses, so they are keywords here
            words("abs", "avg", "cast", "ceil", "coalesce", "count", "floor", "length", "lower", "max", "min", "mod",
                    "nullif", "round", "substring", "sum", "trim", "upper"),
            words("bigint", "boolean", "char", "date", "decimal", "float", "int", "integer", "numeric", "real",
                    "smallint", "time", "timestamp", "varchar"));

    /** This vocabulary with extra words added (duplicates collapse). */
    public @NotNull SqlVocabulary plus(@NotNull Collection<String> keywords, @NotNull Collection<String> statementStarts,
                                       @NotNull Collection<String> functions, @NotNull Collection<String> dataTypes) {
        return new SqlVocabulary(union(this.keywords, keywords), union(this.statementStarts, statementStarts),
                union(this.functions, functions), union(this.dataTypes, dataTypes));
    }

    /** Shorthand for the word lists of {@link #plus}. */
    public static @NotNull Set<String> words(@NotNull String... words) {
        return union(List.of(words), List.of());
    }

    private static @NotNull Set<String> union(@NotNull Collection<String> a, @NotNull Collection<String> b) {
        Set<String> all = new TreeSet<>(a);
        all.addAll(b);
        return Collections.unmodifiableSet(all);
    }

    public SqlVocabulary {
        keywords = union(keywords, List.of());
        statementStarts = union(statementStarts, List.of());
        functions = union(functions, List.of());
        dataTypes = union(dataTypes, List.of());
    }
}
