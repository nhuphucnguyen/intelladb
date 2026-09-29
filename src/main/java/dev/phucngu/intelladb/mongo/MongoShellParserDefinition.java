package dev.phucngu.intelladb.mongo;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import dev.phucngu.intelladb.mongo.MongoShellLanguage.Tokens;
import org.jetbrains.annotations.NotNull;

/** Flat PSI for the MongoDB console, as for IntellaSQL: the file node holds the tokens. */
public final class MongoShellParserDefinition implements ParserDefinition {

    public static final IFileElementType FILE = new IFileElementType(MongoShellLanguage.INSTANCE);

    @Override
    public @NotNull com.intellij.lexer.Lexer createLexer(Project project) {
        return new MongoShellLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            PsiBuilder.Marker marker = builder.mark();
            while (!builder.eof()) {
                builder.advanceLexer();
            }
            marker.done(root);
            return builder.getTreeBuilt();
        };
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return TokenSet.create(Tokens.LINE_COMMENT, Tokens.BLOCK_COMMENT);
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return TokenSet.create(Tokens.STRING);
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return TokenSet.create(Tokens.WHITESPACE);
    }

    @Override
    public @NotNull PsiElement createElement(@NotNull ASTNode node) {
        return new ASTWrapperPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new PsiFileBase(viewProvider, MongoShellLanguage.INSTANCE) {
            @Override
            public @NotNull com.intellij.openapi.fileTypes.FileType getFileType() {
                return MongoShellLanguage.FileType.INSTANCE;
            }
        };
    }
}
