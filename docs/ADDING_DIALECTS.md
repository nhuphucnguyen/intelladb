# Adding a database system to Intella DB

A guide for the day we support another DBMS — SQL Server, ClickHouse, CockroachDB, or any of
the ~22 IDEA covers that we don't yet. Nothing here is hypothetical: every extension point
named is the one PostgreSQL, MySQL, MariaDB, SQLite, H2 or MongoDB already use. Read
`docs/ARCHITECTURE.md` for the big picture first; this document is the "add one dialect"
checklist.

Two paths, decided by one question — *does it speak JDBC?*

| Path | When | Cost so far |
|---|---|---|
| **A. JDBC dialect** | the database has a JDBC driver (SQL Server, Oracle, ClickHouse, Snowflake, …) | a dialect class + introspection queries |
| **B. New engine** | no JDBC (Redis, raw Cassandra CQL, DynamoDB) — or the console shouldn't be SQL (MongoDB shell) | everything in A, plus a `SessionEngine` and a console language |

## The moving parts (how a connection flows today)

```
Dialects.ALL ──> DbDialect (PostgresDialect, MySqlDialect, …)
                    │  jdbcUrl / connectionProperties / driver()
                    │  capabilities() ──> Capabilities chain (RowMutator, ObjectsLoader, …)
                    │  newEngine() ──> JdbcEngine (SQL) or MongoEngine (path B)
                    │                        │
                    │                        └── schema/MetadataLoader + dialect's ObjectsLoader
                    │                                 └── SchemaCatalog / TableMeta / ColumnMeta
                    └── quote/limit/previewStatement/vocabulary/splitterOptions/…
                                                      │
UI (tree, console, grid, completion) <───────────────┘   only talks to DbDialect + SchemaCatalog
```

The rule the codebase enforces: **everything above the dialect is database-neutral.** The UI
never learns a new database exists; registering a dialect makes it appear in the "+" menu,
the Data Sources dialog and the onboarding panel automatically (`Dialects.ALL` drives all
three).

---

## Path A — a JDBC dialect, step by step

The steps are ordered so the plugin compiles and the tests grow with you; each references a
file to copy from.

### 1. Pick the driver, check the license, add it to the build

`build.gradle.kts` ships one driver per supported family:

```kotlin
implementation("org.postgresql:postgresql:42.7.4")
implementation("com.h2database:h2:2.3.232")
```

Driver license decides the flavor of this step:

| License | Example DBMS | How |
|---|---|---|
| MIT / Apache-2.0 / EPL | SQL Server (`mssql-jdbc`), ClickHouse, Snowflake, Exasol, Vertica, Derby, HSQLDB, Hive | bundle it, done |
| GPL/ LGPL with classpath exception | **MySQL Connector/J** | don't ship it — that's why MySQL/MariaDB both run on `mariadb-java-client` (LGPL) |
| Vendor-restricted (FOTS-style) | Oracle `ojdbc`, IBM `jcc`, SAP jConnect, BigQuery, Redshift | needs the *user-provided driver* flow (see §6) before any dialect code matters |

Add the matching rule to `DriverImportGuardTest`: only files whose name starts with the
dialect prefix may import the driver's packages. That keeps driver types (PGobject, …) from
leaking into the UI, and the test failure message tells the next person exactly that.

### 2. Write the dialect class

`src/main/java/dev/phucngu/intelladb/connection/CockroachDialect.java`, implementing
`DbDialect`. **If the new system is wire-compatible with one we have, extend its dialect
instead** — see §5 — otherwise start from `H2Dialect` (smallest) with `PostgresDialect` open
as the reference for "everything filled in". The interface's method groups, in the order
worth tackling:

1. **Identity & URL** — `id()`, `displayName()`, `defaultPort()`, `jdbcUrl(config)`,
   `connectUrl()` (when the user-visible URL differs from what the driver wants), and
   `driver()` — return the driver instance directly; `DriverManager`'s service loader does
   not see plugin-bundled drivers, `DbSession.open` calls `driver().connect(...)`.
2. **Connection behavior** — `connectionProperties()` (SSL, read-only), `timeoutProperties()`
   (names differ per driver: `loginTimeout` vs `socketTimeout`), `readOnlyStatement()`,
   `testConnection()` / `probeDatabases()` / `probeSchemaNames()` only if the defaults
   (JDBC metadata) misbehave.
3. **Namespaces** — `namespaces()` (`DATABASES_AND_SCHEMAS` like PostgreSQL,
   `SCHEMAS_ONLY` like MySQL-as-schemas, `DATABASES_ONLY` for Mongo), `maintenanceDatabase()`,
   `listDatabases()`, `systemSchemas()` (what the tree hides behind "show system"),
   `defaultSchema()`, `useNamespaceStatement()` (`SET search_path` / `USE`).
4. **Introspection** — plain JDBC metadata gets you columns and table names;
   `schema/MetadataLoader` drives that. What it can't see — foreign keys, indexes,
   routines, enums — needs an `ObjectsLoader` (see step 3).
5. **SQL syntax** — `quote()` (brackets for SQL Server, backticks for MySQL — see
   `IdentifierQuoting`), `limit()`/`limitedQuery()`/`countQuery()` (override when the
   grammar isn't `LIMIT n OFFSET m`: SQL Server wants `TOP`/`OFFSET … FETCH`, Oracle
   `FETCH FIRST`), `previewStatement()`, `qualified()`.
6. **Console** — `vocabulary()` (keywords/functions/types for completion —
   `sql/SqlVocabulary`; IDEA's per-dialect catalogs can be mined here, see §7),
   `splitterOptions()` (lexical quirks: `#` comments, backslash escapes, `$tag$` strings).
7. **Grid write-back** — defaults compose INSERT/UPDATE/DELETE through the `RowMutator`
   capability; override `insertStatement` & co. only for special cases (upserts, Mongo-style
   documents).
8. **Driver-specific values** — `displayValue()` (PGobject etc.), `baseColumnName()`,
   `sourceTable()` (what makes a grid editable).

### 3. Capabilities: optional behavior without subclass sprawl

Small behaviors hang off `capabilities()`, not new interface methods — the keyed-collector
pattern we took from IntelliJ's plugin (their `DialectCapability` equivalents):

```java
@Override
public @NotNull Capabilities capabilities() {
    return super.capabilities().extend()
            .bind(ObjectsLoader.class, (connection, namespaces) -> …catalog queries…);
}
```

Bindings shadow along the chain, a dialect states only what it changes, and `Capabilities.GENERIC`
is the fallback. Existing capability interfaces: `RowMutator` (write-back),
`ObjectsLoader` (bulk catalog queries — copy `PostgresObjects` for the shape: one round of
queries for **all** schemas, not the per-table JDBC calls).

### 4. Icons, and registration

- Add `db<Name>.svg` + `db<Name>Connected.svg` under `src/main/resources/icons/`, wire both
  into `IntellaDbIcons.connection(dialectId, connected)` — the only icon switch in the plugin.
- Add the instance to `Dialects.ALL`. That is the whole registration: "+" menu, connection
  dialog, empty-state buttons, keymap, completion scopes all derive from it.

### 5. The wire-compatible shortcut

CockroachDB, Greenplum and Amazon Redshift speak the PostgreSQL protocol. For those:

```java
public final class CockroachDialect extends PostgresDialect {
    @Override public @NotNull String id() { return "cockroach"; }
    @Override public @NotNull String displayName() { return "CockroachDB"; }
    @Override public int defaultPort() { return 26257; }
    // …grammar differences only: systemSchemas(), limit() where they diverge
}
```

Test the differences explicitly (their `LIMIT`/type quirks differ from PG's in spots), but
the win is inheriting introspection, write-back, completion vocabulary and SSL handling.
IntelliJ does the same via shared `postgresbase`/`mysqlbase` dialect modules — same idea,
their shape.

### 6. User-provided drivers (Oracle, Db2, …) — not built yet

Today every dialect's driver ships inside the plugin zip. Vendor-restricted drivers can't,
so those DBMSes need a small piece of platform work first: a driver-classpath field on the
connection (driver jar(s) chosen by the user), an isolated class loader per connection, and
`driver()` loading from it instead of the plugin's classpath. IntelliJ's version of this is
their "driver files" manager with download URLs; our plan tracks it as the *driver download /
bring-your-own driver* work item. Until then, those DBMSes are blocked on packaging, not on
dialect code.

### 7. What to learn from IntelliJ (and what not to copy)

Our working method applies: **decompile as reference, implement fresh.** Useful sources in
`/Applications/IntelliJ IDEA.app/Contents/plugins/DatabaseTools/lib/`:

- `database-plugin.jar` → `dialects/<name>/<name>.minicat.xml.gzip` — the full grammar
  catalog per DBMS (keywords, functions, types) after gunzip; a ready-made seed for
  `SqlVocabulary` (facts like keyword lists are fine to reuse).
- `modules/intellij.database.dialects.<name>.jar` — the dialect's behaviors, decompile for
  *how they handle a quirk* (Vineflower: `java -jar vineflower.jar <classes> <out>`).
- `intellij.database.sql.core.impl.jar` — cross-dialect machinery (completion, formatting).

Don't paste their code into the plugin; port the behavior into our architecture.

### 8. Tests, in the order to write them

1. **Dialect unit tests** — URL building, quoting, `limitedQuery`/`countQuery` grammar,
   vocabulary sanity. Copy `MySqlDialectTest` / `PaginationSqlTest`.
2. **In-memory integration** — H2-style (`H2DialectTest`): real SQL execution without a
   server, for write-back and pagination behaviors.
3. **Live integration** — `MetadataLoaderPostgresTest` pattern: a docker container of the
   real server, tests skipped when it isn't up; asserts the introspection queries against a
   fixture schema (`tools/sample-data.sql` is the demo schema to load). Containers in use
   today: `intelladb-pg`, `-mysql`, `-mariadb`, `-mongo` (see `docker ps`; notes on image
   tags in the project memory).
4. **Update `DriverImportGuardTest`** (step 1) — the suite fails until the new driver's
   imports are confined.
5. **Docs**: flip the row in `docs/PARITY.md`, add the ROADMAP milestone entry, CHANGELOG
   bullet, and one sandbox `runIde` pass against the real server (remember `--rerun-tasks`
   when running gradle tests — the cache replays stale results).

---

## Path B — a new engine (no JDBC, or not SQL)

MongoDB is the worked example; adding Redis or raw Cassandra looks like this:

1. **`SessionEngine`** — the non-JDBC counterpart of `JdbcEngine`
   (`connection/SessionEngine`): open/close, execute, catalog. MongoDB's is
   `mongo/MongoEngine` on `mongodb-driver-sync`; Redis would be Lettuce/Jedis with a command
   model instead of statements.
2. **`DbDialect` still fronts it** — override `newEngine(config, password)`; the rest of the
   plugin (tree, sessions, results plumbing) doesn't know JDBC is gone.
3. **Console language** — `consoleLanguage()` returns your `ConsoleLanguage` constant; add a
   parser definition + highlighter (`mongo/MongoShellLanguage` is the model: lexer, filetype,
   syntax-highlighter factory, `lang.parserDefinition` registrations in plugin.xml) and a
   completion contributor if the language has structure worth completing
   (`MongoCompletionContributor`).
4. **Schemaless results** — `schemaless() = true` lets the grid write any field of a result,
   not just catalog-listed columns; `RowMutator` composes the writes (`insertOne`, `$set`).
5. **Pagination/filtering** — the grid's `LIMIT/OFFSET` grammar is SQL; a non-SQL engine
   implements paging its own way (`skip/limit`) or opts out (Mongo grids hide the cluster
   until that lands).
6. Path A steps 4, 7 and 8 apply unchanged (icons, `Dialects.ALL`, tests, docs).

---

## Candidate list, with the path each needs

| DBMS | Path | Driver | Notes |
|---|---|---|---|
| SQL Server / Azure SQL | A | `mssql-jdbc` (MIT) | biggest win; bracket quoting, `TOP`/`OFFSET FETCH`, no LIMIT |
| CockroachDB, Greenplum, Redshift | A (extend `PostgresDialect`) | pgjdbc | grammar deltas only; Redshift type system is the widest delta |
| Derby, HSQLDB | A | Apache/EPL | embedded like H2 — near copy of `H2Dialect` |
| ClickHouse | A | `clickhouse-jdbc` (Apache-2.0) | own grammar, `LIMIT … OFFSET …`-compatible paging |
| Snowflake | A | Apache-2.0 | account identifiers in the URL; cloud auth (key-pair) |
| Exasol, Vertica | A | MIT-ish | conventional |
| Hive / Spark (HiveServer2) | A | Apache-2.0 | slow introspection; expect `ObjectsLoader` work |
| Oracle | A, blocked on §6 | `ojdbc` (FOTS) | largest grammar of all (IDEA's minicat is 4 MB) |
| IBM Db2, Sybase ASE | A, blocked on §6 | `jcc` / jConnect | ditto |
| BigQuery, DynamoDB | A/B, blocked on §6 + cloud auth | vendor SDKs | OAuth flows, not just drivers |
| Redis | B | Lettuce (Apache-2.0) | command model, key browsing as the "tree" |
| Cassandra (CQL) | B | DataStax driver (Apache-2.0) | CQL is SQL-shaped; a CQL console + engine |
| Couchbase | B | SDK (Apache-2.0) | N1QL — could ride a JDBC-ish path via their N1QL JDBC if acceptable |
| Mongo *SQL* API | A | mongosql JDBC | SQL over MongoDB; separate from the shell we ship |

One milestone per DBMS (the user's tracking preference), smallest first: a wire-compatible
one (CockroachDB) is a good warm-up for the machinery, SQL Server is the one users will ask
for first.
