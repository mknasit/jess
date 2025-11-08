package de.upb.sse.jess.tool;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import de.upb.sse.jess.Jess;
import de.upb.sse.jess.api.PublicApi;
import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.util.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Utility class to process an entire repository and compile each method using Jess.
 */
public class RepositoryProcessor {
    
    private final String projectDir;
    private final List<String> sourceRoots;
    private final List<String> classpathJars;
    private final Jess jess;
    private final Path projectPath;
    private final int maxMethodsToProcess;  // -1 means unlimited
    
    // Statistics
    private final AtomicInteger totalMethods = new AtomicInteger(0);
    private final AtomicInteger methodsFound = new AtomicInteger(0);  // Total methods found (before limit)
    private final AtomicInteger methodsCompiledSuccessfully = new AtomicInteger(0);  // Status == OK
    private final AtomicInteger methodsWithAccessibleBytecode = new AtomicInteger(0);  // Status == OK && targetHasCode == true
    private final AtomicInteger successfulCompilations = new AtomicInteger(0);
    private final AtomicInteger failedCompilations = new AtomicInteger(0);
    private final Map<PublicApi.Status, AtomicInteger> statusCounts = new HashMap<>();
    private final Map<String, AtomicInteger> notEmittedReasons = new HashMap<>();  // Reasons why methods weren't emitted
    private final List<MethodResult> methodResults = new ArrayList<>();
    
    public RepositoryProcessor(String projectDir, List<String> sourceRoots, List<String> classpathJars) {
        this(projectDir, sourceRoots, classpathJars, -1);  // -1 means unlimited
    }
    
    public RepositoryProcessor(String projectDir, List<String> sourceRoots, List<String> classpathJars, int maxMethodsToProcess) {
        this.projectDir = projectDir;
        this.sourceRoots = sourceRoots;
        this.classpathJars = classpathJars;
        this.projectPath = Paths.get(projectDir);
        this.maxMethodsToProcess = maxMethodsToProcess;
        
        // Convert relative source roots to absolute paths
        List<String> absoluteSourceRoots = new ArrayList<>();
        for (String sourceRoot : sourceRoots) {
            Path sourceRootPath = Paths.get(sourceRoot);
            if (sourceRootPath.isAbsolute()) {
                absoluteSourceRoots.add(sourceRoot);
            } else {
                // Resolve relative path against project directory
                Path absolutePath = projectPath.resolve(sourceRoot).normalize();
                absoluteSourceRoots.add(absolutePath.toString());
            }
        }
        
        // Initialize Jess with absolute source roots and classpath
        JessConfiguration config = new JessConfiguration();
        config.setExitOnCompilationFail(false);
        config.setExitOnParsingFail(false);
        config.setFailOnAmbiguity(false);
        
        this.jess = new Jess(config, absoluteSourceRoots, classpathJars);
        
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
        if (maxMethodsToProcess > 0) {
            System.out.println("Method Limit: " + maxMethodsToProcess + " methods");
        } else {
            System.out.println("Method Limit: Unlimited");
        }
        System.out.println();
        
        // Find all Java files in source roots
        List<Path> javaFiles = new ArrayList<>();
        for (String sourceRoot : sourceRoots) {
            Path sourceRootPath = projectPath.resolve(sourceRoot);
            if (Files.exists(sourceRootPath)) {
                List<String> files = FileUtil.getAllJavaFiles(sourceRootPath.toString());
                javaFiles.addAll(files.stream().map(Paths::get).collect(Collectors.toList()));
            }
        }
        
        System.out.println("Found " + javaFiles.size() + " Java files to process");
        System.out.println();
        
        // Process each Java file
        for (Path javaFile : javaFiles) {
            try {
                processJavaFile(javaFile);
            } catch (Exception e) {
                System.err.println("Error processing file " + javaFile + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Generate summary
        return generateResult();
    }
    
    private void processJavaFile(Path javaFile) throws IOException {
        // Parse the Java file
        ParserConfiguration parserConfig = new ParserConfiguration();
        JavaParser parser = new JavaParser(parserConfig);
        
        CompilationUnit cu = parser.parse(javaFile).getResult().orElse(null);
        if (cu == null) {
            System.err.println("Failed to parse: " + javaFile);
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
            processType(javaFile, binaryClassName, type);
        }
    }
    
    private void processType(Path javaFile, String binaryClassName, TypeDeclaration<?> type) {
        if (!(type instanceof ClassOrInterfaceDeclaration)) {
            return;
        }
        
        ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) type;
        
        // Get the compilation unit to inject symbol solver
        CompilationUnit cu = type.findCompilationUnit().orElse(null);
        if (cu != null) {
            try {
                jess.inject(cu);
            } catch (Exception e) {
                // Ignore if injection fails
            }
        }
        
        // Process all methods in the class
        List<MethodDeclaration> methods = classDecl.getMethods();
        for (MethodDeclaration method : methods) {
            // Check if we've reached the method limit
            if (maxMethodsToProcess > 0 && totalMethods.get() >= maxMethodsToProcess) {
                return;  // Stop processing more methods
            }
            
            // Skip abstract methods and methods without body
            if (method.isAbstract() || !method.getBody().isPresent()) {
                continue;
            }
            
            String methodName = method.getNameAsString();
            String jvmDescriptor = getJvmDescriptor(method);
            
            if (jvmDescriptor == null) {
                System.err.println("Could not determine JVM descriptor for method: " + 
                        binaryClassName + "." + methodName);
                continue;
            }
            
            // Track methods found (before limit check)
            methodsFound.incrementAndGet();
            
            // Check limit before processing
            if (maxMethodsToProcess > 0 && totalMethods.get() >= maxMethodsToProcess) {
                return;  // Stop processing more methods
            }
            
            // Process this method
            processMethod(javaFile, binaryClassName, methodName, jvmDescriptor);
        }
        
        // Process nested types (only if we haven't reached the limit)
        if (maxMethodsToProcess <= 0 || totalMethods.get() < maxMethodsToProcess) {
            for (TypeDeclaration<?> nestedType : type.getChildNodesByType(TypeDeclaration.class)) {
                if (nestedType != type) {
                    // Check limit before processing nested type
                    if (maxMethodsToProcess > 0 && totalMethods.get() >= maxMethodsToProcess) {
                        return;
                    }
                    String nestedBinaryClassName = binaryClassName + "$" + nestedType.getNameAsString();
                    processType(javaFile, nestedBinaryClassName, nestedType);
                }
            }
        }
    }
    
    private void processMethod(Path javaFile, String binaryClassName, String methodName, String jvmDescriptor) {
        totalMethods.incrementAndGet();
        
        // Determine source root for this file
        String sourceRoot = findSourceRoot(javaFile);
        if (sourceRoot == null) {
            System.err.println("Could not determine source root for: " + javaFile);
            return;
        }
        
        // Create work directory for this method
        Path workDir = projectPath.resolve("jess-work").resolve(
                binaryClassName.replace("/", "_")).resolve(methodName);
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            System.err.println("Failed to create work directory: " + workDir);
            return;
        }
        
        // Create method ID
        PublicApi.MethodId methodId = new PublicApi.MethodId(binaryClassName, methodName, jvmDescriptor);
        
        // Create options
        List<Path> extraClasspath = classpathJars.stream()
                .map(Paths::get)
                .collect(Collectors.toList());
        PublicApi.Options options = new PublicApi.Options(
                "provided",  // depMode
                "method",    // sliceMode
                300,         // timeoutSec
                extraClasspath,
                workDir
        );
        
        // Compile the method
        PublicApi.Result result = jess.compileSingleMethod(
                projectPath,
                sourceRoot,
                methodId,
                options
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
                javaFile.toString(),
                result
        ));
        
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
    
    private String findSourceRoot(Path javaFile) {
        Path relativePath = projectPath.relativize(javaFile);
        for (String sourceRoot : sourceRoots) {
            if (relativePath.startsWith(sourceRoot)) {
                return sourceRoot;
            }
        }
        return sourceRoots.isEmpty() ? "" : sourceRoots.get(0);
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
                if (typeName.contains("[]")) {
                    int arrayCount = 0;
                    String baseType = typeName;
                    while (baseType.endsWith("[]")) {
                        arrayCount++;
                        baseType = baseType.substring(0, baseType.length() - 2);
                    }
                    String baseDesc = getTypeDescriptor(baseType);
                    StringBuilder desc = new StringBuilder();
                    for (int i = 0; i < arrayCount; i++) {
                        desc.append("[");
                    }
                    desc.append(baseDesc);
                    return desc.toString();
                } else {
                    return "L" + typeName.replace(".", "/") + ";";
                }
        }
    }
    
    private ProcessingResult generateResult() {
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
                new ArrayList<>(methodResults)
        );
    }
    
    /**
     * Result of processing a repository.
     */
    public static class ProcessingResult {
        public final int totalMethods;  // Methods actually processed
        public final int methodsFound;  // Total methods found (before limit)
        public final int maxMethodsToProcess;  // Limit set (-1 means unlimited)
        public final int methodsCompiledSuccessfully;  // Methods with status == OK
        public final int methodsWithAccessibleBytecode;  // Methods with status == OK && targetHasCode == true
        public final int successfulCompilations;  // Same as methodsWithAccessibleBytecode (for backward compatibility)
        public final int failedCompilations;
        public final Map<PublicApi.Status, Integer> statusCounts;
        public final Map<String, Integer> notEmittedReasons;  // Reasons why methods weren't emitted
        public final List<MethodResult> methodResults;
        
        public ProcessingResult(int totalMethods,
                               int methodsFound,
                               int maxMethodsToProcess,
                               int methodsCompiledSuccessfully,
                               int methodsWithAccessibleBytecode,
                               int successfulCompilations, 
                               int failedCompilations,
                               Map<PublicApi.Status, Integer> statusCounts,
                               Map<String, Integer> notEmittedReasons,
                               List<MethodResult> methodResults) {
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

