# Changelog

## 0.2.0

### Added
- **Markdown rendering in AI answers**: headings, **bold**, *italic*, `inline code`,
  links (clickable), bullet lists and extra fenced code blocks now display properly
  instead of showing raw markdown syntax. The first SQL block keeps its Run /
  Insert into Console / Copy buttons.

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
