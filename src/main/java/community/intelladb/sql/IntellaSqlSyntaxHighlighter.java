package community.intelladb.sql;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;

/**
 * Maps IntellaSQL token types to fixed, theme-brightness-aware colors. Embedded
 * EditorTextFields resolve scheme-based attribute keys inconsistently (light-scheme
 * keywords could land on dark backgrounds); fixed attributes cannot misresolve.
 */
public final class IntellaSqlSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final TextAttributesKey[] EMPTY = new TextAttributesKey[0];

    private static final boolean BRIGHT = JBColor.isBright();

    private static final TextAttributesKey SQL_KEYWORD = key("INTellaDB_SQL_KEYWORD",
            BRIGHT ? new Color(0x0033B3) : new Color(0xCF8E6D));
    private static final TextAttributesKey SQL_STRING = key("INTellaDB_SQL_STRING",
            BRIGHT ? new Color(0x067D17) : new Color(0x6AAB73));
    private static final TextAttributesKey SQL_NUMBER = key("INTellaDB_SQL_NUMBER",
            BRIGHT ? new Color(0x1750EB) : new Color(0x2AACB8));
    private static final TextAttributesKey SQL_COMMENT = key("INTellaDB_SQL_COMMENT",
            BRIGHT ? new Color(0x8C8C8C) : new Color(0x7A7A7B));
    private static final TextAttributesKey SQL_BLOCK_COMMENT = key("INTellaDB_SQL_BLOCK_COMMENT",
            BRIGHT ? new Color(0x8C8C8C) : new Color(0x7A7A7B));
    private static final TextAttributesKey SQL_PAREN = key("INTellaDB_SQL_PAREN",
            BRIGHT ? new Color(0x000000) : new Color(0xBCBEC4));
    private static final TextAttributesKey SQL_COMMA = key("INTellaDB_SQL_COMMA",
            BRIGHT ? new Color(0x000000) : new Color(0xBCBEC4));
    private static final TextAttributesKey SQL_OPERATOR = key("INTellaDB_SQL_OPERATOR",
            BRIGHT ? new Color(0x000000) : new Color(0xBCBEC4));

    private IntellaSqlSyntaxHighlighter() {
    }

    /**
     * The deprecated fixed-attributes variant is deliberate: no theme defines these keys,
     * so the fallback attributes always apply and the colors are deterministic.
     */
    @SuppressWarnings("deprecation")
    private static TextAttributesKey key(@NotNull String name, @NotNull Color foreground) {
        return TextAttributesKey.createTextAttributesKey(name,
                new com.intellij.openapi.editor.markup.TextAttributes(foreground, null, null, null, Font.PLAIN));
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
