package de.upb.sse.jess.tool;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.types.ResolvedType;
import de.upb.sse.jess.Jess;
import de.upb.sse.jess.api.PublicApi;
import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.finder.PackageFinder;
import de.upb.sse.jess.util.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Utility class to process an entire repository and compile each method using Jess.
 */
public class RepositoryProcessor {

    private final String projectDir;
    private final List<String> sourceRoots;
    private final List<String> classpathJars;
    private final Set<String> packages;  // Source roots (absolute paths) - same as experiment
    private final Set<String> jars;  // Classpath jars - same as experiment
    private final JessConfiguration config;  // Jess configuration - same as experiment
    private final Path projectPath;
    private final int maxMethodsToProcess;  // -1 means unlimited
    private final int minimumLoc;  // Minimum lines of code (default: 3, actual threshold: minimumLoc + 2 = 5)
    private final Random random;  // Random number generator with fixed seed (same as experiment: 1234)

    // Statistics
    private final AtomicInteger totalMethods = new AtomicInteger(0);
    private final AtomicInteger methodsFound = new AtomicInteger(0);  // Total methods found (before filtering and limit)
    private final AtomicInteger methodsCompiledSuccessfully = new AtomicInteger(0);  // Status == OK
    private final AtomicInteger methodsWithAccessibleBytecode = new AtomicInteger(0);  // Status == OK && targetHasCode == true
    private final AtomicInteger successfulCompilations = new AtomicInteger(0);
    private final AtomicInteger failedCompilations = new AtomicInteger(0);
    private final Map<PublicApi.Status, AtomicInteger> statusCounts = new HashMap<>();
    private final Map<String, AtomicInteger> notEmittedReasons = new HashMap<>();  // Reasons why methods weren't emitted
    private final List<MethodResult> methodResults = new ArrayList<>();

    // Filtering statistics
    private final AtomicInteger excludedByNoRange = new AtomicInteger(0);
    private final AtomicInteger excludedByMinLines = new AtomicInteger(0);
    private final AtomicInteger excludedByAnonymous = new AtomicInteger(0);

    /**
     * Create a RepositoryProcessor that processes ALL methods (no limit, with random selection).
     * Uses default minimum LOC threshold of 3 (actual threshold: 5 lines).
     * Uses random selection with fixed seed (1234) to match experiment setup.
     */
    public RepositoryProcessor(String projectDir, List<String> sourceRoots, List<String> classpathJars) {
        this(projectDir, sourceRoots, classpathJars, -1, 3);  // -1 means unlimited, default MINIMUM_LOC = 3
    }

    /**
     * Create a RepositoryProcessor with method limit.
     * Uses default minimum LOC threshold of 3 (actual threshold: 5 lines).
     * Uses random selection with fixed seed (1234) to match experiment setup.
     */
    public RepositoryProcessor(String projectDir, List<String> sourceRoots, List<String> classpathJars, int maxMethodsToProcess) {
        this(projectDir, sourceRoots, classpathJars, maxMethodsToProcess, 3);  // Default MINIMUM_LOC = 3
    }

    /**
     * Create a RepositoryProcessor with method limit and minimum LOC threshold.
     * Uses random selection with fixed seed (1234) to match experiment setup exactly.
     *
     * @param projectDir Project directory path
     * @param sourceRoots List of source root directories
     * @param classpathJars List of classpath JAR files
     * @param maxMethodsToProcess Maximum number of methods to process (-1 for unlimited)
     * @param minimumLoc Minimum lines of code (actual threshold will be minimumLoc + 2)
     */
    public RepositoryProcessor(String projectDir, List<String> sourceRoots, List<String> classpathJars, int maxMethodsToProcess, int minimumLoc) {
        this.projectDir = projectDir;
        this.sourceRoots = sourceRoots;
        this.classpathJars = classpathJars;
        this.projectPath = Paths.get(projectDir);
        this.maxMethodsToProcess = maxMethodsToProcess;
        this.minimumLoc = minimumLoc;
        // Use same random seed as experiment setup for reproducibility
        this.random = new Random(1234);

        // Use PackageFinder to get source roots (same as experiment setup: RandomJessHandler.compileAll)
        // If sourceRoots is provided, use it; otherwise auto-detect using PackageFinder
        Set<String> packagesSet;
        if (sourceRoots != null && !sourceRoots.isEmpty()) {
            // Convert provided source roots to Set<String> (absolute paths)
            packagesSet = new HashSet<>();
        for (String sourceRoot : sourceRoots) {
            Path sourceRootPath = Paths.get(sourceRoot);
            if (sourceRootPath.isAbsolute()) {
                    packagesSet.add(sourceRoot);
            } else {
                // Resolve relative path against project directory
                Path absolutePath = projectPath.resolve(sourceRoot).normalize();
                    packagesSet.add(absolutePath.toString());
                }
            }
        } else {
            // Auto-detect using PackageFinder (same as experiment: PackageFinder.findPackageRoots(projectPath.toString()))
            packagesSet = PackageFinder.findPackageRoots(projectDir);
        }

        // Store packages and jars for creating new Jess instance per method (same as experiment)
        this.packages = packagesSet;
        this.jars = new HashSet<>(classpathJars);

        // Create Jess configuration (same as experiment: stubbingConfig)
        JessConfiguration jessConfig = new JessConfiguration();
        jessConfig.setExitOnCompilationFail(false);
        jessConfig.setExitOnParsingFail(false);
        jessConfig.setFailOnAmbiguity(false);
        jessConfig.setMinimalStubbing(false);
        jessConfig.setIncludeJdkStubs(false);
        this.config = jessConfig;

        // Initialize status counters
        for (PublicApi.Status status : PublicApi.Status.values()) {
            statusCounts.put(status, new AtomicInteger(0));
        }
    }

    /**
     * Process the entire repository and compile each method.
     *
     * @return ProcessingResult containing statistics and results
     */
    public ProcessingResult processRepository() {
        System.out.println("Starting repository processing...");
        System.out.println("Project Directory: " + projectDir);
        System.out.println("Source Roots: " + sourceRoots);
        System.out.println("Classpath Jars: " + classpathJars);
        System.out.println("Minimum LOC: " + minimumLoc + " (actual threshold: " + (minimumLoc + 2) + " lines)");
        System.out.println("Random Selection: Enabled (seed=1234, same as experiment setup)");
        if (maxMethodsToProcess > 0) {
            System.out.println("Method Limit: " + maxMethodsToProcess + " methods (randomly selected)");
        } else {
            System.out.println("Method Limit: Unlimited (all methods will be processed)");
        }
        System.out.println();

        // STEP 1: Find ALL Java files in entire project directory
        // IMPORTANT: Experiment setup ALWAYS searches entire project, NEVER restricts to source roots
        // Source roots are only used for compilation, NOT for finding files or methods
        System.out.println("Searching for Java files in entire project directory...");
        List<String> relevantJavaFiles = getAllRelevantJavaFiles(projectDir);
        System.out.println("Found " + relevantJavaFiles.size() + " relevant Java files (excluding test files)");
        System.out.println();

        // STEP 2: Collect all methods from these files
        // IMPORTANT: Experiment collects from ALL files, NO source root filtering during collection
        System.out.println("Collecting all methods from repository...");
        List<MethodToProcess> allMethods = new ArrayList<>();

        for (String javaFileStr : relevantJavaFiles) {
            Path javaFile = Paths.get(javaFileStr);
            try {
                collectMethodsFromFile(javaFile, allMethods);
            } catch (Exception e) {
                System.err.println("Error collecting methods from file " + javaFile + ": " + e.getMessage());
                // Continue processing other files
            }
        }

        System.out.println("Collected " + allMethods.size() + " methods (after filtering)");
        System.out.println();

        // STEP 3: Randomly select methods (same as experiment: getRandomClassMethodPairs)
        List<MethodToProcess> methodsToProcess = getRandomMethods(allMethods);

        System.out.println("Selected " + methodsToProcess.size() + " methods to process (random selection with seed 1234)");
        System.out.println();

        // STEP 4: Process selected methods (source roots are used here for compilation)
        for (MethodToProcess method : methodsToProcess) {
            try {
                processMethod(method);
            } catch (Exception e) {
                System.err.println("Error processing method " + method.binaryClassName + "." + method.methodName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Generate summary
        return generateResult();
    }

    /**
     * Internal class to hold method information before processing.
     * Matches experiment's ClassMethodPair structure.
     */
    private static class MethodToProcess {
        final Path javaFile;
        final String binaryClassName;
        final String methodName;
        final String jvmDescriptor;
        final String methodSignature;  // Full signature like "methodName(int, String)" - needed for preSlice()
        final boolean isClinit;  // Whether this is a static initializer

        MethodToProcess(Path javaFile, String binaryClassName, String methodName, String jvmDescriptor, String methodSignature, boolean isClinit) {
            this.javaFile = javaFile;
            this.binaryClassName = binaryClassName;
            this.methodName = methodName;
            this.jvmDescriptor = jvmDescriptor;
            this.methodSignature = methodSignature;
            this.isClinit = isClinit;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodToProcess that = (MethodToProcess) o;
            return Objects.equals(javaFile, that.javaFile) &&
                    Objects.equals(binaryClassName, that.binaryClassName) &&
                    Objects.equals(methodName, that.methodName) &&
                    Objects.equals(jvmDescriptor, that.jvmDescriptor) &&
                    Objects.equals(methodSignature, that.methodSignature) &&
                    isClinit == that.isClinit;
        }

        @Override
        public int hashCode() {
            return Objects.hash(javaFile, binaryClassName, methodName, jvmDescriptor, methodSignature, isClinit);
        }
    }

    /**
     * Collect all methods from a Java file (same logic as experiment's getAllClassMethodPairs).
     * Methods are filtered but not yet processed.
     */
    private void collectMethodsFromFile(Path javaFile, List<MethodToProcess> allMethods) throws IOException {
        // Parse the Java file
        ParserConfiguration parserConfig = new ParserConfiguration();
        JavaParser parser = new JavaParser(parserConfig);

        CompilationUnit cu = parser.parse(javaFile).getResult().orElse(null);
        if (cu == null) {
            return;
        }

        // Get the package and class name
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");

        // Find all classes and interfaces
        List<TypeDeclaration<?>> types = cu.getTypes();
        for (TypeDeclaration<?> type : types) {
            String binaryClassName = getBinaryClassName(packageName, type);
            collectMethodsFromType(javaFile, binaryClassName, type, allMethods);
        }
    }

    /**
     * Collect methods from a type (class/interface).
     * Same logic as experiment's JPUtils.getClassMethodPairs().
     */
    private void collectMethodsFromType(Path javaFile, String binaryClassName, TypeDeclaration<?> type, List<MethodToProcess> allMethods) {
        if (!(type instanceof ClassOrInterfaceDeclaration)) {
            return;
        }

        ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) type;

        // IMPORTANT: Experiment uses CallableDeclaration (includes methods AND constructors)
        // Use findAll to get all callable declarations, same as experiment
        @SuppressWarnings("unchecked")
        List<CallableDeclaration<?>> allCallables = (List<CallableDeclaration<?>>) (List<?>) classDecl.findAll(CallableDeclaration.class);

        for (CallableDeclaration<?> callable : allCallables) {
            // Track all methods found (before filtering)
            methodsFound.incrementAndGet();

            // Apply filtering criteria (same as experiment: JPUtils.getClassMethodPairs)
            // Filter 1: Must have range information
            if (!callable.getRange().isPresent()) {
                excludedByNoRange.incrementAndGet();
                continue;
            }

            // Filter 2: Must have at least (minimumLoc + 2) lines
            int lineCount = callable.getRange().get().end.line - callable.getRange().get().begin.line;
            if (lineCount < (minimumLoc + 2)) {
                excludedByMinLines.incrementAndGet();
                continue;
            }

            // Filter 3: Must not be in anonymous class (parent is not ObjectCreationExpr)
            Optional<com.github.javaparser.ast.Node> parentNodeOpt = callable.getParentNode();
            if (parentNodeOpt.isPresent()) {
                com.github.javaparser.ast.Node parentNode = parentNodeOpt.get();
                if (parentNode instanceof com.github.javaparser.ast.expr.ObjectCreationExpr) {
                    excludedByAnonymous.incrementAndGet();
                    continue;
                }
            }

            // IMPORTANT: Experiment does NOT filter abstract methods or methods without body
            // It includes all CallableDeclaration that pass the above filters

            // Get method signature (same as experiment: md.getSignature().asString())
            String methodSignature = callable.getSignature().asString();

            // Extract method name from signature (e.g., "methodName(int, String)" -> "methodName")
            String methodName = methodSignature.contains("(")
                ? methodSignature.substring(0, methodSignature.indexOf("("))
                : methodSignature;

            // Check if this is a static initializer (<clinit>)
            // Note: InitializerDeclaration is NOT a CallableDeclaration, so we check separately
            boolean isClinit = false;
            // Initializers are handled separately in the experiment, but for now we skip them
            // as they're not in CallableDeclaration

            // Get JVM descriptor
            String jvmDescriptor = null;
            if (callable instanceof MethodDeclaration) {
                jvmDescriptor = getJvmDescriptor((MethodDeclaration) callable);
            } else if (callable instanceof com.github.javaparser.ast.body.ConstructorDeclaration) {
                // For constructors, use the signature as-is (experiment handles constructors)
                jvmDescriptor = "<init>";  // Constructor descriptor
            } else if (isClinit) {
                jvmDescriptor = "<clinit>";  // Static initializer descriptor
            } else {
                // Skip other types for now
                continue;
            }

            if (jvmDescriptor == null && !isClinit) {
                continue;  // Skip if we can't determine descriptor (unless it's clinit)
            }

            // Add to collection (will be randomly selected later)
            allMethods.add(new MethodToProcess(javaFile, binaryClassName, methodName, jvmDescriptor, methodSignature, isClinit));
        }

        // Process nested types recursively
            for (TypeDeclaration<?> nestedType : type.getChildNodesByType(TypeDeclaration.class)) {
                if (nestedType != type) {
                    String nestedBinaryClassName = binaryClassName + "$" + nestedType.getNameAsString();
                collectMethodsFromType(javaFile, nestedBinaryClassName, nestedType, allMethods);
            }
        }
    }

    /**
     * Randomly select methods to process (same logic as experiment's getRandomClassMethodPairs).
     * Uses fixed seed (1234) for reproducibility.
     */
    private List<MethodToProcess> getRandomMethods(List<MethodToProcess> allMethods) {
        if (maxMethodsToProcess <= 0 || allMethods.size() <= maxMethodsToProcess) {
            // Process all methods (no limit or not enough methods)
            return new ArrayList<>(allMethods);
        }

        // Randomly select methods (same as experiment)
        List<MethodToProcess> randomMethods = new ArrayList<>();
        int collisions = 0;
        for (int i = 0; i < maxMethodsToProcess; i++) {
            int randomIdx = random.nextInt(allMethods.size());
            MethodToProcess randomMethod = allMethods.get(randomIdx);
            if (randomMethods.contains(randomMethod)) {
                i--;  // Try again if duplicate
                collisions++;
                if (collisions > maxMethodsToProcess) {
                    break;  // Avoid infinite loop
                }
                continue;
            }
            randomMethods.add(randomMethod);
        }

        return randomMethods;
    }

    /**
     * Check if a file is in one of the source roots.
     */
    private boolean isFileInSourceRoots(Path javaFile) {
        if (sourceRoots.isEmpty()) {
            return true;  // No source roots = accept all files
        }
        try {
            Path absoluteFile = javaFile.isAbsolute() ? javaFile : projectPath.resolve(javaFile).normalize();
            for (String sourceRoot : sourceRoots) {
                Path sourceRootPath = projectPath.resolve(sourceRoot).normalize();
                if (absoluteFile.startsWith(sourceRootPath)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // If we can't check, assume it's not in source roots
        }
        return false;
    }

    /**
     * Get all relevant Java files from a directory.
     * Matches experiment's FileUtils.getAllRelevantJavaFiles() logic, but uses relative paths
     * for test filtering to avoid false positives when project path contains "test".
     * Excludes test files, package-info, and module-info files.
     */
    private List<String> getAllRelevantJavaFiles(String dir) {
        List<String> javaFiles = new ArrayList<>();
        Path projectPathObj = Paths.get(dir);

        try {
            // Find all Java files first
            List<Path> allJavaFiles = Files.find(projectPathObj, 999,
                            (p, bfa) -> bfa.isRegularFile() &&
                                    p.getFileName().toString().matches(".*\\.java")
                    ).collect(Collectors.toList());

            // Filter using relative paths (to avoid false positives when project path contains "test")
            for (Path p : allJavaFiles) {
                String fileName = p.getFileName().toString();

                // Filter package-info and module-info
                if (fileName.contains("package-info") || fileName.contains("module-info")) {
                    continue;
                }

                // Filter test files using relative path (matches experiment intent)
                try {
                    Path relativePath = projectPathObj.relativize(p);
                    String relativePathStr = relativePath.toString().replace("\\", "/").toLowerCase();

                    // Check for test directories within the project (not in the project path itself)
                    if (relativePathStr.contains("/test/") ||
                            relativePathStr.contains("/tests/") ||
                            relativePathStr.contains("/test/java/") ||
                            relativePathStr.contains("/test/src/") ||
                            relativePathStr.startsWith("test/") ||
                            relativePathStr.startsWith("tests/")) {
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    // If paths are on different roots, fall back to simple check
                    String pathStr = p.toString().replace("\\", "/").toLowerCase();
                    if (pathStr.contains("/test/") || pathStr.contains("/tests/")) {
                        continue;
                    }
                }

                javaFiles.add(p.toString());
            }
        } catch (IOException e) {
            System.err.println("ERROR in getAllRelevantJavaFiles: " + e.getMessage());
            e.printStackTrace();
        }
        return javaFiles;
    }

    /**
     * Process a single method using the EXACT same flow as experiment setup (RandomJessHandler.compile).
     * Uses jess.preSlice() and jess.parse() instead of compileSingleMethod().
     */
    private void processMethod(MethodToProcess methodToProcess) {
        totalMethods.incrementAndGet();

        String targetClass = methodToProcess.javaFile.toString();  // Full file path (same as experiment)
        String methodSignature = methodToProcess.methodSignature;
        boolean isClinit = methodToProcess.isClinit;
        String binaryClassName = methodToProcess.binaryClassName;
        String methodName = methodToProcess.methodName;
        String jvmDescriptor = methodToProcess.jvmDescriptor;

        System.out.println("Compiling: " + targetClass + " --- " + methodSignature);

        try {
            // CRITICAL: Create NEW Jess instance for each method (same as experiment: line 92)
            // This prevents state accumulation and StackOverflowError
            Jess jess = new Jess(config, packages, jars);

            long startTime = System.nanoTime();

            // EXACT same flow as RandomJessHandler.compile()
            // Step 1: preSlice (same as experiment)
            if (!isClinit) {
                jess.preSlice(targetClass, Collections.singletonList(methodSignature), Collections.emptyList(), Collections.emptyList());
            } else {
                jess.preSlice(targetClass, Collections.emptyList(), Collections.singletonList(methodSignature), Collections.emptyList());
            }

            // Step 2: parse (same as experiment)
            // CRITICAL: Catch StackOverflowError from JavaParser symbol resolution
            // This happens when there are circular type dependencies in complex projects
            int jessResult;
            try {
                jessResult = jess.parse(targetClass);
            } catch (StackOverflowError e) {
                // JavaParser's symbol resolution can overflow on circular dependencies
                // This is a known limitation of JavaParser, not a bug in our code
                System.err.println("[RepositoryProcessor] StackOverflowError during symbol resolution for " + 
                    methodSignature + " - likely due to circular type dependencies in JavaParser");
                System.err.println("[RepositoryProcessor] Suggestion: Increase JVM stack size with -Xss4m or -Xss8m");
                jessResult = 2; // INTERNAL_ERROR
            }
            long endTime = System.nanoTime();
            long compilationTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            boolean jessSuccess = jessResult == 0;

            // Convert to PublicApi.Result format for compatibility with existing statistics code
            PublicApi.Status status;
            String notes = null;

            if (jessSuccess) {
                // Check if target method has bytecode (would need bytecode comparison, but for now assume OK)
                status = PublicApi.Status.OK;
            } else {
                // Map jessResult to status
                if (jessResult == 1) {
                    status = PublicApi.Status.FAILED_COMPILE;
                    notes = "Compilation failed (exit code 1)";
                } else {
                    status = PublicApi.Status.INTERNAL_ERROR;
                    notes = "Internal error (exit code " + jessResult + ")";
                }
            }

            PublicApi.Result result = new PublicApi.Result(
                status,
                null,  // bytecodePath
                binaryClassName,
                Collections.emptyList(),  // emittedClasses
                null,  // depsResolved
                false,  // usedStubs (would need to check stubbingStats)
                false,  // targetHasCode (would need bytecode comparison)
                "provided",  // depMode
                compilationTime,
                notes
        );

        // Update statistics
        statusCounts.get(result.status).incrementAndGet();

        // Track reasons for TARGET_METHOD_NOT_EMITTED
        if (result.status == PublicApi.Status.TARGET_METHOD_NOT_EMITTED) {
            String reason = result.notes != null && !result.notes.isEmpty()
                    ? result.notes.split("\\|")[0].trim()  // Get first part before "| usedStubs"
                    : "unknown reason";
            notEmittedReasons.computeIfAbsent(reason, k -> new AtomicInteger(0)).incrementAndGet();
        }

        // Track methods that compiled successfully
        // Both OK and TARGET_METHOD_NOT_EMITTED indicate successful compilation
        // (compilation succeeded, but target method may not have been emitted)
        boolean compilationSucceeded = (result.status == PublicApi.Status.OK)
                || (result.status == PublicApi.Status.TARGET_METHOD_NOT_EMITTED);

        if (compilationSucceeded) {
            methodsCompiledSuccessfully.incrementAndGet();
            successfulCompilations.incrementAndGet();

            // Track methods with accessible bytecode (status == OK && targetHasCode == true)
            if (result.status == PublicApi.Status.OK && result.targetHasCode) {
                methodsWithAccessibleBytecode.incrementAndGet();
            }
            // Note: TARGET_METHOD_NOT_EMITTED means compilation succeeded but target method
            // wasn't emitted, so it doesn't have accessible bytecode
        } else {
            failedCompilations.incrementAndGet();
        }

        // Store result
        methodResults.add(new MethodResult(
                binaryClassName,
                methodName,
                jvmDescriptor,
                    targetClass,
                    result
            ));

        } catch (Throwable e) {
            // Same error handling as experiment setup
            e.printStackTrace();

            PublicApi.Result result = new PublicApi.Result(
                PublicApi.Status.INTERNAL_ERROR,
                null,
                binaryClassName,
                Collections.emptyList(),
                null,
                false,
                false,
                "provided",
                0,
                "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );

            // Update statistics
            statusCounts.get(result.status).incrementAndGet();
            failedCompilations.incrementAndGet();

            // Store result
            methodResults.add(new MethodResult(
                    binaryClassName,
                    methodName,
                    jvmDescriptor,
                    targetClass,
                result
        ));
        }

        // Log progress and statistics for each method
        String limitInfo = maxMethodsToProcess > 0 ? " / " + maxMethodsToProcess : "";
        int current = totalMethods.get();
        int compiled = methodsCompiledSuccessfully.get();
        int withBytecode = methodsWithAccessibleBytecode.get();
        int failed = failedCompilations.get();

        // Log every method (or every 10th for less verbose output)
        boolean logThisMethod = (current % 10 == 0) || (current <= 10);

        if (logThisMethod) {
            double successRate = current > 0 ? (double) compiled / current * 100.0 : 0.0;
            double bytecodeRate = current > 0 ? (double) withBytecode / current * 100.0 : 0.0;

            System.out.println(String.format(
                "[JESS] Method %d%s | Compiled: %d (%.1f%%) | Bytecode: %d (%.1f%%) | Failed: %d | %s.%s",
                current, limitInfo, compiled, successRate, withBytecode, bytecodeRate, failed,
                binaryClassName, methodName
            ));
        }

        // Check if we've reached the limit
        if (maxMethodsToProcess > 0 && current >= maxMethodsToProcess) {
            System.out.println("\n[JESS] Reached method limit of " + maxMethodsToProcess + ". Stopping processing.");
            System.out.println("[JESS] Final Statistics:");
            System.out.println(String.format(
                "  Total: %d | Compiled: %d (%.1f%%) | With Bytecode: %d (%.1f%%) | Failed: %d",
                current, compiled, (double) compiled / current * 100.0,
                withBytecode, (double) withBytecode / current * 100.0, failed
            ));
        }
    }

    /**
     * Check if a method should be processed based on filtering criteria.
     * Same logic as JPUtils.getClassMethodPairs() in the experiment.
     *
     * Filters out:
     * 1. Methods without range information
     * 2. Methods with less than (minimumLoc + 2) lines
     * 3. Anonymous class methods (methods inside ObjectCreationExpr)
     */
    private boolean shouldProcessMethod(MethodDeclaration method) {
        // Filter 1: Check if method has range information
        if (!method.getRange().isPresent()) {
            excludedByNoRange.incrementAndGet();
            return false;
        }

        // Filter 2: Check minimum lines of code (actual threshold: minimumLoc + 2)
        int lineCount = method.getRange().get().end.line - method.getRange().get().begin.line;
        if (lineCount < (minimumLoc + 2)) {
            excludedByMinLines.incrementAndGet();
            return false;
        }

        // Filter 3: Check if method is in anonymous class (inside ObjectCreationExpr)
        // This is already handled by checking if method has a body, but we can add explicit check
        Optional<com.github.javaparser.ast.Node> parentNodeOpt = method.getParentNode();
        if (parentNodeOpt.isPresent()) {
            com.github.javaparser.ast.Node parentNode = parentNodeOpt.get();
            if (parentNode instanceof com.github.javaparser.ast.expr.ObjectCreationExpr) {
                excludedByAnonymous.incrementAndGet();
                return false;
            }
        }

        return true;  // Method passes all filters
    }

    /**
     * Find the source root that contains this Java file.
     *
     * Strategy:
     * 1. First, try to match against provided source roots
     * 2. If no match, try to infer the source root from the file's path
     *    (e.g., for modules/lwjgl/vulkan/src/generated/java/... -> modules/lwjgl/vulkan/src/generated/java)
     * 3. If still no match, return the first source root as fallback (but log warning)
     */
    private String findSourceRoot(Path javaFile) {
        Path relativePath = projectPath.relativize(javaFile);

        // Strategy 1: Try to match against provided source roots
        for (String sourceRoot : sourceRoots) {
            if (relativePath.startsWith(sourceRoot)) {
                return sourceRoot;
            }
        }

        // Strategy 2: Infer source root from file path
        // For paths like modules/lwjgl/vulkan/src/generated/java/org/.../File.java
        // Try to find the source root pattern (src/main/java, src/generated/java, etc.)
        String pathStr = relativePath.toString().replace("\\", "/");
        String[] sourceRootPatterns = {
            "src/main/java",
            "src/generated/java",
            "src/java",
            "src"
        };

        for (String pattern : sourceRootPatterns) {
            int patternIndex = pathStr.indexOf("/" + pattern + "/");
            if (patternIndex >= 0) {
                // Extract the source root: everything up to and including the pattern
                String inferredRoot = pathStr.substring(0, patternIndex + pattern.length() + 1);
                // Remove trailing slash if present
                if (inferredRoot.endsWith("/")) {
                    inferredRoot = inferredRoot.substring(0, inferredRoot.length() - 1);
                }
                // Verify this is a reasonable source root (not too deep)
                int depth = inferredRoot.split("/").length;
                if (depth <= 10) {  // Reasonable depth limit
                    // Verify the inferred root actually contains the file
                    String filePathAfterRoot = pathStr.substring(inferredRoot.length());
                    if (filePathAfterRoot.startsWith("/") || filePathAfterRoot.startsWith("\\")) {
                        System.err.println("[findSourceRoot] Inferred source root for " + javaFile.getFileName() +
                                         ": " + inferredRoot + " (file is in: " + pathStr + ")");
                        return inferredRoot;
                    }
                }
            }
        }

        // Strategy 3: Fallback to first source root (but this is likely wrong)
        if (!sourceRoots.isEmpty()) {
            System.err.println("[findSourceRoot] WARNING: Could not find source root for " + javaFile +
                             ". Using fallback: " + sourceRoots.get(0) +
                             ". This may cause 'Source file not found' errors.");
            return sourceRoots.get(0);
        }

        return "";
    }
    
    private String getBinaryClassName(String packageName, TypeDeclaration<?> type) {
        String className = type.getNameAsString();
        if (packageName.isEmpty()) {
            return className;
        }
        return packageName.replace(".", "/") + "/" + className;
    }
    
    private String getJvmDescriptor(MethodDeclaration method) {
        try {
            StringBuilder descriptor = new StringBuilder();
            descriptor.append("(");
            
            // Parameters - try to resolve types using JavaParser
            for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
                String typeDesc = null;
                try {
                    // Try to resolve the type using JavaParser's symbol solver
                    ResolvedType resolvedType = param.getType().resolve();
                    typeDesc = getTypeDescriptorFromResolved(resolvedType);
                } catch (Exception e) {
                    // Fallback to string-based conversion
                    typeDesc = getTypeDescriptor(param.getType().asString());
                }
                descriptor.append(typeDesc);
            }
            
            descriptor.append(")");
            
            // Return type - try to resolve using JavaParser
            String returnTypeDesc = null;
            try {
                ResolvedType resolvedReturnType = method.getType().resolve();
                returnTypeDesc = getTypeDescriptorFromResolved(resolvedReturnType);
            } catch (Exception e) {
                // Fallback to string-based conversion
                returnTypeDesc = getTypeDescriptor(method.getType().asString());
            }
            descriptor.append(returnTypeDesc);
            
            return descriptor.toString();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String getTypeDescriptorFromResolved(ResolvedType resolvedType) {
        if (resolvedType.isPrimitive()) {
            return getTypeDescriptor(resolvedType.asPrimitive().name());
        } else if (resolvedType.isVoid()) {
            return "V";
        } else if (resolvedType.isArray()) {
            ResolvedType componentType = resolvedType.asArrayType().getComponentType();
            return "[" + getTypeDescriptorFromResolved(componentType);
        } else {
            // For reference types, get the qualified name and convert
            String qualifiedName = resolvedType.asReferenceType().getQualifiedName();
            return "L" + qualifiedName.replace(".", "/") + ";";
        }
    }
    
    private String getTypeDescriptor(String typeName) {
        return getTypeDescriptor(typeName, new HashSet<>());
    }
    
    private String getTypeDescriptor(String typeName, Set<String> visited) {
        // Prevent infinite recursion
        if (visited.contains(typeName)) {
            // Fallback: return Object descriptor to avoid infinite loop
            return "Ljava/lang/Object;";
        }
        visited.add(typeName);
        
        try {
            // Simple type mapping - this is a basic implementation
            // For a complete solution, you'd want to use JavaParser's type resolution
            switch (typeName) {
                case "void": return "V";
                case "boolean": return "Z";
                case "byte": return "B";
                case "char": return "C";
                case "short": return "S";
                case "int": return "I";
                case "long": return "J";
                case "float": return "F";
                case "double": return "D";
                default:
                    // For object types, convert package.Class to Lpackage/Class;
                    // Handle arrays - only strip trailing [] (not [] inside generics)
                    if (typeName.endsWith("[]")) {
                        int arrayCount = 0;
                        String baseType = typeName;
                        // Only strip trailing [] pairs, not [] inside generics like List<byte[]>
                        while (baseType.endsWith("[]")) {
                            arrayCount++;
                            baseType = baseType.substring(0, baseType.length() - 2).trim();
                            // Safety check: if we've stripped too much, break
                            if (baseType.isEmpty()) {
                                baseType = "java.lang.Object";
                                break;
                            }
                        }
                        String baseDesc = getTypeDescriptor(baseType, visited);
                        StringBuilder desc = new StringBuilder();
                        for (int i = 0; i < arrayCount; i++) {
                            desc.append("[");
                        }
                        desc.append(baseDesc);
                        return desc.toString();
                    } else {
                        // Remove generic type parameters for JVM descriptor (e.g., List<String> -> List)
                        int genericStart = typeName.indexOf('<');
                        if (genericStart > 0) {
                            typeName = typeName.substring(0, genericStart);
                        }
                        return "L" + typeName.replace(".", "/") + ";";
                    }
            }
        } finally {
            visited.remove(typeName);
        }
    }
    
    private ProcessingResult generateResult() {
        // Print filtering statistics
        int totalFound = methodsFound.get();
        int totalProcessed = totalMethods.get();
        int totalExcluded = excludedByNoRange.get() + excludedByMinLines.get() + excludedByAnonymous.get();

        if (totalFound > 0) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("METHOD FILTERING STATISTICS");
            System.out.println("=".repeat(80));
            System.out.println("Total Methods Found: " + totalFound);
            System.out.println("  - Excluded (no range): " + excludedByNoRange.get());
            System.out.println("  - Excluded (< " + (minimumLoc + 2) + " lines): " + excludedByMinLines.get());
            System.out.println("  - Excluded (anonymous): " + excludedByAnonymous.get());
            System.out.println("  - Total Excluded: " + totalExcluded);
            System.out.println("  - Methods Processed: " + totalProcessed);
            System.out.println("=".repeat(80));
            System.out.println();
        }

        return new ProcessingResult(
                totalMethods.get(),
                methodsFound.get(),
                maxMethodsToProcess,
                methodsCompiledSuccessfully.get(),
                methodsWithAccessibleBytecode.get(),
                successfulCompilations.get(),
                failedCompilations.get(),
                statusCounts.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().get()
                        )),
                notEmittedReasons.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().get()
                        )),
                new ArrayList<>(methodResults),
                excludedByNoRange.get(),
                excludedByMinLines.get(),
                excludedByAnonymous.get()
        );
    }
    
    /**
     * Result of processing a repository.
     */
    public static class ProcessingResult {
        public final int totalMethods;  // Methods actually processed
        public final int methodsFound;  // Total methods found (before filtering and limit)
        public final int maxMethodsToProcess;  // Limit set (-1 means unlimited)
        public final int methodsCompiledSuccessfully;  // Methods with status == OK
        public final int methodsWithAccessibleBytecode;  // Methods with status == OK && targetHasCode == true
        public final int successfulCompilations;  // Same as methodsWithAccessibleBytecode (for backward compatibility)
        public final int failedCompilations;
        public final Map<PublicApi.Status, Integer> statusCounts;
        public final Map<String, Integer> notEmittedReasons;  // Reasons why methods weren't emitted
        public final List<MethodResult> methodResults;
        public final int excludedByNoRange;  // Methods excluded due to no range information
        public final int excludedByMinLines;  // Methods excluded due to insufficient lines
        public final int excludedByAnonymous;  // Methods excluded due to being in anonymous classes
        
        public ProcessingResult(int totalMethods,
                               int methodsFound,
                               int maxMethodsToProcess,
                               int methodsCompiledSuccessfully,
                               int methodsWithAccessibleBytecode,
                               int successfulCompilations, 
                               int failedCompilations,
                               Map<PublicApi.Status, Integer> statusCounts,
                               Map<String, Integer> notEmittedReasons,
                                List<MethodResult> methodResults,
                                int excludedByNoRange,
                                int excludedByMinLines,
                                int excludedByAnonymous) {
            this.totalMethods = totalMethods;
            this.methodsFound = methodsFound;
            this.maxMethodsToProcess = maxMethodsToProcess;
            this.methodsCompiledSuccessfully = methodsCompiledSuccessfully;
            this.methodsWithAccessibleBytecode = methodsWithAccessibleBytecode;
            this.successfulCompilations = successfulCompilations;
            this.failedCompilations = failedCompilations;
            this.statusCounts = statusCounts;
            this.notEmittedReasons = notEmittedReasons;
            this.methodResults = methodResults;
            this.excludedByNoRange = excludedByNoRange;
            this.excludedByMinLines = excludedByMinLines;
            this.excludedByAnonymous = excludedByAnonymous;
        }
        
        /**
         * Success rate: percentage of methods that compiled successfully (status == OK)
         */
        public double getCompilationSuccessRate() {
            return totalMethods > 0 ? (double) methodsCompiledSuccessfully / totalMethods * 100.0 : 0.0;
        }
        
        /**
         * Bytecode access rate: percentage of methods with accessible bytecode out of all methods
         */
        public double getBytecodeAccessRate() {
            return totalMethods > 0 ? (double) methodsWithAccessibleBytecode / totalMethods * 100.0 : 0.0;
        }
        
        /**
         * Bytecode access rate: percentage of methods with accessible bytecode out of successfully compiled methods
         */
        public double getBytecodeAccessRateFromCompiled() {
            return methodsCompiledSuccessfully > 0 
                    ? (double) methodsWithAccessibleBytecode / methodsCompiledSuccessfully * 100.0 
                    : 0.0;
        }
        
        /**
         * @deprecated Use getBytecodeAccessRate() instead
         */
        @Deprecated
        public double getSuccessRate() {
            return getBytecodeAccessRate();
        }
    }
    
    /**
     * Result for a single method compilation.
     */
    public static class MethodResult {
        public final String binaryClassName;
        public final String methodName;
        public final String jvmDescriptor;
        public final String sourceFile;
        public final PublicApi.Result result;
        
        public MethodResult(String binaryClassName, String methodName, String jvmDescriptor,
                           String sourceFile, PublicApi.Result result) {
            this.binaryClassName = binaryClassName;
            this.methodName = methodName;
            this.jvmDescriptor = jvmDescriptor;
            this.sourceFile = sourceFile;
            this.result = result;
        }
        
        /**
         * Get the full path to the bytecode file for this method, if available.
         * @return Full path to the .class file, or null if not available
         */
        public Path getBytecodeFilePath() {
            if (result.classesOutDir != null && result.targetClassFile != null) {
                return result.classesOutDir.resolve(result.targetClassFile);
            }
            return null;
        }
        
        /**
         * Get the directory containing the compiled bytecode for this method.
         * @return Path to the classes output directory, or null if not available
         */
        public Path getBytecodeDirectory() {
            return result.classesOutDir;
        }
        
        /**
         * Check if bytecode is available for this method.
         * @return true if bytecode file exists and is accessible
         */
        public boolean hasBytecode() {
            return result.targetHasCode && getBytecodeFilePath() != null;
        }
    }
}

