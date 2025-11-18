package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LWJGL ecosystem shims (org.lwjgl.*).
 * Verifies that LWJGL types are properly stubbed.
 */
public class LWJGLShimTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("LWJGL OpenGL shims")
    void lwjgl_opengl() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/lwjgl_shims/LWJGLOpenGLTest.java"));
    }
    
    @Test
    @DisplayName("LWJGL GLFW shims")
    void lwjgl_glfw() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/lwjgl_shims/LWJGLGLFWTest.java"));
    }
    
    @Test
    @DisplayName("LWJGL Vulkan shims")
    void lwjgl_vulkan() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/lwjgl_shims/LWJGLVulkanTest.java"));
    }
    
    @Test
    @DisplayName("LWJGL OpenAL shims")
    void lwjgl_openal() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/lwjgl_shims/LWJGLOpenALTest.java"));
    }
    
    @Test
    @DisplayName("LWJGL System utilities")
    void lwjgl_system() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/lwjgl_shims/LWJGLSystemTest.java"));
    }
}

