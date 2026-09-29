package dev.phucngu.intelladb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Driver-specific classes stay behind the dialect: only Postgres*.java may touch pgjdbc,
 * only MySql*.java the MariaDB driver and only Mongo*.java the MongoDB driver (and BSON),
 * so the rest of the plugin stays database-neutral.
 */
class DriverImportGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void onlyPostgresFilesReferencePgjdbc() throws IOException {
        assertEquals(List.of(), offenders("org.postgresql", "Postgres"));
    }

    @Test
    void onlyMySqlFilesReferenceMariaDbDriver() throws IOException {
        assertEquals(List.of(), offenders("org.mariadb", "MySql"));
    }

    @Test
    void onlyMongoFilesReferenceTheMongoDbDriver() throws IOException {
        assertEquals(List.of(), offenders("com.mongodb", "Mongo"));
        assertEquals(List.of(), offenders("org.bson", "Mongo"));
    }

    private static List<String> offenders(String packagePrefix, String filePrefix) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().startsWith(filePrefix))
                    .filter(p -> mentions(p, packagePrefix))
                    .map(p -> MAIN.relativize(p).toString())
                    .sorted()
                    .toList();
        }
    }

    private static boolean mentions(Path file, String text) {
        try {
            return Files.readString(file).contains(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
