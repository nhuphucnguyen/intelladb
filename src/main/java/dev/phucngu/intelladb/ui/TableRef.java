package dev.phucngu.intelladb.ui;

import com.intellij.openapi.application.ApplicationManager;
import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.DbSession;
import dev.phucngu.intelladb.schema.IdentifierQuoting;
import dev.phucngu.intelladb.schema.TableMeta;
import org.jetbrains.annotations.NotNull;

/**
 * Reference to a browsable table (config + schema + meta), the selection payload for
 * "View Data" and the AI panel.
 */
public record TableRef(@NotNull DbConfig config, @NotNull String schema, @NotNull TableMeta meta) {

    public @NotNull String name() {
        return meta.name;
    }

    public @NotNull String qualifiedName() {
        return IdentifierQuoting.quote(schema) + "." + IdentifierQuoting.quote(meta.name);
    }

    /** Loads a row preview into the given results panel (async). */
    public void showIn(@NotNull DbExplorerPanel explorer, @NotNull ResultsPanel panel) {
        DbSession session = explorer.sessionOf(config);
        if (session == null) {
            panel.showMessage("Not connected. Connect '" + config.name + "' first.");
            return;
        }
        String sql = "SELECT * FROM " + qualifiedName() + " LIMIT 200";
        panel.showRunning();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            dev.phucngu.intelladb.connection.SqlResult result = session.execute(sql);
            ApplicationManager.getApplication().invokeLater(() -> panel.showResult(result));
        });
    }
}
