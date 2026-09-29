# Intella DB for IntelliJ IDEA

A free, open-source database client for **IntelliJ IDEA Community** (2026.2+): PostgreSQL
and MySQL connections, a schema browser, a SQL console with a results grid, and an AI assistant that
answers questions about your database in natural language (NL → SQL).

**Status:** working end-to-end, verified visually in the IDE sandbox — see [ROADMAP.md](ROADMAP.md).

## Why

IDEA Community ships without the paid "Database Tools and SQL" experience of the unified IDEA.
This plugin re-implements the everyday essentials with 100% original code on the public
IntelliJ Platform SDK — plus an AI layer that no stock edition offers in this form.

## Features

| Area | What you get |
|------|--------------|
| Connections | Add/edit/delete PostgreSQL and MySQL connections, Test Connection dialog, passwords in the IDE PasswordSafe (or session-only), SSL toggle, JDBC URL override |
| Schema browser | Connection → schema → table/view → column tree with types and primary-key markers, metadata refresh, Copy Table/Schema DDL |
| SQL console | Multi-line editor with SQL highlighting, Ctrl/Cmd+Enter to run, multi-statement scripts (quote/dollar-quote/comment-aware splitter), results grid (first 1000 rows), update counts, timing, server version; edit cells (incl. Set NULL) and delete rows of single-table results with a key, then Submit them back as UPDATE / DELETE |
| Table data | Double-click any table for a first-200-rows preview grid |
| Liquibase support | SQL syntax highlighting injected into `<sql>` blocks of XML changelogs (root `<databaseChangeLog>` or a `liquibase` path segment); falls back to the plugin's own lightweight SQL highlighter when no SQL language is installed |
| Column ↔ value aid | In long INSERTs: caret on a column highlights the matching value in every VALUES tuple (and vice versa), plus inline gray column-name hints before each value. Works in .sql files, Liquibase XML and the SQL console |
| AI assistant (own tool window) | A conversation, not a form: type and hit **Enter** (Shift+Enter = newline); questions, answers, SQL blocks and **inline result tables** all live in one transcript; Run renders the query result right under the answer (or Insert into Console / Copy); live schema DDL in the prompt; per-connection history; a connection switcher at the top — browse saved connections from the DB Explorer tree or switch directly inside the chat; both tool windows work side by side |
| Provider-agnostic AI | Any OpenAI-compatible endpoint: **Z.ai GLM (coding plan or standard API)**, Zhipu BigModel, OpenAI, DeepSeek, OpenRouter, Ollama (local), LM Studio (local), or a custom base URL. API key stored in the IDE secure credential store. |

## AI provider setup

1. `Settings → Tools → Intella DB — AI Provider`
2. Pick a preset (default: **Z.ai GLM — Coding Plan**, `https://api.z.ai/api/coding/paas/v4`;
   its Model dropdown offers **glm-5.3** and **glm-5.3-flash**), paste your API key, click **Test Provider**.
3. Open the **DB Explorer** tool window → **AI Assistant** tab → ask away.

Any endpoint that implements `POST {baseUrl}/chat/completions` with
`{"choices":[{"message":{"content":…}}]}` works — the client is ~150 lines of JDK
`HttpClient` + Gson, no SDK lock-in.

## Building & installing

```bash
./gradlew buildPlugin     # produces build/distributions/intella-db-<version>.zip
./gradlew test            # 23 unit tests (incl. AI wire-format tests over a local HTTP server)
./gradlew runIde          # sandbox IDE with the plugin loaded
```

Install: `Settings → Plugins → ⚙ → Install Plugin from Disk…` and pick the ZIP.

## Demo / verification setup

```bash
brew install postgresql@17 && brew services run postgresql@17
psql -d postgres -f tools/sample-data.sql        # creates user intella/intella123 + intelladb
python3 tools/mock-ai.py                          # mock OpenAI-compatible provider on :8931
```

`tools/mock-ai.log` records every AI request the plugin sends (headers + JSON body), which is
how the wire format was verified.

## Compatibility

- IntelliJ IDEA 2026.2+ (build 262.*, Community or the free mode of the unified IDEA)
- Java 25 toolchain (the 2026.2 platform itself is built with Java 25), Gradle 9.x,
  IntelliJ Platform Gradle Plugin 2.x
- Bundled PostgreSQL JDBC driver 42.7.4 and MariaDB Connector/J 3.5.10 (LGPL-2.1, used for
  MySQL) — no driver install needed

## Legal notes

- Original implementation; no code or resources taken from IntelliJ IDEA Ultimate.
- All IntelliJ Platform usage is via the documented SDK / public extension points.
- "IntelliJ", "PostgreSQL" and "MySQL" are trademarks of their respective owners. All icons in
  `src/main/resources/icons/` are original designs drawn for this plugin.
