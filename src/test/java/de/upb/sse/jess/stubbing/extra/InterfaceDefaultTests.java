package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class InterfaceDefaultTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Default method call on interface")
    void default_method_call() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/interfaces_new/Default1.java"));
    }

}
