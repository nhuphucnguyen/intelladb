package dev.phucngu.intelladb;

import dev.phucngu.intelladb.connection.DbConfig;
import dev.phucngu.intelladb.connection.PostgresDialect;
import dev.phucngu.intelladb.schema.ColumnMeta;
import dev.phucngu.intelladb.schema.DdlGenerator;
import dev.phucngu.intelladb.schema.IdentifierQuoting;
import dev.phucngu.intelladb.schema.SchemaCatalog;
import dev.phucngu.intelladb.schema.TableMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlGeneratorTest {

    @Test
    void generatesCreateTableWithPrimaryKey() {
        ColumnMeta id = new ColumnMeta("id", "int4", false, "nextval('t_id_seq'::regclass)", 1, true, "");
        ColumnMeta name = new ColumnMeta("name", "text", false, "", 2, false, "customer name");
        TableMeta table = new TableMeta("customers", TableMeta.Kind.TABLE, List.of(id, name), "");
        String ddl = DdlGenerator.generate(new SchemaCatalog(List.of(new SchemaCatalog.Schema("public", List.of(table)))));

        assertTrue(ddl.contains("CREATE TABLE public.customers ("));
        assertTrue(ddl.contains("id int4 NOT NULL DEFAULT nextval('t_id_seq'::regclass) PRIMARY KEY,"));
        assertTrue(ddl.contains("name text NOT NULL"));
        assertTrue(ddl.contains("-- customer name"));
        assertTrue(ddl.contains(");"));
        assertFalse(ddl.contains("CREATE VIEW"));
    }

    @Test
    void viewsUseCreateView() {
        TableMeta view = new TableMeta("big_orders", TableMeta.Kind.VIEW, List.of(), "");
        String ddl = DdlGenerator.generate(new SchemaCatalog(List.of(new SchemaCatalog.Schema("public", List.of(view)))));
        assertTrue(ddl.contains("CREATE VIEW public.big_orders ("));
    }

    @Test
    void plainIdentifiersNotQuoted() {
        assertTrue(IdentifierQuoting.quote("customers").equals("customers"));
        assertEquals("\"order-items\"", IdentifierQuoting.quote("order-items"));
        assertEquals("\"CamelCase\"", IdentifierQuoting.quote("CamelCase"));
    }

    @Test
    void jdbcUrlBuilding() {
        DbConfig config = new DbConfig();
        config.host = "db.example.com";
        config.port = 5433;
        config.database = "shop";
        assertEquals("jdbc:postgresql://db.example.com:5433/shop",
                new PostgresDialect().jdbcUrl(config));
        config.sslMode = true;
        assertEquals("jdbc:postgresql://db.example.com:5433/shop?sslmode=require",
                new PostgresDialect().jdbcUrl(config));
        config.jdbcUrlOverride = "jdbc:postgresql://other/db?opt=1";
        assertEquals("jdbc:postgresql://other/db?opt=1", new PostgresDialect().jdbcUrl(config));
    }
}
