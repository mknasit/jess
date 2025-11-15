package de.upb.sse.jess.tool;

import de.upb.sse.jess.api.PublicApi;
import de.upb.sse.jess.configuration.JessConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
//    /** Project directory path */
    private static final String PROJECT_DIR = "/Users/mitul/Documents/study/Thesis/partial compilation/jess/src/test/resources/projects/lwjgl3";

//    /** Source root directories (comma-separated or as list) */
    private static final List<String> SOURCE_ROOTS = Arrays.asList("modules/lwjgl/core/src/main/java");

    /** Classpath JAR files (empty list if none) */
    private static final List<String> CLASSPATH_JARS = Arrays.asList();
    
    /** Maximum number of methods to process (-1 for unlimited, or set to 100, 1000, etc.) */
    private static final int MAX_METHODS = 1000;
    
    // ============================================================================
    
    @Test
    @DisplayName("Process repository methods one by one")
    void testProcessRepository() {
        LocalDateTime testStartTime = LocalDateTime.now();
        String testName = "testProcessRepository";
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        
        // Create tee streams to capture output while still displaying it
        PrintStream teeOut = new PrintStream(new TeeOutputStream(originalOut, capturedOut));
        PrintStream teeErr = new PrintStream(new TeeOutputStream(originalErr, capturedErr));
        
        try {
            // Redirect output to capture logs
            System.setOut(teeOut);
            System.setErr(teeErr);
            
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
            
            // Write comprehensive test log
            writeTestLog(testName, testStartTime, result, capturedOut, capturedErr, null);
            
            // Assertions
            assertTrue(result.totalMethods > 0, "Should have processed at least one method");
            if (MAX_METHODS > 0) {
                assertTrue(result.totalMethods <= MAX_METHODS, "Should not exceed method limit");
            }
        } catch (Throwable t) {
            // Write log even if test fails
            writeTestLog(testName, testStartTime, null, capturedOut, capturedErr, t);
            throw t;
        } finally {
            // Restore original streams
            System.setOut(originalOut);
            System.setErr(originalErr);
            teeOut.close();
            teeErr.close();
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
     * Write comprehensive test log with diagnostic information for understanding failures
     * and improving the Jess tool.
     */
    private static void writeTestLog(String testName, LocalDateTime testStartTime, 
                                     RepositoryProcessor.ProcessingResult result,
                                     ByteArrayOutputStream capturedOut, 
                                     ByteArrayOutputStream capturedErr,
                                     Throwable exception) {
        try {
            // Create logs directory
            Path logsDir = Paths.get("src/test/java/de/upb/sse/jess/tool/logs");
            Files.createDirectories(logsDir);
            
            // Generate unique filename
            String timestamp = testStartTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String status = (exception != null) ? "FAILURE" : (result != null && result.failedCompilations > 0 ? "PARTIAL" : "SUCCESS");
            String fileName = String.format("%s_%s_%s.log", testName, timestamp, status);
            Path logFile = logsDir.resolve(fileName);
            
            StringBuilder content = new StringBuilder();
            content.append("=".repeat(100)).append("\n");
            content.append("TEST EXECUTION LOG - JESS TOOL DIAGNOSTICS\n");
            content.append("=".repeat(100)).append("\n");
            content.append("Test Name: ").append(testName).append("\n");
            content.append("Start Time: ").append(testStartTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            content.append("End Time: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            content.append("Duration: ").append(java.time.Duration.between(testStartTime, LocalDateTime.now()).toMillis()).append(" ms\n");
            content.append("Status: ").append(status).append("\n");
            content.append("=".repeat(100)).append("\n\n");
            
            // Test configuration
            content.append("TEST CONFIGURATION:\n");
            content.append("-".repeat(100)).append("\n");
            content.append("Project Directory: ").append(PROJECT_DIR).append("\n");
            content.append("Source Roots: ").append(SOURCE_ROOTS).append("\n");
            content.append("Classpath Jars: ").append(CLASSPATH_JARS).append("\n");
            content.append("Max Methods: ").append(MAX_METHODS > 0 ? MAX_METHODS : "Unlimited").append("\n");
            content.append("\n");
            
            // Exception information if test failed
            if (exception != null) {
                content.append("TEST EXCEPTION:\n");
                content.append("-".repeat(100)).append("\n");
                content.append("Exception Type: ").append(exception.getClass().getName()).append("\n");
                content.append("Exception Message: ").append(exception.getMessage()).append("\n");
                content.append("\nSTACK TRACE:\n");
                content.append("-".repeat(100)).append("\n");
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                exception.printStackTrace(pw);
                content.append(sw.toString());
                content.append("\n");
            }
            
            // Processing results and statistics
            if (result != null) {
                content.append("PROCESSING RESULTS:\n");
                content.append("=".repeat(100)).append("\n");
                content.append("Total Methods Processed: ").append(result.totalMethods).append("\n");
                content.append("Methods Found (before limit): ").append(result.methodsFound).append("\n");
                content.append("Methods Compiled Successfully: ").append(result.methodsCompiledSuccessfully).append("\n");
                content.append("Compilation Success Rate: ").append(String.format("%.2f%%", result.getCompilationSuccessRate())).append("\n");
                content.append("Methods with Accessible Bytecode: ").append(result.methodsWithAccessibleBytecode).append("\n");
                content.append("Bytecode Access Rate: ").append(String.format("%.2f%%", result.getBytecodeAccessRate())).append("\n");
                content.append("Failed Compilations: ").append(result.failedCompilations).append("\n");
                content.append("\n");
                
                // Status breakdown with diagnostic information
                content.append("STATUS BREAKDOWN (What Failed and Why):\n");
                content.append("-".repeat(100)).append("\n");
                result.statusCounts.entrySet().stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .forEach(entry -> {
                            if (entry.getValue() > 0) {
                                content.append(String.format("  %-30s: %d methods\n", entry.getKey(), entry.getValue()));
                                // Add diagnostic information for each status
                                String diagnostic = getStatusDiagnostic(entry.getKey());
                                if (diagnostic != null) {
                                    content.append("    → ").append(diagnostic).append("\n");
                                }
                            }
                        });
                content.append("\n");
                
                // Failure analysis - what needs to be fixed
                if (result.failedCompilations > 0) {
                    content.append("FAILURE ANALYSIS - What Needs to be Fixed in Jess:\n");
                    content.append("=".repeat(100)).append("\n");
                    
                    // Analyze failure patterns
                    List<RepositoryProcessor.MethodResult> failures = result.methodResults.stream()
                            .filter(mr -> mr.result.status != PublicApi.Status.OK 
                                    && mr.result.status != PublicApi.Status.TARGET_METHOD_NOT_EMITTED)
                            .collect(Collectors.toList());
                    
                    // Group by status and analyze patterns
                    Map<PublicApi.Status, List<RepositoryProcessor.MethodResult>> failuresByStatus = 
                            failures.stream().collect(Collectors.groupingBy(mr -> mr.result.status));
                    
                    for (Map.Entry<PublicApi.Status, List<RepositoryProcessor.MethodResult>> entry : failuresByStatus.entrySet()) {
                        PublicApi.Status statuskey = entry.getKey();
                        List<RepositoryProcessor.MethodResult> statusFailures = entry.getValue();
                        
                        content.append("\n").append(statuskey.toString()).append(" (").append(statusFailures.size()).append(" failures):\n");
                        content.append("-".repeat(100)).append("\n");
                        content.append("  Root Cause: ").append(getStatusRootCause(statuskey)).append("\n");
                        content.append("  What to Fix: ").append(getStatusFixRecommendation(statuskey)).append("\n");
                        content.append("\n  Sample Failures (first 10):\n");
                        
                        statusFailures.stream().limit(10).forEach(mr -> {
                            content.append("    • ").append(mr.binaryClassName).append(".").append(mr.methodName).append("\n");
                            content.append("      Source: ").append(mr.sourceFile).append("\n");
                            if (mr.result.notes != null && !mr.result.notes.isEmpty()) {
                                content.append("      Error: ").append(mr.result.notes).append("\n");
                            }
                            if (mr.result.elapsedMs > 0) {
                                content.append("      Time: ").append(mr.result.elapsedMs).append(" ms\n");
                            }
                            content.append("\n");
                        });
                        
                        if (statusFailures.size() > 10) {
                            content.append("    ... and ").append(statusFailures.size() - 10).append(" more failures of this type\n");
                        }
                    }
                    
                    // Common patterns in failures
                    content.append("\nCOMMON FAILURE PATTERNS:\n");
                    content.append("-".repeat(100)).append("\n");
                    analyzeFailurePatterns(failures, content);
                }
                
                // Methods not emitted analysis
                if (result.notEmittedReasons != null && !result.notEmittedReasons.isEmpty()) {
                    content.append("\nMETHODS NOT EMITTED ANALYSIS:\n");
                    content.append("-".repeat(100)).append("\n");
                    content.append("These methods compiled successfully but the target method wasn't emitted:\n\n");
                    result.notEmittedReasons.entrySet().stream()
                            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                            .forEach(entry -> {
                                double percentage = result.methodsCompiledSuccessfully > 0
                                        ? (double) entry.getValue() / result.methodsCompiledSuccessfully * 100.0
                                        : 0.0;
                                content.append(String.format("  %-50s: %d (%.1f%%)\n", 
                                        entry.getKey(), entry.getValue(), percentage));
                            });
                    content.append("\n");
                }
            }
            
            // Captured console output
            if (capturedOut != null && capturedOut.size() > 0) {
                content.append("\n").append("=".repeat(100)).append("\n");
                content.append("CAPTURED SYSTEM.OUT:\n");
                content.append("=".repeat(100)).append("\n");
                content.append(capturedOut.toString("UTF-8"));
                content.append("\n");
            }
            
            if (capturedErr != null && capturedErr.size() > 0) {
                content.append("\n").append("=".repeat(100)).append("\n");
                content.append("CAPTURED SYSTEM.ERR:\n");
                content.append("=".repeat(100)).append("\n");
                content.append(capturedErr.toString("UTF-8"));
                content.append("\n");
            }
            
            // Write to file
            Files.writeString(logFile, content.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            // Print log location (to original stream)
            PrintStream originalOut = System.out;
            originalOut.println("\n✓ Comprehensive test log written to: " + logFile.toAbsolutePath());
            
        } catch (Exception e) {
            System.err.println("Failed to write test log: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get diagnostic information for a status.
     */
    private static String getStatusDiagnostic(PublicApi.Status status) {
        switch (status) {
            case OK:
                return "Success - method compiled and target method has bytecode";
            case FAILED_PARSE:
                return "JavaParser failed to parse the source file - syntax errors or unsupported features";
            case FAILED_RESOLVE:
                return "Symbol resolution failed - ambiguous references or missing type information";
            case FAILED_COMPILE:
                return "Java compiler failed - type errors, missing dependencies, or compilation errors";
            case MISSING_DEP:
                return "Missing dependency - required class/interface not found in classpath";
            case TIMEOUT:
                return "Processing timed out - took longer than configured timeout";
            case INTERNAL_ERROR:
                return "Internal error in Jess - unexpected exception during processing";
            case TARGET_METHOD_NOT_EMITTED:
                return "Compiled successfully but target method not emitted (sliced away or abstract/native)";
            default:
                return null;
        }
    }
    
    /**
     * Get root cause explanation for a status.
     */
    private static String getStatusRootCause(PublicApi.Status status) {
        switch (status) {
            case FAILED_PARSE:
                return "JavaParser cannot parse the source code (syntax errors, unsupported Java features, or malformed code)";
            case FAILED_RESOLVE:
                return "Symbol resolver cannot resolve types, methods, or fields (ambiguous references, incomplete type information)";
            case FAILED_COMPILE:
                return "Java compiler reports errors (type mismatches, missing classes, incorrect method signatures, or dependency issues)";
            case MISSING_DEP:
                return "Required dependency is missing from classpath (external library, framework class, or generated code)";
            case TIMEOUT:
                return "Processing exceeded timeout limit (complex code, large dependencies, or infinite loops in resolution)";
            case INTERNAL_ERROR:
                return "Unexpected exception in Jess tool (bug in Jess, unhandled edge case, or resource exhaustion)";
            default:
                return "Unknown failure reason";
        }
    }
    
    /**
     * Get fix recommendation for a status.
     */
    private static String getStatusFixRecommendation(PublicApi.Status status) {
        switch (status) {
            case FAILED_PARSE:
                return "Update JavaParser version, add syntax error handling, or support newer Java features";
            case FAILED_RESOLVE:
                return "Improve symbol resolution, handle ambiguous references better, or enhance type inference";
            case FAILED_COMPILE:
                return "Improve stub generation, add missing dependencies to classpath, or fix type resolution issues";
            case MISSING_DEP:
                return "Enhance dependency resolution, improve stub generation for missing classes, or add dependency detection";
            case TIMEOUT:
                return "Optimize processing, add caching, or increase timeout for complex cases";
            case INTERNAL_ERROR:
                return "Fix bugs in Jess, add error handling, or improve resource management";
            default:
                return "Investigate and fix the root cause";
        }
    }
    
    /**
     * Analyze failure patterns to identify common issues.
     */
    private static void analyzeFailurePatterns(List<RepositoryProcessor.MethodResult> failures, StringBuilder content) {
        // Pattern 1: Builder pattern methods
        long builderMethods = failures.stream()
                .filter(mr -> mr.methodName.contains("builder") || mr.methodName.equals("get") || 
                             mr.binaryClassName.contains("$Builder"))
                .count();
        if (builderMethods > 0) {
            content.append("  • Builder pattern methods: ").append(builderMethods)
                   .append(" failures\n");
            content.append("    → Issue: Builder pattern not properly handled in stub generation\n");
            content.append("    → Fix: Improve builder pattern detection and stub generation\n");
        }
        
        // Pattern 2: Getter methods
        long getterMethods = failures.stream()
                .filter(mr -> mr.methodName.startsWith("get") && !mr.methodName.equals("get"))
                .count();
        if (getterMethods > 0) {
            content.append("  • Getter methods: ").append(getterMethods).append(" failures\n");
            content.append("    → Issue: Getter methods may have complex return types or dependencies\n");
        }
        
        // Pattern 3: toString methods
        long toStringMethods = failures.stream()
                .filter(mr -> mr.methodName.equals("toString"))
                .count();
        if (toStringMethods > 0) {
            content.append("  • toString() methods: ").append(toStringMethods).append(" failures\n");
            content.append("    → Issue: toString() may reference fields or methods not properly stubbed\n");
        }
        
        // Pattern 4: Stream/Functional interface methods
        long streamMethods = failures.stream()
                .filter(mr -> mr.binaryClassName.contains("function") || 
                            mr.binaryClassName.contains("Stream") ||
                            mr.binaryClassName.contains("Predicate") ||
                            mr.binaryClassName.contains("Consumer"))
                .count();
        if (streamMethods > 0) {
            content.append("  • Stream/Functional interface methods: ").append(streamMethods).append(" failures\n");
            content.append("    → Issue: Functional interfaces and Stream API not properly handled\n");
            content.append("    → Fix: Improve shim generation for functional interfaces\n");
        }
        
        // Pattern 5: Inner classes
        long innerClasses = failures.stream()
                .filter(mr -> mr.binaryClassName.contains("$"))
                .count();
        if (innerClasses > 0) {
            content.append("  • Inner class methods: ").append(innerClasses).append(" failures\n");
            content.append("    → Issue: Inner classes may have access issues or missing outer class context\n");
        }
        
        // Pattern 6: Methods with stubs
        long methodsWithStubs = failures.stream()
                .filter(mr -> mr.result.usedStubs)
                .count();
        if (methodsWithStubs > 0) {
            content.append("  • Methods using stubs: ").append(methodsWithStubs).append(" failures\n");
            content.append("    → Issue: Generated stubs may be incomplete or incorrect\n");
            content.append("    → Fix: Improve stub generation quality and completeness\n");
        }
    }
    
    /**
     * Helper class to tee output to multiple streams.
     */
    private static class TeeOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream out1;
        private final java.io.OutputStream out2;
        
        public TeeOutputStream(java.io.OutputStream out1, java.io.OutputStream out2) {
            this.out1 = out1;
            this.out2 = out2;
        }
        
        @Override
        public void write(int b) throws IOException {
            out1.write(b);
            out2.write(b);
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out1.write(b, off, len);
            out2.write(b, off, len);
        }
        
        @Override
        public void flush() throws IOException {
            out1.flush();
            out2.flush();
        }
        
        @Override
        public void close() throws IOException {
            out1.flush();
            out2.flush();
            // Don't close the original streams
        }
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
        String fileName =  "failed_compilations_" + timestamp + ".txt";
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
