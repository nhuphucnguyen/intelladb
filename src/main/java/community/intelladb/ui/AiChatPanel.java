package community.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import community.intelladb.ai.AiAssistant;
import community.intelladb.ai.AiCredentials;
import community.intelladb.ai.AiException;
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

import javax.swing.BorderFactory;
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
import java.awt.Font;
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

    private final Project project;
    private final SessionOpener opener;
    private final JPanel transcript = new JPanel(new GridBagLayout());
    private final JBScrollPane scrollPane;
    private final JTextArea input = new JTextArea(2, 36);
    private final JButton sendButton = new JButton("Send");
    private final JBLabel contextLabel = new JBLabel(" ");
    private final ComboBox<DbConfig> connectionCombo = new ComboBox<>();
    private final Map<String, List<ChatMessage>> historyByConfig = new HashMap<>();
    private boolean updatingCombo;
    private boolean disposed;

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
        // Keep the switcher in sync when connections are added/edited/deleted in DB Explorer.
        community.intelladb.connection.ConnectionManager.getInstance(project).addListener(() -> {
            if (!disposed) {
                refreshConnections();
            }
        });

        appendMessage(bubble("Intella DB AI",
                body("Ask about your database in plain English — e.g. “Which customers ordered the most?”\n"
                        + "Answers include a SQL block with a Run button; results appear right here in the chat.\n"
                        + "Switch the connection any time using the selector above."),
                false), false);
    }

    // ------------------------------------------------------------------ connection bar

    private JComponent buildConnectionBar() {
        connectionCombo.setRenderer(new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull javax.swing.JList<? extends DbConfig> list, DbConfig value,
                                  int index, boolean selected, boolean hasFocus) {
                setText(value == null ? "No connections yet"
                        : value.name + "  —  " + value.describe());
            }
        });
        connectionCombo.addActionListener(e -> {
            if (updatingCombo) {
                return;
            }
            Object selected = connectionCombo.getSelectedItem();
            if (selected instanceof DbConfig config) {
                setConnection(config);
            }
        });
        // GridBag keeps the label fixed and lets the combo take the rest of the width,
        // so a narrow tool window shrinks the combo instead of clipping/overlapping it.
        JPanel bar = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 8, 0, 8);
        JBLabel label = new JBLabel("Connection:");
        label.setForeground(SECONDARY_TEXT);
        bar.add(label, gbc);

        gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 0, 4, 8);
        bar.add(connectionCombo, gbc);

        bar.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(5, 0, 5, 0)));
        return bar;
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
        if (selectedConfig() == null && !configs.isEmpty()) {
            setConnection(configs.get(0));
        } else if (configs.isEmpty()) {
            contextLabel.setText("Add a connection in the DB Explorer, then pick it here.");
        }
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
        JPanel south = new JPanel(new BorderLayout());

        contextLabel.setBorder(JBUI.Borders.empty(4, 12, 0, 12));
        contextLabel.setForeground(SECONDARY_TEXT);
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
        contextLabel.setText("Asking about: " + config.name + " (" + config.describe() + ")");
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
            appendMessage(bubble("Intella DB AI",
                    errorBody("Add a connection in the DB Explorer, then pick it in the selector above."), false), false);
            return;
        }
        setConnection(current);
        input.setText("");
        sendButton.setEnabled(false);
        appendMessage(bubble("You", body(question, 56), true), true);
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
                AiAssistant.trim(history, 10),
                question);
        String baseUrl = settings.baseUrl();
        String model = settings.model();
        double temperature = settings.temperature();
        int maxTokens = settings.maxTokens();
        boolean localProvider = settings.presetId().equals("ollama") || settings.presetId().equals("lmstudio");

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
                        ? aiError.getMessage()
                        : "AI request failed: " + cause.getMessage();
                appendMessage(bubble("Intella DB AI", errorBody(message), false), false);
            } else {
                String sql = AiAssistant.firstSqlBlock(answer);
                JPanel answerRow = assistantAnswerRow(answer, sql, current);
                appendMessage(answerRow, false);
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
        thinkingRow = bubble("Intella DB AI", body, false);
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
        return bubble("Intella DB AI (" + settingsLabel() + ")", stack, false);
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
                    ConsolePanel console = explorer.openConsole(forConfig);
                    if (console != null) {
                        console.setSql(sql);
                    }
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

    private @NotNull JPanel bubble(@NotNull String title, @NotNull JComponent body, boolean user) {
        JPanel panel = new BubblePanel(user);
        JBLabel header = new JBLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize2D() - 1f));
        header.setForeground(SECONDARY_TEXT);
        header.setBorder(JBUI.Borders.emptyBottom(4));
        panel.add(header, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Rounded, antialiased bubble. Fills its own background in paintComponent so the
     * corners stay transparent instead of showing a square fill behind the rounded border.
     */
    private static final class BubblePanel extends JPanel {
        private final Color fill;
        private final Color outline;

        BubblePanel(boolean user) {
            super(new BorderLayout());
            this.fill = user ? USER_BUBBLE : AI_BUBBLE;
            this.outline = user ? USER_BUBBLE_BORDER : BUBBLE_BORDER;
            setOpaque(false);
            setBorder(JBUI.Borders.empty(8, 12, 9, 12));
        }

        @Override
        protected void paintComponent(@NotNull Graphics g) {
            Graphics2D gr = (Graphics2D) g.create();
            try {
                gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth() - 1;
                int h = getHeight() - 1;
                gr.setColor(fill);
                gr.fillRoundRect(0, 0, w, h, 14, 14);
                gr.setColor(outline);
                gr.drawRoundRect(0, 0, w, h, 14, 14);
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
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(false);
        area.setBackground(SQL_BLOCK);
        area.setBorder(JBUI.Borders.empty(4, 6));
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
        scroller.setBorder(BorderFactory.createLineBorder(BUBBLE_BORDER));
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

    // ------------------------------------------------------------------ colors
    // Dark-theme values are chosen against the 2026.x dark panel (#2B2D30): the assistant
    // bubble must sit clearly above it, and the user bubble gets an accent-blue tint.

    private static final com.intellij.ui.JBColor USER_BUBBLE =
            com.intellij.ui.JBColor.namedColor("IntellaDb.userBubble",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xDCEBFB) : new Color(0x2F4B72));
    private static final com.intellij.ui.JBColor USER_BUBBLE_BORDER =
            com.intellij.ui.JBColor.namedColor("IntellaDb.userBubbleBorder",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xAECBEF) : new Color(0x476B99));
    private static final com.intellij.ui.JBColor AI_BUBBLE =
            com.intellij.ui.JBColor.namedColor("IntellaDb.aiBubble",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xFFFFFF) : new Color(0x3C4048));
    private static final com.intellij.ui.JBColor BUBBLE_BORDER =
            com.intellij.ui.JBColor.namedColor("IntellaDb.border",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xD5D9DF) : new Color(0x565A63));
    private static final com.intellij.ui.JBColor SECONDARY_TEXT =
            com.intellij.ui.JBColor.namedColor("IntellaDb.secondaryText",
                    com.intellij.ui.JBColor.isBright() ? new Color(0x5E6470) : new Color(0xA6ACB8));
    private static final com.intellij.ui.JBColor SQL_BLOCK =
            com.intellij.ui.JBColor.namedColor("IntellaDb.sqlBlock",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xF2F4F7) : new Color(0x1E1F22));
    private static final com.intellij.ui.JBColor ERROR_TEXT =
            com.intellij.ui.JBColor.namedColor("IntellaDb.errorText",
                    com.intellij.ui.JBColor.isBright() ? new Color(0xCC3D4C) : new Color(0xF2687A));

    @Override
    public void dispose() {
        disposed = true;
    }
}
