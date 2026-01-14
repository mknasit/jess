package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for critical fixes implemented in SpoonStubber:
 * 1. Generic type arguments preservation (Mono<T>, Optional<T>, etc.)
 * 2. Auto-implement interface methods
 * 3. Builder pattern support
 * 4. Field initialization (logger, etc.)
 * 5. Stream API interface methods
 * 6. Type conversion fixes
 * 7. Syntax error fixes
 */
public class CriticalFixesTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("Generic type arguments preserved: Mono<T>")
    void generic_type_args_mono() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/GenericMonoTest.java"));
    }
    
    @Test
    @DisplayName("Generic type arguments preserved: Optional<T>")
    void generic_type_args_optional() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/GenericOptionalTest.java"));
    }
    
    @Test
    @DisplayName("Auto-implement interface methods: class implementing interface")
    void auto_implement_interface_methods() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/InterfaceImplementationTest.java"));
    }
    
    @Test
    @DisplayName("Builder pattern: builder() method and Builder class")
    void builder_pattern() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/BuilderPatternTest.java"));
    }
    
    @Test
    @DisplayName("Field initialization: logger field")
    void field_initialization_logger() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/LoggerFieldTest.java"));
    }
    
    @Test
    @DisplayName("Stream API methods: BaseStream interface methods")
    void stream_api_methods() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/StreamApiMethodsTest.java"));
    }
    
    @Test
    @DisplayName("Type conversion: Unknown type in binary operations")
    void type_conversion_binary_ops() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/TypeConversionTest.java"));
    }
    
    @Test
    @DisplayName("Nested type canonicalization: Outer.Inner -> Outer$Inner (prevents package/type clash)")
    void nested_type_canonicalization() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/NestedTypeCanonicalizationTest.java"));
    }
}

