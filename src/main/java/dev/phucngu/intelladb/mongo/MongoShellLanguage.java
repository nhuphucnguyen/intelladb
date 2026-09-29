package dev.phucngu.intelladb.mongo;

import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.psi.tree.IElementType;
import dev.phucngu.intelladb.IntellaDbIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** The MongoDB console's language: mongosh commands, highlighted and completed like the SQL console. */
public final class MongoShellLanguage extends Language {

    public static final MongoShellLanguage INSTANCE = new MongoShellLanguage();

    private MongoShellLanguage() {
        super("IntellaMongo");
    }

    /** Token types of {@link MongoShellLexer}. */
    public static final class Tokens {
        public static final IElementType LINE_COMMENT = new IElementType("IDB_MONGO_LINE_COMMENT", INSTANCE);
        public static final IElementType BLOCK_COMMENT = new IElementType("IDB_MONGO_BLOCK_COMMENT", INSTANCE);
        public static final IElementType STRING = new IElementType("IDB_MONGO_STRING", INSTANCE);
        public static final IElementType REGEX = new IElementType("IDB_MONGO_REGEX", INSTANCE);
        public static final IElementType NUMBER = new IElementType("IDB_MONGO_NUMBER", INSTANCE);
        /** db, use, show, true, false, null, new */
        public static final IElementType KEYWORD = new IElementType("IDB_MONGO_KEYWORD", INSTANCE);
        /** $gt, $set, $match… */
        public static final IElementType OPERATOR_NAME = new IElementType("IDB_MONGO_OPERATOR_NAME", INSTANCE);
        /** A field name before ':' in an object */
        public static final IElementType PROPERTY = new IElementType("IDB_MONGO_PROPERTY", INSTANCE);
        /** ObjectId, ISODate, NumberLong… and a method name before '(' */
        public static final IElementType FUNCTION = new IElementType("IDB_MONGO_FUNCTION", INSTANCE);
        public static final IElementType IDENTIFIER = new IElementType("IDB_MONGO_IDENTIFIER", INSTANCE);
        public static final IElementType BRACKET = new IElementType("IDB_MONGO_BRACKET", INSTANCE);
        public static final IElementType COMMA = new IElementType("IDB_MONGO_COMMA", INSTANCE);
        public static final IElementType SEMICOLON = new IElementType("IDB_MONGO_SEMICOLON", INSTANCE);
        public static final IElementType OPERATOR = new IElementType("IDB_MONGO_OPERATOR", INSTANCE);
        public static final IElementType WHITESPACE = new IElementType("IDB_MONGO_WHITESPACE", INSTANCE);

        private Tokens() {
        }
    }

    /** The console file's type (not registered for any extension). */
    public static final class FileType extends LanguageFileType {
        public static final FileType INSTANCE = new FileType();

        private FileType() {
            super(MongoShellLanguage.INSTANCE);
        }

        @Override
        public @NotNull String getName() {
            return "Intella DB MongoDB Shell";
        }

        @Override
        public @NotNull String getDescription() {
            return "MongoDB shell commands (Intella DB console)";
        }

        @Override
        public @NotNull String getDefaultExtension() {
            return "idbmongo";
        }

        @Override
        public Icon getIcon() {
            return IntellaDbIcons.MONGODB;
        }
    }
}
