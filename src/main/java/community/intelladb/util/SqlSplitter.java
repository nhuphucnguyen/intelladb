package community.intelladb.util;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Splits a SQL script into individual statements, respecting '', "", comments and dollar-quotes. */
public final class SqlSplitter {

    /**
     * Splits on top-level semicolons. Trailing semicolon optional per statement; empty
     * (whitespace/comment-only) statements are dropped. Comments are preserved inside statements.
     */
    public static @NotNull List<String> split(@NotNull String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);
            // Line comments
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int end = script.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                current.append(script, i, end);
                i = end;
                continue;
            }
            // Block comments
            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                current.append(script, i, end);
                i = end;
                continue;
            }
            // Dollar-quoted strings ($tag$ ... $tag$)
            if (c == '$') {
                int close = script.indexOf('$', i + 1);
                if (close > i && close - i <= 20 && isTagBody(script, i + 1, close)) {
                    String tag = script.substring(i, close + 1);
                    int bodyEnd = script.indexOf(tag, close + 1);
                    int end = bodyEnd < 0 ? n : bodyEnd + tag.length();
                    current.append(script, i, end);
                    i = end;
                    continue;
                }
            }
            // Quoted strings and quoted identifiers
            if (c == '\'' || c == '"') {
                int end = i + 1;
                while (end < n) {
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
                current.append(script, i, end);
                i = end;
                continue;
            }
            if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
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
