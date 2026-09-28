package community.intelladb.connection;

import org.jetbrains.annotations.NotNull;

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
    public boolean sslMode = false;
    /** Non-empty replaces the generated URL entirely (advanced use). */
    public String jdbcUrlOverride = "";

    public @NotNull DbDialect dialect() {
        return Dialects.byId(dialectId);
    }

    public @NotNull String describe() {
        if (!jdbcUrlOverride.isBlank()) {
            return name;
        }
        return host + ":" + port + "/" + database;
    }

    public @NotNull DbConfig copy() {
        DbConfig c = new DbConfig();
        c.id = id;
        c.name = name;
        c.dialectId = dialectId;
        c.host = host;
        c.port = port;
        c.database = database;
        c.user = user;
        c.savePassword = savePassword;
        c.sslMode = sslMode;
        c.jdbcUrlOverride = jdbcUrlOverride;
        return c;
    }
}
