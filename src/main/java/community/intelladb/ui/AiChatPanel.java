package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import community.intelladb.IntellaDbIcons;
import community.intelladb.ai.AiAssistant;
import community.intelladb.ai.AiCredentials;
import community.intelladb.ai.AiException;
import community.intelladb.ai.AiPreset;
import community.intelladb.ai.AiSettings;
import community.intelladb.ai.ChatMessage;
import community.intelladb.ai.MarkdownToHtml;
import community.intelladb.ai.OpenAiCompatibleClient;
import community.intelladb.connection.DbConfig;
import community.intelladb.connection.DbSession;
import community.intelladb.connection.SessionOpener;
import community.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Conversation-style AI chat, hosted as a tab inside the DB Explorer tool window.
 * Questions, answers, SQL blocks and — via Run — the query results all live in one
 * scrolling transcript. A connection switcher at the top picks which saved connection
 * the chat talks to. Enter sends, Shift+Enter inserts a newline.
 */
public final class AiChatPanel extends JPanel implements Disposable {

    private static final int BUBBLE_TEXT_WIDTH = 460;
    private static final int INLINE_RESULT_HEIGHT = 190;
    private static final int MIN_INPUT_ROWS = 2;
    private static final int MAX_INPUT_ROWS = 8;

    private final Project project;
    private final SessionOpener opener;
    private final JPanel transcript = new JPanel(new GridBagLayout());
    private final JBScrollPane scrollPane;
    private final JBTextArea input = new JBTextArea(MIN_INPUT_ROWS, 36);
    private final JButton sendButton = new JButton("Send");
    private final ComboBox<DbConfig> connectionCombo = new ComboBox<>();
    private final Map<String, List<ChatMessage>> historyByConfig = new HashMap<>();
    private boolean updatingCombo;
    private boolean disposed;
    /** Scope for listeners registered by this panel (dispose() is called directly, not via Disposer). */
    private final Disposable listenerScope = Disposer.newDisposable("IntellaDb AI chat");
    /** Providers the model picker offers; refreshed off the EDT (it reads the keychain). */
    private volatile List<AiPreset> usableProviders = List.of();
    /** Bumped by New Chat; answers from an older generation are discarded. */
    private int chatGeneration;

    @Nullable
    private CompletableFuture<?> pending;
    @Nullable
    private JPanel thinkingRow;

    public AiChatPanel(@NotNull Project project) {
        this.project = project;
        this.opener = SessionOpener.getInstance(project);

        setLayout(new BorderLayout());

        transcript.setBorder(JBUI.Borders.empty(4, 4, 8, 4));
        scrollPane = new JBScrollPane(transcript);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
        add(buildInputArea(), BorderLayout.SOUTH);
        add(buildConnectionBar(), BorderLayout.NORTH);

        refreshConnections();
        refreshProviders();
        AiSettings.getInstance().addChangeListener(this::refreshProviders, listenerScope);
        // Keep the switcher in sync when connections are added/edited/deleted in DB Explorer.
        community.intelladb.connection.ConnectionManager.getInstance(project).addListener(() -> {
            if (!disposed) {
                refreshConnections();
            }
        });

    }

    // ------------------------------------------------------------------ connection bar

    private JComponent buildConnectionBar() {
        connectionCombo.setRenderer(new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull javax.swing.JList<? extends DbConfig> list, DbConfig value,
                                  int index, boolean selected, boolean hasFocus) {
                if (value == null) {
                    setText("No connections — add one in the Explorer tab");
                    setIcon(IntellaDbIcons.CONNECTION);
                } else {
                    setText(value.name + "  ·  " + value.describe());
                    boolean connected = community.intelladb.connection.ConnectionManager.getInstance(project)
                            .session(value.id) != null;
                    setIcon(connected ? IntellaDbIcons.CONNECTION_CONNECTED : IntellaDbIcons.CONNECTION);
                }
            }
        });
        connectionCombo.addActionListener(e -> {
            if (updatingCombo) {
                return;
            }
            input.requestFocusInWindow();
        });
        connectionCombo.setToolTipText("Connection the assistant answers about");
        // Header mirrors the Explorer tab: one toolbar row. The combo takes the free
        // width (shrinking in a narrow window); chat actions sit on the right.
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ModelPickerAction());
        group.addSeparator();
        group.add(new DumbAwareAction("New Chat", "Clear the conversation and start over",
                AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                newChat();
            }
        });
        group.add(new DumbAwareAction("AI Provider Settings", "Configure the AI provider (provider, key, model)",
                AllIcons.General.Settings) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "community.intelladb.ai.provider");
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("IntellaDbAiChat", group, true);
        toolbar.setTargetComponent(this);

        JPanel bar = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        JPanel comboHost = new JPanel(new BorderLayout());
        comboHost.setBorder(JBUI.Borders.empty(3, 6, 3, 0));
        comboHost.add(connectionCombo, BorderLayout.CENTER);
        bar.add(comboHost, BorderLayout.CENTER);
        bar.add(toolbar.getComponent(), BorderLayout.EAST);
        bar.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        return bar;
    }

    /** Clears the transcript and every per-connection history, cancelling any request in flight. */
    private void newChat() {
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        thinkingRow = null;
        chatGeneration++; // drop late callbacks from the previous conversation
        historyByConfig.clear();
        transcript.removeAll();
        sendButton.setEnabled(true);
        transcript.revalidate();
        transcript.repaint();
        input.requestFocusInWindow();
    }

    /** Reloads saved connections into the switcher, keeping the current selection when possible. */
    private void refreshConnections() {
        List<DbConfig> configs = opener.configs();
        String selectedId = selectedConfig() != null ? selectedConfig().id : null;
        updatingCombo = true;
        try {
            connectionCombo.removeAllItems();
            for (DbConfig config : configs) {
                connectionCombo.addItem(config);
            }
            DbConfig toSelect = configs.stream()
                    .filter(c -> c.id.equals(selectedId))
                    .findFirst()
                    .orElse(configs.isEmpty() ? null : configs.get(0));
            if (toSelect != null) {
                connectionCombo.setSelectedItem(toSelect);
            }
        } finally {
            updatingCombo = false;
        }
        connectionCombo.setEnabled(!configs.isEmpty());
    }

    // ------------------------------------------------------------------ layout helpers

    private void addRow(@NotNull Component row, boolean right) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weightx = 1.0;
        // Assistant/result rows span the window; user messages hug the right edge
        // so the two sides of the conversation read differently.
        gbc.anchor = right ? GridBagConstraints.NORTHEAST : GridBagConstraints.NORTH;
        gbc.fill = right ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        transcript.add(row, gbc);
        refreshTranscript();
    }

    private void refreshTranscript() {
        transcript.revalidate();
        transcript.repaint();
        ApplicationManager.getApplication().invokeLater(() -> {
            scrollPane.getHorizontalScrollBar().setValue(0);
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private JComponent buildInputArea() {
        // Compose box: one bordered field (text + Send inside), like the platform's AI
        // Assistant / commit message input, instead of a bare textarea beside a button.
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setBorder(JBUI.Borders.empty(6, 8));
        // The placeholder replaces the old welcome message in the transcript.
        input.getEmptyText().setText("Ask about your database in plain English — e.g. “Which customers ordered the most?”");
        input.getEmptyText().appendLine("Enter to send · Shift+Enter for a new line",
                com.intellij.ui.SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, null);
        JBScrollPane inputScroll = new JBScrollPane(input);
        inputScroll.setBorder(JBUI.Borders.empty());

        sendButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true); // accent-filled primary button
        sendButton.setFocusable(false);
        sendButton.addActionListener(e -> send());
        JPanel sendHost = new JPanel(new BorderLayout());
        sendHost.setOpaque(false);
        sendHost.setBorder(JBUI.Borders.empty(0, 0, 6, 6));
        sendHost.add(sendButton, BorderLayout.SOUTH);

        JPanel compose = new JPanel(new BorderLayout());
        compose.setBackground(input.getBackground());
        compose.setBorder(JBUI.Borders.customLine(JBColor.border()));
        compose.add(inputScroll, BorderLayout.CENTER);
        compose.add(sendHost, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(JBUI.Borders.empty(6, 8, 8, 8));
        south.add(compose, BorderLayout.CENTER);

        // Enter sends; Shift+Enter inserts a line break. It must be bound explicitly: a
        // JTextArea only maps plain Enter to insert-break and drops the typed '\n' of
        // Shift+Enter, so without this binding multi-line questions were impossible.
        javax.swing.InputMap keys = input.getInputMap(javax.swing.JComponent.WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "intella-send");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                javax.swing.text.DefaultEditorKit.insertBreakAction);
        // Grow with the text (2–8 lines), then scroll.
        input.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                int lines = Math.max(MIN_INPUT_ROWS, Math.min(MAX_INPUT_ROWS, input.getLineCount()));
                if (lines != input.getRows()) {
                    input.setRows(lines);
                    south.revalidate();
                }
            }
        });
        input.getActionMap().put("intella-send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                send();
            }
        });
        return south;
    }

    // ------------------------------------------------------------------ public API

    /** Points the chat at the given connection (selects it in the header switcher). */
    public void setConnection(@NotNull DbConfig config) {
        for (int i = 0; i < connectionCombo.getItemCount(); i++) {
            if (connectionCombo.getItemAt(i).id.equals(config.id)) {
                connectionCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    public void setDraft(@NotNull String text) {
        input.setText(text);
        input.requestFocusInWindow();
    }

    /** Opens the AI tab inside the DB Explorer, optionally pre-filling the input. */
    public static void openInExplorer(@NotNull Project project, @NotNull String text) {
        var toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(DbToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.activate(() -> {
            for (var content : toolWindow.getContentManager().getContents()) {
                if (content.getComponent() instanceof DbExplorerPanel explorer) {
                    explorer.openAiAssistant();
                    AiChatPanel panel = explorer.aiPanel();
                    if (panel != null && !text.isBlank()) {
                        panel.setDraft(text);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ sending

    private void send() {
        String question = input.getText().trim();
        if (question.isEmpty()) {
            return;
        }
        DbConfig current = selectedConfig();
        if (current == null) {
            appendMessage(bubble(null,
                    errorBody("Add a connection in the Explorer tab, then pick it in the selector above."), false), false);
            return;
        }
        input.setText("");
        sendButton.setEnabled(false);
        appendMessage(bubble(null, body(question, 56), true), true);
        // Connects (with password prompt) when needed, then continues on the EDT.
        // The provider pre-check runs inside doSend's background path (PasswordSafe
        // must not be read on the EDT).
        opener.withSession(current, session -> doSend(current, session, question));
    }

    private void doSend(@NotNull DbConfig current, @NotNull DbSession session, @NotNull String question) {
        AiSettings settings = AiSettings.getInstance();
        List<ChatMessage> history = historyByConfig.computeIfAbsent(current.id, k -> new ArrayList<>());
        showThinking();

        List<ChatMessage> messages = AiAssistant.conversation(
                AiAssistant.systemPrompt(session.catalog(), settings.includeSchema()),
                List.copyOf(history), // append-only: keeps the request prefix cacheable
                question);
        String baseUrl = settings.baseUrl();
        String model = settings.model();
        double temperature = settings.temperature();
        double topP = settings.topP();
        int maxTokens = settings.maxTokens();
        boolean localProvider = !settings.preset().needsApiKey();

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            // PasswordSafe reads must stay off the EDT, and local providers must not
            // touch the credential store at all — they never need a key, and the
            // macOS keychain lookup can block on an access prompt.
            String key = localProvider ? null : AiCredentials.read();
            boolean missingKey = (key == null || key.isBlank()) && !localProvider;
            if (baseUrl.isBlank() || model.isBlank() || missingKey) {
                throw new AiException("No AI provider is configured (or the API key is missing).\n"
                        + "Open Settings → Tools → Intella DB — AI Provider.");
            }
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    baseUrl, key == null ? "" : key, model, temperature, topP, maxTokens);
            return client.chat(messages);
        });
        pending = future;
        int generation = chatGeneration;
        future.whenComplete((answer, error) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || generation != chatGeneration) {
                return; // New Chat already reset the transcript
            }
            if (pending == future) {
                pending = null;
            }
            sendButton.setEnabled(true);
            hideThinking();
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                String message = cause instanceof AiException aiError
                        ? aiError.getMessage()
                        : cause instanceof java.util.concurrent.CancellationException
                        ? "Cancelled."
                        : "AI request failed: " + cause.getMessage();
                appendMessage(bubble(null, errorBody(message), false), false);
            } else {
                String sql = AiAssistant.firstSqlBlock(answer);
                JPanel answerRow = assistantAnswerRow(answer, sql, current);
                appendMessage(answerRow, false);
                history.add(ChatMessage.user(question));
                history.add(ChatMessage.assistant(answer));
                List<ChatMessage> compacted = AiAssistant.compact(history,
                        AiAssistant.MAX_HISTORY_MESSAGES, AiAssistant.MAX_HISTORY_CHARS);
                if (compacted != history) {
                    history.clear();
                    history.addAll(compacted);
                }
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
        thinkingRow = bubble(settingsLabel(), body, false);
        appendMessage(thinkingRow, false);
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
        JPanel stack = new JPanel();
        stack.setLayout(new javax.swing.BoxLayout(stack, javax.swing.BoxLayout.Y_AXIS));
        stack.setOpaque(false);
        if (sql == null || sql.isBlank()) {
            stack.add(markdownBody(answer));
        } else {
            int fence = answer.indexOf("```");
            String before = answer.substring(0, Math.max(0, fence)).trim();
            int close = fence >= 0 ? answer.indexOf("```", fence + 3) : -1;
            String after = close >= 0 ? answer.substring(close + 3).trim() : "";
            if (!before.isBlank()) {
                stack.add(markdownBody(before));
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
            if (!after.isBlank()) {
                stack.add(markdownBody(after));
            }
        }
        return bubble(settingsLabel(), stack, false);
    }

    /** Renders an answer (or its prose parts) as markdown: paragraphs, lists, code fences. */
    private @NotNull JComponent markdownBody(@NotNull String markdown) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 6, 0);
        for (MarkdownToHtml.Block block : MarkdownToHtml.split(markdown)) {
            JComponent child;
            if (block instanceof MarkdownToHtml.Code code) {
                child = sqlBlock(code.text());
            } else if (block instanceof MarkdownToHtml.Paragraph paragraph) {
                child = htmlParagraph(paragraph.html());
            } else {
                continue;
            }
            panel.add(child, gbc);
        }
        return panel;
    }

    /** Swing-HTML paragraph with the platform's word-wrap kit; hyperlinks open in the browser. */
    private @NotNull JComponent htmlParagraph(@NotNull String innerHtml) {
        JEditorPane pane = new JEditorPane();
        pane.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        pane.setText("<html><head></head><body style=\"text-align:left\">" + innerHtml + "</body></html>");
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(null);
        pane.setForeground(JBUI.CurrentTheme.Label.foreground());
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        pane.setFont(JBUI.Fonts.label(13));
        pane.setFocusable(false);
        // Measure the wrapped height at the width the bubble will actually give us.
        int width = Math.max(120, bubbleTextWidth());
        pane.setSize(width, Integer.MAX_VALUE);
        Dimension size = new Dimension(width, pane.getPreferredSize().height);
        pane.setPreferredSize(size);
        pane.setMinimumSize(size);
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                BrowserUtil.browse(e.getURL());
            }
        });
        return pane;
    }

    /** Runs the SQL and appends the result table into the conversation. */
    private void runSqlInline(@NotNull DbConfig forConfig, @NotNull String sql) {
        opener.withSession(forConfig, session -> {
            ResultsPanel results = new ResultsPanel(project);
            results.setPreferredSize(new Dimension(bubbleTextWidth(), INLINE_RESULT_HEIGHT));
            results.showRunning();
            JPanel resultRow = bubble("Query result", results, false);
            appendMessage(resultRow, false);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                SqlResult result = session.execute(sql);
                ApplicationManager.getApplication().invokeLater(() -> {
                    results.showResult(result);
                    refreshTranscript();
                });
            });
        });
    }

    /** Hands the SQL to the DB Explorer's console (activating it if needed). */
    private void insertIntoConsole(@NotNull DbConfig forConfig, @NotNull String sql) {
        var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DbToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.activate(() -> {
            for (var content : toolWindow.getContentManager().getContents()) {
                if (content.getComponent() instanceof DbExplorerPanel explorer) {
                    explorer.openConsole(forConfig, sql);
                }
            }
        });
    }

    private void appendMessage(@NotNull JPanel row, boolean right) {
        addRow(row, right);
    }

    private @Nullable DbConfig selectedConfig() {
        return connectionCombo.getSelectedItem() instanceof DbConfig config ? config : null;
    }

    // ------------------------------------------------------------------ bubble building

    /**
     * A transcript row. User messages are a compact filled pill hugging the right edge;
     * assistant rows (answers, errors, results) are flat, full-width, under a small
     * icon + label header — the way the platform's own AI chat reads, and the prose
     * gets the whole width instead of sitting in a second box.
     */
    private @NotNull JPanel bubble(@Nullable String title, @NotNull JComponent body, boolean user) {
        if (user) {
            JPanel panel = new BubblePanel();
            panel.add(body, BorderLayout.CENTER);
            return panel;
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(2, 6, 6, 6));
        boolean result = "Query result".equals(title);
        JBLabel header = new JBLabel(title == null ? "Assistant" : title,
                result ? IntellaDbIcons.TABLE : IntellaDbIcons.AI, javax.swing.SwingConstants.LEFT);
        header.setFont(JBUI.Fonts.smallFont());
        header.setForeground(SECONDARY_TEXT);
        header.setIconTextGap(JBUI.scale(5));
        header.setBorder(JBUI.Borders.emptyBottom(5));
        panel.add(header, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Rounded, antialiased pill for user messages. Fills its own background in
     * paintComponent so the corners stay transparent instead of showing a square fill.
     */
    private static final class BubblePanel extends JPanel {
        BubblePanel() {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(JBUI.Borders.empty(7, 12, 8, 12));
        }

        @Override
        protected void paintComponent(@NotNull Graphics g) {
            Graphics2D gr = (Graphics2D) g.create();
            try {
                gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = JBUI.scale(16);
                gr.setColor(USER_BUBBLE);
                gr.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } finally {
                gr.dispose();
            }
            super.paintComponent(g);
        }
    }

    private int bubbleTextWidth() {
        // Used for components created after the window is visible (bodies, SQL blocks,
        // result tables). Horizontal scrolling is off, so the viewport cannot be widened
        // by content; it is the real usable width.
        int viewport = scrollPane.getViewport().getWidth();
        int host = getWidth();
        int base = viewport >= 120 ? viewport : (host >= 120 ? host : 0);
        if (base < 120) {
            return BUBBLE_TEXT_WIDTH;
        }
        return Math.max(200, Math.min(680, base - 56));
    }

    /**
     * Plain wrapped text body. A JTextArea word-wraps inside whatever width the layout
     * assigns — unlike fixed-width Swing HTML it never paints past its box, so bubbles
     * stay readable at any tool-window width. maxColumns lets short texts (user
     * messages) hug their content instead of stretching to the full window.
     */
    private @NotNull JComponent body(@NotNull String text) {
        return body(text, Integer.MAX_VALUE);
    }

    private @NotNull JComponent body(@NotNull String text, int maxColumns) {
        JBTextArea area = new JBTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty());
        int charWidth = Math.max(1, area.getFontMetrics(area.getFont()).charWidth('n'));
        int longest = text.lines().mapToInt(String::length).max().orElse(20);
        int wrapColumns = Math.max(24, bubbleTextWidth() / charWidth);
        area.setColumns(Math.max(10, Math.min(maxColumns, Math.min(longest + 1, wrapColumns))));
        return area;
    }

    private @NotNull JComponent errorBody(@NotNull String text) {
        JComponent area = body(text);
        area.setForeground(ERROR_TEXT);
        return area;
    }

    private @NotNull JComponent sqlBlock(@NotNull String sql) {
        JTextArea area = new JTextArea(sql);
        // Editor font + editor background: SQL reads like code from the user's own scheme.
        area.setFont(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(false);
        area.setBackground(SQL_BLOCK);
        area.setBorder(JBUI.Borders.empty(6, 8));
        long lines = sql.lines().count();
        int maxLen = sql.lines().mapToInt(String::length).max().orElse(40);
        int rows = (int) Math.min(10, Math.max(2, lines));
        // Cap the preferred/min width to the tool window: a 64-column JTextArea would
        // force the whole transcript row wider than the viewport and clip every bubble.
        int charWidth = area.getFontMetrics(area.getFont()).charWidth('0');
        int fitColumns = Math.max(16, (bubbleTextWidth() - 56) / Math.max(1, charWidth));
        int columns = Math.max(12, Math.min(fitColumns, Math.min(64, maxLen)));
        area.setRows(rows);
        area.setColumns(columns);
        JScrollPane scroller = new JScrollPane(area);
        scroller.setBorder(JBUI.Borders.customLine(JBColor.border()));
        Dimension size = new Dimension(bubbleTextWidth(), area.getPreferredSize().height + 12);
        scroller.setPreferredSize(size);
        // GridBag compresses rows down to their minimum when the transcript slightly
        // overflows the viewport — code blocks must not be compressible to one line.
        scroller.setMinimumSize(size);
        return scroller;
    }

    private static @NotNull String settingsLabel() {
        String model = AiSettings.getInstance().model();
        return model.isBlank() ? "AI" : model;
    }

    // ------------------------------------------------------------------ model picker

    /**
     * Re-reads which providers are usable (set up + key saved). If the active provider is
     * no longer usable — e.g. its key was removed — the chat switches to the first usable one.
     */
    private void refreshProviders() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<AiPreset> usable = AiCredentials.usablePresets();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed) {
                    return;
                }
                usableProviders = usable;
                AiSettings settings = AiSettings.getInstance();
                if (!usable.isEmpty() && !usable.contains(settings.preset())) {
                    AiPreset first = usable.get(0);
                    settings.setActive(first.id(), settings.model(first.id()));
                }
            });
        });
    }

    /** "glm-5.3 ▾" next to the connection selector: models grouped by provider. */
    private final class ModelPickerAction extends ComboBoxAction {
        ModelPickerAction() {
            setSmallVariant(true);
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button,
                                                                     @NotNull DataContext context) {
            AiSettings settings = AiSettings.getInstance();
            DefaultActionGroup group = new DefaultActionGroup();
            for (AiPreset provider : usableProviders) {
                group.addSeparator(provider.label());
                for (String model : settings.models(provider.id())) {
                    boolean active = provider.id().equals(settings.presetId()) && model.equals(settings.model());
                    group.add(new DumbAwareAction(model, provider.label() + " — " + model,
                            active ? AllIcons.Actions.Checked : null) {
                        @Override
                        public void actionPerformed(@NotNull AnActionEvent e) {
                            settings.setActive(provider.id(), model);
                        }
                    });
                }
            }
            group.addSeparator();
            group.add(new DumbAwareAction("Configure AI Providers…", "Add keys for more providers",
                    AllIcons.General.Settings) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "community.intelladb.ai.provider");
                }
            });
            return group;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            AiSettings settings = AiSettings.getInstance();
            List<AiPreset> usable = usableProviders;
            if (usable.contains(settings.preset())) {
                e.getPresentation().setText(settings.model());
                e.getPresentation().setDescription("Model: " + settings.preset().label() + " — " + settings.model());
            } else {
                e.getPresentation().setText(usable.isEmpty() ? "No AI provider" : "Select model");
                e.getPresentation().setDescription(usable.isEmpty()
                        ? "Add an API key in Settings → Tools → Intella DB — AI Provider" : "Pick the model to chat with");
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    // ------------------------------------------------------------------ colors
    // Every color is resolved lazily from the current theme / editor scheme, so the chat
    // matches whatever LaF is active (and follows live theme switches) instead of
    // hardcoding one light and one dark palette. Themes can still override via IntellaDb.*.

    /** Neutral pill: the platform's inactive-selection color. */
    private static final JBColor USER_BUBBLE = JBColor.namedColor("IntellaDb.userBubble",
            JBColor.lazy(() -> JBUI.CurrentTheme.List.Selection.background(false)));
    private static final JBColor SECONDARY_TEXT = JBColor.namedColor("IntellaDb.secondaryText",
            JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    /** SQL blocks sit on the editor background, like code in the editor. */
    private static final JBColor SQL_BLOCK = JBColor.namedColor("IntellaDb.sqlBlock",
            JBColor.lazy(() -> EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground()));
    private static final JBColor ERROR_TEXT = JBColor.namedColor("Label.errorForeground",
            new JBColor(new Color(0xC7222D), new Color(0xFF5261)));

    @Override
    public void dispose() {
        disposed = true;
        Disposer.dispose(listenerScope);
    }
}
