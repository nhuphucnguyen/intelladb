package dev.phucngu.intelladb.sql;

import com.intellij.lang.Language;
import com.intellij.psi.tree.IElementType;

/**
 * Minimal SQL language owned by this plugin. Used for SQL language injection into
 * Liquibase XML changelogs when the platform's own SQL language is unavailable, and as
 * the highlighting fallback for the SQL console. It is syntax-highlighting only —
 * no semantic parser.
 */
public final class IntellaSqlLanguage extends Language {

    public static final IntellaSqlLanguage INSTANCE = new IntellaSqlLanguage();

    private IntellaSqlLanguage() {
        super("IntellaSQL");
    }

    /** Token element types for the lightweight lexer. */
    public static final class Tokens {
        public static final IElementType LINE_COMMENT = new IElementType("IDB_LINE_COMMENT", INSTANCE);
        public static final IElementType BLOCK_COMMENT = new IElementType("IDB_BLOCK_COMMENT", INSTANCE);
        public static final IElementType STRING = new IElementType("IDB_STRING", INSTANCE);
        public static final IElementType NUMBER = new IElementType("IDB_NUMBER", INSTANCE);
        public static final IElementType KEYWORD = new IElementType("IDB_KEYWORD", INSTANCE);
        public static final IElementType IDENTIFIER = new IElementType("IDB_IDENTIFIER", INSTANCE);
        public static final IElementType PARENTHESIS = new IElementType("IDB_PAREN", INSTANCE);
        public static final IElementType COMMA = new IElementType("IDB_COMMA", INSTANCE);
        public static final IElementType SEMICOLON = new IElementType("IDB_SEMICOLON", INSTANCE);
        public static final IElementType OPERATOR = new IElementType("IDB_OPERATOR", INSTANCE);
        public static final IElementType WHITESPACE = new IElementType("IDB_WHITESPACE", INSTANCE);

        private Tokens() {
        }
    }
}
