package de.upb.sse.jess.tool;

import de.upb.sse.jess.api.PublicApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for RepositoryProcessor that processes entire repositories.
 * 
 * Simply modify the parameters below to test different repositories.
 */
public class RepositoryProcessorTest {
    
    // ============================================================================
    // CONFIGURATION - Modify these parameters as needed
    // ============================================================================
    
    /** Project directory path */
    private static final String PROJECT_DIR = "/Users/mitul/Documents/study/Thesis/partial compilation/JessTesting/src/test/resources/projects/commons-io";
    
    /** Source root directories (comma-separated or as list) */
    private static final List<String> SOURCE_ROOTS = Arrays.asList("src/main/java/");
    
    /** Classpath JAR files (empty list if none) */
    private static final List<String> CLASSPATH_JARS = Arrays.asList();
    
    /** Maximum number of methods to process (-1 for unlimited, or set to 100, 1000, etc.) */
    private static final int MAX_METHODS = -1;
    
    // ============================================================================
    
    @Test
    @DisplayName("Process repository methods one by one")
    void testProcessRepository() {
        // Check if the project directory exists
        if (!java.nio.file.Files.exists(Paths.get(PROJECT_DIR))) {
            System.out.println("⚠ Project directory does not exist: " + PROJECT_DIR);
            System.out.println("Please update PROJECT_DIR in the test file.");
            return;
        }
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("REPOSITORY PROCESSOR TEST");
        System.out.println("=".repeat(80));
        System.out.println("Project Directory: " + PROJECT_DIR);
        System.out.println("Source Roots: " + SOURCE_ROOTS);
        System.out.println("Classpath Jars: " + CLASSPATH_JARS);
        if (MAX_METHODS > 0) {
            System.out.println("Method Limit: " + MAX_METHODS);
        } else {
            System.out.println("Method Limit: Unlimited");
        }
        System.out.println("=".repeat(80));
        System.out.println();
        
        // Create processor
        RepositoryProcessor processor = new RepositoryProcessor(
                PROJECT_DIR, 
                SOURCE_ROOTS, 
                CLASSPATH_JARS, 
                MAX_METHODS
        );
        
        // Process repository (statistics are logged during processing)
        RepositoryProcessor.ProcessingResult result = processor.processRepository();
        
        // Print final statistics
        printResults(result);
        
        // Assertions
        assertTrue(result.totalMethods > 0, "Should have processed at least one method");
        if (MAX_METHODS > 0) {
            assertTrue(result.totalMethods <= MAX_METHODS, "Should not exceed method limit");
        }
    }
    
    private static void printResults(RepositoryProcessor.ProcessingResult result) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FINAL STATISTICS");
        System.out.println("=".repeat(80));
        
        // Overall statistics
        System.out.println("\nOVERALL STATISTICS:");
        System.out.println("  Total Methods Processed: " + result.totalMethods);
        if (result.maxMethodsToProcess > 0) {
            System.out.println("  Method Limit: " + result.maxMethodsToProcess);
            System.out.println("  Methods Found (before limit): " + result.methodsFound);
            if (result.totalMethods >= result.maxMethodsToProcess) {
                System.out.println("  ⚠ Limit reached - processing stopped at " + result.maxMethodsToProcess + " methods");
            }
        } else {
            System.out.println("  Methods Found: " + result.methodsFound);
        }
        System.out.println();
        
        // Compilation statistics
        System.out.println("COMPILATION STATISTICS:");
        System.out.println("  Methods Compiled Successfully (Status = OK): " + result.methodsCompiledSuccessfully);
        System.out.println("  Compilation Success Rate: " + String.format("%.2f%%", result.getCompilationSuccessRate()));
        System.out.println("  Failed Compilations: " + result.failedCompilations);
        System.out.println();
        
        // Bytecode access statistics
        System.out.println("BYTECODE ACCESS STATISTICS:");
        System.out.println("  Methods with Accessible Bytecode: " + result.methodsWithAccessibleBytecode);
        System.out.println("  Bytecode Access Rate (out of all methods): " + String.format("%.2f%%", result.getBytecodeAccessRate()));
        System.out.println("  Bytecode Access Rate (out of compiled methods): " + String.format("%.2f%%", result.getBytecodeAccessRateFromCompiled()));
        System.out.println();
        
        // Summary
        System.out.println("SUMMARY:");
        System.out.println("  ✓ " + result.methodsCompiledSuccessfully + " out of " + result.totalMethods + 
                " methods compiled successfully using Jess tool (" + String.format("%.2f%%", result.getCompilationSuccessRate()) + ")");
        System.out.println("  ✓ " + result.methodsWithAccessibleBytecode + " out of " + result.totalMethods + 
                " methods have accessible bytecode (" + String.format("%.2f%%", result.getBytecodeAccessRate()) + ")");
        System.out.println("  ✓ " + result.methodsWithAccessibleBytecode + " out of " + result.methodsCompiledSuccessfully + 
                " compiled methods have accessible bytecode (" + String.format("%.2f%%", result.getBytecodeAccessRateFromCompiled()) + ")");
        System.out.println();
        
        // Status breakdown
        System.out.println("STATUS BREAKDOWN:");
        result.statusCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(entry -> {
                    if (entry.getValue() > 0) {
                        System.out.println("  " + entry.getKey() + ": " + entry.getValue());
                    }
                });
        System.out.println();
        
        // Why methods weren't emitted (for TARGET_METHOD_NOT_EMITTED)
        if (result.notEmittedReasons != null && !result.notEmittedReasons.isEmpty()) {
            int totalNotEmitted = result.notEmittedReasons.values().stream()
                    .mapToInt(Integer::intValue).sum();
            System.out.println("WHY METHODS WEREN'T EMITTED (out of " + totalNotEmitted + " compiled but not emitted):");
            result.notEmittedReasons.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(entry -> {
                        double percentage = totalNotEmitted > 0 
                                ? (double) entry.getValue() / totalNotEmitted * 100.0 
                                : 0.0;
                        System.out.println(String.format("  %s: %d (%.1f%%)", 
                                entry.getKey(), entry.getValue(), percentage));
                    });
            System.out.println();
            System.out.println("EXPLANATION:");
            System.out.println("  • 'no class contained the method': Method was sliced away or not found in bytecode");
            System.out.println("  • 'present without Code': Method exists but has no Code body (abstract/native/optimized away)");
            System.out.println("  • These methods compiled successfully, but the target method wasn't emitted");
            System.out.println("    in the bytecode, so they don't have accessible bytecode.");
            System.out.println();
        }
        
        System.out.println("=".repeat(80));
    }
}
