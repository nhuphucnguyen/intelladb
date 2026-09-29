package dev.phucngu.intelladb.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.phucngu.intelladb.connection.ConnectionTestReport;
import dev.phucngu.intelladb.connection.DbConfig;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Opens MongoDB clients from connection settings: the {@code mongodb://} URL (or the one
 * the user pasted, {@code mongodb+srv://} included), the dialog's user and password,
 * driver properties as connection-string options ({@code authSource}, {@code replicaSet}…)
 * and TLS. Shared by sessions and the connection dialog.
 */
public final class MongoConnector {

    private MongoConnector() {
    }

    /** The URL the dialog shows: {@code mongodb://host:port/database}. */
    public static @NotNull String url(@NotNull DbConfig config) {
        if (!config.jdbcUrlOverride.isBlank()) {
            return config.jdbcUrlOverride.trim();
        }
        return "mongodb://" + config.host + ':' + config.port + '/' + config.database;
    }

    /** A connected client; the first round trip ({@code ping}) proves the settings work. */
    public static @NotNull MongoClient open(@NotNull DbConfig config, @Nullable String password, int timeoutSeconds)
            throws SQLException {
        MongoClient client;
        try {
            client = MongoClients.create(settings(config, password, timeoutSeconds));
        } catch (RuntimeException e) {
            throw new SQLException(message(e), e);
        }
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw new SQLException(message(e), e);
        }
    }

    static @NotNull MongoClientSettings settings(@NotNull DbConfig config, @Nullable String password,
                                                 int timeoutSeconds) throws SQLException {
        ConnectionString connection;
        try {
            connection = new ConnectionString(withOptions(url(config), config.driverProperties));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Invalid MongoDB URL: " + e.getMessage(), e);
        }
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(connection)
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .applicationName("Intella DB")
                .applyToClusterSettings(c -> c.serverSelectionTimeout(timeoutSeconds, TimeUnit.SECONDS))
                .applyToSocketSettings(s -> s.connectTimeout(timeoutSeconds, TimeUnit.SECONDS));
        if (!config.noAuth && !config.user.isBlank() && connection.getCredential() == null) {
            // Like mongosh: users authenticate against the URL's database unless authSource says otherwise.
            String source = connection.getDatabase() == null || connection.getDatabase().isEmpty()
                    ? "admin" : connection.getDatabase();
            builder.credential(MongoCredential.createCredential(config.user, source,
                    password == null ? new char[0] : password.toCharArray()));
        }
        if (config.sslMode) {
            SSLContext context = sslContext(config);
            boolean trust = "trust".equals(config.sslModeName);
            builder.applyToSslSettings(ssl -> {
                ssl.enabled(true).invalidHostNameAllowed(trust);
                if (context != null) {
                    ssl.context(context);
                }
            });
        }
        return builder.build();
    }

    /**
     * Driver properties as connection-string options; {@code authSource} and friends are the
     * options users need most. Options already in a pasted URL are kept.
     */
    static @NotNull String withOptions(@NotNull String url, @NotNull Map<String, String> options) {
        StringBuilder sb = new StringBuilder(url);
        boolean hasQuery = url.contains("?");
        if (!hasQuery && !options.isEmpty()) {
            // mongodb://host/db?opt needs the slash before the query: mongodb://host/?opt
            int schemeEnd = url.indexOf("://");
            if (schemeEnd >= 0 && url.indexOf('/', schemeEnd + 3) < 0) {
                sb.append('/');
            }
        }
        for (Map.Entry<String, String> option : options.entrySet()) {
            if (option.getKey().isBlank()) {
                continue;
            }
            sb.append(hasQuery ? '&' : '?');
            hasQuery = true;
            sb.append(URLEncoder.encode(option.getKey().trim(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(option.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * TLS context: a CA certificate (PEM) when one is given, or trusting any certificate in
     * "trust" mode; null leaves the JVM's defaults (system CAs, full verification).
     */
    private static @Nullable SSLContext sslContext(@NotNull DbConfig config) throws SQLException {
        boolean trust = "trust".equals(config.sslModeName);
        if (config.sslRootCert.isBlank() && !trust) {
            return null;
        }
        try {
            TrustManager[] managers;
            if (trust) {
                managers = new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }};
            } else {
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(null, null);
                try (InputStream in = Files.newInputStream(Path.of(config.sslRootCert.trim()))) {
                    int i = 0;
                    for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                        store.setCertificateEntry("ca" + i++, certificate);
                    }
                }
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(store);
                managers = factory.getTrustManagers();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, null);
            return context;
        } catch (Exception e) {
            throw new SQLException("Could not set up TLS: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ connection dialog

    /** Test Connection: server version, driver, ping and TLS, like the JDBC dialects' report. */
    public static @NotNull ConnectionTestReport testConnection(@NotNull DbConfig config, @Nullable String password)
            throws SQLException {
        try (MongoClient client = open(config, password, 5)) {
            Document build = client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
            String version = String.valueOf(build.get("version"));
            List<?> modules = build.getList("modules", Object.class, List.of());
            long start = System.nanoTime();
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            long ping = (System.nanoTime() - start) / 1_000_000;
            String topology = topology(client);
            return new ConnectionTestReport("MongoDB",
                    version + (modules.contains("enterprise") ? " (Enterprise)" : "") + ", " + topology,
                    shortVersion(version), "plain=exact, delimited=exact",
                    "MongoDB Java Driver (ver. " + driverVersion() + ")", ping, config.sslMode ? "yes (TLS)" : "no");
        } catch (MongoException e) {
            throw new SQLException(message(e), e);
        }
    }

    /** "standalone", "replica set rs0" or "sharded cluster": what transactions depend on. */
    static @NotNull String topology(@NotNull MongoClient client) {
        Document hello = client.getDatabase("admin").runCommand(new Document("hello", 1));
        if ("isdbgrid".equals(hello.getString("msg"))) {
            return "sharded cluster";
        }
        String set = hello.getString("setName");
        return set != null ? "replica set " + set : "standalone";
    }

    static @NotNull String shortVersion(@NotNull String version) {
        String[] parts = version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    static @NotNull String driverVersion() {
        String version = MongoClient.class.getPackage().getImplementationVersion();
        if (version == null) {
            try {
                version = String.valueOf(Class.forName("com.mongodb.internal.build.MongoDriverVersion")
                        .getField("VERSION").get(null));
            } catch (ReflectiveOperationException e) {
                version = "unknown";
            }
        }
        return version;
    }

    /** Database names for the dialog's dropdown and schema list (system ones included). */
    public static @NotNull List<String> databaseNames(@NotNull DbConfig config, @Nullable String password)
            throws SQLException {
        try (MongoClient client = open(config, password, 5)) {
            List<String> names = new ArrayList<>();
            client.listDatabaseNames().forEach(names::add);
            names.sort(null);
            return names;
        } catch (MongoException e) {
            throw new SQLException(message(e), e);
        }
    }

    /** The server's own message, without the driver's "Command failed with error 13 (…)" framing. */
    static @NotNull String message(@NotNull Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof com.mongodb.MongoCommandException command) {
            String server = command.getErrorMessage();
            if (server != null && !server.isBlank()) {
                return server + " (" + command.getErrorCodeName() + ")";
            }
        }
        if (e instanceof com.mongodb.MongoWriteException write) {
            return write.getError().getMessage();
        }
        return message;
    }
}
