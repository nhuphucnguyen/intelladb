package community.intelladb.sql;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/** Maps IntellaSQL token types to the standard editor color scheme keys. */
public final class IntellaSqlSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final TextAttributesKey[] EMPTY = new TextAttributesKey[0];

    public static final TextAttributesKey SQL_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey SQL_STRING = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey SQL_NUMBER = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey SQL_COMMENT = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey SQL_BLOCK_COMMENT = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    public static final TextAttributesKey SQL_PAREN = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_PAREN", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey SQL_COMMA = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_COMMA", DefaultLanguageHighlighterColors.COMMA);
    public static final TextAttributesKey SQL_OPERATOR = TextAttributesKey.createTextAttributesKey(
            "INTellaDB_SQL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new IntellaSqlLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == null) {
            return EMPTY;
        }
        if (tokenType == IntellaSqlLanguage.Tokens.KEYWORD) {
            return pack(SQL_KEYWORD);
        }
        if (tokenType == IntellaSqlLanguage.Tokens.STRING) {
            return pack(SQL_STRING);
        }
        if (tokenType == IntellaSqlLanguage.Tokens.NUMBER) {
            return pack(SQL_NUMBER);
        }
        if (tokenType == IntellaSqlLanguage.Tokens.LINE_COMMENT) {
            return pack(SQL_COMMENT);
        }
        if (tokenType == IntellaSqlLanguage.Tokens.BLOCK_COMMENT) {
            return pack(SQL_BLOCK_COMMENT);
        }
        if (tokenType == IntellaSqlLanguage.Tokens.PARENTHESIS) {
            return pack(SQL_PAREN);
        }
        if (tokenType == IntellaSqlLanguage.Tokens.COMMA) {
            return pack(SQL_COMMA);
        }
        if (tokenType == IntellaSqlLanguage.Tokens.OPERATOR) {
            return pack(SQL_OPERATOR);
        }
        return EMPTY;
    }

    /** EP target for {@code lang.syntaxHighlighterFactory}. */
    public static final class Factory extends com.intellij.openapi.fileTypes.SyntaxHighlighterFactory {
        @Override
        public @NotNull com.intellij.openapi.fileTypes.SyntaxHighlighter getSyntaxHighlighter(
                com.intellij.openapi.project.Project project, com.intellij.openapi.vfs.VirtualFile file) {
            return new IntellaSqlSyntaxHighlighter();
        }
    }
}
