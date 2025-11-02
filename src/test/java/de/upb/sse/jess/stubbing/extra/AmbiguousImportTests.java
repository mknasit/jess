package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AmbiguousImportTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Ambiguous simple via two star imports")
    void star_import_ambiguity() {
        assertEquals(0,  jess.parse("src/test/resources/stubbing/extra/imports_new/Ambiguity1.java"));
    }

    @Test
    @DisplayName("Lenient mode would pick first (documented) [still ok test]")
    void star_pick_first_lenient() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/imports_new/LenientNote.java"));
    }

}
