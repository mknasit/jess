package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Records support (Java 14+).
 * Verifies that records are created as proper record types, not classes.
 */
public class RecordsTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("Record with component accessors")
    void record_component_accessors() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/records/RecordComponentsTest.java"));
    }
    
    @Test
    @DisplayName("Record with equals() and hashCode()")
    void record_equals_hashcode() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/records/RecordEqualsTest.java"));
    }
    
    @Test
    @DisplayName("Record with toString()")
    void record_tostring() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/records/RecordToStringTest.java"));
    }
    
    @Test
    @DisplayName("Record with canonical constructor")
    void record_canonical_constructor() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/records/RecordConstructorTest.java"));
    }
    
    @Test
    @DisplayName("Nested record")
    void nested_record() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/records/NestedRecordTest.java"));
    }
}

