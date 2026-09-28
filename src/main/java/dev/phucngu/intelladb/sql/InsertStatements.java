package dev.phucngu.intelladb.sql;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-level parser for INSERT statements that maps each column in the column list to
 * the value slot at the same position in every VALUES tuple — the data behind the
 * "which value belongs to which column" caret highlighting.
 *
 * Tolerant by design: anything it cannot confidently pair (missing column list,
 * INSERT ... SELECT, mismatched counts) simply yields no pairing rather than wrong pairing.
 */
public final class InsertStatements {

    /** One parsed INSERT statement. Offsets are document-relative. */
    public static final class Statement {
        public final int startOffset;
        public final int endOffset;
        public final List<TextRange> columns;
        public final List<List<TextRange>> tuples;

        Statement(int startOffset, int endOffset, List<TextRange> columns, List<List<TextRange>> tuples) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.columns = columns;
            this.tuples = tuples;
        }

        public boolean isPairable() {
            return !columns.isEmpty() && !tuples.isEmpty();
        }
    }

    /** The pair for one caret position: the column slot index, and which segments exist there. */
    public record Pairing(TextRange columnRange, TextRange caretValueRange, List<TextRange> sameSlotValues) {
    }

    private record Tok(int type, int start, int end) {
    }

    private static final int T_WORD = 0;
    private static final int T_NUMBER = 1;
    private static final int T_STRING = 2;
    private static final int T_QUOTED = 3;   // "quoted identifier"
    private static final int T_PUNCT = 4;    // ( ) , ; . and operators
    private static final int T_OTHER = 5;

    private InsertStatements() {
    }

    /** Parses every INSERT statement it can find; never throws. */
    public static @NotNull List<Statement> parse(@NotNull String text) {
        List<Statement> result = new ArrayList<>();
        if (text.length() > 200_000) {
            return result;
        }
        List<Tok> tokens = tokenize(text);
        int i = 0;
        while (i < tokens.size()) {
            Tok t = tokens.get(i);
            if (t.type == T_WORD && isKeyword(t, text, "insert") && atStatementBoundary(text, tokens, i)) {
                int from = i;
                i = parseInsert(text, tokens, i, result);
                if (i <= from) {
                    i = from + 1;
                }
            } else {
                i++;
            }
        }
        return result;
    }

    /** Finds the column/value slot under {@code offset} within the statement, or null. */
    public static @Nullable Pairing pairingFor(@NotNull Statement statement, int offset) {
        int columnIndex = -1;
        TextRange caretColumn = null;
        for (int c = 0; c < statement.columns.size(); c++) {
            if (statement.columns.get(c).containsOffset(offset)) {
                columnIndex = c;
                caretColumn = statement.columns.get(c);
                break;
            }
        }
        TextRange caretValue = null;
        int valueColumnIndex = -1;
        if (columnIndex < 0) {
            for (List<TextRange> tuple : statement.tuples) {
                for (int v = 0; v < tuple.size(); v++) {
                    TextRange range = tuple.get(v);
                    if (range.containsOffset(offset)) {
                        caretValue = range;
                        valueColumnIndex = v;
                        break;
                    }
                }
                if (caretValue != null) {
                    break;
                }
            }
        }
        int index = columnIndex >= 0 ? columnIndex : valueColumnIndex;
        if (index < 0 || index >= statement.columns.size()) {
            return null;
        }
        List<TextRange> sameSlot = new ArrayList<>();
        for (List<TextRange> tuple : statement.tuples) {
            if (index < tuple.size()) {
                sameSlot.add(tuple.get(index));
            }
        }
        return new Pairing(statement.columns.get(index), caretValue, sameSlot);
    }

    // ------------------------------------------------------------------ parsing

    private static int parseInsert(@NotNull String text, @NotNull List<Tok> tokens, int insertIdx,
                                   @NotNull List<Statement> out) {
        int i = insertIdx + 1;
        // optional INTO
        if (i < tokens.size() && tokens.get(i).type == T_WORD && isKeyword(tokens.get(i), text, "into")) {
            i++;
        }
        // skip table name (a.b.c) — identifiers, dots, and nothing else structural
        while (i < tokens.size()) {
            Tok t = tokens.get(i);
            boolean isDot = t.type == T_PUNCT && charAt(text, t) == '.';
            if (t.type == T_WORD || t.type == T_QUOTED || t.type == T_STRING
                    || t.type == T_NUMBER || isDot) {
                i++;
            } else {
                break;
            }
        }
        if (i >= tokens.size() || tokens.get(i).type != T_PUNCT || charAt(text, tokens.get(i)) != '(') {
            // INSERT ... without a column list: still record the statement, but it is not pairable
            int end = i;
            while (end < tokens.size() && !(tokens.get(end).type == T_PUNCT
                    && charAt(text, tokens.get(end)) == ';')) {
                end++;
            }
            int endOffset = end < tokens.size() ? tokens.get(end).end
                    : endOffsetOf(text, tokens, insertIdx, tokens.size());
            out.add(new Statement(tokens.get(insertIdx).start, endOffset, List.of(), List.of()));
            return Math.max(end, insertIdx + 1);
        }
        int columnsOpen = i;
        int close = matchParen(text, tokens, i);
        if (close < 0) {
            return i;
        }
        List<TextRange> columns = segmentsBetween(text, tokens, columnsOpen, close);
        i = close + 1;
        // optional VALUES keyword
        if (i < tokens.size() && tokens.get(i).type == T_WORD && isKeyword(tokens.get(i), text, "values")) {
            i++;
        }
        List<List<TextRange>> tuples = new ArrayList<>();
        int statementEnd = i;
        while (i < tokens.size()) {
            Tok t = tokens.get(i);
            if (t.type == T_PUNCT && charAt(text, t) == '(') {
                int tupleClose = matchParen(text, tokens, i);
                if (tupleClose < 0) {
                    break;
                }
                tuples.add(segmentsBetween(text, tokens, i, tupleClose));
                i = tupleClose + 1;
                statementEnd = i;
                // expect , then next tuple, or end of statement
                if (i < tokens.size() && tokens.get(i).type == T_PUNCT && charAt(text, tokens.get(i)) == ',') {
                    i++;
                    if (i < tokens.size() && tokens.get(i).type == T_PUNCT && charAt(text, tokens.get(i)) == '(') {
                        continue;
                    }
                    // something else followed the comma (shouldn't happen) — stop pairing
                    break;
                }
                break;
            }
            // INSERT ... SELECT etc. — no value tuples to pair
            break;
        }
        out.add(new Statement(tokens.get(insertIdx).start,
                endOffsetOf(text, tokens, insertIdx, statementEnd), columns, tuples));
        return Math.max(statementEnd, insertIdx + 1);
    }

    /**
     * Character offset just past the statement — the end of the last consumed token
     * ({@code lastTokenIdx} is a token index, exclusive), extended over a terminating
     * semicolon so a caret resting on it still counts as inside the statement.
     */
    private static int endOffsetOf(@NotNull String text, @NotNull List<Tok> tokens,
                                   int insertIdx, int lastTokenIdx) {
        int last = lastTokenIdx - 1;
        if (last < insertIdx) {
            return tokens.get(insertIdx).end;
        }
        int end = tokens.get(last).end;
        if (last + 1 < tokens.size() && tokens.get(last + 1).type == T_PUNCT
                && charAt(text, tokens.get(last + 1)) == ';') {
            end = tokens.get(last + 1).end;
        }
        return end;
    }

    private static int matchParen(@NotNull String text, @NotNull List<Tok> tokens, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            Tok t = tokens.get(i);
            if (t.type != T_PUNCT) {
                continue;
            }
            char c = charAt(text, t);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Segments are the token ranges between top-level commas of the token span (open, close). */
    private static @NotNull List<TextRange> segmentsBetween(@NotNull String text, @NotNull List<Tok> tokens,
                                                            int openIdx, int closeIdx) {
        List<TextRange> segments = new ArrayList<>();
        int depth = 0;
        int segStart = -1;
        int segEnd = -1;
        for (int i = openIdx + 1; i < closeIdx; i++) {
            Tok t = tokens.get(i);
            boolean isParen = t.type == T_PUNCT;
            char c = isParen ? charAt(text, t) : ' ';
            boolean isTopComma = t.type == T_PUNCT && c == ',' && depth == 0;
            if (isTopComma) {
                if (segStart >= 0) {
                    segments.add(new TextRange(segStart, segEnd));
                }
                segStart = -1;
                segEnd = -1;
                continue;
            }
            if (isParen && c == '(') {
                depth++;
            }
            if (t.type == T_WORD || t.type == T_STRING || t.type == T_NUMBER || t.type == T_QUOTED
                    || isParen || t.type == T_OTHER) {
                if (segStart < 0) {
                    segStart = t.start;
                }
                segEnd = t.end;
            }
            if (isParen && c == ')' && depth > 0) {
                depth--;
            }
        }
        if (segStart >= 0) {
            segments.add(new TextRange(segStart, segEnd));
        }
        return segments;
    }

    // ------------------------------------------------------------------ tokenizer

    private static @NotNull List<Tok> tokenize(@NotNull String text) {
        List<Tok> tokens = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (text.charAt(j) == '\'') {
                        if (j + 1 < n && text.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        j++;
                        break;
                    }
                    j++;
                }
                tokens.add(new Tok(T_STRING, i, Math.min(j, n)));
                i = Math.min(j, n);
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < n && text.charAt(j) != '"') {
                    j++;
                }
                tokens.add(new Tok(T_QUOTED, i, Math.min(j + 1, n)));
                i = Math.min(j + 1, n);
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_'
                        || text.charAt(j) == '$')) {
                    j++;
                }
                tokens.add(new Tok(Character.isDigit(c) ? T_NUMBER : T_WORD, i, j));
                i = j;
                continue;
            }
            if (c == '(' || c == ')' || c == ',' || c == ';' || c == '.') {
                tokens.add(new Tok(T_PUNCT, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Tok(T_OTHER, i, i + 1));
            i++;
        }
        return tokens;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean atStatementBoundary(@NotNull String text, @NotNull List<Tok> tokens, int idx) {
        if (idx == 0) {
            return true;
        }
        Tok prev = tokens.get(idx - 1);
        return prev.type == T_PUNCT && charAt(text, prev) == ';';
    }

    private static char charAt(@NotNull String text, @NotNull Tok t) {
        return text.charAt(t.start);
    }

    private static boolean isKeyword(@NotNull Tok t, @NotNull String text, @NotNull String keyword) {
        return t.type == T_WORD && keyword.equalsIgnoreCase(text.substring(t.start, t.end));
    }
}
