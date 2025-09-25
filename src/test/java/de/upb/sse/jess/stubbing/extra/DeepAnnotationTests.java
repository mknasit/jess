package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class DeepAnnotationTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Missing annotation type on class")
    void missing_annotation_type() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/annotations_new/Anno1.java"));
    }

    @Test
    @DisplayName("Type-use annotation in generics")
    void type_use_annotation() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/annotations_new/Anno2.java"));
    }

}
