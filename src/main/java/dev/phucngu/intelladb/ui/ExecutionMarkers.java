package dev.phucngu.intelladb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import dev.phucngu.intelladb.connection.SqlResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Per-console execution feedback in the editor, like IntelliJ's consoles: a green ✓ (or
 * red ✗) in the gutter on the first line of each executed statement, and its timing
 * ("606 ms") rendered after the statement's last line. Marks move with edits and are
 * replaced when the same statement runs again.
 */
final class ExecutionMarkers {

    /** Start offset is tracked by {@code anchor}; gutter icons and inlays exist once per open editor. */
    private record Mark(@NotNull RangeMarker anchor, @NotNull List<RangeHighlighter> gutters,
                        @NotNull List<Inlay<?>> inlays) {
    }

    private final Project project;
    private final Document document;
    private final List<Mark> marks = new ArrayList<>();

    ExecutionMarkers(@NotNull Project project, @NotNull Document document) {
        this.project = project;
        this.document = document;
    }

    void mark(int start, int end, @NotNull SqlResult result) {
        if (end > document.getTextLength()) {
            return; // text changed while the statement ran
        }
        clearRange(start, end);
        String label = result.isSuccessful() ? result.durationMs + " ms" : "failed";
        List<RangeHighlighter> gutters = new ArrayList<>();
        List<Inlay<?>> inlays = new ArrayList<>();
        for (Editor editor : EditorFactory.getInstance().getEditors(document, project)) {
            RangeHighlighter gutter = editor.getMarkupModel().addRangeHighlighter(start, start,
                    HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE);
            gutter.setGutterIconRenderer(new StatusIcon(result));
            gutters.add(gutter);
            Inlay<?> inlay = editor.getInlayModel().addAfterLineEndElement(end, true,
                    new TimingRenderer(label, !result.isSuccessful()));
            if (inlay != null) {
                inlays.add(inlay);
            }
        }
        marks.add(new Mark(document.createRangeMarker(start, start), gutters, inlays));
    }

    /** Drops marks of statements starting inside [start, end] — they are about to be re-marked. */
    private void clearRange(int start, int end) {
        for (Iterator<Mark> it = marks.iterator(); it.hasNext(); ) {
            Mark mark = it.next();
            RangeMarker anchor = mark.anchor();
            if (!anchor.isValid() || (anchor.getStartOffset() >= start && anchor.getStartOffset() <= end)) {
                dispose(mark);
                it.remove();
            }
        }
    }

    void clearAll() {
        marks.forEach(ExecutionMarkers::dispose);
        marks.clear();
    }

    private static void dispose(@NotNull Mark mark) {
        mark.anchor().dispose();
        mark.gutters().forEach(RangeHighlighter::dispose);
        mark.inlays().forEach(inlay -> {
            if (inlay.isValid()) {
                inlay.dispose();
            }
        });
    }

    private static final class StatusIcon extends GutterIconRenderer {
        private final boolean ok;
        private final String tooltip;

        StatusIcon(@NotNull SqlResult result) {
            this.ok = result.isSuccessful();
            this.tooltip = switch (result.kind) {
                case ROWS -> result.rows.size() + " row(s) retrieved in " + result.durationMs + " ms";
                case UPDATE_COUNT -> result.updateCount + " row(s) affected in " + result.durationMs + " ms";
                case MESSAGE -> "Completed in " + result.durationMs + " ms";
                case ERROR -> result.text;
            };
        }

        @Override
        public @NotNull Icon getIcon() {
            return ok ? AllIcons.General.InspectionsOK : AllIcons.General.Error;
        }

        @Override
        public @Nullable String getTooltipText() {
            return tooltip;
        }

        @Override
        public @NotNull Alignment getAlignment() {
            return Alignment.LEFT;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StatusIcon other && other.ok == ok && Objects.equals(other.tooltip, tooltip);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ok, tooltip);
        }
    }

    /** Grey (or error-red) text after the line end, in the editor's own font. */
    private static final class TimingRenderer implements EditorCustomElementRenderer {
        private static final int GAP = 12;
        private final String text;
        private final boolean error;

        TimingRenderer(@NotNull String text, boolean error) {
            this.text = text;
            this.error = error;
        }

        private static @NotNull FontMetrics metrics(@NotNull Inlay<?> inlay) {
            Editor editor = inlay.getEditor();
            return editor.getContentComponent().getFontMetrics(
                    editor.getColorsScheme().getFont(EditorFontType.ITALIC));
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            return metrics(inlay).stringWidth(text) + JBUI.scale(GAP);
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle region,
                          @NotNull TextAttributes attributes) {
            Editor editor = inlay.getEditor();
            Color color;
            if (error) {
                color = JBUI.CurrentTheme.Label.errorForeground();
            } else {
                TextAttributes hint = editor.getColorsScheme()
                        .getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND);
                color = hint != null && hint.getForegroundColor() != null ? hint.getForegroundColor() : JBColor.GRAY;
            }
            g.setColor(color);
            g.setFont(editor.getColorsScheme().getFont(EditorFontType.ITALIC));
            g.drawString(text, region.x + JBUI.scale(GAP), region.y + editor.getAscent());
        }
    }
}
