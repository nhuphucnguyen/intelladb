package dev.phucngu.intelladb.sql;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Console fallback file type: used when the platform's own SQL file type is unavailable.
 * Deliberately not registered against the {@code sql} extension to avoid conflicting
 * with the platform's SQL file type.
 */
public final class IntellaSqlFileType extends LanguageFileType {

    public static final IntellaSqlFileType INSTANCE = new IntellaSqlFileType();

    private IntellaSqlFileType() {
        super(IntellaSqlLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "Intella DB SQL";
    }

    @Override
    public @NotNull String getDescription() {
        return "SQL (Intella DB fallback highlighting)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "idbsql";
    }

    @Override
    public @Nullable Icon getIcon() {
        return dev.phucngu.intelladb.IntellaDbIcons.TABLE;
    }
}
