# Architecture

Intella DB is a plain-Java IntelliJ Platform plugin (no Kotlin, no external UI frameworks)
with four layers. Everything runs inside one tool window, **DB Explorer**.

```
┌─────────────────────────── DB Explorer (ui/) ───────────────────────────┐
│  toolbar: + │ connect/disconnect │ refresh │ console │ data │ AI │ gear │
│ ┌──────────────┐ ┌────────────────────────────────────────────────────┐ │
│ │ Connection   │ │ tabs:  SQL Console │ Data — s.t │ AI — Result     │ │
│ │ tree         │ │        AI Assistant (persistent)                  │ │
│ └──────────────┘ └────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
        │ connection/              │ schema/           │ ai/
        │ DbConfig (persisted)     │ MetadataLoader    │ AiSettings (persisted)
        │ DbDialect/Postgres…      │ SchemaCatalog     │ AiPreset registry
        │ DbSession (JDBC)         │ DdlGenerator      │ OpenAiCompatibleClient
        │ ConnectionManager (svc)  │ IdentifierQuoting │ AiAssistant (prompts)
        └──────────── PasswordSafe (both DB passwords & AI key) ─────────┘
```

## Layers

**`connection/`** — owns configuration and JDBC.
`DbConfig` is an XML-serializable POJO stored by the `ConnectionManager` project service
(`PersistentStateComponent`, `intella-db.xml`); passwords never touch that file — they go to
`PasswordSafe` under service name "Intella DB", or stay in an in-memory map for
save-password-off configs. `DbDialect` is the seam for everything database-specific (see
"Dialects" below); `PostgresDialect` and `MySqlDialect` implement it. `DbSession` wraps a single
`Connection` (monitor-serialized), materializes `Statement.execute` outcomes into `SqlResult`
(rows capped at 1000 / update count / message / error).

**`schema/`** — a snapshot model. `MetadataLoader` walks plain JDBC metadata
(`getSchemas/getTables/getColumns/getPrimaryKeys`) into a `SchemaCatalog` of immutable
records. Because it only uses `DatabaseMetaData`, any JDBC database is supportable.
`DdlGenerator` renders the catalog as CREATE TABLE statements — used both for Copy DDL and
as the AI prompt context. `SqlSplitter` splits scripts on top-level semicolons while
respecting `''`, `""`, `--`, `/* */` and, per the dialect's `SqlSplitter.Options`, `$tag$`
dollar-quoting (PostgreSQL) or backticks, `#` comments and backslash escapes (MySQL); unit-tested.

**`ai/`** — deliberately thin and provider-agnostic. `AiSettings` (application service,
`intella-db-ai.xml`) stores preset id/base URL/model/temperature/max tokens — never the key;
`AiCredentials` reads/writes the key in `PasswordSafe`. `OpenAiCompatibleClient` posts the
OpenAI chat-completions shape with JDK `HttpClient` and parses with Gson (~150 lines);
every preset in `AiPreset` is just a base URL + default model. `AiAssistant` builds the
system prompt (rules + `DdlGenerator` output), trims history, and extracts ```sql fenced
blocks from answers.

**`ui/`** — Swing on platform components: `SimpleToolWindowPanel`, `JBSplitter`,
`JBTabbedPane`, `Tree` + `ColoredTreeCellRenderer`, `EditorTextField` (SQL file type when the
platform provides one, plain text otherwise), `TableView`/`ListTableModel` grids, `DialogWrapper`
connection dialog, `FormBuilder` settings page (`applicationConfigurable` under Tools).
All JDBC and HTTP work happens on pooled threads; UI updates via `invokeLater` —
inside the modal connection dialog with `ModalityState.stateForComponent(...)` so results
land while the dialog is open.

## Concurrency & error model

- One physical JDBC connection per config, guarded by a monitor (a tool window never issues
  truly concurrent statements).
- `DbExplorerPanel.withSession(config, action)` is the single connect-or-reuse path: it
  reads PasswordSafe, prompts for a missing password, connects on a pooled thread
  (loading the catalog eagerly), then runs `action` on the EDT. Both the console's Run,
  table preview and the AI panel route through it, so password handling exists exactly once.
- AI requests are `CompletableFuture.supplyAsync` with a Cancel button; failures surface as
  chat error bubbles with the provider's message.

## Threading traps that shaped the code

- **Modality:** EDT callbacks that must land during a modal dialog need an explicit
  `ModalityState` — the default queues until the dialog closes (this was a real bug).
- **JDBC drivers in plugins:** `DriverManager` service discovery cannot see plugin-bundled
  drivers, so `DbSession.open` connects with `dialect.driver().connect(url, props)`, and
  treats a null result (URL not accepted) as an error.

## Dialects

Everything that differs between database products sits on `DbDialect`; the defaults are
the PostgreSQL/ANSI behaviour, so a new dialect only overrides what differs:

- **URL and connect:** `jdbcUrl` (what the user sees), `connectUrl` (rewrite for the
  driver, e.g. `jdbc:mysql:` → `jdbc:mariadb:`), `connectionProperties` (SSL, auth),
  `timeoutProperties` (drivers disagree on units), `driver()`, `sslModes()`,
  `readOnlyStatement()`, `timeZoneStatement()`, `listDatabases()`.
- **Namespace model:** `namespaces()` is `DATABASES_AND_SCHEMAS` (PostgreSQL: connection →
  database → schema) or `SCHEMAS_ONLY` (MySQL: a JDBC catalog *is* the schema shown under
  the connection, as in IntelliJ). `MetadataLoader` walks `getCatalogs()` instead of
  `getSchemas()` for the latter and passes the name as the catalog argument.
- **Extra metadata:** `objectsLoader()` returns an optional `ObjectsLoader`
  (`PostgresObjects`, `MySqlObjects`) that fills a dialect-neutral `CatalogObjects` with
  what JDBC metadata misses — keys, foreign keys, indexes, checks, routines, and for
  PostgreSQL sequences, types, extensions. `null` means plain JDBC only.
- **SQL syntax:** `quote`, `limit`, `useNamespaceStatement`, `defaultSchema`,
  `isSystemSchema`, and `splitterOptions()` (`SqlSplitter.Options`: dollar quotes,
  backticks, `#` comments, backslash escapes).
- **Driver-specific values:** `displayValue` (PGobject) and `sourceTable` (which single
  table a result set came from, when the driver reports it).

Only files named `Postgres*.java` / `MySql*.java` may reference `org.postgresql` /
`org.mariadb` (enforced by `DriverImportGuardTest`). Bundled drivers: pgjdbc
(BSD-2-Clause) and MariaDB Connector/J (LGPL-2.1, chosen for MySQL because MySQL's own
Connector/J is GPL).

## Testing

`MetadataLoaderPostgresTest` and `MetadataLoaderMySqlTest` run against real local servers
(see their javadoc for the environment variables) and are skipped when none is reachable.
Pure-JVM JUnit tests cover `SqlSplitter`, `DdlGenerator`/`IdentifierQuoting`, the dialects, prompt building
and SQL-block extraction, and the full `OpenAiCompatibleClient` wire format against a
JDK-built-in `HttpServer` (URL path, Bearer header, JSON body, HTTP-error/parse-error paths).
UI behavior is verified by driving `runIde` with computer use (see ROADMAP M8).
