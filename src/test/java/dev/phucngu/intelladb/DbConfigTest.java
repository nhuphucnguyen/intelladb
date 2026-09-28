package dev.phucngu.intelladb;

import com.intellij.util.xmlb.XmlSerializer;
import dev.phucngu.intelladb.connection.DbConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbConfigTest {

    private static DbConfig sample() {
        DbConfig config = new DbConfig();
        config.name = "shop@db";
        config.urlOnly = true;
        config.jdbcUrlOverride = "jdbc:postgresql://db/shop";
        config.readOnly = true;
        config.autoCommit = false;
        config.timeZone = "Asia/Singapore";
        config.keepAlive = true;
        config.keepAliveSeconds = 30;
        config.startupScript = "SET statement_timeout = '5s';";
        config.sslMode = true;
        config.sslModeName = "verify-full";
        config.sslRootCert = "/certs/ca.pem";
        config.schemas.addAll(List.of("public", "inventory"));
        config.showSystemSchemas = true;
        config.driverProperties.put("ApplicationName", "intella");
        return config;
    }

    @Test
    void copyIsDeepAndComplete() {
        DbConfig config = sample();
        DbConfig copy = config.copy();

        assertEquals(config.id, copy.id);
        assertEquals("Asia/Singapore", copy.timeZone);
        assertEquals("verify-full", copy.sslModeName);
        assertTrue(copy.readOnly && copy.urlOnly && copy.keepAlive && !copy.autoCommit);
        assertEquals(List.of("public", "inventory"), copy.schemas);
        assertEquals(Map.of("ApplicationName", "intella"), copy.driverProperties);
        assertNotSame(config.schemas, copy.schemas);
        assertNotSame(config.driverProperties, copy.driverProperties);
    }

    @Test
    void survivesXmlPersistence() {
        DbConfig restored = XmlSerializer.deserialize(XmlSerializer.serialize(sample()), DbConfig.class);

        assertEquals(30, restored.keepAliveSeconds);
        assertEquals("SET statement_timeout = '5s';", restored.startupScript);
        assertEquals(List.of("public", "inventory"), restored.schemas);
        assertEquals(Map.of("ApplicationName", "intella"), restored.driverProperties);
    }
}
