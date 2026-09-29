package dev.phucngu.intelladb.connection;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registry of supported dialects. */
public final class Dialects {

    private static final List<DbDialect> ALL = List.of(new PostgresDialect(), new MySqlDialect(), new MariaDbDialect(),
            new dev.phucngu.intelladb.mongo.MongoDialect());
    private static final Map<String, DbDialect> BY_ID =
            ALL.stream().collect(Collectors.toUnmodifiableMap(DbDialect::id, Function.identity()));

    public static @NotNull List<DbDialect> all() {
        return ALL;
    }

    public static @NotNull DbDialect byId(@NotNull String id) {
        DbDialect dialect = BY_ID.get(id);
        return dialect != null ? dialect : ALL.get(0);
    }

    private Dialects() {
    }
}
