package community.intelladb.ai;

import community.intelladb.schema.DdlGenerator;
import community.intelladb.schema.SchemaCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds prompts and parses answers for the database chat: NL questions answered against
 * the live schema, with SQL in fenced ```sql blocks when a query is needed.
 */
public final class AiAssistant {

    public static final Pattern SQL_BLOCK = Pattern.compile(
            "```\\s*(?:sql|postgresql|postgres)?\\s*\\n(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** System prompt establishing the assistant's contract. */
    public static @NotNull ChatMessage systemPrompt(@Nullable SchemaCatalog catalog, boolean includeSchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Intella DB, an assistant embedded in an IntelliJ IDEA database tool window. ")
          .append("The user asks questions about their database in natural language.\n")
          .append("Rules:\n")
          .append("- If the answer needs data, give a short explanation and then exactly one SQL query ")
          .append("in a fenced ```sql block. The query must be a single statement, read-only unless the user explicitly asks to modify data.\n")
          .append("- Prefer schema-qualified names (schema.table).\n")
          .append("- If the question is conceptual or the answer is already in the schema, reply in plain text without a SQL block.\n")
          .append("- Never invent tables or columns that are not in the schema.\n");
        if (catalog != null && includeSchema && !catalog.isEmpty()) {
            sb.append("\nDatabase schema (PostgreSQL DDL):\n\n")
              .append(DdlGenerator.generate(catalog));
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

    /** Trims history to the last N exchanges (roles alternate user/assistant). */
    public static @NotNull List<ChatMessage> trim(@NotNull List<ChatMessage> history, int keepMessages) {
        if (history.size() <= keepMessages) {
            return List.copyOf(history);
        }
        return List.copyOf(history.subList(history.size() - keepMessages, history.size()));
    }

    private AiAssistant() {
    }
}
