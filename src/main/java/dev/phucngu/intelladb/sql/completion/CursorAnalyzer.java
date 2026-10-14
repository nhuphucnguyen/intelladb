package dev.phucngu.intelladb.sql.completion;

import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import dev.phucngu.intelladb.sql.completion.CursorContext.Clause;
import dev.phucngu.intelladb.sql.completion.CursorContext.DerivedTable;
import dev.phucngu.intelladb.sql.completion.CursorContext.Operand;
import dev.phucngu.intelladb.sql.completion.CursorContext.TableReference;
import dev.phucngu.intelladb.sql.completion.SqlTokenizer.Token;
import dev.phucngu.intelladb.sql.completion.SqlTokenizer.Type;
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
    /** Words that end a select-list item instead of being its alias. */
    private static final Set<String> NOT_A_SELECT_ALIAS = Set.of(
            "asc", "desc", "from", "nulls", "over");
    /** Comparison operator words (punctuation operators are single chars: = < > !). */
    private static final Set<String> COMPARISON_WORDS = Set.of("like", "ilike", "in", "between", "is");

    public static @NotNull CursorContext analyze(@NotNull String text, int offset, @NotNull CompletionScope scope) {
        List<Token> all = SqlTokenizer.tokenize(text, scope.splitterOptions());
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
        int qualifierStart = qualifier.isEmpty() ? -1 : before.get(k).start();

        Facts facts = new Facts();
        Clause clause = clauseOf(head, facts);
        List<TableReference> tables = tableReferences(others);
        List<DerivedTable> derived = derivedTables(others, scope);
        return new CursorContext(clause, List.copyOf(qualifier), prefix, tables, facts.insertTarget,
                facts.keyword, facts.joinTarget, aliasFollows(others, offset), qualifierStart, derived,
                clause == Clause.EXPRESSION ? comparisonOperand(head) : null);
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

    /**
     * For {@code WHERE id = |} / {@code WHERE name like |}: the other side of the comparison, as
     * the operand ending right before the operator group that ends {@code head}. Null when the
     * caret does not continue a comparison (or the other side is not a name or literal).
     */
    private static @Nullable Operand comparisonOperand(@NotNull List<Token> head) {
        int i = head.size();
        // Skip the operator itself: one comparison word, or a run of = < > ! characters (>=, <=, <>).
        if (i > 0 && head.get(i - 1).type() == Type.WORD) {
            String word = head.get(i - 1).value().toLowerCase(Locale.ROOT);
            if (!COMPARISON_WORDS.contains(word)) {
                if (word.equals("not")) { // IS NOT / NOT LIKE / NOT BETWEEN
                    i--;
                    if (i > 0 && head.get(i - 1).type() == Type.WORD
                            && COMPARISON_WORDS.contains(head.get(i - 1).value().toLowerCase(Locale.ROOT))) {
                        i--;
                    }
                    // fall through to the operand scan below
                } else {
                    return null;
                }
            } else {
                i--;
            }
        } else {
            while (i > 0 && head.get(i - 1).type() == Type.PUNCT
                    && "=<>!".indexOf(head.get(i - 1).value()) >= 0) {
                i--;
            }
            if (i == head.size()) {
                return null; // no operator right before the caret's word
            }
        }
        // The operand: a dotted name chain (u.id), a bare column name (id) or a literal.
        int end = i;
        if (end > 0 && head.get(end - 1).type() == Type.STRING) {
            return Operand.literal("string");
        }
        if (end > 0 && head.get(end - 1).type() == Type.NUMBER) {
            return Operand.literal("int");
        }
        int chainEnd = end;
        while (chainEnd >= 2 && head.get(chainEnd - 1).isName() && head.get(chainEnd - 2).isPunct('.')) {
            chainEnd -= 2;
        }
        if (chainEnd > 0 && head.get(chainEnd - 1).isName()) {
            chainEnd--;
        } else {
            return null; // an expression too complex to name, e.g. upper(u.email)
        }
        List<Token> nameTokens = head.subList(chainEnd, end);
        if (nameTokens.size() == 1) {
            return new Operand(null, nameTokens.get(0).value(), null);
        }
        TableReference table = reference(nameTokens.subList(0, nameTokens.size() - 1));
        return Operand.column(new TableReference(table.schema(), table.name(), table.alias()),
                nameTokens.get(nameTokens.size() - 1).value());
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

    /**
     * Every table the statement names after FROM, JOIN, UPDATE, INTO or USING, with its alias.
     * Parenthesised parts are skipped: a subquery's or CTE's own FROM is not this statement's.
     */
    static @NotNull List<TableReference> tableReferences(@NotNull List<Token> tokens) {
        List<TableReference> tables = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isPunct('(')) {
                depth++;
                continue;
            }
            if (t.isPunct(')')) {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth > 0) {
                continue;
            }
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

    // ------------------------------------------------------------------ CTEs and subquery aliases

    /**
     * The statement's own tables: CTEs ({@code WITH recent AS (SELECT …)} referenced by name) and
     * subqueries in FROM ({@code FROM (SELECT …) x} referenced by alias), each with the columns its
     * select list produces. {@code *} items are expanded through the catalog when it is available.
     */
    private static @NotNull List<DerivedTable> derivedTables(@NotNull List<Token> tokens,
                                                             @NotNull CompletionScope scope) {
        List<DerivedTable> derived = new ArrayList<>();
        if (!tokens.isEmpty() && tokens.get(0).is("with")) {
            derived.addAll(cteTables(tokens, scope));
        }
        derived.addAll(subqueryTables(tokens, scope));
        return derived;
    }

    /** {@code WITH [RECURSIVE] name [(cols)] AS (SELECT …), name2 AS (SELECT …)}. */
    private static @NotNull List<DerivedTable> cteTables(@NotNull List<Token> tokens,
                                                         @NotNull CompletionScope scope) {
        List<DerivedTable> result = new ArrayList<>();
        int i = 1;
        if (i < tokens.size() && tokens.get(i).is("recursive")) {
            i++;
        }
        while (i < tokens.size() && tokens.get(i).isName()) {
            String name = tokens.get(i).value();
            i++;
            List<String> declaredColumns = new ArrayList<>();
            if (i < tokens.size() && tokens.get(i).isPunct('(')) { // WITH t(a, b) AS (…)
                int close = matchingParenthesis(tokens, i);
                if (close < 0) {
                    break;
                }
                for (Token inner : tokens.subList(i + 1, close)) {
                    if (inner.isName()) {
                        declaredColumns.add(inner.value());
                    }
                }
                i = close + 1;
            }
            if (i + 1 >= tokens.size() || !tokens.get(i).is("as")
                    || !tokens.get(i + 1).isPunct('(')) {
                break;
            }
            int close = matchingParenthesis(tokens, i + 1);
            if (close < 0) {
                break;
            }
            List<ColumnMeta> columns = selectListColumns(tokens.subList(i + 2, close), scope, result);
            if (!declaredColumns.isEmpty()) {
                columns = renamed(columns, declaredColumns);
            }
            result.add(new DerivedTable(name, columns));
            i = close + 1;
            if (i < tokens.size() && tokens.get(i).isPunct(',')) {
                i++;
                continue;
            }
            break;
        }
        return result;
    }

    /** {@code FROM (SELECT …) x} / {@code JOIN (SELECT …) AS x ON …}: one per parenthesised subquery. */
    private static @NotNull List<DerivedTable> subqueryTables(@NotNull List<Token> tokens,
                                                              @NotNull CompletionScope scope) {
        List<DerivedTable> result = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).isPunct('(') || i + 1 >= tokens.size() || !tokens.get(i + 1).is("select")) {
                continue;
            }
            int close = matchingParenthesis(tokens, i);
            if (close < 0 || close + 1 >= tokens.size()) {
                continue;
            }
            int aliasAt = close + 1;
            if (aliasAt < tokens.size() && tokens.get(aliasAt).is("as")) {
                aliasAt++;
            }
            if (aliasAt < tokens.size() && tokens.get(aliasAt).isName()
                    && !NOT_AN_ALIAS.contains(tokens.get(aliasAt).value().toLowerCase(Locale.ROOT))) {
                result.add(new DerivedTable(tokens.get(aliasAt).value(),
                        selectListColumns(tokens.subList(i + 1, close), scope, List.of())));
            }
        }
        return result;
    }

    /** The columns a select's list produces: {@code col}, {@code t.col}, {@code expr AS alias}, {@code *}. */
    private static @NotNull List<ColumnMeta> selectListColumns(@NotNull List<Token> select,
                                                               @NotNull CompletionScope scope,
                                                               @NotNull List<DerivedTable> outer) {
        int start = -1;
        for (int i = 0; i < select.size(); i++) {
            if (select.get(i).is("select")) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }
        if (start < select.size() && (select.get(start).is("distinct") || select.get(start).is("all"))) {
            start++;
        }
        int end = select.size();
        int depth = 0;
        for (int i = start; i < select.size(); i++) {
            Token t = select.get(i);
            if (t.isPunct('(')) {
                depth++;
            } else if (t.isPunct(')')) {
                depth--;
            } else if (depth == 0 && (t.is("from") || t.isPunct(';'))) {
                end = i;
                break;
            }
        }
        List<ColumnMeta> columns = new ArrayList<>();
        List<TableReference> fromTables = tableReferences(select);
        for (List<Token> item : commaItems(select.subList(start, end))) {
            columns.addAll(itemColumns(item, fromTables, scope, outer));
        }
        return columns;
    }

    /** Top-level comma-separated items of a select list. */
    private static @NotNull List<List<Token>> commaItems(@NotNull List<Token> tokens) {
        List<List<Token>> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isPunct('(')) {
                depth++;
            } else if (t.isPunct(')')) {
                depth--;
            } else if (depth == 0 && t.isPunct(',')) {
                items.add(tokens.subList(start, i));
                start = i + 1;
            }
        }
        items.add(tokens.subList(start, tokens.size()));
        return items;
    }

    /** One select-list item's output columns (a {@code *} can produce several). */
    private static @NotNull List<ColumnMeta> itemColumns(@NotNull List<Token> item,
                                                         @NotNull List<TableReference> fromTables,
                                                         @NotNull CompletionScope scope,
                                                         @NotNull List<DerivedTable> outer) {
        if (item.isEmpty()) {
            return List.of();
        }
        // expr AS alias / expr alias
        int last = item.size() - 1;
        int aliasAt = -1;
        if (item.get(last).isName() && !item.get(last).is("select")) {
            if (last >= 1 && item.get(last - 1).is("as")) {
                aliasAt = last;
            } else if (last >= 2 && !NOT_A_SELECT_ALIAS.contains(item.get(last).value().toLowerCase(Locale.ROOT))
                    && endsAnExpression(item.get(last - 1))) {
                aliasAt = last;
            }
        }
        // t.* or *
        if (aliasAt < 0 && item.get(last).isPunct('*')) {
            if (last >= 2 && item.get(last - 1).isPunct('.') && item.get(last - 2).isName()) {
                TableMeta table = resolve(item.get(last - 2).value(), fromTables, scope, outer);
                return table == null ? List.of() : table.columns;
            }
            if (item.size() == 1) {
                List<ColumnMeta> all = new ArrayList<>();
                for (TableReference reference : fromTables) {
                    TableMeta table = resolve(reference.name(), fromTables, scope, outer);
                    if (table != null) {
                        all.addAll(table.columns);
                    }
                }
                return all;
            }
        }
        // a plain column: name or table.name
        int nameStart = aliasAt < 0 ? qualifiedNameStart(item, last) : qualifiedNameStart(item, aliasAt - 1);
        if (nameStart < 0) {
            return aliasAt < 0 ? List.of() : List.of(column(item.get(aliasAt).value(), ""));
        }
        List<Token> names = item.subList(nameStart, aliasAt < 0 ? last + 1 : aliasAt);
        String type = names.size() >= 3
                ? typeOf(names.get(0).value(), names.get(2).value(), fromTables, scope, outer)
                : "";
        String name = names.get(names.size() - 1).value();
        if (aliasAt >= 0) {
            return List.of(column(item.get(aliasAt).value(), type.isEmpty() ? typeOf(names.get(0).value(), name,
                    fromTables, scope, outer) : type));
        }
        return List.of(column(name, type));
    }

    /** Whether {@code t} can end an expression (so the name after it is an implicit alias). */
    private static boolean endsAnExpression(@NotNull Token t) {
        return t.isPunct(')') || t.type() == Type.STRING || t.type() == Type.NUMBER;
    }

    private static @NotNull ColumnMeta column(@NotNull String name, @NotNull String typeName) {
        return new ColumnMeta(name, typeName, true, "", 0, false, "");
    }

    private static @NotNull List<ColumnMeta> renamed(@NotNull List<ColumnMeta> columns,
                                                     @NotNull List<String> names) {
        List<ColumnMeta> result = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnMeta c = columns.get(i);
            result.add(i < names.size() ? column(names.get(i), c.typeName) : c);
        }
        return result;
    }

    private static @Nullable String typeOf(@NotNull String qualifier, @NotNull String column,
                                           @NotNull List<TableReference> fromTables,
                                           @NotNull CompletionScope scope,
                                           @NotNull List<DerivedTable> outer) {
        TableMeta table = resolve(qualifier, fromTables, scope, outer);
        if (table == null) {
            return "";
        }
        for (ColumnMeta c : table.columns) {
            if (c.name.equalsIgnoreCase(column)) {
                return c.typeName;
            }
        }
        return "";
    }

    /** A table by bare name or by the alias the FROM clause gave it. */
    private static @Nullable TableMeta resolve(@NotNull String name, @NotNull List<TableReference> fromTables,
                                               @NotNull CompletionScope scope,
                                               @NotNull List<DerivedTable> outer) {
        for (DerivedTable d : outer) {
            if (d.label().equalsIgnoreCase(name)) {
                return new TableMeta(d.label(), TableMeta.Kind.TABLE, d.columns(), "");
            }
        }
        SchemaCatalog catalog = scope.catalog();
        if (catalog == null) {
            return null;
        }
        String aliased = null;
        for (TableReference reference : fromTables) {
            if (reference.alias() != null && reference.alias().equalsIgnoreCase(name)) {
                aliased = reference.name();
            }
        }
        String target = aliased != null ? aliased : name;
        for (SchemaCatalog.Schema schema : schemaOrder(catalog, scope)) {
            for (TableMeta table : schema.tables()) {
                if (table.name.equalsIgnoreCase(target)) {
                    return table;
                }
            }
        }
        return null;
    }

    private static @NotNull List<SchemaCatalog.Schema> schemaOrder(@NotNull SchemaCatalog catalog,
                                                                   @NotNull CompletionScope scope) {
        List<SchemaCatalog.Schema> schemas = new ArrayList<>();
        if (scope.currentSchema() != null) {
            for (SchemaCatalog.Schema schema : catalog.schemas()) {
                if (schema.name().equals(scope.currentSchema())) {
                    schemas.add(schema);
                    break;
                }
            }
        }
        schemas.addAll(catalog.schemas());
        return schemas;
    }

    /** Index of the ')' matching the '(' at {@code open}, or -1 when it never closes. */
    private static int matchingParenthesis(@NotNull List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).isPunct('(')) {
                depth++;
            } else if (tokens.get(i).isPunct(')')) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
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
