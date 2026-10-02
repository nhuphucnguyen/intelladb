package dev.phucngu.intelladb.sql;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Maps IntellaSQL token types to keys that fall back to the platform's default language
 * colors, so the active editor color scheme (light, dark, high contrast, custom) supplies
 * them and they follow theme switches.
 */
public final class IntellaSqlSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final TextAttributesKey[] EMPTY = new TextAttributesKey[0];

    private static final TextAttributesKey SQL_KEYWORD = key("INTELLADB_SQL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    private static final TextAttributesKey SQL_STRING = key("INTELLADB_SQL_STRING", DefaultLanguageHighlighterColors.STRING);
    private static final TextAttributesKey SQL_NUMBER = key("INTELLADB_SQL_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    private static final TextAttributesKey SQL_COMMENT = key("INTELLADB_SQL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    private static final TextAttributesKey SQL_BLOCK_COMMENT = key("INTELLADB_SQL_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    private static final TextAttributesKey SQL_PAREN = key("INTELLADB_SQL_PAREN", DefaultLanguageHighlighterColors.PARENTHESES);
    private static final TextAttributesKey SQL_COMMA = key("INTELLADB_SQL_COMMA", DefaultLanguageHighlighterColors.COMMA);
    private static final TextAttributesKey SQL_OPERATOR = key("INTELLADB_SQL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);

    private IntellaSqlSyntaxHighlighter() {
    }

    private static TextAttributesKey key(@NotNull String name, @NotNull TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey(name, fallback);
    }

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
