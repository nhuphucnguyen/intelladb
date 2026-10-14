package dev.phucngu.intelladb.ui;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/** Marker colors for connections — the tree's colored dot and the console tab's tint. */
final class ConnectionColors {

    static final List<String> PALETTE = List.of(
            "F23C2E", "F97A19", "F2C128", "59B349", "2FB5D8", "4477F2", "9B51E0", "E5569D");

    private ConnectionColors() {
    }

    static @Nullable Color decode(@Nullable String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(hex.strip(), 16));
        } catch (NumberFormatException wrongFormat) {
            return null;
        }
    }

    /** A filled swatch for the Set Color menu. */
    static @NotNull Icon swatch(@NotNull String hex) {
        Color color = decode(hex);
        if (color == null) {
            color = Color.GRAY;
        }
        Color fill = color;
        return new Icon() {
            @Override
            public int getIconWidth() {
                return JBUI.scale(12);
            }

            @Override
            public int getIconHeight() {
                return JBUI.scale(12);
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(fill);
                    int inset = JBUI.scale(1);
                    g2.fillOval(x + inset, y + inset, getIconWidth() - 2 * inset, getIconHeight() - 2 * inset);
                } finally {
                    g2.dispose();
                }
            }
        };
    }
}
