package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for shim generation process.
 * Verifies that shims are generated correctly for common library types
 * across different ecosystems (SLF4J, Commons Lang, Android, LWJGL, etc.).
 * 
 * This test suite validates the entire shim generation mechanism, not just
 * specific library implementations.
 */
public class ShimGenerationTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("SLF4J Logger shim generation")
    void slf4j_logger_shim() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/SLF4JShimTest.java"));
    }
    
    @Test
    @DisplayName("Apache Commons Lang shim generation")
    void commons_lang_shim() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/CommonsLangShimTest.java"));
    }
    
    @Test
    @DisplayName("Android core shim generation")
    void android_core_shim() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/AndroidCoreShimTest.java"));
    }
    
    @Test
    @DisplayName("LWJGL OpenGL shim generation")
    void lwjgl_opengl_shim() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/LWJGLShimTest.java"));
    }
    
    @Test
    @DisplayName("Shim return type inference")
    void shim_return_types() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/ShimReturnTypesTest.java"));
    }
    
    @Test
    @DisplayName("Shim method parameter inference")
    void shim_parameter_types() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/ShimParameterTypesTest.java"));
    }
    
    @Test
    @DisplayName("Nested type shim generation")
    void nested_type_shims() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/NestedTypeShimTest.java"));
    }
    
    @Test
    @DisplayName("Generic type shim generation")
    void generic_type_shims() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/shim_common/GenericTypeShimTest.java"));
    }
}

