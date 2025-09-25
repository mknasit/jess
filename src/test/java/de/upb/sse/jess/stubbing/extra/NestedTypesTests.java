package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class NestedTypesTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Member class owner resolution (Outer.Inner)")
    void member_class_owner() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/nested_new/MemberOwner1.java"));
    }

    @Test
    @DisplayName("Anonymous class method call")
    void anonymous_class_call() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/nested_new/Anon1.java"));
    }

}
