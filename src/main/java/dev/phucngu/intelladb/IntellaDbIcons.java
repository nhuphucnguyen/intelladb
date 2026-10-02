package dev.phucngu.intelladb;

import com.intellij.openapi.util.IconLoader;
import dev.phucngu.intelladb.connection.MariaDbDialect;
import dev.phucngu.intelladb.connection.MySqlDialect;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.mongo.MongoDialect;
import javax.swing.Icon;

/**
 * Icons for Intella DB. All are drawn for this plugin except the database product logos
 * (dbPostgres*, dbMysql*, dbMariadb*, dbMongodb*), which are the official PostgreSQL, MySQL, MariaDB
 * and MongoDB marks.
 */
public final class IntellaDbIcons {
    public static final Icon TOOL_WINDOW = IconLoader.getIcon("/icons/dbToolWindow.svg", IntellaDbIcons.class);
    public static final Icon CONNECTION = IconLoader.getIcon("/icons/dbConnection.svg", IntellaDbIcons.class);
    public static final Icon CONNECTION_CONNECTED = IconLoader.getIcon("/icons/dbConnectionConnected.svg", IntellaDbIcons.class);
    public static final Icon POSTGRES = IconLoader.getIcon("/icons/dbPostgres.svg", IntellaDbIcons.class);
    public static final Icon POSTGRES_CONNECTED = IconLoader.getIcon("/icons/dbPostgresConnected.svg", IntellaDbIcons.class);
    public static final Icon MYSQL = IconLoader.getIcon("/icons/dbMysql.svg", IntellaDbIcons.class);
    public static final Icon MYSQL_CONNECTED = IconLoader.getIcon("/icons/dbMysqlConnected.svg", IntellaDbIcons.class);
    public static final Icon MARIADB = IconLoader.getIcon("/icons/dbMariadb.svg", IntellaDbIcons.class);
    public static final Icon MARIADB_CONNECTED = IconLoader.getIcon("/icons/dbMariadbConnected.svg", IntellaDbIcons.class);
    public static final Icon MONGODB = IconLoader.getIcon("/icons/dbMongodb.svg", IntellaDbIcons.class);
    public static final Icon MONGODB_CONNECTED = IconLoader.getIcon("/icons/dbMongodbConnected.svg", IntellaDbIcons.class);
    public static final Icon DATABASE = IconLoader.getIcon("/icons/dbDatabase.svg", IntellaDbIcons.class);
    public static final Icon SCHEMA = IconLoader.getIcon("/icons/dbSchema.svg", IntellaDbIcons.class);
    public static final Icon TABLE = IconLoader.getIcon("/icons/dbTable.svg", IntellaDbIcons.class);
    public static final Icon VIEW = IconLoader.getIcon("/icons/dbView.svg", IntellaDbIcons.class);
    public static final Icon COLUMN = IconLoader.getIcon("/icons/dbColumn.svg", IntellaDbIcons.class);
    public static final Icon KEY = IconLoader.getIcon("/icons/dbKey.svg", IntellaDbIcons.class);
    /** A column that is (part of) its table's primary key: the column icon with a key beside it. */
    public static final Icon PRIMARY_KEY_COLUMN = IconLoader.getIcon("/icons/dbColumnKey.svg", IntellaDbIcons.class);
    public static final Icon FOREIGN_KEY = IconLoader.getIcon("/icons/dbForeignKey.svg", IntellaDbIcons.class);
    public static final Icon SEQUENCE = IconLoader.getIcon("/icons/dbSequence.svg", IntellaDbIcons.class);
    public static final Icon INDEX = IconLoader.getIcon("/icons/dbIndex.svg", IntellaDbIcons.class);
    public static final Icon SUBMIT = IconLoader.getIcon("/icons/dbSubmit.svg", IntellaDbIcons.class);
    /** Submit with changes waiting: the same arrow outlined in green. */
    public static final Icon SUBMIT_PENDING = IconLoader.getIcon("/icons/dbSubmitPending.svg", IntellaDbIcons.class);
    public static final Icon MAKE_GLOBAL = IconLoader.getIcon("/icons/dbMakeGlobal.svg", IntellaDbIcons.class);
    public static final Icon MAKE_PROJECT = IconLoader.getIcon("/icons/dbMakeProject.svg", IntellaDbIcons.class);
    public static final Icon AI = IconLoader.getIcon("/icons/dbAi.svg", IntellaDbIcons.class);

    /** The connection icon for a database product; unknown dialects get the generic cylinder. */
    public static Icon connection(String dialectId, boolean connected) {
        return switch (dialectId == null ? "" : dialectId) {
            case PostgresDialect.ID -> connected ? POSTGRES_CONNECTED : POSTGRES;
            case MySqlDialect.ID -> connected ? MYSQL_CONNECTED : MYSQL;
            case MariaDbDialect.ID -> connected ? MARIADB_CONNECTED : MARIADB;
            case MongoDialect.ID -> connected ? MONGODB_CONNECTED : MONGODB;
            default -> connected ? CONNECTION_CONNECTED : CONNECTION;
        };
    }

    private IntellaDbIcons() {
    }
}
