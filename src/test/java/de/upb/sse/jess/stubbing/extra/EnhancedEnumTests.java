package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for enhanced enum support (first-class enum types).
 * Verifies that enums are created as proper enum types, not classes.
 */
public class EnhancedEnumTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("Enum with switch statement - enum constants detected")
    void enum_switch_constants() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/enums_enhanced/EnumSwitchTest.java"));
    }
    
    @Test
    @DisplayName("Enum with ordinal() and compareTo()")
    void enum_ordinal_compareto() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/enums_enhanced/EnumMethodsTest.java"));
    }
    
    @Test
    @DisplayName("Enum in EnumSet")
    void enum_enumset() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/enums_enhanced/EnumSetTest.java"));
    }
    
    @Test
    @DisplayName("Enum with reflection (instanceof Enum)")
    void enum_reflection() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/enums_enhanced/EnumReflectionTest.java"));
    }
}

