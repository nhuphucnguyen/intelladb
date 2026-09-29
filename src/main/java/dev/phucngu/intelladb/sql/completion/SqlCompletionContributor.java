package dev.phucngu.intelladb.sql.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionSorter;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementWeigher;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import dev.phucngu.intelladb.IntellaDbIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.function.Supplier;

/**
 * Code completion for IntellaSQL (the SQL console and injected SQL): the IDE-facing adapter
 * around {@link CursorAnalyzer} and {@link SuggestionEngine}. It works on the text, not the
 * PSI, so it is the same logic the unit tests exercise.
 * <p>
 * An editor with a connection behind it (the SQL console) puts a {@link #SCOPE} supplier on
 * its file; everything else completes against {@link CompletionScope#offline()}.
 */
public final class SqlCompletionContributor extends CompletionContributor {

    /** Supplies the completion scope for a file (read on a background thread, per completion). */
    public static final Key<Supplier<CompletionScope>> SCOPE = Key.create("IntellaDb.completionScope");

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        if (parameters.getCompletionType() != CompletionType.BASIC) {
            return;
        }
        PsiFile file = parameters.getOriginalFile();
        CompletionScope scope = scopeOf(file);
        // The original file's text: the completion copy has a dummy identifier spliced in at the caret.
        CursorContext context = CursorAnalyzer.analyze(file.getText(), parameters.getOffset(), scope.splitterOptions());
        if (context.clause() == CursorContext.Clause.NONE) {
            return;
        }
        CamelHumpMatcher matcher = new CamelHumpMatcher(context.prefix(), false);
        // Joins go above everything, even an exact match of the typed prefix ("ON o" → the condition before alias o);
        // the rest keeps the platform's order, so typing "or" still picks OR over order_date.
        LookupElementWeigher joinsFirst = new LookupElementWeigher("intelladbJoinsFirst") {
            @Override
            public @NotNull Comparable<Boolean> weigh(@NotNull LookupElement element) {
                return !(element.getObject() instanceof Suggestion s && isJoin(s));
            }
        };
        CompletionResultSet matching = result.withPrefixMatcher(matcher).withRelevanceSorter(
                CompletionSorter.defaultSorter(parameters, matcher).weighBefore("prefix", joinsFirst));
        for (Suggestion suggestion : SuggestionEngine.suggest(context, scope)) {
            matching.addElement(PrioritizedLookupElement.withPriority(render(suggestion), suggestion.priority()));
        }
    }

    public static @NotNull CompletionScope scopeOf(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getViewProvider().getVirtualFile();
        Supplier<CompletionScope> supplier = virtualFile.getUserData(SCOPE);
        return supplier != null ? supplier.get() : CompletionScope.offline();
    }

    /** The lookup element for a suggestion; shared with the MongoDB console's completion. */
    public static @NotNull LookupElementBuilder render(@NotNull Suggestion s) {
        // The inserted text is the main lookup string; the bare name also matches (e.g. "ord" → sales.orders).
        LookupElementBuilder element = LookupElementBuilder.create(s, s.insertText())
                .withLookupString(s.lookup())
                // Joins show what they insert; everything else its name (an added alias is a surprise, not a label).
                .withPresentableText(isJoin(s) ? s.insertText() : s.lookup())
                .withCaseSensitivity(false)
                .withIcon(iconOf(s.kind()))
                .withBoldness(s.kind() == Suggestion.Kind.KEYWORD);
        if (!s.detail().isEmpty()) {
            element = element.withTypeText(s.detail(), true);
        }
        if (!s.location().isEmpty()) {
            element = s.kind() == Suggestion.Kind.ROUTINE
                    ? element.withTailText(s.location(), true)
                    : element.withTailText("  " + s.location(), true);
        }
        if (s.kind() == Suggestion.Kind.FUNCTION || s.kind() == Suggestion.Kind.ROUTINE) {
            element = element.withInsertHandler(ParenthesesInsertHandler.getInstance(true));
        }
        return element;
    }

    private static boolean isJoin(@NotNull Suggestion s) {
        return s.kind() == Suggestion.Kind.JOIN || s.kind() == Suggestion.Kind.JOIN_CONDITION;
    }

    private static @Nullable Icon iconOf(@NotNull Suggestion.Kind kind) {
        return switch (kind) {
            case JOIN -> IntellaDbIcons.TABLE;
            case JOIN_CONDITION -> IntellaDbIcons.FOREIGN_KEY;
            case SCHEMA -> IntellaDbIcons.SCHEMA;
            case TABLE -> IntellaDbIcons.TABLE;
            case VIEW -> IntellaDbIcons.VIEW;
            case COLUMN -> IntellaDbIcons.COLUMN;
            case KEY_COLUMN -> IntellaDbIcons.KEY;
            case ALIAS -> AllIcons.Nodes.Variable;
            case FUNCTION, ROUTINE -> AllIcons.Nodes.Function;
            case TYPE -> AllIcons.Nodes.Type;
            case KEYWORD -> null;
        };
    }
}
