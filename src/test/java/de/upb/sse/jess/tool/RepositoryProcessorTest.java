package de.upb.sse.jess.tool;

import de.upb.sse.jess.api.PublicApi;
import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.finder.PackageFinder;
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
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Project directory path (REQUIRED) */
   //  private static final String PROJECT_DIR = "/Users/mitul/Documents/study/Thesis/partial compilation/jess/src/test/resources/project/im-server";
       private static final String PROJECT_DIR = "/Users/mitul/Documents/study/Thesis/partial compilation/jess/src/test/resources/project/server";

    /**
     * Source root directories (OPTIONAL - leave null to auto-detect like experiment script)
     *
     * IMPORTANT: Behavior depends on this value:
     * 
     * 1. If NULL or EMPTY:
     *    - Auto-detects source roots using PackageFinder (same as experiment)
     *    - Searches ENTIRE project directory for Java files
     *    - Processes methods from ALL Java files found (matching experiment behavior)
     * 
     * 2. If PROVIDED (e.g., Arrays.asList("src/main/java")):
     *    - Uses ONLY the provided source roots (no auto-detection)
     *    - Searches ONLY within the specified source root directories
     *    - Processes methods ONLY from files within those source roots
     *    - This is useful when you know the exact source root and want to restrict processing
     *
     * Examples:
     *   - Auto-detect: null or Arrays.asList() → searches entire project
     *   - Single root: Arrays.asList("src/main/java") → only processes files in src/main/java
     *   - Multi-module: Arrays.asList("modules/lwjgl/core/src/main/java") → only processes that module
     *   - Multiple roots: Arrays.asList("module1/src/main/java", "module2/src/main/java")
     */
    // For lwjgl3 with specific module:
   //  private static final List<String> SOURCE_ROOTS = Arrays.asList("src/main/java");
    // For auto-detection (same as experiment):
   private static final List<String> SOURCE_ROOTS = null;
    /** Classpath JAR files (empty list if none) */
    // Server project has 3 JAR files in target directories
//    private static final List<String> CLASSPATH_JARS = Arrays.asList(
//            "broker/target/moquette-broker-1.4.0.jar",
//            "common/target/common-1.4.0.jar",
//            "sdk/target/sdk-1.4.0.jar"
//    );

    private static final List<String> CLASSPATH_JARS = Arrays.asList();

    /** Maximum number of methods to process (-1 for unlimited, or set to 100, 1000, etc.) */
    private static final int MAX_METHODS = 100;

    /** Minimum lines of code threshold (default: 3, actual threshold: MINIMUM_LOC + 2 = 5 lines) */
    private static final int MINIMUM_LOC = 3;

    /**
     * Method selection mode:
     *   - RANDOM: Random selection with fixed seed (1234) - same methods each run (reproducible)
     *   - SEQUENTIAL: Select first N methods in order - same methods each run (deterministic)
     * 
     * Use RANDOM for:
     *   - Matching experiment setup (same as experiment script)
     *   - Testing with diverse method samples
     * 
     * Use SEQUENTIAL for:
     *   - Debugging specific methods (easier to predict which methods will be processed)
     *   - Consistent testing (same methods processed in same order every time)
     *   - Easier to track progress (methods 1, 2, 3, ... instead of random selection)
     */
    private static final RepositoryProcessor.SelectionMode SELECTION_MODE = RepositoryProcessor.SelectionMode.RANDOM;
    // Alternative: RepositoryProcessor.SelectionMode.RANDOM

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
            Path projectPath = Paths.get(PROJECT_DIR);
            if (!java.nio.file.Files.exists(projectPath)) {
                System.out.println("⚠ Project directory does not exist: " + PROJECT_DIR);
                System.out.println("Please update PROJECT_DIR in the test file.");
                return;
            }

            // Determine source roots (same logic as experiment script)
            List<String> sourceRoots = determineSourceRoots(projectPath);

            // Validate that we have source roots
            if (sourceRoots == null || sourceRoots.isEmpty()) {
                System.err.println("\n❌ ERROR: No source roots found!");
                System.err.println("Please either:");
                System.err.println("  1. Set SOURCE_ROOTS manually: Arrays.asList(\"src/main/java\")");
                System.err.println("  2. Ensure the project has Java files in standard locations");
                return;
            }

            // Determine if source roots were manually provided or auto-detected
            boolean manualSourceRoots = (SOURCE_ROOTS != null && !SOURCE_ROOTS.isEmpty());
            String sourceRootMode = manualSourceRoots ? "MANUAL (only files in source roots will be processed)" : "AUTO-DETECTED (entire project will be searched)";

            System.out.println("\n" + "=".repeat(80));
            System.out.println("REPOSITORY PROCESSOR TEST");
            System.out.println("=".repeat(80));
            System.out.println("Project Directory: " + PROJECT_DIR);
            System.out.println("Source Roots: " + sourceRoots);
            System.out.println("Source Root Mode: " + sourceRootMode);
            System.out.println("Classpath Jars: " + CLASSPATH_JARS);
            System.out.println("Minimum LOC: " + MINIMUM_LOC + " (actual threshold: " + (MINIMUM_LOC + 2) + " lines)");
            if (SELECTION_MODE == RepositoryProcessor.SelectionMode.RANDOM) {
                System.out.println("Selection Mode: RANDOM (seed=1234, same as experiment setup)");
            } else {
                System.out.println("Selection Mode: SEQUENTIAL (first N methods in order)");
            }
            if (MAX_METHODS > 0) {
                System.out.println("Method Limit: " + MAX_METHODS + " methods (" + 
                    (SELECTION_MODE == RepositoryProcessor.SelectionMode.RANDOM ? "randomly selected" : "sequentially selected") + ")");
            } else {
                System.out.println("Method Limit: Unlimited");
            }
            System.out.println("=".repeat(80));
            System.out.println();

            // Create processor with filtering threshold and selection mode
            RepositoryProcessor processor = new RepositoryProcessor(
                    PROJECT_DIR,
                    sourceRoots,
                    CLASSPATH_JARS,
                    MAX_METHODS,
                    MINIMUM_LOC,
                    SELECTION_MODE
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
        System.out.println("  Total Methods Found (before filtering): " + result.methodsFound);
        System.out.println("  Total Methods Processed: " + result.totalMethods);
        if (result.maxMethodsToProcess > 0) {
            System.out.println("  Method Limit: " + result.maxMethodsToProcess);
            if (result.totalMethods >= result.maxMethodsToProcess) {
                System.out.println("  ⚠ Limit reached - processing stopped at " + result.maxMethodsToProcess + " methods");
            }
        }
        System.out.println();

        // Filtering statistics
        if (result.methodsFound > 0) {
            int totalExcluded = result.excludedByNoRange + result.excludedByMinLines + result.excludedByAnonymous;
            System.out.println("FILTERING STATISTICS:");
            System.out.println("  Excluded (no range): " + result.excludedByNoRange);
            System.out.println("  Excluded (< " + (MINIMUM_LOC + 2) + " lines): " + result.excludedByMinLines);
            System.out.println("  Excluded (anonymous): " + result.excludedByAnonymous);
            System.out.println("  Total Excluded: " + totalExcluded);
            System.out.println("  Filtering Rate: " + String.format("%.2f%%", (double) totalExcluded / result.methodsFound * 100.0));
            System.out.println();
        }

        // Compilation statistics
        System.out.println("COMPILATION STATISTICS:");
        System.out.println("  Methods Compiled Successfully (Status = OK): " + result.methodsCompiledSuccessfully);
        System.out.println("  Compilation Success Rate: " + String.format("%.2f%%", result.getCompilationSuccessRate()));
        System.out.println("  Failed Compilations: " + result.failedCompilations);
        System.out.println();

        // Context vs slice-only statistics
        System.out.println("CONTEXT VS SLICE-ONLY STATISTICS:");
        System.out.println("  Methods Processed with Context: " + result.methodsWithContext);
        System.out.println("  Methods Processed with Slice Only: " + result.methodsSliceOnly);
        if (result.methodsWithContext > 0) {
            double contextCompilationRate = (double) result.methodsWithContextCompiled / result.methodsWithContext * 100.0;
            System.out.println("  Context Methods Compiled: " + result.methodsWithContextCompiled + 
                             " / " + result.methodsWithContext + " (" + String.format("%.2f%%", contextCompilationRate) + ")");
        }
        if (result.methodsSliceOnly > 0) {
            double sliceCompilationRate = (double) result.methodsSliceOnlyCompiled / result.methodsSliceOnly * 100.0;
            System.out.println("  Slice-Only Methods Compiled: " + result.methodsSliceOnlyCompiled + 
                             " / " + result.methodsSliceOnly + " (" + String.format("%.2f%%", sliceCompilationRate) + ")");
        }
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
            content.append("Selection Mode: ").append(SELECTION_MODE).append("\n");
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
                
                // Context vs slice-only statistics
                content.append("CONTEXT VS SLICE-ONLY STATISTICS:\n");
                content.append("-".repeat(100)).append("\n");
                content.append("Methods Processed with Context: ").append(result.methodsWithContext).append("\n");
                content.append("Methods Processed with Slice Only: ").append(result.methodsSliceOnly).append("\n");
                if (result.methodsWithContext > 0) {
                    double contextCompilationRate = (double) result.methodsWithContextCompiled / result.methodsWithContext * 100.0;
                    content.append("Context Methods Compiled: ").append(result.methodsWithContextCompiled)
                           .append(" / ").append(result.methodsWithContext)
                           .append(" (").append(String.format("%.2f%%", contextCompilationRate)).append(")\n");
                }
                if (result.methodsSliceOnly > 0) {
                    double sliceCompilationRate = (double) result.methodsSliceOnlyCompiled / result.methodsSliceOnly * 100.0;
                    content.append("Slice-Only Methods Compiled: ").append(result.methodsSliceOnlyCompiled)
                           .append(" / ").append(result.methodsSliceOnly)
                           .append(" (").append(String.format("%.2f%%", sliceCompilationRate)).append(")\n");
                }
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
     * Determine source roots using the same logic as the experiment script.
     *
     * Priority:
     * 1. If SOURCE_ROOTS is provided and not empty, use it
     * 2. Otherwise, auto-detect using PackageFinder (same as experiment)
     *
     * This matches exactly how RandomJessHandler.getSourceRootsWithOverride() works,
     * but simplified for the test (without CSV override for now).
     *
     * Note: PackageFinder returns absolute paths, but RepositoryProcessor expects
     * relative paths from the project directory, so we convert them.
     */
    private static List<String> determineSourceRoots(Path projectPath) {
        // Strategy 1: Use manually provided source roots if available
        if (SOURCE_ROOTS != null && !SOURCE_ROOTS.isEmpty()) {
            System.out.println("[SOURCE-ROOT] Using manually provided source roots: " + SOURCE_ROOTS);
            return SOURCE_ROOTS;
        }

        // Strategy 2: Auto-detect using PackageFinder (same as experiment script)
        System.out.println("[SOURCE-ROOT] Auto-detecting source roots using PackageFinder (same as experiment script)...");
        System.out.println("[SOURCE-ROOT] Project path: " + projectPath.toString());

        // Check if project has Java files first
        try {
            long javaFileCount = java.nio.file.Files.walk(projectPath, 999)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("package-info"))
                    .filter(p -> !p.toString().contains("module-info"))
                    .count();
            System.out.println("[SOURCE-ROOT] Found " + javaFileCount + " Java files in project");
        } catch (Exception e) {
            System.err.println("[SOURCE-ROOT] ERROR checking for Java files: " + e.getMessage());
        }

        Set<String> autoDetected = PackageFinder.findPackageRoots(projectPath.toString());

        if (autoDetected.isEmpty()) {
            System.err.println("[SOURCE-ROOT] WARNING: PackageFinder returned no source roots despite finding Java files.");
            System.err.println("[SOURCE-ROOT] Attempting fallback: searching for common source root patterns...");

            // Fallback: Try to find common source root patterns
            List<String> fallbackRoots = findSourceRootsByPattern(projectPath);
            if (!fallbackRoots.isEmpty()) {
                System.out.println("[SOURCE-ROOT] Fallback found " + fallbackRoots.size() + " source root(s): " + fallbackRoots);
                return fallbackRoots;
            }

            System.err.println("[SOURCE-ROOT] Fallback also failed. Please provide SOURCE_ROOTS manually.");
            System.err.println("[SOURCE-ROOT] Example: Arrays.asList(\"src/main/java\")");
            System.err.println("[SOURCE-ROOT] Or for lwjgl3: Arrays.asList(\"modules/lwjgl/core/src/main/java\")");
            return new ArrayList<>();
        }

        // Convert absolute paths to relative paths (RepositoryProcessor expects relative paths)
        List<String> relativeSourceRoots = new ArrayList<>();
        for (String absolutePath : autoDetected) {
            try {
                Path absolutePathObj = Paths.get(absolutePath);
                if (absolutePathObj.startsWith(projectPath)) {
                    // Convert to relative path
                    Path relativePath = projectPath.relativize(absolutePathObj);
                    relativeSourceRoots.add(relativePath.toString());
                } else {
                    // Path doesn't start with project path - might be a different format
                    // Try to extract just the relative part
                    String projectPathStr = projectPath.toString();
                    if (absolutePath.startsWith(projectPathStr)) {
                        String relative = absolutePath.substring(projectPathStr.length());
                        if (relative.startsWith("/") || relative.startsWith("\\")) {
                            relative = relative.substring(1);
                        }
                        relativeSourceRoots.add(relative);
                    } else {
                        System.err.println("[SOURCE-ROOT] WARNING: Source root path doesn't match project path:");
                        System.err.println("  Project: " + projectPathStr);
                        System.err.println("  Root: " + absolutePath);
                        // Use as-is and hope RepositoryProcessor can handle it
                        relativeSourceRoots.add(absolutePath);
                    }
                }
            } catch (Exception e) {
                System.err.println("[SOURCE-ROOT] ERROR converting path: " + absolutePath + " - " + e.getMessage());
                // Use as-is
                relativeSourceRoots.add(absolutePath);
            }
        }

        System.out.println("[SOURCE-ROOT] Auto-detected " + relativeSourceRoots.size() + " source root(s): " + relativeSourceRoots);

        return relativeSourceRoots;
    }

    /**
     * Fallback method to find source roots by searching for common patterns.
     * Used when PackageFinder fails to detect source roots.
     */
    private static List<String> findSourceRootsByPattern(Path projectPath) {
        List<String> foundRoots = new ArrayList<>();

        try {
            System.out.println("[SOURCE-ROOT] Searching for common source root patterns...");

            // Strategy 1: Direct pattern matching for known structures
            String[] directPatterns = {
                    "src/main/java",
                    "src",
                    "library/src",
                    "app/src/main/java"
            };

            for (String pattern : directPatterns) {
                Path candidate = projectPath.resolve(pattern);
                if (java.nio.file.Files.exists(candidate) && java.nio.file.Files.isDirectory(candidate)) {
                    try {
                        boolean hasJavaFiles = java.nio.file.Files.walk(candidate, 10)
                                .anyMatch(p -> p.toString().endsWith(".java") &&
                                        !p.toString().contains("package-info") &&
                                        !p.toString().contains("module-info"));

                        if (hasJavaFiles) {
                            Path relative = projectPath.relativize(candidate);
                            String relativeStr = relative.toString();
                            if (!foundRoots.contains(relativeStr)) {
                                foundRoots.add(relativeStr);
                                System.out.println("[SOURCE-ROOT] Found direct pattern: " + relativeStr);
                            }
                        }
                    } catch (Exception e) {
                        // Skip this pattern
                    }
                }
            }

            // Strategy 2: Search for modules/*/core/src/main/java pattern (for lwjgl3, camel, etc.)
            Path modulesDir = projectPath.resolve("modules");
            System.out.println("[SOURCE-ROOT] Checking for modules directory: " + modulesDir);
            System.out.println("[SOURCE-ROOT] Modules dir exists: " + java.nio.file.Files.exists(modulesDir));
            System.out.println("[SOURCE-ROOT] Modules dir is directory: " + (java.nio.file.Files.exists(modulesDir) ? java.nio.file.Files.isDirectory(modulesDir) : false));

            if (java.nio.file.Files.exists(modulesDir) && java.nio.file.Files.isDirectory(modulesDir)) {
                try {
                    System.out.println("[SOURCE-ROOT] Listing modules directory...");
                    java.nio.file.Files.list(modulesDir).forEach(modulePath -> {
                        try {
                            String moduleName = modulePath.getFileName().toString();
                            System.out.println("[SOURCE-ROOT] Checking module: " + moduleName);

                            if (java.nio.file.Files.isDirectory(modulePath)) {
                                // Try modules/X/core/src/main/java
                                Path corePath = modulePath.resolve("core/src/main/java");
                                System.out.println("[SOURCE-ROOT]   Checking core path: " + corePath);
                                System.out.println("[SOURCE-ROOT]   Core path exists: " + java.nio.file.Files.exists(corePath));

                                if (java.nio.file.Files.exists(corePath) && java.nio.file.Files.isDirectory(corePath)) {
                                    try {
                                        // Use depth 10 to find Java files in nested package structures
                                        boolean hasJavaFiles = java.nio.file.Files.walk(corePath, 10)
                                                .anyMatch(p -> p.toString().endsWith(".java") &&
                                                        !p.toString().contains("package-info") &&
                                                        !p.toString().contains("module-info"));

                                        System.out.println("[SOURCE-ROOT]   Core path has Java files: " + hasJavaFiles);

                                        // Also count how many Java files we found for debugging
                                        if (hasJavaFiles) {
                                            long javaFileCount = java.nio.file.Files.walk(corePath, 10)
                                                    .filter(p -> p.toString().endsWith(".java") &&
                                                            !p.toString().contains("package-info") &&
                                                            !p.toString().contains("module-info"))
                                                    .count();
                                            System.out.println("[SOURCE-ROOT]   Found " + javaFileCount + " Java files in core path");
                                        }

                                        if (hasJavaFiles) {
                                            Path relative = projectPath.relativize(corePath);
                                            String relativeStr = relative.toString();
                                            System.out.println("[SOURCE-ROOT]   Relative path: " + relativeStr);
                                            if (!foundRoots.contains(relativeStr)) {
                                                foundRoots.add(relativeStr);
                                                System.out.println("[SOURCE-ROOT] ✓ Found module core: " + relativeStr);
                                            }
                                        }
                                    } catch (Exception e) {
                                        System.err.println("[SOURCE-ROOT]   Error checking Java files in core path: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }

                                // Also try modules/X/src/main/java
                                Path srcPath = modulePath.resolve("src/main/java");
                                System.out.println("[SOURCE-ROOT]   Checking src path: " + srcPath);

                                if (java.nio.file.Files.exists(srcPath) && java.nio.file.Files.isDirectory(srcPath)) {
                                    try {
                                        // Use depth 10 to find Java files in nested package structures
                                        boolean hasJavaFiles = java.nio.file.Files.walk(srcPath, 10)
                                                .anyMatch(p -> p.toString().endsWith(".java") &&
                                                        !p.toString().contains("package-info") &&
                                                        !p.toString().contains("module-info"));

                                        if (hasJavaFiles) {
                                            Path relative = projectPath.relativize(srcPath);
                                            String relativeStr = relative.toString();
                                            if (!foundRoots.contains(relativeStr)) {
                                                foundRoots.add(relativeStr);
                                                System.out.println("[SOURCE-ROOT] ✓ Found module src: " + relativeStr);
                                            }
                                        }
                                    } catch (Exception e) {
                                        System.err.println("[SOURCE-ROOT]   Error checking Java files in src path: " + e.getMessage());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("[SOURCE-ROOT] Error processing module: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    System.err.println("[SOURCE-ROOT] Error listing modules directory: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.out.println("[SOURCE-ROOT] Modules directory not found or not a directory");
            }

            // Strategy 3: Walk directory tree looking for directories ending in "src/main/java"
            // This is more expensive but catches edge cases
            if (foundRoots.isEmpty()) {
                System.out.println("[SOURCE-ROOT] Walking directory tree for src/main/java patterns...");
                try {
                    java.nio.file.Files.walk(projectPath, 8)
                            .filter(p -> java.nio.file.Files.isDirectory(p))
                            .filter(p -> {
                                String pathStr = p.toString();
                                return pathStr.endsWith(File.separator + "src" + File.separator + "main" + File.separator + "java") ||
                                        pathStr.endsWith("/src/main/java") ||
                                        pathStr.endsWith("\\src\\main\\java");
                            })
                            .filter(p -> {
                                // Check if this directory contains Java files
                                try {
                                    return java.nio.file.Files.walk(p, 10)
                                            .anyMatch(jp -> jp.toString().endsWith(".java") &&
                                                    !jp.toString().contains("package-info") &&
                                                    !jp.toString().contains("module-info"));
                                } catch (Exception e) {
                                    return false;
                                }
                            })
                            .limit(10)  // Limit to avoid too many results
                            .forEach(p -> {
                                try {
                                    Path relative = projectPath.relativize(p);
                                    String relativeStr = relative.toString();
                                    if (!foundRoots.contains(relativeStr)) {
                                        foundRoots.add(relativeStr);
                                        System.out.println("[SOURCE-ROOT] Found via walk: " + relativeStr);
                                    }
                                } catch (Exception e) {
                                    // Skip
                                }
                            });
                } catch (Exception e) {
                    System.err.println("[SOURCE-ROOT] Error in directory walk: " + e.getMessage());
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            System.err.println("[SOURCE-ROOT] ERROR in fallback search: " + e.getMessage());
            e.printStackTrace();
        }

        // Remove duplicates and sort
        List<String> result = foundRoots.stream()
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (result.isEmpty()) {
            System.err.println("[SOURCE-ROOT] Fallback search found no source roots.");
        }

        return result;
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
