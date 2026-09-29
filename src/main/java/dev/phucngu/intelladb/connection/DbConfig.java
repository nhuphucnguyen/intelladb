package dev.phucngu.intelladb.connection;

import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A saved connection definition. Passwords are never stored here — see {@link ConnectionManager}. */
public class DbConfig {

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String dialectId = PostgresDialect.ID;
    public String host = "localhost";
    public int port = 5432;
    public String database = "";
    public String user = "";
    /** Whether the password is persisted in the IDE PasswordSafe (otherwise asked per session). */
    public boolean savePassword = true;
    /** With savePassword off: forget the password after connecting, so every connect asks. */
    public boolean neverRememberPassword = false;
    /** "No auth": connect without user and password (trust/peer authentication). */
    public boolean noAuth = false;
    public boolean sslMode = false;
    /** Dialect-specific mode name when SSL is on (see {@link DbDialect#sslModes()}). */
    public String sslModeName = "require";
    public String sslRootCert = "";
    public String sslCert = "";
    public String sslKey = "";
    /** "URL only" connection type: only the URL (plus credentials) is used. */
    public boolean urlOnly = false;
    /** Non-empty replaces the generated URL entirely (advanced use). */
    public String jdbcUrlOverride = "";

    // Options
    /**
     * Read-only session. PostgreSQL refuses writes and DDL; MySQL (verified against 8.0) refuses
     * DML, DDL and TRUNCATE alike. Like any session setting, the user can switch it off again with SQL.
     */
    public boolean readOnly = false;
    /** Initial transaction mode of new consoles: auto-commit, or manual commit/rollback. */
    public boolean autoCommit = true;
    /** Session time zone (e.g. "UTC", "Asia/Singapore"); empty keeps the server default. */
    public String timeZone = "";
    public boolean keepAlive = false;
    public int keepAliveSeconds = 60;
    public boolean autoDisconnect = false;
    public int autoDisconnectSeconds = 300;
    /** SQL run after every connect (e.g. SET statements). */
    public String startupScript = "";

    // Schemas
    /** Schemas to introspect; empty means all non-system schemas. */
    public List<String> schemas = new ArrayList<>();
    public boolean showSystemSchemas = false;

    // Advanced
    /** Extra JDBC driver properties; they win over the ones derived from the settings above. */
    public Map<String, String> driverProperties = new LinkedHashMap<>();

    public @NotNull DbDialect dialect() {
        return Dialects.byId(dialectId);
    }

    public @NotNull String describe() {
        if (urlOnly || !jdbcUrlOverride.isBlank()) {
            return name;
        }
        return database.isEmpty() ? host + ":" + port : host + ":" + port + "/" + database;
    }

    public @NotNull DbConfig copy() {
        DbConfig c = new DbConfig();
        XmlSerializerUtil.copyBean(this, c);
        c.schemas = new ArrayList<>(schemas);
        c.driverProperties = new LinkedHashMap<>(driverProperties);
        return c;
    }
}
