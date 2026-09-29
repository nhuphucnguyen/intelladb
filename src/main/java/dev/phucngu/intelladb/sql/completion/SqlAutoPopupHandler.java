package dev.phucngu.intelladb.sql.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import dev.phucngu.intelladb.sql.IntellaSqlLanguage;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * Opens completion where the next word is predictable but no letter has been typed yet:
 * after a '.' ({@code alias.}, {@code schema.}), and after the space following JOIN or ON
 * (a related table with its condition, or the join condition itself).
 */
public final class SqlAutoPopupHandler extends TypedHandlerDelegate {

    private static final Set<String> POPUP_AFTER = Set.of("join", "on");

    @Override
    public @NotNull Result checkAutoPopup(char charTyped, @NotNull Project project, @NotNull Editor editor,
                                          @NotNull PsiFile file) {
        if (file.getLanguage() != IntellaSqlLanguage.INSTANCE) {
            return Result.CONTINUE;
        }
        if (charTyped == '.' || (charTyped == ' ' && POPUP_AFTER.contains(wordBeforeCaret(editor)))) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            return Result.STOP;
        }
        return Result.CONTINUE;
    }

    /** The word right before the caret (the typed character is not in the document yet), lower-case. */
    private static @NotNull String wordBeforeCaret(@NotNull Editor editor) {
        CharSequence text = editor.getDocument().getCharsSequence();
        int end = editor.getCaretModel().getOffset();
        int start = end;
        while (start > 0 && SqlTokenizer.isWordPart(text.charAt(start - 1))) {
            start--;
        }
        return text.subSequence(start, end).toString().toLowerCase(Locale.ROOT);
    }
}
