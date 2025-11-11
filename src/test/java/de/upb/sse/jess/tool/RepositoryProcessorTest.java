package de.upb.sse.jess.tool;

import de.upb.sse.jess.api.PublicApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
//    {
//        "name": "Protocol Buffer",
//            "projectDir": "/Users/mitul/Documents/study/Thesis/partial compilation/JessTesting/src/test/resources/projects/protobuf",
//            "sourceRoots": ["java/core/src/main/java/com/google/protobuf"],
//        "classpathJars": []
//    }
    /** Project directory path */
    private static final String PROJECT_DIR = "/Users/mitul/Documents/study/Thesis/partial compilation/JessTesting/src/test/resources/projects/protobuf";
    
    /** Source root directories (comma-separated or as list) */
    private static final List<String> SOURCE_ROOTS = Arrays.asList("java/core/src/main/java/");
    
    /** Classpath JAR files (empty list if none) */
    private static final List<String> CLASSPATH_JARS = Arrays.asList();
    
    /** Maximum number of methods to process (-1 for unlimited, or set to 100, 1000, etc.) */
    private static final int MAX_METHODS = 1000;
    
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
        
        // Record failed compilation cases to a text file
        recordFailedCompilations(result);
        
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
    
    /**
     * Record all failed compilation cases to a text file for analysis.
     */
    private static void recordFailedCompilations(RepositoryProcessor.ProcessingResult result) {
        // Filter failed compilations (status != OK && status != TARGET_METHOD_NOT_EMITTED)
        List<RepositoryProcessor.MethodResult> failedMethods = result.methodResults.stream()
                .filter(mr -> {
                    de.upb.sse.jess.api.PublicApi.Status status = mr.result.status;
                    return status != de.upb.sse.jess.api.PublicApi.Status.OK 
                            && status != de.upb.sse.jess.api.PublicApi.Status.TARGET_METHOD_NOT_EMITTED;
                })
                .collect(Collectors.toList());
        
        if (failedMethods.isEmpty()) {
            System.out.println("\n✓ No failed compilations to record.");
            return;
        }
        
        // Create output file path in the logs directory
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = "failed_compilations_" + timestamp + ".txt";
        Path logsDir = Paths.get("/Users/mitul/Documents/study/Thesis/partial compilation/jess/src/test/resources/logs");
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            System.err.println("Failed to create logs directory: " + e.getMessage());
        }
        Path outputFile = logsDir.resolve(fileName);
        
        try {
            StringBuilder content = new StringBuilder();
            content.append("=".repeat(100)).append("\n");
            content.append("FAILED COMPILATION CASES\n");
            content.append("=".repeat(100)).append("\n");
            content.append("Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            content.append("Project Directory: ").append(PROJECT_DIR).append("\n");
            content.append("Total Failed Compilations: ").append(failedMethods.size()).append("\n");
            content.append("Total Methods Processed: ").append(result.totalMethods).append("\n");
            content.append("=".repeat(100)).append("\n\n");
            
            // Analyze failure patterns
            long builderMethods = failedMethods.stream()
                    .filter(mr -> mr.methodName.contains("builder") || mr.methodName.equals("get") || 
                                 mr.methodName.contains("checkOrigin") || mr.binaryClassName.contains("$Builder"))
                    .count();
            long getterMethods = failedMethods.stream()
                    .filter(mr -> mr.methodName.startsWith("get") && !mr.methodName.equals("get"))
                    .count();
            long toStringMethods = failedMethods.stream()
                    .filter(mr -> mr.methodName.equals("toString"))
                    .count();
            long streamMethods = failedMethods.stream()
                    .filter(mr -> mr.binaryClassName.contains("function") || mr.binaryClassName.contains("Stream"))
                    .count();
            
            content.append("FAILURE PATTERN ANALYSIS:\n");
            content.append("-".repeat(100)).append("\n");
            content.append("  Builder pattern methods (builder, get, checkOrigin): ").append(builderMethods).append("\n");
            content.append("  Getter methods (get*): ").append(getterMethods).append("\n");
            content.append("  toString() methods: ").append(toStringMethods).append("\n");
            content.append("  Stream/Functional interface methods: ").append(streamMethods).append("\n");
            content.append("\n");
            content.append("NOTE: Current error messages only show compiler exit codes.\n");
            content.append("      To diagnose issues better, we need to capture actual compiler error messages.\n");
            content.append("      Consider enhancing CompilerInvoker to capture stderr output.\n");
            content.append("\n");
            
            // Group by status for summary
            java.util.Map<de.upb.sse.jess.api.PublicApi.Status, Long> statusCounts = failedMethods.stream()
                    .collect(Collectors.groupingBy(mr -> mr.result.status, Collectors.counting()));
            
            content.append("FAILURE STATUS SUMMARY:\n");
            content.append("-".repeat(100)).append("\n");
            statusCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(entry -> {
                        content.append(String.format("  %-30s: %d cases (%.1f%%)\n", 
                                entry.getKey(), 
                                entry.getValue(),
                                (double) entry.getValue() / failedMethods.size() * 100.0));
                    });
            content.append("\n");
            
            // Detailed list of failures
            content.append("DETAILED FAILURE LIST:\n");
            content.append("=".repeat(100)).append("\n\n");
            
            for (int i = 0; i < failedMethods.size(); i++) {
                RepositoryProcessor.MethodResult mr = failedMethods.get(i);
                content.append(String.format("FAILURE #%d\n", i + 1));
                content.append("-".repeat(100)).append("\n");
                content.append("Status: ").append(mr.result.status).append("\n");
                content.append("Class: ").append(mr.binaryClassName).append("\n");
                content.append("Method: ").append(mr.methodName).append("\n");
                content.append("Descriptor: ").append(mr.jvmDescriptor).append("\n");
                content.append("Source File: ").append(mr.sourceFile).append("\n");
                
                if (mr.result.notes != null && !mr.result.notes.isEmpty()) {
                    content.append("Notes: ").append(mr.result.notes).append("\n");
                }
                
                if (mr.result.elapsedMs > 0) {
                    content.append("Elapsed Time: ").append(mr.result.elapsedMs).append(" ms\n");
                }
                
                if (mr.result.depsResolved != null && !mr.result.depsResolved.isEmpty()) {
                    content.append("Dependencies Resolved: ").append(mr.result.depsResolved).append("\n");
                }
                
                content.append("Used Stubs: ").append(mr.result.usedStubs ? "Yes" : "No").append("\n");
                content.append("\n");
            }
            
            // Write to file
            Files.writeString(outputFile, content.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            System.out.println("\n✓ Recorded " + failedMethods.size() + " failed compilation cases to:");
            System.out.println("  " + outputFile.toAbsolutePath());
            
        } catch (IOException e) {
            System.err.println("Failed to write failed compilations file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
