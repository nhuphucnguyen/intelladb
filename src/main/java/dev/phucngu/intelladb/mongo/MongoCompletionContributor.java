package dev.phucngu.intelladb.mongo;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.psi.PsiFile;
import dev.phucngu.intelladb.sql.completion.CompletionScope;
import dev.phucngu.intelladb.sql.completion.SqlCompletionContributor;
import dev.phucngu.intelladb.sql.completion.Suggestion;
import org.jetbrains.annotations.NotNull;

/**
 * Completion in the MongoDB console: the IDE adapter around {@link MongoCompletion}, fed
 * with the console's catalog and current database through the same scope as SQL consoles.
 */
public final class MongoCompletionContributor extends CompletionContributor {

    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        if (parameters.getCompletionType() != CompletionType.BASIC) {
            return;
        }
        PsiFile file = parameters.getOriginalFile();
        CompletionScope scope = SqlCompletionContributor.scopeOf(file);
        MongoCompletion.Result completion = MongoCompletion.suggest(file.getText(), parameters.getOffset(),
                scope.catalog(), scope.currentSchema());
        if (completion.suggestions().isEmpty()) {
            return;
        }
        CompletionResultSet matching = result.withPrefixMatcher(new CamelHumpMatcher(completion.prefix(), false));
        for (Suggestion suggestion : completion.suggestions()) {
            matching.addElement(PrioritizedLookupElement.withPriority(
                    SqlCompletionContributor.render(suggestion).withCaseSensitivity(true), suggestion.priority()));
        }
        result.stopHere();
    }
}
