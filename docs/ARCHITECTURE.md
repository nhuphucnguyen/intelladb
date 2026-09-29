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
- **Vocabulary:** `vocabulary()` returns the `SqlVocabulary` completion offers — keywords,
  statement starters, functions, data types — as `SqlVocabulary.ANSI.plus(…)`.
- **Driver-specific values:** `displayValue` (PGobject), `sourceTable` (which single
  table a result set came from, when the driver reports it) and `baseColumnName` (the
  table column behind an aliased result column — pgjdbc needs its own metadata call).

## Editing results

`SqlResult.sourceColumns` names the base column of each result column when the rows come
from one table (null for binary columns). `ResultsPanel` makes the grid editable when that
table is in the catalog and `RowUpdates.rowKey` finds a key (PK, else a unique key over NOT
NULL columns) whose columns are all in the result. `ResultGrid` keeps edits (null = SET NULL)
and rows marked deleted as pending state beside the loaded rows; Submit turns each edited
row into `UPDATE … SET … WHERE key = <loaded value>` and each deleted one into `DELETE …
WHERE key = …` (`RowUpdates`, string literals the server converts to the column type) and runs them with `DbSession.applyRowUpdates`: each must change exactly one row, in a
transaction of its own under auto-commit or behind a savepoint inside the user's manual
transaction.

Only files named `Postgres*.java` / `MySql*.java` may reference `org.postgresql` /
`org.mariadb` (enforced by `DriverImportGuardTest`). Bundled drivers: pgjdbc
(BSD-2-Clause) and MariaDB Connector/J (LGPL-2.1, chosen for MySQL because MySQL's own
Connector/J is GPL).

## SQL completion

`sql/completion/` is layered so the logic is plain Java and the IDE only sees an adapter:

```
SqlCompletionContributor  (IDE: CompletionContributor → LookupElements; SqlAutoPopupHandler opens it on '.')
   │  text + caret                          │  CompletionScope (from the file's SCOPE user data)
   ▼                                        ▼
CursorAnalyzer ──► CursorContext ──► SuggestionEngine ──► List<Suggestion>
   │ SqlTokenizer                          catalog · current schema · SqlVocabulary · quoting
```

- `CursorAnalyzer` works on text, not PSI: it tokenizes with the dialect's lexical rules
  (`SqlTokenizer`, same options as `SqlSplitter`), isolates the statement around the caret,
  and scans back for the clause keyword (skipping parenthesised parts) to produce a
  `CursorContext` — the clause (statement start, table, expression, keyword, INSERT column
  list, type), the `a.b.` qualifier, the typed prefix and every table reference (with
  alias) in the statement.
- `SuggestionEngine` resolves those references against the `SchemaCatalog` (qualified →
  that schema; else the current schema first, then any) and ranks `Suggestion`s; prefix
  filtering is left to the IDE's matcher. Joins come from `TableMeta.foreignKeys` in both
  directions: after `JOIN` (the analyzer reports the clause keyword) a related table with its
  `ON`, after `JOIN t ON` (the analyzer reports the joined table) the condition itself.
  `TableAliases` makes initials-based aliases that avoid the statement's names and keywords.
- The contributor adds a weigher ahead of the platform's prefix weigher so joins sort first;
  `SqlAutoPopupHandler` opens the popup after `.` and after the space following JOIN / ON.
- `CompletionScope` is what a file completes against. `SqlConsole` publishes one per
  completion (its connection's dialect, catalog and effective schema); anything else
  gets `CompletionScope.offline()` (ANSI words only).

## Testing

`MetadataLoaderPostgresTest` and `MetadataLoaderMySqlTest` run against real local servers
(see their javadoc for the environment variables) and are skipped when none is reachable.
Pure-JVM JUnit tests cover `SqlSplitter`, `DdlGenerator`/`IdentifierQuoting`, the dialects, prompt building
and SQL-block extraction, and the full `OpenAiCompatibleClient` wire format against a
JDK-built-in `HttpServer` (URL path, Bearer header, JSON body, HTTP-error/parse-error paths).
`CursorAnalyzerTest` and `SuggestionEngineTest` cover completion logic in plain JUnit;
`SqlCompletionIdeTest` (a `BasePlatformTestCase`, run through the JUnit vintage engine)
drives the registered contributor in a headless IDE — lookup contents, prefix matching and
insertion (qualified tables, quoting, parentheses for functions, keyword case).
UI behavior is verified by driving `runIde` with computer use (see ROADMAP M8).
