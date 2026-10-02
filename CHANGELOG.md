# Changelog

## Unreleased

### Added
- **PostgreSQL connections without a database browse every database.** Leave Database empty
  and the tree shows each database on the server with its schemas; the Schemas tab lists
  every database's schemas as `database.schema`, so you can pick exactly the ones to show
  (databases with nothing picked are left out). The session starts on `postgres` and opens a
  connection per database as you use it: View Data, the console (its schema switcher lists
  schemas by database), edits written back from results, and Commit / Rollback all run on
  the right database. Databases you may not connect to are skipped. In the Schemas tab each
  database expands to its schemas; checking a database checks all of them.
- **Primary key columns are marked** with a key beside the column icon — in the explorer
  tree and in the results grid's column headers (when the rows come from one known table) —
  and their tooltips say "primary key".

## 0.3.0

### Added
- **MongoDB connections** (driver "MongoDB"), on the bundled MongoDB Java driver. The tree shows
  databases → collections (and views) → the fields found in a sample of their documents, with
  their BSON types, plus indexes. The console speaks the MongoDB shell: `use`, `show dbs` /
  `collections`, `db.pets.find({…}).sort({…}).limit(…)`, `findOne`, `aggregate`,
  `countDocuments`, `distinct`, `insertOne` / `insertMany`, `updateOne` / `updateMany`,
  `replaceOne`, `deleteOne` / `deleteMany`, `findOneAndUpdate` and friends, index commands,
  `explain()`, `db.runCommand` and more — with `ObjectId("…")`, `ISODate("…")`, regexes and
  unquoted keys as in mongosh, its own highlighting, and completion of collections, methods,
  fields, query and update operators, pipeline stages, accumulators and `"$field"` paths.
  Documents appear as grid rows (nested values as JSON you can open in the viewer); results of
  a `find` are editable like SQL results: edits and deletions are written back by `_id` with
  `updateOne` / `deleteOne`, keeping each field's type. Tx: Manual runs multi-document
  transactions on replica sets, read-only connections refuse writes, Cancel kills the running
  operation, Test Connection shows the server version and topology, and the AI assistant
  answers with mongosh commands. Driver properties become connection-string options
  (`authSource`, `replicaSet`…); a pasted `mongodb+srv://` URL works too.
- **MariaDB connections** (driver "MariaDB"), on the bundled MariaDB Connector/J. They work
  like MySQL connections (databases shown as schemas, the same objects, editing and SSL
  options), with `jdbc:mariadb://` URLs, the MariaDB logo, and completion that knows
  MariaDB's own words: `RETURNING`, sequences (`NEXTVAL`, `LASTVAL`, `SETVAL`), `INTERSECT` /
  `EXCEPT`, and the `INET4` / `INET6` / `UUID` types.
- **Database logos on connections.** PostgreSQL connections show the PostgreSQL elephant and
  MySQL connections the MySQL dolphin — in the explorer tree, the Services view, the AI
  chat's connection picker and the Data Sources dialog — with a green dot when connected.
- **Test Connection shows what it found.** A green check (or a red error icon) and the server
  version appear next to the link, and a balloon lists the DBMS and its version, identifier
  case sensitivity, the JDBC driver, the round-trip ping and whether the session actually
  uses SSL, with **Copy**. Click the status to show the balloon again.
- **Edit cells in the results grid and save them back.** When a result comes from one table
  that has a primary key (or a unique key over NOT NULL columns) and includes the key
  columns — a table data preview, or a console `SELECT` — double-click a cell (or just start
  typing) to edit it. Edited cells are highlighted and counted in the status line until you
  press **Submit** (the ↑ button, or Ctrl/Cmd+Enter in the grid), which writes one `UPDATE`
  per edited row, all or nothing: every row must match exactly once, otherwise nothing
  changes. With Tx: Auto the updates commit at once; in a manual transaction they stay
  pending until Commit (a failed submit only undoes itself). **Revert Changes** drops the
  edits. The UPDATEs are logged in the console's Output tab. Read-only connections, joins,
  computed columns, keyless tables and binary values stay read-only (Submit's tooltip says
  why).
- **Edit JSON and long values in the value dialog.** For an editable cell, the JSON viewer
  (the JSON badge, Shift+Enter, or double-clicking a multi-line value) opens as an editor:
  **Apply** turns the text into a pending edit of the cell — highlighted, saved with Submit
  like any other — and **Discard** drops it. Invalid JSON gets a warning but can still be
  applied; JSON that was on one line is stored compact again, so only a real change counts.
- The Submit arrow turns green while changes are waiting.
- **Global connections**, shared by every project in the IDE. **Make Global** (↗ in the DB
  Explorer toolbar, or the connection's context menu) moves a connection out of the project
  into the IDE-wide store; **Make Project** moves it back. Global connections are
  listed first and marked "· global"; each project keeps its own sessions, consoles and
  history for them, and the saved password is shared. Deleting one removes it everywhere
  (the confirmation says so).
- **A tidier Output tab.** Each execution is a block of its own: a grey `--` header with the
  time, the connection and the statement's first 60 characters on one line (leading
  comments skipped), the outcome indented below it — errors in red, multi-line messages kept
  aligned — and a blank line before the next block. A grid Submit gets one header with its
  UPDATE / DELETE statements listed; Commit and Rollback get headers too.
- **One "Data Sources" dialog for all connections**, as in IntelliJ: Add and Edit open it
  with every connection listed on the left under *Global Data Sources* and *Project Data
  Sources* — the one you right-clicked (or a new one) selected — and its settings on the
  right. The list's toolbar adds (per driver), removes, duplicates, and makes a data source
  global or project (↗ / ↙). Edits are kept per connection while you switch between them and
  saved only by **Apply** or **OK**; Cancel drops them all. Only connections whose settings
  actually changed are saved and reconnected.
- **Set NULL** on the selected cells (context menu, Ctrl+Alt+N / Cmd+Opt+N) — works on
  multi-line values too.
- **Delete rows** from the grid: the − button, the context menu or Ctrl+Y / Cmd+Backspace
  marks the selected rows deleted (struck through, red); Submit runs one `DELETE … WHERE
  <key>` per row in the same all-or-nothing unit as the updates, and Revert restores them.
- **The Schemas tab explains an empty list.** When a server has only system schemas (hidden by
  default), the tab says so and how to show them, instead of just "Nothing to show".

## 0.2.0

Released 2026-09-29: MySQL support and SQL completion.

### Added
- **SQL completion in the console**, for PostgreSQL and MySQL alike. It knows the clause the
  caret is in: statement keywords at the start; tables and schemas after FROM / JOIN /
  UPDATE / INTO (current schema's tables as plain names, other schemas' qualified — typing
  `ord` finds `sales.orders`); columns of the statement's tables (even when FROM comes after
  the caret), their aliases, built-in functions and database functions in expressions;
  `alias.`, `table.` and `schema.` qualifiers (the popup opens on `.`); the target table's
  columns in `INSERT INTO t (…)`; data types in column definitions and `CAST(… AS …)`.
  Keywords, functions and types come from the connection's dialect (e.g. `ILIKE`,
  `string_agg`, `jsonb` vs `SHOW`, `group_concat`, `mediumtext`), keywords follow the case
  being typed, identifiers are quoted as the dialect needs (`"Order Items"`, `` `order` ``),
  and nothing is suggested inside strings, comments or after `AS`. Without a connection
  (e.g. SQL injected into Liquibase changelogs) ANSI keywords are still offered.
- **Join completion from foreign keys.** After `JOIN ` the popup opens by itself and lists
  the tables related to the ones already in the statement, with the whole join ready to
  insert (`customers c ON c.id = o.customer_id`), in either FK direction. After `ON ` it
  opens with the condition itself (`oi.order_id = o.id`; composite keys joined with `AND`,
  self-joins both ways). Joins rank above everything else.
- **Automatic table aliases.** A table picked after FROM / JOIN is inserted with an alias
  made of its initials (`orders o`, `order_items oi`, `OrderItems oi`), numbered when the
  statement already uses it or it is a keyword (`u1`, `as1`); not added when an alias is
  already written, nor after INTO / UPDATE.
- **MySQL connections** (driver "MySQL"), through the bundled MariaDB Connector/J (LGPL).
  As in IntelliJ's database tools, MySQL databases appear as schemas directly under the
  connection (no database level); the "N of M" badge sits on the connection. Keys,
  foreign keys, indexes, check constraints, functions/procedures and users come from
  `information_schema`. The URL field shows the familiar `jdbc:mysql://host:port` form
  (rewritten for the driver when connecting; `jdbc:mariadb:` URLs work too), the Database
  field is optional, SSL modes are trust / verify-ca / verify-full, read-only mode blocks
  writes and DDL, and the console's schema picker switches with `USE`.
- The SQL splitter and highlighter understand MySQL syntax: backtick identifiers,
  `#` comments and backslash escapes in strings.
- The AI prompt names the connection's SQL dialect, and answers fenced as ```mysql are
  recognised.

### Changed
- Database-specific behaviour now lives behind `DbDialect` (namespace model, identifier
  quoting, LIMIT, schema switching, system-schema check, splitter options, SSL modes,
  driver, driver-specific values and result metadata). PostgreSQL behaviour is unchanged,
  except that schema names in generated DDL are quoted when they need it.
- JDBC drivers are connected through `Driver.connect` instead of `DriverManager`.
- A system schema ticked in the connection dialog's Schemas tab stays in the list and
  is introspected even while "Show internal system schemas" is off.
- Numeric result columns are recognised by normalised type name, including MySQL types.

## 0.1.0

Released 2026-09-29 (tag `v0.1.0`), the first tagged release: everything through M51.
During development these notes were numbered 0.1.0–0.3.1; they are folded in here,
newest first.

### Pre-release 0.3.1

#### Fixed
- **The INSERT column↔value aid only worked near the start of a statement**: the
  statement's end offset was computed in token units instead of character offsets,
  so any caret inside the VALUES tuple (or on the closing part of the statement) fell
  outside the parsed statement and the pairing highlights and inline column hints
  never appeared. The statement range now covers the full statement through its
  terminating semicolon (regression-tested).
- **AI chat could hang forever at "thinking…" with a local provider (Ollama/LM
  Studio)**: the API key was read from the IDE credential store even though local
  providers never need one, and on macOS that keychain lookup can block on an access
  prompt. The key is now only read for providers that actually use one.
- **A stray bright "chip" appeared behind the DB Explorer's tab headers** (most visible
  behind the AI Assistant tab): the tabbed pane paints its own selected-tab highlight
  across the full tab rect, and the custom pill drawn on top of it was smaller, so the
  platform highlight peeked out around every tab. Headers no longer paint a pill of
  their own — the platform highlight alone marks the active tab, and there is nothing
  left to peek from behind it.
- **A NullPointerException was logged when closing a project or the IDE**: connection
  change listeners rebuilt the explorer tree while the project was already disposing.
  Tree refreshes are now skipped once the project is disposed, and tree keys tolerate
  nodes without a user object.
- Middle-clicking a tab header now actually closes the tab (previously only clicks on
  the empty tab-strip area did).
- The "Running…" status in results grids used raw blue, which was barely readable in
  dark themes; it now uses the theme's link color.

### Pre-release 0.3.0

Released 2026-09-28. Includes everything below (M10-M18): the IntelliJ-style layout
(consoles and data grids as editor tabs, schema selector in the console toolbar), the
AI chat as a DB Explorer tab, readable SQL colors in dark themes, the JSON cell viewer,
closable/bordered tabs, and the one-time password prompt.

### Pre-release 0.2.0

#### Changed
- **Layout now follows IntelliJ Ultimate's database tools**:
  - The **Database explorer** is a tree-only tool window (connections → schemas →
    objects) with the AI chat as its one work tab.
  - **Query consoles open as editor tabs** in the main editor ("console_1.sql
    @<data source>"), not inside the tool window — with standard editor-tab close and
    split behavior. One console per connection; "Insert into Console" and the toolbar
    button jump to it.
  - **Table data previews open as editor tabs** too ("<table> @<data source>").
  - **The console toolbar has a schema selector** (like IntelliJ's schema combo): it
    lists the connection's schemas and runs `SET search_path` before subsequent
    executions; the data source is shown next to it as "@<name>".

#### Changed
- **The AI Assistant now lives as a tab inside the DB Explorer** (next to SQL Console
  and data-preview tabs) instead of being a second tool-window icon in the stripe. It
  opens automatically, supports the same close/middle-click behavior as other tabs, and
  the toolbar's AI button plus the tree's "Ask AI about this table" open or focus it.

#### Added
- **Cell value viewer**: double-click any cell in a results grid (console, View Data,
  AI chat results) to see the full value in a dialog with a Copy button. JSON and
  JSONB values are automatically pretty-printed.
- **Markdown rendering in AI answers**: headings, **bold**, *italic*, `inline code`,
  links (clickable), bullet lists and extra fenced code blocks now display properly
  instead of showing raw markdown syntax. The first SQL block keeps its Run /
  Insert into Console / Copy buttons.

#### Fixed
- **Console SQL text was nearly invisible in dark themes** (dark-blue keywords on a
  dark background): the embedded editor resolved the platform SQL highlighter's
  attribute keys from the light default scheme. The console now uses the plugin's own
  SQL highlighter with fixed, theme-brightness-aware colors (keywords orange, strings
  green, comments gray in dark themes), so text is always readable.
- **Tabs in the DB Explorer could not be closed** — the close action existed but nothing
  was wired to it. Console and data-preview tabs now have a × button in their header,
  and middle-clicking a tab closes it (like editor tabs). Each tab header is drawn as a
  bordered pill (theme border color) with a filled background for the selected tab, and
  clicking a header switches to that tab.
- **Connection tree could render blank** after the tooltip addition: tooltip text set
  during off-screen layout passes made ToolTipManager throw
  IllegalComponentStateException — now guarded with `isShowing()`. Truncated tree labels
  also show their full text as a tooltip.
- **"Insert into Console" from the AI chat did nothing**: the document change was made
  in a bare write action, which the platform rejects outside a command
  (IncorrectOperationException) — it now runs in a WriteCommandAction and the SQL lands
  in the console.

#### Improved
- The password prompt (shown only when a connection has no stored credential) now offers
  "Save password in the IDE credential store" — answering once stops the prompt from
  reappearing on every IDE start. Credentials are reused automatically: live session →
  in-memory password for this IDE run → PasswordSafe.
- AI Assistant chat visuals reworked: rounded, antialiased bubbles with theme-aware
  colors that keep clear contrast in dark themes (the assistant bubble previously used
  the same color as the panel background and was effectively invisible).
- Connection selector at the top of the AI Assistant now stretches with the tool window
  (GridBag layout with a separator line) instead of clipping when the window is narrow.
- Chat text wraps to the actual tool-window width, so nothing is cut off in narrow
  tool windows.

### Pre-release 0.1.0

Initial release.

- PostgreSQL connections with Test Connection dialog; passwords in the IDE PasswordSafe
  (or session-only when "save password" is off)
- Dialect abstraction (`DbDialect`) with PostgreSQL as the first implementation
- Schema browser: schemas → tables/views → columns with types and primary-key markers,
  refresh, Copy Table/Schema DDL
- SQL console: SQL-highlighted editor, Ctrl/Cmd+Enter, multi-statement scripts
  (quote/dollar-quote/comment-aware splitter), results grid (1000-row cap),
  update counts, per-batch timing and server version
- Table data preview (first 200 rows)
- AI assistant (provider-agnostic, OpenAI-compatible wire format):
  - Presets: Z.ai GLM coding plan / standard API (model dropdown: glm-5.3,
    glm-5.3-flash; free text allowed), Zhipu BigModel, OpenAI, DeepSeek,
    OpenRouter, Ollama, LM Studio, custom base URL
  - API key in the IDE secure credential store; never in project files
  - Chat with live schema DDL context; NL→SQL answers get Run SQL /
    Insert into Console / Copy SQL actions; per-connection history; cancellable
  - Settings page (Tools → Intella DB — AI Provider) with Test Provider ping
- AI settings persist via the IDE's PropertiesComponent (options/other.xml), flushed on Apply
- 32 unit tests, including wire-format tests against an in-process HTTP server
- `tools/`: sample-data.sql (demo schema) and mock-ai.py (deterministic mock provider
  that logs every request for wire-format inspection)
