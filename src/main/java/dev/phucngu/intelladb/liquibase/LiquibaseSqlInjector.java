package dev.phucngu.intelladb.liquibase;

import com.intellij.lang.Language;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.InjectedLanguagePlaces;
import com.intellij.psi.LanguageInjector;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import dev.phucngu.intelladb.sql.IntellaSqlLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Injects SQL highlighting into the text of Liquibase changelog {@code <sql>} elements,
 * so long statements inside XML changelogs get keyword/string coloring.
 * Uses the platform SQL language when available and falls back to the plugin's own
 * lightweight SQL language otherwise. Only files that look like Liquibase changelogs
 * (root {@code <databaseChangeLog>} or a "liquibase" path segment) are touched.
 */
public final class LiquibaseSqlInjector implements LanguageInjector {

    @Override
    public void getLanguagesToInject(@NotNull PsiLanguageInjectionHost host,
                                     @NotNull InjectedLanguagePlaces places) {
        if (!(host instanceof XmlText text)) {
            return;
        }
        if (!(text.getParent() instanceof XmlTag tag)) {
            return;
        }
        String tagName = tag.getLocalName() != null ? tag.getLocalName() : tag.getName();
        if (tagName == null || !"sql".equalsIgnoreCase(tagName)) {
            return;
        }
        if (!isLiquibaseChangelog(text.getContainingFile())) {
            return;
        }
        Language sql = sqlLanguage();
        if (sql != null) {
            places.addPlace(sql, TextRange.from(0, text.getTextLength()), "\n", "\n");
        }
    }

    private static boolean isLiquibaseChangelog(@Nullable PsiFile file) {
        if (!(file instanceof XmlFile xmlFile)) {
            return false;
        }
        XmlTag root = xmlFile.getRootTag();
        if (root != null) {
            String rootName = root.getLocalName() != null ? root.getLocalName() : root.getName();
            if ("databaseChangeLog".equalsIgnoreCase(rootName)) {
                return true;
            }
        }
        return file.getVirtualFile() != null
                && file.getVirtualFile().getPath().toLowerCase().contains("liquibase");
    }

    private static @Nullable Language sqlLanguage() {
        Language platform = Language.findLanguageByID("SQL");
        return platform != null ? platform : IntellaSqlLanguage.INSTANCE;
    }
}
