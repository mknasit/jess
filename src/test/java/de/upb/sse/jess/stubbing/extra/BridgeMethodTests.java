package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class BridgeMethodTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Generic override that implies bridge method")
    void bridge_method_needed() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/bridge_new/Bridge1.java"));
    }

}
