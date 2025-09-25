package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class ArrayCornerTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Method call on element of multidim array")
    void multi_dim_element_call() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/arrays_new/Array1.java"));
    }

    @Test
    @DisplayName("Class literals for arrays/primitives")
    void class_literals_arrays_primitives() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/arrays_new/ClassLit.java"));
    }

}
