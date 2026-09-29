package dev.phucngu.intelladb.connection;

/** The language a connection's console speaks, which picks its highlighting, splitting and completion. */
public enum ConsoleLanguage {
    SQL,
    /** mongosh-style commands: {@code db.pets.find({…})}. */
    MONGO_SHELL
}
