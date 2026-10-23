package dev.phucngu.intelladb.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ConnectionManagerCredentialsTest {

    @Test
    void eachConnectionHasItsOwnKeychainEntry() {
        // The macOS keychain overwrites by service name only: a shared one kept a single password.
        assertNotEquals(ConnectionManager.attributes("a").getServiceName(),
                ConnectionManager.attributes("b").getServiceName());
        assertEquals("a", ConnectionManager.attributes("a").getUserName());
    }
}
