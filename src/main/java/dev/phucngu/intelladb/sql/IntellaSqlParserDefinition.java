package dev.phucngu.intelladb.sql;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/** Minimal flat-tree parser definition so injected IntellaSQL fragments have PSI + highlighting. */
public final class IntellaSqlParserDefinition implements ParserDefinition {

    public static final IFileElementType FILE = new IFileElementType(IntellaSqlLanguage.INSTANCE);

    @Override
    public @NotNull com.intellij.lexer.Lexer createLexer(Project project) {
        return new IntellaSqlLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        // Flat tree: the file node (the {@code root} type given in) directly holds the tokens.
        // Closing the outermost marker with any other type breaks incremental reparsing —
        // the platform diffs old vs new trees expecting a FILE node on top.
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
        return TokenSet.create(IntellaSqlLanguage.Tokens.LINE_COMMENT, IntellaSqlLanguage.Tokens.BLOCK_COMMENT);
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return TokenSet.create(IntellaSqlLanguage.Tokens.STRING);
    }

    @Override
    public @NotNull PsiElement createElement(@NotNull ASTNode node) {
        return new ASTWrapperPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new IntellaSqlFile(viewProvider);
    }

    /** PsiFileBase is abstract; a trivial concrete subclass is all the injected PSI needs. */
    private static final class IntellaSqlFile extends com.intellij.extapi.psi.PsiFileBase {
        IntellaSqlFile(@NotNull FileViewProvider viewProvider) {
            super(viewProvider, IntellaSqlLanguage.INSTANCE);
        }

        @Override
        public @NotNull com.intellij.openapi.fileTypes.FileType getFileType() {
            return IntellaSqlFileType.INSTANCE;
        }
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return TokenSet.create(TokenType.WHITE_SPACE);
    }
}
