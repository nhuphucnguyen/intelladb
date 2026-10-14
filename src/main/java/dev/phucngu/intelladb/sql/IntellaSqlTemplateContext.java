package dev.phucngu.intelladb.sql;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

/**
 * Where Intella DB's SQL live templates ({@code sel}, {@code ins}, {@code upd}…) apply: any
 * IntellaSQL code, i.e. the SQL console and SQL injected into other files.
 */
public final class IntellaSqlTemplateContext extends TemplateContextType {

    /** The {@code <context><option name=…>} identifier live templates refer to. */
    public static final String ID = "INTELLA_SQL";

    public IntellaSqlTemplateContext() {
        super(ID, "Intella SQL");
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext context) {
        PsiElement element = context.getFile().findElementAt(context.getStartOffset());
        return element != null && element.getLanguage() == IntellaSqlLanguage.INSTANCE;
    }
}
