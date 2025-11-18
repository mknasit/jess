package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Priority 1 Critical Fixes:
 * 1. Multiple interface implementation (3+ interfaces, missing methods)
 * 2. Complex builder pattern (AbstractBuilder<T>, checkOrigin, setters)
 * 3. Nested generics (Mono<ResponseEntity<Map<String, List<T>>>>)
 * 4. gRPC/Protocol Buffers shims
 */
public class Priority1CriticalFixesTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("Multiple interfaces: class implements 3 interfaces with missing methods")
    void multiple_interfaces_three() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/MultipleInterfacesThree.java"));
    }
    

    @DisplayName("Complex builder: AbstractBuilder<T> with checkOrigin and setters")
    void complex_builder_abstract() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/ComplexBuilderTest.java"));
    }
    
    @Test
    @DisplayName("Nested generics: Mono<ResponseEntity<Map<String, List<T>>>>")
    void nested_generics_complex() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/NestedGenericsTest.java"));
    }
    
    @Test
    @DisplayName("gRPC: MetadataGrpc class with newStub method")
    void grpc_metadata_grpc() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/GrpcTest.java"));
    }
    
    @Test
    @DisplayName("Protocol Buffers: GeneratedMessageLite usage")
    void protobuf_generated_message() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/ProtobufTest.java"));
    }
}

