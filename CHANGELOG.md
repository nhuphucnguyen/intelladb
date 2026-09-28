# Changelog

## 0.3.0

Released 2026-09-28. Includes everything below (M10-M18): the IntelliJ-style layout
(consoles and data grids as editor tabs, schema selector in the console toolbar), the
AI chat as a DB Explorer tab, readable SQL colors in dark themes, the JSON cell viewer,
closable/bordered tabs, and the one-time password prompt.

## 0.2.0

### Changed
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

### Changed
- **The AI Assistant now lives as a tab inside the DB Explorer** (next to SQL Console
  and data-preview tabs) instead of being a second tool-window icon in the stripe. It
  opens automatically, supports the same close/middle-click behavior as other tabs, and
  the toolbar's AI button plus the tree's "Ask AI about this table" open or focus it.

### Added
- **Cell value viewer**: double-click any cell in a results grid (console, View Data,
  AI chat results) to see the full value in a dialog with a Copy button. JSON and
  JSONB values are automatically pretty-printed.
- **Markdown rendering in AI answers**: headings, **bold**, *italic*, `inline code`,
  links (clickable), bullet lists and extra fenced code blocks now display properly
  instead of showing raw markdown syntax. The first SQL block keeps its Run /
  Insert into Console / Copy buttons.

### Fixed
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

### Improved
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

## 0.1.0

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
