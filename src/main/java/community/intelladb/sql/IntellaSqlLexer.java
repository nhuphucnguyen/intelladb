package community.intelladb.sql;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Hand-rolled SQL tokenizer for highlighting: strings with '' escapes, quoted identifiers,
 * line/block comments, numbers, and keyword-aware words. Deliberately permissive —
 * unknown characters become OPERATOR tokens rather than errors.
 */
public final class IntellaSqlLexer extends LexerBase {

    private static final Set<String> KEYWORDS = Set.of(
            "add", "all", "alter", "and", "as", "asc", "auto_increment", "between", "bigserial", "bigint",
            "boolean", "by", "case", "cast", "check", "column", "commit", "constraint", "create", "cross",
            "current_date", "current_timestamp", "default", "delete", "desc", "distinct", "double", "drop",
            "else", "end", "exists", "foreign", "from", "full", "group", "having", "if", "in", "index",
            "inner", "insert", "integer", "into", "is", "join", "json", "jsonb", "key", "left", "like",
            "limit", "not", "null", "numeric", "offset", "on", "or", "order", "outer", "precision",
            "primary", "references", "returning", "right", "rollback", "row", "select", "serial",
            "set", "smallint", "table", "text", "then", "timestamp", "to", "truncate", "union",
            "unique", "update", "uuid", "values", "varchar", "view", "when", "where", "with");

    private CharSequence buffer;
    private int endOffset;
    private int position;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.position = startOffset;
        this.endOffset = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        locateToken();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        position = tokenEnd;
        locateToken();
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return endOffset;
    }

    private void locateToken() {
        tokenStart = position;
        if (position >= endOffset) {
            tokenEnd = endOffset;
            tokenType = null;
            return;
        }
        char c = buffer.charAt(position);
        if (Character.isWhitespace(c)) {
            int i = position + 1;
            while (i < endOffset && Character.isWhitespace(buffer.charAt(i))) {
                i++;
            }
            tokenEnd = i;
            tokenType = IntellaSqlLanguage.Tokens.WHITESPACE;
            return;
        }
        if (c == '-' && position + 1 < endOffset && buffer.charAt(position + 1) == '-') {
            int i = position + 2;
            while (i < endOffset && buffer.charAt(i) != '\n') {
                i++;
            }
            tokenEnd = i;
            tokenType = IntellaSqlLanguage.Tokens.LINE_COMMENT;
            return;
        }
        if (c == '/' && position + 1 < endOffset && buffer.charAt(position + 1) == '*') {
            int i = position + 2;
            while (i + 1 < endOffset && !(buffer.charAt(i) == '*' && buffer.charAt(i + 1) == '/')) {
                i++;
            }
            i = Math.min(i + 2, endOffset);
            tokenEnd = i;
            tokenType = IntellaSqlLanguage.Tokens.BLOCK_COMMENT;
            return;
        }
        if (c == '\'') {
            int i = position + 1;
            while (i < endOffset) {
                if (buffer.charAt(i) == '\'') {
                    if (i + 1 < endOffset && buffer.charAt(i + 1) == '\'') {
                        i += 2; // '' escape
                        continue;
                    }
                    i++;
                    break;
                }
                i++;
            }
            tokenEnd = Math.min(i, endOffset);
            tokenType = IntellaSqlLanguage.Tokens.STRING;
            return;
        }
        if (c == '"') {
            int i = position + 1;
            while (i < endOffset && buffer.charAt(i) != '"') {
                i++;
            }
            tokenEnd = Math.min(i + 1, endOffset);
            tokenType = IntellaSqlLanguage.Tokens.IDENTIFIER;
            return;
        }
        if (Character.isDigit(c)) {
            int i = position + 1;
            while (i < endOffset && (Character.isLetterOrDigit(buffer.charAt(i)) || buffer.charAt(i) == '.'
                    || ((buffer.charAt(i) == '+' || buffer.charAt(i) == '-')
                    && (buffer.charAt(i - 1) == 'e' || buffer.charAt(i - 1) == 'E')))) {
                i++;
            }
            tokenEnd = i;
            tokenType = IntellaSqlLanguage.Tokens.NUMBER;
            return;
        }
        if (Character.isLetter(c) || c == '_') {
            int i = position + 1;
            while (i < endOffset && (Character.isLetterOrDigit(buffer.charAt(i)) || buffer.charAt(i) == '_')) {
                i++;
            }
            String word = buffer.subSequence(position, i).toString();
            tokenEnd = i;
            tokenType = KEYWORDS.contains(word.toLowerCase()) ? IntellaSqlLanguage.Tokens.KEYWORD
                    : IntellaSqlLanguage.Tokens.IDENTIFIER;
            return;
        }
        if (c == '(' || c == ')') {
            tokenEnd = position + 1;
            tokenType = IntellaSqlLanguage.Tokens.PARENTHESIS;
            return;
        }
        if (c == ',') {
            tokenEnd = position + 1;
            tokenType = IntellaSqlLanguage.Tokens.COMMA;
            return;
        }
        if (c == ';') {
            tokenEnd = position + 1;
            tokenType = IntellaSqlLanguage.Tokens.SEMICOLON;
            return;
        }
        if (c == '.') {
            tokenEnd = position + 1;
            tokenType = IntellaSqlLanguage.Tokens.OPERATOR;
            return;
        }
        int i = position + 1;
        tokenEnd = i;
        tokenType = IntellaSqlLanguage.Tokens.OPERATOR;
    }
}
