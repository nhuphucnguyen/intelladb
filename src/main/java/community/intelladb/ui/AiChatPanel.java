package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import community.intelladb.IntellaDbIcons;
import community.intelladb.ai.AiAssistant;
import community.intelladb.ai.AiCredentials;
import community.intelladb.ai.AiException;
import community.intelladb.ai.AiSettings;
import community.intelladb.ai.ChatMessage;
import community.intelladb.ai.OpenAiCompatibleClient;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.DbSession;
import community.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Chat panel for asking about the database in natural language. Answers that contain
 * SQL get Run / Insert-to-console / Copy buttons. Uses the selected connection's
 * schema as context.
 */
public final class AiChatPanel extends JPanel {

    private final Project project;
    private final DbExplorerPanel explorer;
    private final JPanel messagesPanel = new JPanel();
    private final JTextArea input = new JTextArea(3, 40);
    private final JButton sendButton = new JButton("Ask");
    private final JBLabel contextLabel = new JBLabel("Select a connection in the tree, then ask a question.");
    private final Map<String, List<ChatMessage>> historyByConfig = new HashMap<>();
    private final JBLabel statusLabel = new JBLabel(" ");

    @Nullable
    private DbConfig config;
    @Nullable
    private CompletableFuture<?> pending;
    @Nullable
    private JPanel thinkingBubble;

    public AiChatPanel(@NotNull Project project, @NotNull DbExplorerPanel explorer) {
        this.project = project;
        this.explorer = explorer;

        setLayout(new BorderLayout());
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        JPanel messagesHost = new JPanel(new BorderLayout());
        messagesHost.add(messagesPanel, BorderLayout.NORTH);
        add(new JBScrollPane(messagesHost), BorderLayout.CENTER);
        add(buildInputArea(), BorderLayout.SOUTH);

        appendInfoBubble("Ask about your database in plain English — e.g. “Which customers ordered the most?”\n"
                + "Answers that need data include a ready-to-run SQL query.");
    }

    private JComponent buildInputArea() {
        JPanel south = new JPanel(new BorderLayout());
        south.add(contextToolbar(), BorderLayout.NORTH);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        JPanel inputHost = new JPanel(new BorderLayout());
        inputHost.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
        inputHost.add(new JBScrollPane(input), BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        sendButton.setAlignmentY(Component.TOP_ALIGNMENT);
        sendButton.addActionListener(e -> send());
        buttons.add(sendButton);
        JButton clearButton = new JButton("Clear");
        clearButton.setAlignmentY(Component.TOP_ALIGNMENT);
        clearButton.addActionListener(e -> clearChat());
        buttons.add(clearButton);
        inputHost.add(buttons, BorderLayout.EAST);

        statusLabel.setBorder(JBUI.Borders.empty(2, 8));
        south.add(inputHost, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);

        javax.swing.KeyStroke ctrlEnter = javax.swing.KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK);
        input.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                "send");
        input.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK),
                "send");
        input.getActionMap().put("send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                send();
            }
        });
        return south;
    }

    private JComponent contextToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        contextLabel.setBorder(JBUI.Borders.empty(6, 8, 0, 8));
        bar.add(contextLabel, BorderLayout.CENTER);
        return bar;
    }

    // ------------------------------------------------------------------ public API

    public void setConnection(@NotNull DbConfig config) {
        this.config = config;
        contextLabel.setText("Asking about: " + config.name + " (" + config.describe() + ")"
                + (AiSettings.getInstance().includeSchema() ? "  ·  schema included in prompt" : "  ·  schema omitted"));
    }

    public void setDraft(@NotNull String text) {
        input.setText(text);
        input.requestFocusInWindow();
    }

    public void clearChat() {
        if (config != null) {
            historyByConfig.remove(config.id);
        }
        messagesPanel.removeAll();
        appendInfoBubble("Chat cleared. Ask a new question.");
        refreshMessages();
    }

    // ------------------------------------------------------------------ sending

    private void send() {
        String question = input.getText().trim();
        if (question.isEmpty()) {
            return;
        }
        // Fall back to the selected connection, then to the first configured one.
        DbConfig current = config != null ? config : explorer.selectedConfig();
        if (current == null && !explorer.manager().configs().isEmpty()) {
            current = explorer.manager().configs().get(0);
        }
        if (current == null) {
            appendErrorBubble("Add a connection in the DB Explorer tree first.");
            refreshMessages();
            return;
        }
        setConnection(current);
        AiSettings settings = AiSettings.getInstance();
        String key = AiCredentials.read();
        boolean localProvider = settings.presetId().equals("ollama") || settings.presetId().equals("lmstudio");
        boolean missingKey = (key == null || key.isBlank()) && !localProvider;
        if (settings.baseUrl().isBlank() || settings.model().isBlank() || missingKey) {
            appendErrorBubble("No AI provider is configured (or the API key is missing).\n"
                    + "Open Settings → Tools → Intella DB — AI Provider.");
            refreshMessages();
            return;
        }

        appendUserBubble(question);
        refreshMessages();
        input.setText("");
        sendButton.setEnabled(false);
        DbConfig target = current;
        // Connects (with password prompt) when needed, then continues on the EDT.
        explorer.withSession(target, session -> doSend(target, session, question));
    }

    private void doSend(@NotNull DbConfig current, @NotNull DbSession session, @NotNull String question) {
        AiSettings settings = AiSettings.getInstance();
        String key = AiCredentials.read();
        List<ChatMessage> history = historyByConfig.computeIfAbsent(current.id, k -> new ArrayList<>());
        showThinking();

        List<ChatMessage> messages = AiAssistant.conversation(
                AiAssistant.systemPrompt(session.catalog(), settings.includeSchema()),
                AiAssistant.trim(history, 10),
                question);

        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                settings.baseUrl(), key == null ? "" : key, settings.model(),
                settings.temperature(), settings.maxTokens());
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> client.chat(messages));
        pending = future;
        future.whenComplete((answer, error) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (pending == future) {
                pending = null;
            }
            sendButton.setEnabled(true);
            hideThinking();
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                appendErrorBubble(cause instanceof AiException aiError
                        ? aiError.getMessage()
                        : "AI request failed: " + cause.getMessage());
            } else {
                String sql = AiAssistant.firstSqlBlock(answer);
                appendAssistantBubble(current, answer, sql);
                history.add(ChatMessage.user(question));
                history.add(ChatMessage.assistant(answer));
            }
            refreshMessages();
        }));
    }

    private void showThinking() {
        thinkingBubble = bubble("Intella DB AI", "thinking…", false);
        JPanel row = new JPanel(new BorderLayout());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            if (pending != null) {
                pending.cancel(true);
            }
        });
        row.add(cancel, BorderLayout.EAST);
        thinkingBubble.add(row, BorderLayout.SOUTH);
        messagesPanel.add(thinkingBubble);
        refreshMessages();
    }

    private void hideThinking() {
        if (thinkingBubble != null) {
            messagesPanel.remove(thinkingBubble);
            thinkingBubble = null;
        }
    }

    // ------------------------------------------------------------------ bubbles

    private static @NotNull JPanel bubble(@NotNull String title, @NotNull String body, boolean isUser) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 8, 4, 8),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(BUBBLE_BORDER, 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8))));
        panel.setBackground(isUser ? USER_BUBBLE : AI_BUBBLE);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));

        JBLabel header = new JBLabel(title);
        header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD, header.getFont().getSize2D() - 1f));
        panel.add(header, BorderLayout.NORTH);

        JTextArea text = new JTextArea(body);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setOpaque(false);
        text.setBorder(JBUI.Borders.emptyTop(2));
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    private void appendUserBubble(@NotNull String text) {
        messagesPanel.add(bubble("You", text, true));
    }

    private void appendAssistantBubble(@NotNull DbConfig forConfig, @NotNull String answer, @Nullable String sql) {
        JPanel panel = bubble("Intella DB AI (" + settingsLabel() + ")", answer, false);
        if (sql != null && !sql.isBlank()) {
            JTextArea sqlArea = new JTextArea(sql);
            sqlArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN,
                    sqlArea.getFont().getSize()));
            sqlArea.setLineWrap(true);
            sqlArea.setWrapStyleWord(true);
            sqlArea.setEditable(false);
            sqlArea.setBackground(SQL_BLOCK);
            sqlArea.setBorder(JBUI.Borders.empty(6, 8));
            JPanel sqlHost = new JPanel(new BorderLayout());
            sqlHost.setOpaque(false);
            sqlHost.add(sqlArea, BorderLayout.CENTER);
            panel.add(sqlHost, BorderLayout.SOUTH);

            JPanel actions = new JPanel();
            actions.setOpaque(false);
            JButton run = new JButton("Run SQL", AllIcons.Actions.Execute);
            run.addActionListener(e -> runSql(forConfig, sql));
            JButton toConsole = new JButton("Insert into Console", AllIcons.Nodes.Console);
            toConsole.addActionListener(e -> insertIntoConsole(forConfig, sql));
            JButton copy = new JButton("Copy SQL", AllIcons.Actions.Copy);
            copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(sql)));
            actions.add(run);
            actions.add(toConsole);
            actions.add(copy);
            JPanel actionHost = new JPanel(new BorderLayout());
            actionHost.setOpaque(false);
            actionHost.add(actions, BorderLayout.WEST);
            panel.add(actionHost, BorderLayout.SOUTH);
        }
        messagesPanel.add(panel);
    }

    private void appendErrorBubble(@NotNull String text) {
        JPanel panel = bubble("Intella DB AI", text, false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 8, 4, 8),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(ERROR_BORDER, 1, true),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8))));
        messagesPanel.add(panel);
    }

    private void appendInfoBubble(@NotNull String text) {
        messagesPanel.add(bubble("Intella DB AI", text, false));
    }

    private void refreshMessages() {
        messagesPanel.revalidate();
        messagesPanel.repaint();
        SwingUtilities.invokeLater(() -> messagesPanel.scrollRectToVisible(
                new java.awt.Rectangle(0, Math.max(0, messagesPanel.getHeight() - 40), 1, 1)));
    }

    private static @NotNull String settingsLabel() {
        AiSettings settings = AiSettings.getInstance();
        String model = settings.model();
        return model.isBlank() ? "AI" : model;
    }

    // ------------------------------------------------------------------ answer actions

    private void runSql(@NotNull DbConfig forConfig, @NotNull String sql) {
        explorer.withSession(forConfig, session -> {
            ResultsPanel panel = explorer.openResultsTab("AI — Result");
            panel.showRunning();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                SqlResult result = session.execute(sql);
                ApplicationManager.getApplication().invokeLater(() -> panel.showResult(result));
            });
        });
    }

    private void insertIntoConsole(@NotNull DbConfig forConfig, @NotNull String sql) {
        ConsolePanel console = explorer.openConsole(forConfig);
        if (console != null) {
            console.setSql(sql);
        }
    }

    // ------------------------------------------------------------------ colors

    private static final com.intellij.ui.JBColor USER_BUBBLE =
            com.intellij.ui.JBColor.namedColor("IntellaDb.userBubble", new Color(0xE2EEF9));
    private static final com.intellij.ui.JBColor AI_BUBBLE =
            com.intellij.ui.JBColor.namedColor("IntellaDb.aiBubble",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xF5F5F5) : new Color(0x2B2D30));
    private static final com.intellij.ui.JBColor SQL_BLOCK =
            com.intellij.ui.JBColor.namedColor("IntellaDb.sqlBlock",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xF0F0F0) : new Color(0x1E1F22));
    private static final com.intellij.ui.JBColor BUBBLE_BORDER =
            com.intellij.ui.JBColor.namedColor("IntellaDb.border",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xD0D0D0) : new Color(0x43454A));
    private static final com.intellij.ui.JBColor ERROR_BORDER =
            com.intellij.ui.JBColor.namedColor("IntellaDb.errorBorder", new Color(0xE55765));
}
