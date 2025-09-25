package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class AdvancedGenericsTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Wildcard bounds in params/returns")
    void wildcard_capture() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/generics_adv_new/Bounds1.java"));
    }

    @Test
    @DisplayName("Generic method with bounded type param")
    void method_type_params() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/generics_adv_new/MethodTP1.java"));
    }

}
