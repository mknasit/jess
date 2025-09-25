package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class OverloadEdgeTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Varargs applicability should avoid gap")
    void varargs_applicable() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/overloads_new/Varargs1.java"));
    }

    @Test
    @DisplayName("Boxing/widening in overloads")
    void boxing_widening() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/overloads_new/Boxing1.java"));
    }

}
