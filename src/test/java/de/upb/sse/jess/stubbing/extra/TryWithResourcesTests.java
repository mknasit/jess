package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class TryWithResourcesTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Try-with-resources requires AutoCloseable.close()")
    void twr_requires_close() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/twr_new/TWR1.java"));
    }

}
