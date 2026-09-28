package community.intelladb.sql;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;

/** Renders the gray {@code column_name =} hint before a VALUES value. */
final class ColumnHintRenderer implements EditorCustomElementRenderer {

    private final String text;

    ColumnHintRenderer(@NotNull String columnName) {
        this.text = columnName + " =";
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        Editor editor = inlay.getEditor();
        Font font = font(editor);
        return editor.getContentComponent().getFontMetrics(font).stringWidth(text)
                + spaceWidth(editor);
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle targetRange,
                      @NotNull TextAttributes attributes) {
        Editor editor = inlay.getEditor();
        Font font = font(editor);
        Font original = g.getFont();
        Color originalColor = g.getColor();
        g.setFont(font);
        g.setColor(hintColor(editor));
        int baseline = targetRange.y + targetRange.height
                - (targetRange.height - g.getFontMetrics(font).getAscent()) / 2 - 1;
        g.drawString(text, targetRange.x, baseline);
        g.setFont(original);
        g.setColor(originalColor);
    }

    private static int spaceWidth(@NotNull Editor editor) {
        return editor.getContentComponent().getFontMetrics(font(editor)).stringWidth(" ");
    }

    private static @NotNull Font font(@NotNull Editor editor) {
        Font base = editor.getColorsScheme().getFont(com.intellij.openapi.editor.colors.EditorFontType.ITALIC);
        float smaller = Math.max(9f, base.getSize2D() - 1.5f);
        return base.deriveFont(Font.ITALIC, smaller);
    }

    private static @NotNull Color hintColor(@NotNull Editor editor) {
        return com.intellij.ui.JBColor.namedColor("IntellaDb.columnHint",
                com.intellij.ui.JBColor.isBright() ? new Color(0x787878) : new Color(0x8C8C8C));
    }
}
