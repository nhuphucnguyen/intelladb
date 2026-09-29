package dev.phucngu.intelladb;

import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;

/** Original icons for Intella DB (drawn for this plugin, no third-party assets). */
public final class IntellaDbIcons {
    public static final Icon TOOL_WINDOW = IconLoader.getIcon("/icons/dbToolWindow.svg", IntellaDbIcons.class);
    public static final Icon CONNECTION = IconLoader.getIcon("/icons/dbConnection.svg", IntellaDbIcons.class);
    public static final Icon CONNECTION_CONNECTED = IconLoader.getIcon("/icons/dbConnectionConnected.svg", IntellaDbIcons.class);
    public static final Icon DATABASE = IconLoader.getIcon("/icons/dbDatabase.svg", IntellaDbIcons.class);
    public static final Icon SCHEMA = IconLoader.getIcon("/icons/dbSchema.svg", IntellaDbIcons.class);
    public static final Icon TABLE = IconLoader.getIcon("/icons/dbTable.svg", IntellaDbIcons.class);
    public static final Icon VIEW = IconLoader.getIcon("/icons/dbView.svg", IntellaDbIcons.class);
    public static final Icon COLUMN = IconLoader.getIcon("/icons/dbColumn.svg", IntellaDbIcons.class);
    public static final Icon KEY = IconLoader.getIcon("/icons/dbKey.svg", IntellaDbIcons.class);
    public static final Icon FOREIGN_KEY = IconLoader.getIcon("/icons/dbForeignKey.svg", IntellaDbIcons.class);
    public static final Icon SEQUENCE = IconLoader.getIcon("/icons/dbSequence.svg", IntellaDbIcons.class);
    public static final Icon INDEX = IconLoader.getIcon("/icons/dbIndex.svg", IntellaDbIcons.class);
    public static final Icon SUBMIT = IconLoader.getIcon("/icons/dbSubmit.svg", IntellaDbIcons.class);
    /** Submit with changes waiting: the same arrow outlined in green. */
    public static final Icon SUBMIT_PENDING = IconLoader.getIcon("/icons/dbSubmitPending.svg", IntellaDbIcons.class);
    public static final Icon AI = IconLoader.getIcon("/icons/dbAi.svg", IntellaDbIcons.class);

    private IntellaDbIcons() {
    }
}
