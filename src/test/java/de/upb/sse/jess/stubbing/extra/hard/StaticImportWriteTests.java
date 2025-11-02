package de.upb.sse.jess.stubbing.extra.hard;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class StaticImportWriteTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }

    @Test
    @DisplayName("Static imported field write must be stubbed as mutable public static")
    void staticFieldWrite() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/static_import_write/Use.java"));
    }
}
