package dev.phucngu.intelladb.mongo;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.SingleLazyInstanceSyntaxHighlighterFactory;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import com.intellij.ui.JBColor;
import dev.phucngu.intelladb.mongo.MongoShellLanguage.Tokens;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.util.Map;

/** Fixed, theme-brightness-aware colors for the MongoDB console, like the SQL console's highlighter. */
public final class MongoShellHighlighter extends SyntaxHighlighterBase {

    private static final boolean BRIGHT = JBColor.isBright();
    private static final TextAttributesKey[] EMPTY = new TextAttributesKey[0];

    private static final Map<IElementType, TextAttributesKey> KEYS = Map.of(
            Tokens.KEYWORD, key("KEYWORD", 0x0033B3, 0xCF8E6D, Font.PLAIN),
            Tokens.STRING, key("STRING", 0x067D17, 0x6AAB73, Font.PLAIN),
            Tokens.REGEX, key("REGEX", 0x264EFF, 0x42C3D4, Font.PLAIN),
            Tokens.NUMBER, key("NUMBER", 0x1750EB, 0x2AACB8, Font.PLAIN),
            Tokens.LINE_COMMENT, key("LINE_COMMENT", 0x8C8C8C, 0x7A7A7B, Font.ITALIC),
            Tokens.BLOCK_COMMENT, key("BLOCK_COMMENT", 0x8C8C8C, 0x7A7A7B, Font.ITALIC),
            Tokens.OPERATOR_NAME, key("OPERATOR_NAME", 0x871094, 0xC77DBB, Font.PLAIN),
            Tokens.PROPERTY, key("PROPERTY", 0x871094, 0xC77DBB, Font.PLAIN),
            Tokens.FUNCTION, key("FUNCTION", 0x00627A, 0x56A8F5, Font.PLAIN));

    /** Deterministic colors: no theme defines these keys, so the fallback always applies (see IntellaSqlSyntaxHighlighter). */
    @SuppressWarnings("deprecation")
    private static @NotNull TextAttributesKey key(@NotNull String name, int light, int dark, int style) {
        return TextAttributesKey.createTextAttributesKey("INTELLADB_MONGO_" + name,
                new TextAttributes(new Color(BRIGHT ? light : dark), null, null, null, style));
    }

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new MongoShellLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        TextAttributesKey key = tokenType == null ? null : KEYS.get(tokenType);
        return key == null ? EMPTY : pack(key);
    }

    public static final class Factory extends SingleLazyInstanceSyntaxHighlighterFactory {
        @Override
        protected @NotNull SyntaxHighlighter createHighlighter() {
            return new MongoShellHighlighter();
        }
    }
}
