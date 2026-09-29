package dev.phucngu.intelladb.sql.completion;

import dev.phucngu.intelladb.util.SqlSplitter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits SQL into the tokens completion reasons about. Follows the same lexical rules as
 * {@link SqlSplitter} (per-dialect quotes and comments), and keeps comments and strings as
 * tokens so the caret can be recognised as being inside one.
 */
final class SqlTokenizer {

    enum Type { WORD, QUOTED_IDENTIFIER, STRING, NUMBER, COMMENT, PUNCT }

    /**
     * @param value      the identifier without its quotes for {@link Type#QUOTED_IDENTIFIER}, else the text
     * @param terminated false for a string, quoted identifier or block comment still open at the end of the text
     */
    record Token(@NotNull Type type, int start, int end, @NotNull String value, boolean terminated) {
        boolean is(@NotNull String keyword) {
            return type == Type.WORD && value.equalsIgnoreCase(keyword);
        }

        boolean isPunct(char c) {
            return type == Type.PUNCT && value.length() == 1 && value.charAt(0) == c;
        }

        boolean isName() {
            return type == Type.WORD || type == Type.QUOTED_IDENTIFIER;
        }
    }

    static @NotNull List<Token> tokenize(@NotNull String text, @NotNull SqlSplitter.Options options) {
        List<Token> tokens = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if ((c == '-' && i + 1 < n && text.charAt(i + 1) == '-') || (c == '#' && options.hashComments())) {
                int end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                tokens.add(new Token(Type.COMMENT, i, end, text.substring(i, end), true));
                i = end;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                tokens.add(new Token(Type.COMMENT, i, end, text.substring(i, end), close >= 0));
                i = end;
            } else if (c == '$' && options.dollarQuotes() && dollarTagEnd(text, i) > 0) {
                int tagEnd = dollarTagEnd(text, i);
                String tag = text.substring(i, tagEnd);
                int close = text.indexOf(tag, tagEnd);
                int end = close < 0 ? n : close + tag.length();
                tokens.add(new Token(Type.STRING, i, end, text.substring(i, end), close >= 0));
                i = end;
            } else if (c == '\'' || c == '"' || (c == '`' && options.backtickQuotes())) {
                int end = quotedEnd(text, i, c, options.backslashEscapes() && c != '`');
                boolean terminated = end <= n && end > i + 1 && text.charAt(end - 1) == c;
                end = Math.min(end, n);
                if (c == '\'') {
                    tokens.add(new Token(Type.STRING, i, end, text.substring(i, end), terminated));
                } else {
                    String inner = text.substring(i + 1, terminated ? end - 1 : end);
                    tokens.add(new Token(Type.QUOTED_IDENTIFIER, i, end,
                            inner.replace(String.valueOf(c) + c, String.valueOf(c)), terminated));
                }
                i = end;
            } else if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(Type.NUMBER, i, end, text.substring(i, end), true));
                i = end;
            } else if (isWordStart(c)) {
                int end = i + 1;
                while (end < n && isWordPart(text.charAt(end))) {
                    end++;
                }
                tokens.add(new Token(Type.WORD, i, end, text.substring(i, end), true));
                i = end;
            } else {
                tokens.add(new Token(Type.PUNCT, i, i + 1, String.valueOf(c), true));
                i++;
            }
        }
        return tokens;
    }

    static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** End (exclusive) of a quoted run starting at {@code start}; past the text when unterminated. */
    private static int quotedEnd(@NotNull String text, int start, char quote, boolean backslashEscapes) {
        int n = text.length();
        int i = start + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
            } else if (c == quote) {
                if (i + 1 < n && text.charAt(i + 1) == quote) {
                    i += 2; // doubled quote
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return n + 1;
    }

    /** End (exclusive) of a {@code $tag$} opener at {@code start}, or -1 when there is none. */
    private static int dollarTagEnd(@NotNull String text, int start) {
        int close = text.indexOf('$', start + 1);
        if (close < 0 || close - start > 20) {
            return -1;
        }
        for (int i = start + 1; i < close; i++) {
            if (!isWordPart(text.charAt(i)) || text.charAt(i) == '$') {
                return -1;
            }
        }
        return close + 1;
    }

    private SqlTokenizer() {
    }
}
