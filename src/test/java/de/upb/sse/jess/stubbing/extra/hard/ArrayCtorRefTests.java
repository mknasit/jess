package de.upb.sse.jess.stubbing.extra.hard;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayCtorRefTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }

    @Test
    @DisplayName("Array ctor reference String[]::new assigned to generic FI")
    void arrayCtorRef() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/array_ctor_ref/Use.java"));
    }
}
