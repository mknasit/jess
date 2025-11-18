package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class StreamApiTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    
    @Test
    @DisplayName("Stream forEach with lambda")
    void stream_forEach() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/stream_new/StreamForEach.java"));
    }
    
    @Test
    @DisplayName("Stream map with method reference")
    void stream_map() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/stream_new/StreamMap.java"));
    }
    
    @Test
    @DisplayName("Stream filter and collect")
    void stream_filter() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/stream_new/StreamFilter.java"));
    }
    
    @Test
    @DisplayName("Collection forEach with lambda")
    void collection_forEach() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/stream_new/CollectionForEach.java"));
    }
}

