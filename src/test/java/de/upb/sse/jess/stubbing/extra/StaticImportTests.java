package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class StaticImportTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Static import of method used bare")
    void static_import_method() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/staticimports_new/StaticMethod1.java"));
    }

    @Test
    @DisplayName("Static import of field used bare")
    void static_import_field() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/staticimports_new/StaticField1.java"));
    }

}
