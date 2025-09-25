package de.upb.sse.jess.stubbing.extra;


import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class RepeatableAnnotationTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }

    @Test
    @DisplayName("Repeatable annotations require both the repeatable and its container")
    void repeatableNeedsContainer() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/repeatable_ann/Use.java"));
    }
}
