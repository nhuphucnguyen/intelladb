package dev.phucngu.intelladb.connection;

/** How a database product arranges the containers above tables. */
public enum NamespaceModel {
    /** connection → database → schema (PostgreSQL): the connection is bound to one database. */
    DATABASES_AND_SCHEMAS,
    /**
     * connection → schema (MySQL): a "database" is what JDBC calls a catalog, and is shown
     * as a schema directly under the connection, like IntelliJ's database tools do.
     */
    SCHEMAS_ONLY
}
