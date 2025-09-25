package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class MethodRefTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Static method reference Type::method")
    void method_ref_static() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/mref_new/StaticRef.java"));
    }

    @Test
    @DisplayName("Instance method reference obj::method")
    void method_ref_instance() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/mref_new/InstRef.java"));
    }

}
