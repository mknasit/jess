package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import de.upb.sse.jess.exceptions.AmbiguityException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class LambdaAndMethodRefTests {
    private static Jess jess;
    @BeforeEach void setup() { jess = new Jess(); }
    @Test
    @DisplayName("Lambda assigned to unresolved functional interface")
    void lambda_unresolved_fi() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/lambdas_new/Lambda1.java"));
    }

    @Test
    @DisplayName("Constructor method reference Type::new")
    void method_ref_ctor() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/lambdas_new/MethodRefCtor.java"));
    }

}
