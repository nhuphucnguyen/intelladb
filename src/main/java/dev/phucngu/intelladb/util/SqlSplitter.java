package dev.phucngu.intelladb.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into individual statements, respecting quotes and comments. What
 * counts as a quote or comment differs per database, hence {@link Options}; the MongoDB
 * shell's JavaScript-like syntax is one of them.
 */
public final class SqlSplitter {

    /**
     * Lexical rules of a SQL dialect that change where a statement ends.
     *
     * @param dollarQuotes     {@code $tag$ … $tag$} strings (PostgreSQL)
     * @param backtickQuotes   backtick-quoted identifiers (MySQL)
     * @param hashComments     {@code # …} line comments (MySQL; an operator in PostgreSQL)
     * @param backslashEscapes a backslash escapes the next character in string literals (MySQL default mode)
     * @param dashComments     {@code -- …} line comments (SQL; a decrement in JavaScript)
     * @param slashComments    {@code // …} line comments (the MongoDB shell)
     * @param newlineEnds      a line break outside brackets ends the statement unless the next
     *                         line continues it with {@code .} (the MongoDB shell)
     */
    public record Options(boolean dollarQuotes, boolean backtickQuotes, boolean hashComments,
                          boolean backslashEscapes, boolean dashComments, boolean slashComments,
                          boolean newlineEnds) {
        public static final Options POSTGRES = new Options(true, false, false, false, true, false, false);
        public static final Options MYSQL = new Options(false, true, true, true, true, false, false);
        public static final Options MONGO = new Options(false, true, false, true, false, true, true);

        /** Whether a line comment starts at {@code i}. */
        public boolean lineCommentAt(@NotNull CharSequence text, int i) {
            char c = text.charAt(i);
            boolean twice = i + 1 < text.length() && text.charAt(i + 1) == c;
            return (c == '-' && twice && dashComments) || (c == '/' && twice && slashComments)
                    || (c == '#' && hashComments);
        }
    }

    /**
     * Splits on top-level semicolons. Trailing semicolon optional per statement; empty
     * (whitespace/comment-only) statements are dropped. Comments are preserved inside statements.
     */
    public static @NotNull List<String> split(@NotNull String script) {
        return split(script, Options.POSTGRES);
    }

    public static @NotNull List<String> split(@NotNull String script, @NotNull Options options) {
        return ranges(script, options).stream().map(Statement::text).toList();
    }

    /** One statement of a script: trimmed text plus its [start, end) offsets in the script. */
    public record Statement(int start, int end, @NotNull String text) {
        public boolean contains(int offset) {
            return offset >= start && offset <= end;
        }
    }

    /** Like {@link #split} but keeps where each statement sits in the script. */
    public static @NotNull List<Statement> ranges(@NotNull String script) {
        return ranges(script, Options.POSTGRES);
    }

    public static @NotNull List<Statement> ranges(@NotNull String script, @NotNull Options options) {
        List<Statement> statements = new ArrayList<>();
        int segmentStart = 0;
        int i = 0;
        int n = script.length();
        int depth = 0; // (), [] and {} — only tracked where a line break can end a statement
        while (i < n) {
            char c = script.charAt(i);
            // Line comments
            if (options.lineCommentAt(script, i)) {
                int end = script.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                i = end;
                continue;
            }
            // Block comments
            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                i = end;
                continue;
            }
            // Dollar-quoted strings ($tag$ ... $tag$)
            if (c == '$' && options.dollarQuotes()) {
                int close = script.indexOf('$', i + 1);
                if (close > i && close - i <= 20 && isTagBody(script, i + 1, close)) {
                    String tag = script.substring(i, close + 1);
                    int bodyEnd = script.indexOf(tag, close + 1);
                    int end = bodyEnd < 0 ? n : bodyEnd + tag.length();
                    i = end;
                    continue;
                }
            }
            // Quoted strings and quoted identifiers
            if (c == '\'' || c == '"' || (c == '`' && options.backtickQuotes())) {
                int end = i + 1;
                while (end < n) {
                    if (c != '`' && options.backslashEscapes() && script.charAt(end) == '\\') {
                        end += 2; // \' \\ and friends
                        continue;
                    }
                    if (script.charAt(end) == c) {
                        if (end + 1 < n && script.charAt(end + 1) == c) {
                            end += 2; // escaped quote ('' or "")
                            continue;
                        }
                        end++;
                        break;
                    }
                    end++;
                }
                end = Math.min(end, n);
                i = end;
                continue;
            }
            if (c == ';') {
                addTrimmed(statements, script, segmentStart, i);
                i++;
                segmentStart = i;
                depth = 0;
                continue;
            }
            if (options.newlineEnds()) {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                    depth--;
                } else if (c == '\n' && depth == 0 && endsAtLineBreak(script, segmentStart, i, options)) {
                    addTrimmed(statements, script, segmentStart, i);
                    segmentStart = i + 1;
                }
            }
            i++;
        }
        addTrimmed(statements, script, segmentStart, n);
        return statements;
    }

    /**
     * Whether the line break at {@code newline} ends the statement begun at {@code start}:
     * not when nothing but comments came before it, when the line ends in an operator that
     * needs more ({@code . , : ( = + …}), or when the next line starts with {@code .} (a chained call).
     */
    private static boolean endsAtLineBreak(@NotNull String script, int start, int newline, @NotNull Options options) {
        String before = stripLeadingComments(script.substring(start, newline), options).strip();
        if (before.isEmpty()) {
            return false;
        }
        char last = before.charAt(before.length() - 1);
        if (".,:([{=+-*/&|?!<>".indexOf(last) >= 0) {
            return false;
        }
        String after = stripLeadingComments(script.substring(newline + 1), options);
        return after.isEmpty() || after.charAt(0) != '.';
    }

    /** The statement without the whitespace and comments before its first token. */
    public static @NotNull String stripLeadingComments(@NotNull String sql) {
        return stripLeadingComments(sql, Options.POSTGRES);
    }

    public static @NotNull String stripLeadingComments(@NotNull String sql, @NotNull Options options) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            if (Character.isWhitespace(sql.charAt(i))) {
                i++;
            } else if (options.lineCommentAt(sql, i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        return sql.substring(i);
    }

    private static void addTrimmed(@NotNull List<Statement> out, @NotNull String script, int start, int end) {
        while (start < end && Character.isWhitespace(script.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(script.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            out.add(new Statement(start, end, script.substring(start, end)));
        }
    }

    /**
     * The statement to run for a caret at {@code offset} ("Playground" mode): the one
     * containing it, else the nearest one before it (caret after the ';' or on a blank
     * line below), else the first one after it.
     */
    public static @Nullable Statement at(@NotNull String script, int offset) {
        return at(script, offset, Options.POSTGRES);
    }

    public static @Nullable Statement at(@NotNull String script, int offset, @NotNull Options options) {
        List<Statement> all = ranges(script, options);
        Statement before = null;
        for (Statement statement : all) {
            if (statement.contains(offset)) {
                return statement;
            }
            if (statement.end() <= offset) {
                before = statement;
            }
        }
        if (before != null) {
            return before;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static boolean isTagBody(@NotNull String s, int start, int end) {
        if (start >= end) {
            return true; // $$ empty tag
        }
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private SqlSplitter() {
    }
}
