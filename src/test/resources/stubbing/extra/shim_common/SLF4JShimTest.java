package fixtures.shim_common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SLF4JShimTest {
    @TargetMethod
    void useLogger(String message) {
        Logger logger = LoggerFactory.getLogger(SLF4JShimTest.class);
        logger.info(message);
        logger.debug("Debug: " + message);
        logger.warn("Warning: " + message);
        logger.error("Error: " + message);
    }
}

