package dev.phucngu.intelladb.sql.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What the caret is in the middle of, as far as completion is concerned — the output of
 * {@link CursorAnalyzer} and the input of {@link SuggestionEngine}.
 *
 * @param clause       the kind of word that belongs at the caret
 * @param qualifier    names before the caret's word, e.g. {@code [public, users]} for {@code public.users.na|}
 * @param prefix       the part of the word already typed (may be empty)
 * @param tables       tables the statement references (FROM, JOIN, UPDATE, INTO…), wherever they appear
 * @param insertTarget the table of an {@code INSERT INTO t (…)} column list the caret is in
 * @param keyword      the lower-case keyword that opened the clause ({@code from}, {@code join}, {@code on}…), if any
 * @param joinTarget   for {@code JOIN t ON |}: the joined table, whose join condition comes next
 * @param aliasFollows the word at the caret is already followed by an alias (or is a schema part)
 */
public record CursorContext(@NotNull Clause clause, @NotNull List<String> qualifier, @NotNull String prefix,
                            @NotNull List<TableReference> tables, @Nullable TableReference insertTarget,
                            @Nullable String keyword, @Nullable TableReference joinTarget, boolean aliasFollows) {

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

    static @NotNull CursorContext none() {
        return new CursorContext(Clause.NONE, List.of(), "", List.of(), null, null, null, false);
    }
}
