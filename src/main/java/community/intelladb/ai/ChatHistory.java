package community.intelladb.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.SequentialTaskExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The AI assistant's conversations, newest first, so they survive a restart and old ones
 * can be reopened. Kept per project as gzipped JSON in the IDE system directory — outside
 * the project, since answers can quote schema and data — and saved in the background
 * after every change. Accessed on the EDT.
 */
@Service(Service.Level.PROJECT)
public final class ChatHistory {

    /** Conversations kept; the oldest beyond this are dropped. */
    public static final int MAX_CONVERSATIONS = 100;
    private static final int VERSION = 1;

    /**
     * One question and its answer.
     *
     * @param model the model that answered (shown above the answer)
     */
    public record Turn(@NotNull String connectionId, @NotNull String connectionName, @NotNull String question,
                       @NotNull String answer, @NotNull String model, @NotNull LocalDateTime at) {
    }

    /** A conversation; {@link #turns} grows as the chat continues. */
    public static final class Conversation {
        public final String id;
        public final LocalDateTime createdAt;
        private final List<Turn> turns = new ArrayList<>();

        Conversation(@NotNull String id, @NotNull LocalDateTime createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }

        public @NotNull List<Turn> turns() {
            return List.copyOf(turns);
        }

        /** The first question, on one line. */
        public @NotNull String title() {
            return turns.isEmpty() ? "New chat" : turns.get(0).question().strip().replaceAll("\\s+", " ");
        }

        public @NotNull LocalDateTime updatedAt() {
            return turns.isEmpty() ? createdAt : turns.get(turns.size() - 1).at();
        }
    }

    private final List<Conversation> conversations = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final @Nullable Path file;
    private final @Nullable ExecutorService saver;

    public ChatHistory(@NotNull Project project) {
        this(Path.of(com.intellij.openapi.application.PathManager.getSystemPath(),
                "intelladb", "chat", project.getLocationHash() + ".json.gz"));
    }

    /** For tests: persisted to {@code file}, or in memory only when it is null. */
    public ChatHistory(@Nullable Path file) {
        this.file = file;
        this.saver = file == null ? null
                : SequentialTaskExecutor.createSequentialApplicationPoolExecutor("IntellaDB chat history");
        if (file != null) {
            conversations.addAll(load(file));
        }
    }

    public static @NotNull ChatHistory getInstance(@NotNull Project project) {
        return project.getService(ChatHistory.class);
    }

    /** Conversations with at least one turn, most recently updated first. */
    public @NotNull List<Conversation> conversations() {
        return conversations.stream().filter(c -> !c.turns.isEmpty()).toList();
    }

    public @Nullable Conversation find(@NotNull String id) {
        return conversations.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    /** Starts an empty conversation; it is listed (and saved) once it gets its first turn. */
    public @NotNull Conversation start() {
        Conversation conversation = new Conversation(UUID.randomUUID().toString(), LocalDateTime.now());
        conversations.add(0, conversation);
        return conversation;
    }

    public void addTurn(@NotNull Conversation conversation, @NotNull Turn turn) {
        conversation.turns.add(turn);
        // Most recently updated first.
        conversations.remove(conversation);
        conversations.add(0, conversation);
        while (conversations.size() > MAX_CONVERSATIONS) {
            conversations.remove(conversations.size() - 1);
        }
        changed();
    }

    public void delete(@NotNull Conversation conversation) {
        if (conversations.remove(conversation)) {
            changed();
        }
    }

    public void clear() {
        conversations.clear();
        changed();
    }

    public void addListener(@NotNull Runnable listener, @NotNull Disposable parent) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    /** For tests: blocks until every save queued so far has been written. */
    @TestOnly
    public void awaitSaved() throws Exception {
        if (saver != null) {
            saver.submit(() -> { }).get();
        }
    }

    private void changed() {
        save();
        listeners.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        if (file == null || saver == null) {
            return;
        }
        JsonObject root = toJson(conversations()); // built on the EDT: conversations are mutable
        saver.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)),
                        StandardCharsets.UTF_8)) {
                    writer.write(root.toString());
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Logger.getInstance(ChatHistory.class).warn("Could not save the AI chat history", e);
            }
        });
    }

    private static @NotNull JsonObject toJson(@NotNull List<Conversation> conversations) {
        JsonArray array = new JsonArray();
        for (Conversation conversation : conversations) {
            JsonObject json = new JsonObject();
            json.addProperty("id", conversation.id);
            json.addProperty("createdAt", conversation.createdAt.toString());
            JsonArray turns = new JsonArray();
            for (Turn turn : conversation.turns) {
                JsonObject t = new JsonObject();
                t.addProperty("connectionId", turn.connectionId());
                t.addProperty("connectionName", turn.connectionName());
                t.addProperty("question", turn.question());
                t.addProperty("answer", turn.answer());
                t.addProperty("model", turn.model());
                t.addProperty("at", turn.at().toString());
                turns.add(t);
            }
            json.add("turns", turns);
            array.add(json);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("conversations", array);
        return root;
    }

    private static @NotNull List<Conversation> load(@NotNull Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8)) {
            List<Conversation> loaded = new ArrayList<>();
            for (JsonElement element : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("conversations")) {
                JsonObject json = element.getAsJsonObject();
                Conversation conversation = new Conversation(json.get("id").getAsString(),
                        LocalDateTime.parse(json.get("createdAt").getAsString()));
                for (JsonElement t : json.getAsJsonArray("turns")) {
                    JsonObject turn = t.getAsJsonObject();
                    conversation.turns.add(new Turn(turn.get("connectionId").getAsString(),
                            turn.get("connectionName").getAsString(), turn.get("question").getAsString(),
                            turn.get("answer").getAsString(), turn.get("model").getAsString(),
                            LocalDateTime.parse(turn.get("at").getAsString())));
                }
                loaded.add(conversation);
            }
            return loaded;
        } catch (Exception e) {
            return List.of(); // corrupt or from an incompatible version: start over
        }
    }
}
