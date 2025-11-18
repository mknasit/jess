package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class ModernJavaFeaturesTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    
    @Test
    @DisplayName("Optional with map and orElse")
    void optional_operations() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/modern_new/OptionalTest.java"));
    }
    
    @Test
    @DisplayName("CompletableFuture with thenApply")
    void completable_future() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/modern_new/CompletableFutureTest.java"));
    }
    
    @Test
    @DisplayName("Generic interface with extends")
    void generic_interface_extends() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/modern_new/GenericExtends.java"));
    }
    
    @Test
    @DisplayName("Multiple interface implementation")
    void multiple_interfaces() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/modern_new/MultipleInterfaces.java"));
    }
}

