package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class StaticInitTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Static field initializer calls helper")
    void static_field_needs_helper() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/staticinit_new/StaticInit1.java"));
    }

}
