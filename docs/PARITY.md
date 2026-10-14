# IntelliJ IDEA Database parity matrix

Status of every major Database Tools and SQL (JetBrains) feature in Intella DB.
Source of the inventory: reverse-engineering of the shipped DatabaseTools plugin
(see ../intellij-idea-re/database-plugin-design.md — plugin.xml extension points,
module composition, Das*/Db* model, Dbms keyed-extension dispatch).

Legend: ✅ done · 🟡 partial · ❌ missing

## Connections

| Feature | Status | Notes |
|---|---|---|
| Project + global (app-level) data sources | ✅ | `ConnectionManager` + `GlobalConnections` |
| PasswordSafe credential storage | ✅ | service "Intella DB"; save-password-off in-memory mode |
| Test connection / database + schema probing | ✅ | dialog probes per dialect |
| Read-only connections | 🟡 | enforced for MongoDB; SQL dialects still need a guard |
| Connection groups / colors | 🟡 | per-connection marker color (tree dot + console tab tint); groups missing |
| SSH tunnels | ❌ | planned: Apache MINA sshd local port forwarding |
| Driver download for unbundled databases | ❌ | unblocks Oracle/MSSQL/ClickHouse without bundling |
| Keep-alive / auto-disconnect | ✅ | `ConnectionManager.housekeep` |
| Session monitor panel | ❌ | |
| SSL modes per dialect | ✅ | `DbDialect.sslModes()` |

## Dialects

| Dialect | Status | Notes |
|---|---|---|
| PostgreSQL | ✅ | objectsLoader: keys/FKs/indexes/checks/routines/sequences/types/extensions |
| MySQL | ✅ | on MariaDB Connector/J; databases-as-schemas like IntelliJ |
| MariaDB | ✅ | `MariaDbDialect extends MySqlDialect` |
| MongoDB | ✅ | full non-JDBC engine path, mongosh console, transactions |
| SQLite | ✅ | embedded; xerial driver, `main` schema synthesized |
| H2 | ✅ | embedded; `PUBLIC` schema, hidden `INFORMATION_SCHEMA` |
| SQL Server | ❌ | mssql-jdbc (MIT) |
| Oracle | ❌ | via driver download |
| ClickHouse | ❌ | Apache-2.0 driver |
| Snowflake | ❌ | |

## Explorer / DDL

| Feature | Status | Notes |
|---|---|---|
| Tree: databases/schemas/tables/columns + PK markers | ✅ | `ConnectionTreePanel` |
| Extra object kinds (routines, sequences, indexes, keys, checks…) | ✅ | `CatalogObjects` (PG/MySQL) |
| Refresh preserving expansion/selection | ✅ | |
| Toolbar grammar `[+ ▾][Properties…][Refresh][Disconnect][Console][Edit Data F4][DDL ⌘B][Eye ▾]` | ✅ | `ui/actions/explorer/*` |
| Eye menu view options (groups/counts/details) | ✅ | `TreeViewOptions`, persisted per project |
| Speed search in the tree | ✅ | `TreeSpeedSearch` over the rendered labels |
| Onboarding empty state (quick-create buttons) | ✅ | shown while there are no data sources |
| Context menus as named popups (Navigate ▾, SQL Scripts ▾) | ✅ | |
| Copy DDL (table/schema) | ✅ | `DdlGenerator` |
| Go to DDL as a read-only editor tab | ✅ | `DbExplorerPanel.openDdl`, ⌘B on tree nodes |
| Table editor with Data + DDL tabs | ✅ | `TableEditorPanel`, DDL regenerated on tab select |
| DDL for every object kind (routines, indexes…) | ❌ | |
| Object search (tree + everywhere) | 🟡 | tree speed search; no Everywhere/Go to Database Object |
| Quick documentation popup | ❌ | |
| Schema compare | ❌ | |
| Export database as SQL script | ❌ | |

## Console

| Feature | Status | Notes |
|---|---|---|
| SQL console per connection | ✅ | `SqlConsole` (LightVirtualFile, gutter markers, timings) |
| Multiple named consoles per connection | ✅ | `ConsoleStore` by console id, rename, jump-to-console popup, new/duplicate from tree |
| Multi-statement execution + script mode | ✅ | `SqlSplitter`, Playground/Script run modes, Execute Selection as Single Statement |
| Execute ▾ (modes + selection-as-one-statement) | ✅ | mode popup next to Execute, each entry runs right away |
| Connection selector beside the schema selector | ✅ | rebinds the console to a same-language connection |
| Transactions (Auto/Manual, commit/rollback) | ✅ | savepoint-aware row-update unit |
| Query history | ✅ | `QueryHistory` + view, gzip JSON store |
| `?` parameter prompts | ❌ | needs `PreparedStatement` path |
| Run configurations | ❌ | |
| SQL formatter | ❌ | per-dialect profiles |
| Bookmarks | ❌ | |

## Data grid

| Feature | Status | Notes |
|---|---|---|
| Table data preview | ✅ | first 200 rows |
| Results grid, multi-result tabs | ✅ | `ResultsPanel`/`ResultsHub` |
| Toolbar grammar `[Rerun][Cancel] | [pages][size ▾] | [+ row][del][revert][submit]` | ✅ | `ResultsPanel` |
| **Pagination** (first/prev/size ▾/next/last, Ctrl+Alt+Up/Down) | 🟡 | SQL dialects page via `LIMIT/OFFSET`, row counts fetched for table previews; MongoDB not paged yet |
| Filter/sort bar (WHERE + ORDER BY, Ctrl+Alt+Shift+F) | ✅ | appended for table previews, wrapped otherwise; gear-toggleable |
| Gear menu (filter bar, transpose, column chooser, reset view, statistics) | ✅ | transpose is a read-only view |
| Statistics footer (n/Σ/avg/min/max per numeric column) | ✅ | over the selection, else all rows |
| Preview Pending Changes before Submit | ✅ | read-only DML listing with Copy |
| Inline cell edit → UPDATE (all-or-nothing) | ✅ | `RowUpdates`, PK/unique-key guarded |
| Set NULL / delete rows | ✅ | |
| **Add row → INSERT** | ✅ | composes into the same all-or-nothing Submit unit; insert-only grids without a key |
| CSV/TSV/JSON/SQL-inserts/Markdown export | ✅ | `ResultExporter` |
| CSV/TSV **import** | ❌ | |
| LOB / image cell viewers | ❌ | |
| Result diff (rerun vs cached) | ❌ | |
| Aggregate footer | ✅ | statistics footer (gear menu) |

## SQL language

| Feature | Status | Notes |
|---|---|---|
| Syntax highlighting (PG/MySQL/Mongo lexers) | ✅ | |
| Completion: keywords/tables/columns, `.` popup | ✅ | `CursorAnalyzer`/`SuggestionEngine` |
| FK join completion + auto aliases | ✅ | |
| Mongo shell completion | ✅ | |
| Liquibase XML SQL injection | ✅ | |
| Real PSI (statements/clauses) | ❌ | flat parser today; gates the items below |
| Inspections (unknown table/column) + quick-fixes | ❌ | |
| Find usages (tables/columns) | ❌ | |
| Rename refactoring | ❌ | |
| Live templates | ❌ | |
| Structure view | ❌ | |

## AI

| Feature | Status | Notes |
|---|---|---|
| Provider-agnostic chat, presets, PasswordSafe key | ✅ | `OpenAiCompatibleClient` |
| Schema DDL in system prompt, dialect-aware | ✅ | |
| NL→SQL, run inline, insert into console | ✅ | |
| Conversation history per connection | ✅ | |
| **Streaming responses** | ❌ | SSE `data:` line path |
| Explain this error | ❌ | |
| Index advice | ❌ | |
| Per-table AI actions | 🟡 | "Ask AI about this table" only |

## Extras

| Feature | Status | Notes |
|---|---|---|
| EXPLAIN plan action | ❌ | per-dialect capability |
| ER diagram | ❌ | FK graph from catalog |
