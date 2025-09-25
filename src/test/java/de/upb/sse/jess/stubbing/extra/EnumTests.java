package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class EnumTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Enum utilities: values() & valueOf(String)")
    void enum_values_valueOf() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/enums_new/Enum1.java"));
    }

    @Test
    @DisplayName("Enum static initializer needs helper method")
    void enum_helper_in_initializer() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/enums_new/Enum2.java"));
    }

}
