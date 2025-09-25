package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class InterfaceSuperCallTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }

    @Test
    @DisplayName("Qualified default call: I1.super.m() inside another interface default")
    void qualifiedInterfaceSuper() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/interface_super/Use.java"));
    }
}
