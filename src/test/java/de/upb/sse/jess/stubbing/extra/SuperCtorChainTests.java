package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class SuperCtorChainTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Subclass ctor requires parent no-arg ctor")
    void implicit_super_noarg() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/super_new/Super1.java"));
    }

}
