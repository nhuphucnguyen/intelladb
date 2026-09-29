package dev.phucngu.intelladb.sql.completion;

import dev.phucngu.intelladb.sql.completion.CursorContext.Clause;
import dev.phucngu.intelladb.sql.completion.CursorContext.TableReference;
import dev.phucngu.intelladb.sql.completion.SqlTokenizer.Token;
import dev.phucngu.intelladb.sql.completion.SqlTokenizer.Type;
import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Works out what belongs at the caret from the text alone — no parser, no PSI. It looks at
 * the statement around the caret: the nearest clause keyword before it (skipping
 * parenthesised sub-expressions), the {@code a.b.} qualifier right before the word being
 * typed, and every table reference in the statement, before or after the caret (so
 * {@code SELECT | FROM users} knows about {@code users}). Tolerant of half-written SQL.
 */
public final class CursorAnalyzer {

    /** Keywords after which a table name follows. */
    private static final Set<String> TABLE_KEYWORDS = Set.of(
            "from", "join", "update", "into", "table", "truncate", "references", "straight_join");
    /** Keywords after which an expression (columns, functions…) follows. */
    private static final Set<String> EXPRESSION_KEYWORDS = Set.of(
            "select", "where", "on", "by", "having", "set", "and", "or", "not", "when", "then", "else", "case",
            "returning", "distinct", "between", "like", "ilike", "in", "is");
    /** Statement-starting words after which another statement keyword follows. */
    private static final Set<String> PREFIX_STATEMENTS = Set.of("explain", "analyze");
    /** Words that end a table reference instead of being its alias. */
    private static final Set<String> NOT_AN_ALIAS = Set.of(
            "as", "cross", "default", "except", "fetch", "for", "force", "from", "full", "group", "having",
            "ignore", "inner", "intersect", "into", "join", "lateral", "left", "limit", "natural", "offset",
            "on", "order", "outer", "partition", "returning", "right", "select", "set", "straight_join",
            "tablesample", "union", "use", "using", "values", "where", "window");

    public static @NotNull CursorContext analyze(@NotNull String text, int offset, @NotNull SqlSplitter.Options options) {
        List<Token> all = SqlTokenizer.tokenize(text, options);
        for (Token t : all) {
            if (inside(t, offset) && t.type() != Type.WORD && t.type() != Type.NUMBER && t.type() != Type.PUNCT) {
                return CursorContext.none(); // string, comment or quoted identifier
            }
        }
        List<Token> statement = statementAround(all, offset);

        Token current = null;
        List<Token> before = new ArrayList<>();
        List<Token> others = new ArrayList<>(); // the statement without the word being typed
        for (Token t : statement) {
            if (t.start() < offset && offset <= t.end() && t.type() != Type.PUNCT) {
                current = t;
                continue;
            }
            others.add(t);
            if (t.end() <= offset) {
                before.add(t);
            }
        }
        if (current != null && current.type() != Type.WORD) {
            return CursorContext.none(); // a number
        }
        String prefix = current == null ? "" : text.substring(current.start(), offset);

        List<String> qualifier = new ArrayList<>();
        int k = before.size();
        while (k >= 2 && before.get(k - 1).isPunct('.') && before.get(k - 2).isName()) {
            qualifier.add(0, before.get(k - 2).value());
            k -= 2;
        }
        List<Token> head = before.subList(0, k);

        Facts facts = new Facts();
        Clause clause = clauseOf(head, facts);
        return new CursorContext(clause, List.copyOf(qualifier), prefix, tableReferences(others), facts.insertTarget,
                facts.keyword, facts.joinTarget, aliasFollows(others, offset));
    }

    /** What the clause scan found besides the clause itself. */
    private static final class Facts {
        @Nullable TableReference insertTarget;
        @Nullable String keyword;
        @Nullable TableReference joinTarget;
    }

    /** Whether the word at the caret is already followed by an alias (or is the schema part of a dotted name). */
    private static boolean aliasFollows(@NotNull List<Token> others, int offset) {
        for (Token t : others) {
            if (t.start() >= offset) {
                return t.is("as") || t.isPunct('.')
                        || (t.isName() && !NOT_AN_ALIAS.contains(t.value().toLowerCase(Locale.ROOT)));
            }
        }
        return false;
    }

    private static boolean inside(@NotNull Token t, int offset) {
        if (offset <= t.start()) {
            return false;
        }
        if (offset < t.end()) {
            return true;
        }
        // At the very end: still inside an unterminated token or a line comment (which runs to the newline).
        boolean lineComment = t.type() == Type.COMMENT && !t.value().startsWith("/*");
        return offset == t.end() && (!t.terminated() || lineComment);
    }

    /** The tokens of the statement containing the caret: between the ';' before and the ';' after it. Comments dropped. */
    private static @NotNull List<Token> statementAround(@NotNull List<Token> all, int offset) {
        List<Token> statement = new ArrayList<>();
        for (Token t : all) {
            if (t.isPunct(';')) {
                if (t.end() <= offset) {
                    statement.clear();
                    continue;
                }
                break;
            }
            if (t.type() != Type.COMMENT) {
                statement.add(t);
            }
        }
        return statement;
    }

    /** Scans back from the caret for the clause it is in; parenthesised parts before it are skipped. */
    private static @NotNull Clause clauseOf(@NotNull List<Token> head, @NotNull Facts facts) {
        int depth = 0;
        for (int i = head.size() - 1; i >= 0; i--) {
            Token t = head.get(i);
            if (t.isPunct(')')) {
                depth++;
                continue;
            }
            if (t.isPunct('(')) {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                return parenthesisClause(head, i, facts); // the caret is inside these parentheses
            }
            if (depth > 0 || t.type() != Type.WORD) {
                continue;
            }
            String word = t.value().toLowerCase(Locale.ROOT);
            if (TABLE_KEYWORDS.contains(word) || ((word.equals("describe") || word.equals("desc")) && i == 0)) {
                facts.keyword = word;
                List<Token> rest = head.subList(i + 1, head.size());
                return rest.isEmpty() || rest.get(rest.size() - 1).isPunct(',') ? Clause.TABLE : Clause.KEYWORD;
            }
            if (EXPRESSION_KEYWORDS.contains(word)) {
                facts.keyword = word;
                if (word.equals("on") && i == head.size() - 1) {
                    facts.joinTarget = joinedTable(head, i); // "JOIN t ON |": the join condition comes next
                }
                return Clause.EXPRESSION;
            }
            if (word.equals("as") && i == head.size() - 1) {
                int open = enclosingParenthesis(head, i);
                return open > 0 && head.get(open - 1).is("cast") ? Clause.TYPE : Clause.NONE; // else naming an alias
            }
            if (PREFIX_STATEMENTS.contains(word) && i == head.size() - 1 && i <= 1) {
                return Clause.STATEMENT_START;
            }
        }
        return head.isEmpty() ? Clause.STATEMENT_START : Clause.KEYWORD;
    }

    /** The caret is inside the parenthesis at {@code open}: decide by what precedes it. */
    private static @NotNull Clause parenthesisClause(@NotNull List<Token> head, int open, @NotNull Facts facts) {
        List<Token> inner = head.subList(open + 1, head.size());
        int nameStart = qualifiedNameStart(head, open - 1);
        if (nameStart > 0) {
            Token keyword = head.get(nameStart - 1);
            if (keyword.is("into")) {
                facts.insertTarget = reference(head.subList(nameStart, open));
                return Clause.INSERT_COLUMNS;
            }
            if (keyword.is("table") || keyword.is("exists")) { // CREATE TABLE [IF NOT EXISTS] t (…)
                List<Token> definition = sinceLastComma(inner);
                if (definition.size() == 1 && definition.get(0).isName()) {
                    return Clause.TYPE; // "name |": the column's type comes next
                }
                return definition.isEmpty() ? Clause.NONE : Clause.KEYWORD;
            }
        }
        if (open > 0 && head.get(open - 1).is("cast")) {
            return !inner.isEmpty() && inner.get(inner.size() - 1).is("as") ? Clause.TYPE : Clause.EXPRESSION;
        }
        return Clause.EXPRESSION; // function arguments, IN lists, VALUES tuples, sub-expressions
    }

    /** The table of the JOIN that the ON at {@code on} belongs to, or null if there is none. */
    private static @Nullable TableReference joinedTable(@NotNull List<Token> head, int on) {
        for (int i = on - 1; i >= 0; i--) {
            if (head.get(i).is("join") || head.get(i).is("straight_join")) {
                List<TableReference> joined = tableReferences(head.subList(i, on));
                return joined.isEmpty() ? null : joined.get(0);
            }
        }
        return null;
    }

    /** Index of the unclosed '(' before {@code index}, or -1 when at top level. */
    private static int enclosingParenthesis(@NotNull List<Token> tokens, int index) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).isPunct(')')) {
                depth++;
            } else if (tokens.get(i).isPunct('(')) {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /** Start index of the dotted name ending at {@code end}, or -1 if {@code end} is not a name. */
    private static int qualifiedNameStart(@NotNull List<Token> tokens, int end) {
        if (end < 0 || !tokens.get(end).isName()) {
            return -1;
        }
        int start = end;
        while (start >= 2 && tokens.get(start - 1).isPunct('.') && tokens.get(start - 2).isName()) {
            start -= 2;
        }
        return start;
    }

    /** Tokens after the last top-level comma (the current item of a comma list). */
    private static @NotNull List<Token> sinceLastComma(@NotNull List<Token> tokens) {
        int depth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.isPunct(')')) {
                depth++;
            } else if (t.isPunct('(')) {
                depth--;
            } else if (depth == 0 && t.isPunct(',')) {
                return tokens.subList(i + 1, tokens.size());
            }
        }
        return tokens;
    }

    /** Every table the statement names after FROM, JOIN, UPDATE, INTO or USING, with its alias. */
    static @NotNull List<TableReference> tableReferences(@NotNull List<Token> tokens) {
        List<TableReference> tables = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            boolean list = t.is("from") || t.is("update") || t.is("using");
            if (!list && !t.is("join") && !t.is("into") && !t.is("straight_join")) {
                continue;
            }
            int j = i + 1;
            while (j < tokens.size() && tokens.get(j).isName()) {
                int end = j;
                while (end + 2 < tokens.size() && tokens.get(end + 1).isPunct('.') && tokens.get(end + 2).isName()) {
                    end += 2;
                }
                String alias = null;
                int next = end + 1;
                if (next < tokens.size() && tokens.get(next).is("as")) {
                    next++;
                }
                if (next < tokens.size() && tokens.get(next).isName()
                        && !NOT_AN_ALIAS.contains(tokens.get(next).value().toLowerCase(Locale.ROOT))) {
                    alias = tokens.get(next).value();
                    next++;
                }
                TableReference reference = reference(tokens.subList(j, end + 1));
                tables.add(new TableReference(reference.schema(), reference.name(), alias));
                if (list && next < tokens.size() && tokens.get(next).isPunct(',')) {
                    j = next + 1; // FROM a, b
                } else {
                    break;
                }
            }
        }
        return tables;
    }

    /** {@code [db.]schema.name} tokens as a reference (a leading database part is dropped). */
    private static @NotNull TableReference reference(@NotNull List<Token> dotted) {
        List<String> names = new ArrayList<>();
        for (Token t : dotted) {
            if (t.isName()) {
                names.add(t.value());
            }
        }
        String name = names.get(names.size() - 1);
        @Nullable String schema = names.size() >= 2 ? names.get(names.size() - 2) : null;
        return new TableReference(schema, name, null);
    }

    private CursorAnalyzer() {
    }
}
