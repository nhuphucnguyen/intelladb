package dev.phucngu.intelladb.mongo;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SingleLazyInstanceSyntaxHighlighterFactory;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import dev.phucngu.intelladb.mongo.MongoShellLanguage.Tokens;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Scheme-driven colors for the MongoDB console, like the SQL console's highlighter. */
public final class MongoShellHighlighter extends SyntaxHighlighterBase {

    private static final TextAttributesKey[] EMPTY = new TextAttributesKey[0];

    private static final Map<IElementType, TextAttributesKey> KEYS = Map.of(
            Tokens.KEYWORD, key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD),
            Tokens.STRING, key("STRING", DefaultLanguageHighlighterColors.STRING),
            Tokens.REGEX, key("REGEX", DefaultLanguageHighlighterColors.STRING),
            Tokens.NUMBER, key("NUMBER", DefaultLanguageHighlighterColors.NUMBER),
            Tokens.LINE_COMMENT, key("LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT),
            Tokens.BLOCK_COMMENT, key("BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT),
            Tokens.OPERATOR_NAME, key("OPERATOR_NAME", DefaultLanguageHighlighterColors.INSTANCE_FIELD),
            Tokens.PROPERTY, key("PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD),
            Tokens.FUNCTION, key("FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL));

    private static @NotNull TextAttributesKey key(@NotNull String name, @NotNull TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey("INTELLADB_MONGO_" + name, fallback);
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
