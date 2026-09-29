package dev.phucngu.intelladb.ai;

import dev.phucngu.intelladb.connection.DbDialect;
import dev.phucngu.intelladb.connection.SqlResult;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds prompts and parses answers for the database chat: NL questions answered against
 * the live schema, with a query in a fenced block when one is needed — ```sql, or
 * ```javascript with MongoDB shell commands for MongoDB.
 */
public final class AiAssistant {

    public static final Pattern SQL_BLOCK = Pattern.compile(
            "```\\s*(?:sql|postgresql|postgres|mysql|mariadb|javascript|js|mongodb|mongosh|mongo)?\\s*\\n(.*?)```",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** System prompt establishing the assistant's contract. */
    public static @NotNull ChatMessage systemPrompt(@Nullable SchemaCatalog catalog, boolean includeSchema,
                                                    @NotNull DbDialect dialect) {
        StringBuilder sb = new StringBuilder();
        String language = dialect.queryLanguage();
        boolean sql = language.equals("SQL");
        sb.append("You are Intella DB, an assistant embedded in an IntelliJ IDEA database tool window. ")
          .append("The user asks questions about their database in natural language.\n")
          .append("Rules:\n")
          .append("- If the answer needs data, give a short explanation and then exactly one ")
          .append(sql ? "SQL query" : "command").append(" in a fenced ```").append(dialect.codeFence())
          .append(" block. It must be a single statement, read-only unless the user explicitly asks to modify data.\n")
          .append("- Write ").append(language).append(" for ").append(dialect.displayName()).append(".\n")
          .append(sql ? "- Prefer schema-qualified names (schema.table).\n"
                  : "- Use db.getSiblingDB(\"database\").getCollection(\"collection\") when the database matters; "
                  + "the console supports find, findOne, aggregate, countDocuments, distinct, insertOne/Many, "
                  + "updateOne/Many, replaceOne, deleteOne/Many, createIndex, getIndexes and db.runCommand.\n")
          .append("- If the question is conceptual or the answer is already in the schema, reply in plain text without a code block.\n")
          .append(sql ? "- Never invent tables or columns that are not in the schema.\n"
                  : "- Never invent collections that are not in the schema; its fields come from sampled documents, so others may exist.\n")
          .append("- When the user runs one of your queries, its result is included at the start of their next ")
          .append("message under \"Query result\" (possibly truncated). Use it to answer; do not ask them to paste it.\n");
        if (catalog != null && includeSchema && !catalog.isEmpty()) {
            sb.append("\nDatabase schema (").append(dialect.displayName()).append(sql ? " DDL" : "").append("):\n\n")
              .append(dialect.describeSchema(catalog));
        } else {
            sb.append("\nNo schema is currently loaded; say so if the question depends on it.\n");
        }
        return ChatMessage.system(sb.toString());
    }

    /** Extracts the first fenced SQL block from an answer, or null. */
    public static @Nullable String firstSqlBlock(@NotNull String answer) {
        Matcher matcher = SQL_BLOCK.matcher(answer);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /** Builds the message list for a question, appending prior conversation for context. */
    public static @NotNull List<ChatMessage> conversation(@NotNull ChatMessage system,
                                                          @NotNull List<ChatMessage> history,
                                                          @NotNull String question) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.addAll(history);
        messages.add(ChatMessage.user(question));
        return messages;
    }

    /** Rows of a query result shared with the model; the rest is summarized as a count. */
    public static final int MAX_RESULT_ROWS = 50;
    /** Longest cell value shared; longer values are cut with an ellipsis. */
    public static final int MAX_RESULT_CELL_CHARS = 200;

    /**
     * A query result as the model sees it: the SQL, then a markdown table (at most
     * {@link #MAX_RESULT_ROWS} rows, long cells shortened), an update count or the error.
     */
    public static @NotNull String describeResult(@NotNull String sql, @NotNull SqlResult result) {
        return describeResult(sql, result, "sql");
    }

    public static @NotNull String describeResult(@NotNull String sql, @NotNull SqlResult result, @NotNull DbDialect dialect) {
        return describeResult(sql, result, dialect.codeFence());
    }

    private static @NotNull String describeResult(@NotNull String sql, @NotNull SqlResult result, @NotNull String fence) {
        StringBuilder sb = new StringBuilder("Query result of:\n```").append(fence).append('\n').append(sql.strip()).append("\n```\n");
        switch (result.kind) {
            case ROWS -> {
                int total = result.rows.size();
                sb.append(total).append(total == 1 ? " row" : " rows")
                  .append(result.truncated ? " (more rows exist; fetching stopped)" : "").append(":\n\n");
                if (!result.columns.isEmpty()) {
                    sb.append('|');
                    result.columns.forEach(column -> sb.append(' ').append(cell(column)).append(" |"));
                    sb.append("\n|");
                    result.columns.forEach(column -> sb.append(" --- |"));
                    sb.append('\n');
                    for (Object[] row : result.rows.subList(0, Math.min(total, MAX_RESULT_ROWS))) {
                        sb.append('|');
                        for (Object value : row) {
                            sb.append(' ').append(value == null ? "NULL" : cell(String.valueOf(value))).append(" |");
                        }
                        sb.append('\n');
                    }
                    if (total > MAX_RESULT_ROWS) {
                        sb.append("\n(").append(total - MAX_RESULT_ROWS).append(" more rows not shown)\n");
                    }
                }
            }
            case UPDATE_COUNT -> sb.append(result.updateCount).append(" rows affected\n");
            case MESSAGE -> sb.append(result.text).append('\n');
            case ERROR -> sb.append("Error: ").append(result.text).append('\n');
        }
        return sb.toString();
    }

    private static @NotNull String cell(@NotNull String value) {
        String flat = value.replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ");
        return flat.length() <= MAX_RESULT_CELL_CHARS ? flat : flat.substring(0, MAX_RESULT_CELL_CHARS) + "…";
    }

    /** The user message sent to the model: shared query results (if any), then the question. */
    public static @NotNull String withContext(@NotNull String context, @NotNull String question) {
        return context.isBlank() ? question : context.strip() + "\n\n" + question;
    }

    /** History limits: generous enough for a working session, bounded for cost. */
    public static final int MAX_HISTORY_MESSAGES = 40;          // 20 question/answer exchanges
    public static final int MAX_HISTORY_CHARS = 200_000;        // ~50K tokens

    /**
     * Keeps the conversation cache-friendly. Providers with prefix (context) caching — Z.ai
     * GLM, OpenAI, DeepSeek — bill repeated leading input at a discount, but only while the
     * request starts with exactly the same messages as before. A sliding window ("last N")
     * drops the oldest message on every turn, so nothing after the system prompt ever hits
     * the cache. Instead the history is append-only and, once it exceeds a limit, the
     * oldest half is dropped in one step (whole user/assistant exchanges), after which the
     * prefix is stable again for many turns.
     *
     * @return {@code history} itself when within limits, otherwise a compacted copy
     */
    public static @NotNull List<ChatMessage> compact(@NotNull List<ChatMessage> history,
                                                     int maxMessages, int maxChars) {
        if (history.size() <= maxMessages && chars(history) <= maxChars) {
            return history;
        }
        List<ChatMessage> kept = history;
        while (kept.size() > 2 && (kept.size() > maxMessages / 2 || chars(kept) > maxChars / 2)) {
            kept = kept.subList(2, kept.size()); // one user/assistant exchange at a time
        }
        return new ArrayList<>(kept);
    }

    private static int chars(@NotNull List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += message.content().length();
        }
        return total;
    }

    private AiAssistant() {
    }
}
