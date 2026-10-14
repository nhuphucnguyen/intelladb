package dev.phucngu.intelladb.sql.completion;

import dev.phucngu.intelladb.schema.ColumnMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What the caret is in the middle of, as far as completion is concerned — the output of
 * {@link CursorAnalyzer} and the input of {@link SuggestionEngine}.
 *
 * @param clause        the kind of word that belongs at the caret
 * @param qualifier     names before the caret's word, e.g. {@code [public, users]} for {@code public.users.na|}
 * @param prefix        the part of the word already typed (may be empty)
 * @param tables        tables the statement references (FROM, JOIN, UPDATE, INTO…), wherever they appear
 * @param insertTarget  the table of an {@code INSERT INTO t (…)} column list the caret is in
 * @param keyword       the lower-case keyword that opened the clause ({@code from}, {@code join}, {@code on}…), if any
 * @param joinTarget    for {@code JOIN t ON |}: the joined table, whose join condition comes next
 * @param aliasFollows  the word at the caret is already followed by an alias (or is a schema part)
 * @param qualifierStart where the {@code qualifier.word} dotted chain starts in the text ({@code a.b.}), or -1
 * @param derivedTables CTEs ({@code WITH t AS (SELECT …)}) and subquery aliases ({@code FROM (…) x}),
 *                      with the columns their select list produces
 * @param expected      for a caret at one side of a comparison ({@code WHERE id = |}): the other side
 */
public record CursorContext(@NotNull Clause clause, @NotNull List<String> qualifier, @NotNull String prefix,
                            @NotNull List<TableReference> tables, @Nullable TableReference insertTarget,
                            @Nullable String keyword, @Nullable TableReference joinTarget, boolean aliasFollows,
                            int qualifierStart, @NotNull List<DerivedTable> derivedTables,
                            @Nullable Operand expected) {

    public enum Clause {
        /** Inside a string or comment, or naming an alias: nothing to suggest. */
        NONE,
        /** The first word of a statement. */
        STATEMENT_START,
        /** A table name (after FROM, JOIN, UPDATE, INTO…). */
        TABLE,
        /** An expression: columns, functions, keywords (SELECT list, WHERE, ON, ORDER BY…). */
        EXPRESSION,
        /** After a complete table reference or elsewhere a keyword is expected. */
        KEYWORD,
        /** The column list of {@code INSERT INTO t (…)}. */
        INSERT_COLUMNS,
        /** A data type (column definition in CREATE TABLE, CAST … AS). */
        TYPE
    }

    /** A table as written in the statement: {@code schema.name alias}. */
    public record TableReference(@Nullable String schema, @NotNull String name, @Nullable String alias) {
    }

    /**
     * A table that exists only inside the statement: a CTE ({@code WITH t AS (SELECT … FROM users)}) or a
     * subquery alias ({@code FROM (SELECT id FROM users) x}). {@code label} is what the statement refers
     * to it by: the CTE name, or the subquery's alias.
     */
    public record DerivedTable(@NotNull String label, @NotNull List<ColumnMeta> columns) {
    }

    /**
     * The other side of the comparison the caret is one side of: a column of a table the statement
     * references, or a literal ({@code "string"} / {@code 42} as its category).
     */
    public record Operand(@Nullable TableReference table, @Nullable String column, @Nullable String literal) {
        static @NotNull Operand column(@NotNull TableReference table, @NotNull String column) {
            return new Operand(table, column, null);
        }

        static @NotNull Operand literal(@NotNull String category) {
            return new Operand(null, null, category);
        }
    }

    static @NotNull CursorContext none() {
        return new CursorContext(Clause.NONE, List.of(), "", List.of(), null, null, null, false, -1, List.of(),
                null);
    }
}
