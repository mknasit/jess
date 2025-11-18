package de.upb.sse.jess.stubbing.extra;

import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for shim generator - verifies that common library shims are generated.
 */
public class ShimGeneratorTests {
    private static Jess jess;
    
    @BeforeEach 
    void setup() { 
        jess = new Jess(); 
    }
    
    @Test
    @DisplayName("SLF4J Logger shim")
    void slf4j_logger_shim() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/SLF4JLoggerTest.java"));
    }
    
    @Test
    @DisplayName("Apache Commons Lang StringUtils shim")
    void commons_lang_shim() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/CommonsLangTest.java"));
    }
    
    @Test
    @DisplayName("ANTLR Parser shim")
    void antlr_parser_shim() {
        assertEquals(0, jess.parse("src/test/resources/stubbing/extra/critical_fixes/ANTLRParserTest.java"));
    }
}

