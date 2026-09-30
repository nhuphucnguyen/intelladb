package dev.phucngu.intelladb.ui;

import com.intellij.openapi.application.ApplicationManager;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reference to a browsable table (config + schema + meta), the selection payload for
 * "View Data" and the AI panel. {@code database} names the table's database when the
 * connection browses every database ({@link DbConfig#allDatabases()}), else it is null.
 */
public record TableRef(@NotNull DbConfig config, @Nullable String database, @NotNull String schema,
                       @NotNull TableMeta meta) {

    public @NotNull String name() {
        return meta.name;
    }

    public @NotNull String qualifiedName() {
        DbDialect dialect = config.dialect();
        return dialect.quote(schema) + "." + dialect.quote(meta.name);
    }

    /** Loads a row preview into the given results panel (async). */
    public void showIn(@NotNull DbExplorerPanel explorer, @NotNull ResultsPanel panel) {
        DbSession session = explorer.sessionOf(config);
        if (session == null) {
            panel.showMessage("Not connected. Connect '" + config.name + "' first.");
            return;
        }
        String sql = config.dialect().previewStatement(schema, meta.name, 200);
        panel.showRunning();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            dev.phucngu.intelladb.connection.SqlResult result = session.execute(database, sql);
            ApplicationManager.getApplication().invokeLater(() -> panel.showResult(result));
        });
    }
}
