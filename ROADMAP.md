# Roadmap

Built milestone by milestone, same discipline as the sibling
[spring-boot-essentials](../springboot-plugin-intella-idea) project.

| # | Milestone | Status |
|---|-----------|--------|
| M1 | Project skeleton: Gradle (IntelliJ Platform Gradle Plugin 2.18.1, IDEA 2026.2, Java 25), plugin.xml, DB Explorer tool window, original SVG icon set | ✅ |
| M2 | Connection management: `DbConfig` persistence, dialect abstraction (`DbDialect`/`PostgresDialect`), `DbSession` JDBC wrapper, `ConnectionManager` project service, PasswordSafe integration, add/edit/delete/Test-Connection dialog | ✅ |
| M3 | Schema browser: JDBC `DatabaseMetaData` loader (`MetadataLoader`), `SchemaCatalog` model, tree with schemas/tables/columns + PK markers, refresh, Copy DDL | ✅ |
| M4 | SQL console: `SqlSplitter` (quotes, dollar-quotes, comments), results grid (`TableView`), multi-statement execution, update counts, timing, Ctrl/Cmd+Enter | ✅ |
| M5 | Table data preview (first 200 rows per table, double-click) | ✅ |
| M6 | AI layer: `AiPreset` registry, provider-agnostic `OpenAiCompatibleClient` (JDK HttpClient + Gson), `AiSettings` + PasswordSafe key storage, Settings page with Test Provider | ✅ |
| M7 | AI chat panel: system prompt with live schema DDL (`DdlGenerator`), NL→SQL with fenced-block extraction, Run SQL / Insert into Console / Copy SQL actions, per-connection history, cancellable requests | ✅ |
| M8 | Visual verification in `runIde` sandbox via computer use (all flows above), bug-fix round: modal-dialog modality, plugin-bundled JDBC driver loading, preset combo labels, connected-state label, AI panel connection fallback | ✅ |
| M9 | Reasoning-model handling (GLM): empty-answer detection pointing at Max tokens, ping budget 512, default max tokens 2048 — verified against the live Z.ai coding plan | ✅ |
| M9b | Liquibase: SQL injection into XML `<sql>` blocks + INSERT column↔value caret aid with inline column hints (7 new unit tests) — verified in sandbox | ✅ |
| M10 | More dialects: MySQL done (M53); SQLite / H2 remaining | in progress |
| M11 | Data editing in the grid: inline edit + UPDATE, Set NULL and row deletion done (M56–M57), CSV export done; adding rows remaining | in progress |
| M12 | AI: streaming responses, "explain this error", index advice, per-table context menus | planned |
| M13 | Query history, bookmarks, schema compare | planned |
| M9c | Conversation-style chat with inline results (replaced separate AI—Result tab) | ✅ |
| M9e | AI Assistant split into its own tool window with a connection switcher; shared SessionOpener for Explorer + chat | ✅ |
| M52 | Dialect seams: every PostgreSQL assumption moved behind `DbDialect` (namespace model, quoting, LIMIT, schema switching, system schemas, splitter options, `Driver.connect`, value/metadata handling, SSL modes, AI prompt dialect); PostgreSQL behaviour unchanged | ✅ |
| M53 | MySQL dialect on the bundled MariaDB Connector/J: databases shown as schemas under the connection (like IntelliJ), `MySqlObjects` from information_schema, backtick/`#`/backslash-aware splitter and lexer, integration test against a real server | ✅ |
| M54 | SQL completion for both dialects: `CursorAnalyzer` (clause, qualifier, table refs from text) → `SuggestionEngine` (catalog + `SqlVocabulary` per dialect) → IDE contributor; popup on `.`; unit + headless-IDE tests | ✅ |
| M55 | FK-driven join completion (`JOIN ` → related table + ON, `ON ` → condition; popup opens on the space) and automatic initials-based table aliases after FROM / JOIN | ✅ |
| M56 | Inline cell editing in the results grid: rows of one keyed table (PK, else NOT NULL unique key) are editable, pending edits highlighted, Submit (↑ / Ctrl/Cmd+Enter) writes one UPDATE per row all-or-nothing (own transaction under Tx: Auto, savepoint inside a manual one), Revert; base column names via `DbDialect.baseColumnName` | ✅ |
| M57 | Set NULL (context menu, Ctrl+Alt+N / Cmd+Opt+N) and Delete Rows (−, Ctrl+Y / Cmd+Backspace): deleted rows struck through until Submit, which runs their DELETEs in the same all-or-nothing unit; grid context menu with the edit actions | ✅ |
| M58 | Global connections: app-level `GlobalConnections` store merged into each project's `ConnectionManager`; Make Global / Make Project (toolbar ↗, context menu, dialog checkbox); sessions stay per project | ✅ |

## Verification record (M8)

Each flow was driven in the real IDE UI with screenshots:

- Add Connection → Test Connection shows `Connected — PostgreSQL 17.11 (Homebrew)…`
- Tree: connection → `public` → `customers`/`order_items`/`orders`/`products` → columns with types
- Double-click table → "Data — public.customers" grid, `6 rows · 4 ms`
- Console: two-statement JOIN script → grid + `2 statement(s) OK · 13 ms · PostgreSQL 17.11`
- Settings → Test Provider → HTTP round-trip against the mock provider
  (`tools/mock-ai.log` shows `Authorization: Bearer …`, `model`, `messages[]`)
- Chat: "How many orders did each customer place?" → answer confirms
  `schema context: received — DDL present in system prompt` + SQL block →
  Run SQL → "AI — Result" tab, `6 rows · 7 ms`
