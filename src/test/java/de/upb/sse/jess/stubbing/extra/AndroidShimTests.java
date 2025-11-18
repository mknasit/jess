package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Android ecosystem shims (android.*, androidx.*).
 * Verifies that Android types are properly stubbed.
 */
public class AndroidShimTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("Android Context and Activity shims")
    void android_context_activity() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/android_shims/AndroidContextTest.java"));
    }
    
    @Test
    @DisplayName("Android View and ViewGroup shims")
    void android_view_widgets() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/android_shims/AndroidViewTest.java"));
    }
    
    @Test
    @DisplayName("Android Bundle and Intent shims")
    void android_bundle_intent() {
        int result = jess.parse("src/test/resources/stubbing/extra/android_shims/AndroidBundleTest.java");
        if (result != 0) {
            // Print detailed error information for debugging
            System.err.println("Compilation failed with exit code: " + result);
            // Note: lastCompilationErrors is private, but errors are printed during compilation
        }
        assertEquals(0, result);
    }
    
    @Test
    @DisplayName("AndroidX AppCompatActivity shim")
    void androidx_appcompat() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/android_shims/AndroidXActivityTest.java"));
    }

}

