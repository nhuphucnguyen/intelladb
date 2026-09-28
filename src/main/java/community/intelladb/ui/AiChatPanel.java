package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
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
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Conversation-style chat panel. Questions, answers, and — when the user clicks Run —
 * the query results all live in one scrolling transcript. Enter sends, Shift+Enter
 * inserts a newline.
 */
public final class AiChatPanel extends JPanel {

    private static final int BUBBLE_TEXT_WIDTH = 460;
    private static final int INLINE_RESULT_HEIGHT = 190;

    private final Project project;
    private final DbExplorerPanel explorer;
    private final JPanel transcript = new JPanel(new GridBagLayout());
    private final JBScrollPane scrollPane;
    private final JTextArea input = new JTextArea(2, 36);
    private final JButton sendButton = new JButton("Send");
    private final JBLabel contextLabel = new JBLabel(" ");
    private final Map<String, List<ChatMessage>> historyByConfig = new HashMap<>();

    @Nullable
    private DbConfig config;
    @Nullable
    private CompletableFuture<?> pending;
    @Nullable
    private JPanel thinkingRow;

    public AiChatPanel(@NotNull Project project, @NotNull DbExplorerPanel explorer) {
        this.project = project;
        this.explorer = explorer;

        setLayout(new BorderLayout());
        transcript.setBorder(JBUI.Borders.empty(4, 4, 8, 4));
        scrollPane = new JBScrollPane(transcript);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
        add(buildInputArea(), BorderLayout.SOUTH);

        appendMessage(bubble("Intella DB AI",
                htmlBody("Ask about your database in plain English — e.g. “Which customers ordered the most?”<br>"
                        + "Answers include a SQL block with a Run button; results appear right here in the chat."),
                false, null));
    }

    // ------------------------------------------------------------------ layout helpers

    private void addRow(@NotNull Component row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        transcript.add(row, gbc);
        refreshTranscript();
    }

    private void refreshTranscript() {
        transcript.revalidate();
        transcript.repaint();
        ApplicationManager.getApplication().invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private JComponent buildInputArea() {
        JPanel south = new JPanel(new BorderLayout());

        contextLabel.setBorder(JBUI.Borders.empty(4, 8, 0, 8));
        south.add(contextLabel, BorderLayout.NORTH);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        JPanel inputHost = new JPanel(new BorderLayout());
        inputHost.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
        inputHost.add(new JScrollPane(input), BorderLayout.CENTER);
        sendButton.setAlignmentY(Component.TOP_ALIGNMENT);
        sendButton.addActionListener(e -> send());
        inputHost.add(sendButton, BorderLayout.EAST);
        south.add(inputHost, BorderLayout.CENTER);

        // Enter sends; Shift+Enter (and any modified Enter) falls through to newline.
        input.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "intella-send");
        input.getActionMap().put("intella-send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                send();
            }
        });
        return south;
    }

    // ------------------------------------------------------------------ public API

    public void setConnection(@NotNull DbConfig config) {
        this.config = config;
        contextLabel.setText("Asking about: " + config.name + " (" + config.describe() + ")");
    }

    public void setDraft(@NotNull String text) {
        input.setText(text);
        input.requestFocusInWindow();
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
            appendMessage(bubble("Intella DB AI",
                    errorBody("Add a connection in the DB Explorer tree first."), false, null));
            return;
        }
        setConnection(current);
        input.setText("");
        sendButton.setEnabled(false);
        appendMessage(bubble("You", htmlBody(escapeHtml(question)), true, null));
        DbConfig target = current;
        // Connects (with password prompt) when needed, then continues on the EDT.
        // The provider pre-check runs inside doSend's background path (PasswordSafe
        // must not be read on the EDT).
        explorer.withSession(target, session -> doSend(target, session, question));
    }

    private void doSend(@NotNull DbConfig current, @NotNull DbSession session, @NotNull String question) {
        AiSettings settings = AiSettings.getInstance();
        List<ChatMessage> history = historyByConfig.computeIfAbsent(current.id, k -> new ArrayList<>());
        showThinking();

        List<ChatMessage> messages = AiAssistant.conversation(
                AiAssistant.systemPrompt(session.catalog(), settings.includeSchema()),
                AiAssistant.trim(history, 10),
                question);
        String baseUrl = settings.baseUrl();
        String model = settings.model();
        double temperature = settings.temperature();
        int maxTokens = settings.maxTokens();
        boolean localProvider = settings.presetId().equals("ollama") || settings.presetId().equals("lmstudio");

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            // PasswordSafe read must stay off the EDT.
            String key = AiCredentials.read();
            boolean missingKey = (key == null || key.isBlank()) && !localProvider;
            if (baseUrl.isBlank() || model.isBlank() || missingKey) {
                throw new AiException("No AI provider is configured (or the API key is missing).\n"
                        + "Open Settings → Tools → Intella DB — AI Provider.");
            }
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    baseUrl, key == null ? "" : key, model, temperature, maxTokens);
            return client.chat(messages);
        });
        pending = future;
        future.whenComplete((answer, error) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (pending == future) {
                pending = null;
            }
            sendButton.setEnabled(true);
            hideThinking();
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                String message = cause instanceof AiException aiError
                        ? escapeHtml(aiError.getMessage()).replace("\n", "<br>")
                        : "AI request failed: " + escapeHtml(String.valueOf(cause.getMessage()));
                appendMessage(bubble("Intella DB AI", errorBody(message), false, null));
            } else {
                String sql = AiAssistant.firstSqlBlock(answer);
                JPanel answerRow = assistantAnswerRow(answer, sql, current);
                appendMessage(answerRow);
                history.add(ChatMessage.user(question));
                history.add(ChatMessage.assistant(answer));
            }
        }));
    }

    // ------------------------------------------------------------------ transcript rows

    private void showThinking() {
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        JBLabel label = new JBLabel("thinking…");
        label.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        body.add(label, BorderLayout.CENTER);
        JButton cancel = new JButton("Cancel");
        cancel.setMargin(JBUI.insets(2, 8));
        cancel.addActionListener(e -> {
            if (pending != null) {
                pending.cancel(true);
            }
        });
        body.add(cancel, BorderLayout.EAST);
        thinkingRow = bubble("Intella DB AI", body, false, null);
        appendMessage(thinkingRow);
    }

    private void hideThinking() {
        if (thinkingRow != null) {
            transcript.remove(thinkingRow);
            thinkingRow = null;
            transcript.revalidate();
            transcript.repaint();
        }
    }

    private @NotNull JPanel assistantAnswerRow(@NotNull String answer, @Nullable String sql, @NotNull DbConfig forConfig) {
        JComponent body;
        JPanel row;
        if (sql == null || sql.isBlank()) {
            body = htmlBody(escapeHtml(answer).replace("\n", "<br>"));
            row = bubble("Intella DB AI (" + escapeHtml(settingsLabel()) + ")", body, false, null);
        } else {
            String prose = answer.substring(0, answer.indexOf(sql) >= 0
                    ? Math.max(0, answer.indexOf("```"))
                    : answer.length()).trim();
            JPanel stack = new JPanel();
            stack.setLayout(new javax.swing.BoxLayout(stack, javax.swing.BoxLayout.Y_AXIS));
            stack.setOpaque(false);
            if (!prose.isBlank()) {
                stack.add(htmlBody(escapeHtml(prose).replace("\n", "<br>")));
            }
            stack.add(sqlBlock(sql));
            JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
            actions.setOpaque(false);
            JButton run = new JButton("Run", AllIcons.Actions.Execute);
            run.addActionListener(e -> runSqlInline(forConfig, sql));
            JButton toConsole = new JButton("Insert into Console", AllIcons.Nodes.Console);
            toConsole.addActionListener(e -> insertIntoConsole(forConfig, sql));
            JButton copy = new JButton("Copy", AllIcons.Actions.Copy);
            copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(sql)));
            actions.add(run);
            actions.add(toConsole);
            actions.add(copy);
            stack.add(actions);
            row = bubble("Intella DB AI (" + escapeHtml(settingsLabel()) + ")", stack, false, null);
        }
        return row;
    }

    /** Runs the SQL and appends the result table into the conversation. */
    private void runSqlInline(@NotNull DbConfig forConfig, @NotNull String sql) {
        explorer.withSession(forConfig, session -> {
            ResultsPanel results = new ResultsPanel();
            results.setPreferredSize(new Dimension(BUBBLE_TEXT_WIDTH, INLINE_RESULT_HEIGHT));
            results.showRunning();
            JPanel resultRow = bubble("Query result", results, false, null);
            appendMessage(resultRow);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                SqlResult result = session.execute(sql);
                ApplicationManager.getApplication().invokeLater(() -> {
                    results.showResult(result);
                    refreshTranscript();
                });
            });
        });
    }

    private void insertIntoConsole(@NotNull DbConfig forConfig, @NotNull String sql) {
        ConsolePanel console = explorer.openConsole(forConfig);
        if (console != null) {
            console.setSql(sql);
        }
    }

    private void appendMessage(@NotNull JPanel row) {
        addRow(row);
    }

    // ------------------------------------------------------------------ bubble building

    private @NotNull JPanel bubble(@NotNull String title, @NotNull JComponent body, boolean user,
                                   @SuppressWarnings("unused") Object ignored) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(user ? USER_BUBBLE : AI_BUBBLE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BUBBLE_BORDER, 1, true),
                JBUI.Borders.empty(6, 10, 8, 10)));
        JBLabel header = new JBLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize2D() - 1f));
        header.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
        header.setBorder(JBUI.Borders.emptyBottom(4));
        panel.add(header, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /** HTML body label with a fixed wrap width — sizes correctly inside GridBagLayout rows. */
    private @NotNull JBLabel htmlBody(@NotNull String html) {
        JBLabel label = new JBLabel("<html><body style='width:" + BUBBLE_TEXT_WIDTH + "px'>"
                + html + "</body></html>");
        return label;
    }

    private @NotNull JBLabel errorBody(@NotNull String html) {
        JBLabel label = htmlBody(html);
        label.setForeground(ERROR_BORDER);
        return label;
    }

    private @NotNull JComponent sqlBlock(@NotNull String sql) {
        JTextArea area = new JTextArea(sql);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setEditable(false);
        area.setLineWrap(false);
        area.setBackground(SQL_BLOCK);
        area.setBorder(JBUI.Borders.empty(4, 6));
        long lines = sql.lines().count();
        int maxLen = sql.lines().mapToInt(String::length).max().orElse(40);
        int rows = (int) Math.min(10, Math.max(2, lines));
        int columns = (int) Math.min(64, Math.max(20, maxLen));
        area.setRows(rows);
        area.setColumns(columns);
        JScrollPane scroller = new JScrollPane(area);
        scroller.setBorder(BorderFactory.createLineBorder(BUBBLE_BORDER));
        return scroller;
    }

    private static @NotNull String escapeHtml(@NotNull String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static @NotNull String settingsLabel() {
        String model = AiSettings.getInstance().model();
        return model.isBlank() ? "AI" : model;
    }

    // ------------------------------------------------------------------ colors

    private static final com.intellij.ui.JBColor USER_BUBBLE =
            com.intellij.ui.JBColor.namedColor("IntellaDb.userBubble", new Color(0xE2EEF9));
    private static final com.intellij.ui.JBColor AI_BUBBLE =
            com.intellij.ui.JBColor.namedColor("IntellaDb.aiBubble",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xF7F7F7) : new Color(0x2B2D30));
    private static final com.intellij.ui.JBColor SQL_BLOCK =
            com.intellij.ui.JBColor.namedColor("IntellaDb.sqlBlock",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xF0F0F0) : new Color(0x1E1F22));
    private static final com.intellij.ui.JBColor BUBBLE_BORDER =
            com.intellij.ui.JBColor.namedColor("IntellaDb.border",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xD0D0D0) : new Color(0x43454A));
    private static final com.intellij.ui.JBColor ERROR_BORDER =
            com.intellij.ui.JBColor.namedColor("IntellaDb.errorBorder", new Color(0xE55765));
}
