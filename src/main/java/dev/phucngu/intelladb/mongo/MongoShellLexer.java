package dev.phucngu.intelladb.mongo;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import dev.phucngu.intelladb.mongo.MongoShellLanguage.Tokens;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Highlighting tokens for the MongoDB console: JavaScript-ish strings, numbers, // and
 * block comments, regex literals, {@code $operators}, field names before ':', shell
 * constructors and method calls. Like the SQL lexer it never fails: anything else is an
 * operator token.
 */
public final class MongoShellLexer extends LexerBase {

    private static final Set<String> KEYWORDS = Set.of("db", "use", "show", "true", "false", "null", "undefined", "new");

    private CharSequence buffer;
    private int endOffset;
    private int position;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;
    /** Last token that wasn't whitespace or a comment: a '/' after a value divides, else starts a regex. */
    private IElementType previous;
    private char previousChar;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.position = startOffset;
        this.endOffset = endOffset;
        this.previous = null;
        this.previousChar = 0;
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
        if (tokenType != null && tokenType != Tokens.WHITESPACE && tokenType != Tokens.LINE_COMMENT
                && tokenType != Tokens.BLOCK_COMMENT) {
            previous = tokenType;
            previousChar = buffer.charAt(tokenEnd - 1);
        }
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
            tokenEnd = skip(position + 1, ch -> Character.isWhitespace(ch));
            tokenType = Tokens.WHITESPACE;
        } else if (c == '/' && next(1) == '/') {
            int i = position + 2;
            while (i < endOffset && buffer.charAt(i) != '\n') {
                i++;
            }
            tokenEnd = i;
            tokenType = Tokens.LINE_COMMENT;
        } else if (c == '/' && next(1) == '*') {
            int i = position + 2;
            while (i + 1 < endOffset && !(buffer.charAt(i) == '*' && buffer.charAt(i + 1) == '/')) {
                i++;
            }
            tokenEnd = Math.min(i + 2, endOffset);
            tokenType = Tokens.BLOCK_COMMENT;
        } else if (c == '/' && regexAllowed()) {
            tokenEnd = regexEnd();
            tokenType = Tokens.REGEX;
        } else if (c == '\'' || c == '"' || c == '`') {
            int i = position + 1;
            while (i < endOffset && buffer.charAt(i) != c && (c == '`' || buffer.charAt(i) != '\n')) {
                i += buffer.charAt(i) == '\\' ? 2 : 1;
            }
            tokenEnd = Math.min(i + 1, endOffset);
            tokenType = followedByColon(tokenEnd) ? Tokens.PROPERTY : Tokens.STRING;
        } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(next(1)))) {
            int i = position + 1;
            while (i < endOffset && (Character.isLetterOrDigit(buffer.charAt(i)) || buffer.charAt(i) == '.'
                    || buffer.charAt(i) == '_' || ((buffer.charAt(i) == '+' || buffer.charAt(i) == '-')
                    && (buffer.charAt(i - 1) == 'e' || buffer.charAt(i - 1) == 'E')))) {
                i++;
            }
            tokenEnd = i;
            tokenType = Tokens.NUMBER;
        } else if (MongoShellParser.isIdentifierStart(c)) {
            int i = skip(position + 1, ch -> MongoShellParser.isIdentifierPart((char) ch));
            String word = buffer.subSequence(position, i).toString();
            tokenEnd = i;
            if (c == '$') {
                tokenType = Tokens.OPERATOR_NAME;
            } else if (followedByColon(i)) {
                tokenType = Tokens.PROPERTY;
            } else if (KEYWORDS.contains(word)) {
                tokenType = Tokens.KEYWORD;
            } else if (followedBy(i, '(')) {
                tokenType = Tokens.FUNCTION;
            } else {
                tokenType = Tokens.IDENTIFIER;
            }
        } else {
            tokenEnd = position + 1;
            tokenType = switch (c) {
                case '(', ')', '[', ']', '{', '}' -> Tokens.BRACKET;
                case ',' -> Tokens.COMMA;
                case ';' -> Tokens.SEMICOLON;
                default -> Tokens.OPERATOR;
            };
        }
    }

    /** A '/' starts a regex where a value may start: after ( [ { , : = or at the beginning. */
    private boolean regexAllowed() {
        return previous == null || (previous == Tokens.BRACKET && "([{".indexOf(previousChar) >= 0)
                || previous == Tokens.COMMA || (previous == Tokens.OPERATOR && ":=!&|?".indexOf(previousChar) >= 0);
    }

    private int regexEnd() {
        int i = position + 1;
        boolean inClass = false;
        while (i < endOffset && buffer.charAt(i) != '\n') {
            char ch = buffer.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '[') {
                inClass = true;
            } else if (ch == ']') {
                inClass = false;
            } else if (ch == '/' && !inClass) {
                return skip(i + 1, Character::isLetter);
            }
            i++;
        }
        return Math.min(i, endOffset);
    }

    private boolean followedByColon(int from) {
        return followedBy(from, ':');
    }

    private boolean followedBy(int from, char wanted) {
        int i = skip(from, ch -> ch == ' ' || ch == '\t');
        return i < endOffset && buffer.charAt(i) == wanted;
    }

    private char next(int offset) {
        int i = position + offset;
        return i < endOffset ? buffer.charAt(i) : 0;
    }

    private int skip(int from, @NotNull java.util.function.IntPredicate while_) {
        int i = from;
        while (i < endOffset && while_.test(buffer.charAt(i))) {
            i++;
        }
        return i;
    }
}
