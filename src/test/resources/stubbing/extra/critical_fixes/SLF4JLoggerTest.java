package fixtures.critical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SLF4JLoggerTest {
    private static final Logger logger = LoggerFactory.getLogger(SLF4JLoggerTest.class);
    
    @TargetMethod
    void logMessage(String message) {
        logger.info(message);
        logger.debug("Debug: " + message);
        logger.warn("Warning: " + message);
        logger.error("Error: " + message);
    }
}

