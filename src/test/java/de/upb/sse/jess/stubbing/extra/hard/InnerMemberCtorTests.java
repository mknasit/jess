package de.upb.sse.jess.stubbing.extra.hard;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class InnerMemberCtorTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }

    @Test
    @DisplayName("Member inner class: new Outer().new Inner(..) requires proper owner/nesting")
    void innerCtorNeedsOuter() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/inner_member/Use.java"));
    }
}
