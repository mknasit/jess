package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * Minimal, simple Spoon-based stubber that:
 * - Loads ALL source roots + slice (no FQN filtering)
 * - Tolerates partial models (keeps context even if some errors occur)
 * - Falls back to slice-only only if absolutely no model exists
 * - Uses simple collector and stubber without fancy heuristics
 */
public final class MinimalSpoonStubbingRunner implements Stubber {
    private final JessConfiguration cfg;
    private final List<Path> sourceRoots;

    public MinimalSpoonStubbingRunner(JessConfiguration cfg) {
        this.cfg = cfg;
        this.sourceRoots = cfg.getSourceRoots() != null 
            ? cfg.getSourceRoots() 
            : java.util.Collections.emptyList();
    }

    @Override
    public int run(Path slicedSrcDir, List<Path> classpathJars) throws Exception {
        System.out.println("\n>> Using stubber: MinimalSpoonStubbingRunner (simple, robust mode)");

        // 1) Create Launcher & Environment
        Launcher launcher = new Launcher();
        var env = launcher.getEnvironment();
        env.setNoClasspath(classpathJars == null || classpathJars.isEmpty());
        
        if (classpathJars != null && !classpathJars.isEmpty()) {
            env.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
        }
        
        int complianceLevel = determineComplianceLevel(cfg.getTargetVersion());
        env.setComplianceLevel(complianceLevel);
        env.setAutoImports(false);
        env.setSourceOutputDirectory(slicedSrcDir.toFile());

        // 2) Compute FQNs from slice directory first (to filter duplicates)
        Set<String> slicedFqns = computeSlicedTypeFqns(slicedSrcDir);
        System.out.println("[MinimalSpoon] Found " + slicedFqns.size() + " FQNs in slice directory");
        
        // 3) Add source roots with FQN filtering (exclude files that define types already in slice)
        // This prevents duplicate type definitions that can cause model building to fail
        if (!sourceRoots.isEmpty() && !slicedFqns.isEmpty()) {
            System.out.println("[MinimalSpoon] Adding source roots with FQN filtering...");
            addSourceRootsWithFqnFilter(launcher, slicedFqns);
        } else if (!sourceRoots.isEmpty()) {
            // Fallback: if we couldn't compute slice FQNs, add all source roots (conservative)
            System.out.println("[MinimalSpoon] Warning: Could not compute slice FQNs, adding all source roots (may cause duplicates)");
            for (Path root : sourceRoots) {
                if (root != null && java.nio.file.Files.exists(root)) {
                    launcher.addInputResource(root.toString());
                    System.out.println("[MinimalSpoon] Added source root: " + root);
                }
            }
        }

        // 4) Always add the sliced directory last (takes precedence)
        launcher.addInputResource(slicedSrcDir.toString());
        System.out.println("[MinimalSpoon] Added slice directory: " + slicedSrcDir);

        // 4) Build model, tolerate partial result
        CtModel model = null;
        Factory factory = null;
        boolean usedContext = true;

        try {
            System.out.println("[MinimalSpoon] Building model with context...");
            launcher.buildModel();
            model = launcher.getModel();
            factory = launcher.getFactory();
            
            if (model == null) {
                throw new IllegalStateException("Model is null after build");
            }
            
            // Verify model actually has types
            boolean modelIsValid = true;
            try {
                Collection<spoon.reflect.declaration.CtType<?>> testTypes = model.getAllTypes();
                if (testTypes == null || testTypes.isEmpty()) {
                    System.err.println("[MinimalSpoon] WARNING: Model built but contains 0 types - will fall back to slice-only model");
                    modelIsValid = false;
                }
            } catch (StackOverflowError e) {
                System.err.println("[MinimalSpoon] StackOverflowError checking model types - model may be corrupted, will fall back to slice-only");
                modelIsValid = false;
            }
            
            if (modelIsValid && model != null && factory != null) {
                System.out.println("[MinimalSpoon] Model built successfully with context");
            } else {
                // Model is empty - set to null to trigger fallback
                model = null;
                factory = null;
            }
        } catch (ModelBuildingException e) {
            System.err.println("[MinimalSpoon] ModelBuildingException: " + e.getMessage());
            e.printStackTrace();
            CtModel partial = launcher.getModel();
            Factory partialFactory = launcher.getFactory();
            
            if (partial != null && partialFactory != null) {
                // Verify partial model has types
                try {
                    Collection<spoon.reflect.declaration.CtType<?>> testTypes = partial.getAllTypes();
                    if (testTypes != null && !testTypes.isEmpty()) {
                        System.err.println("[MinimalSpoon] Using partial model with context despite errors (" + testTypes.size() + " types).");
                        model = partial;
                        factory = partialFactory;
                    } else {
                        throw new IllegalStateException("Partial model is empty");
                    }
                } catch (Throwable t) {
                    System.err.println("[MinimalSpoon] Partial model is invalid: " + t.getMessage());
                    partial = null; // Force fallback
                }
            }
            
            if (model == null || factory == null) {
                System.err.println("[MinimalSpoon] No valid model available; falling back to slice-only model.");
                usedContext = false;
                
                Launcher sliceOnly = new Launcher();
                var env2 = sliceOnly.getEnvironment();
                env2.setNoClasspath(env.getNoClasspath());
                env2.setComplianceLevel(env.getComplianceLevel());
                env2.setAutoImports(false);
                env2.setSourceOutputDirectory(slicedSrcDir.toFile());
                
                if (classpathJars != null && !classpathJars.isEmpty()) {
                    env2.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
                }
                
                sliceOnly.addInputResource(slicedSrcDir.toString());
                try {
                    sliceOnly.buildModel();
                    model = sliceOnly.getModel();
                    factory = sliceOnly.getFactory();
                    
                    if (model == null || factory == null) {
                        throw new IllegalStateException("Slice-only model is null");
                    }
                    
                    // Verify slice-only model has types
                    try {
                        Collection<spoon.reflect.declaration.CtType<?>> testTypes = model.getAllTypes();
                        if (testTypes == null || testTypes.isEmpty()) {
                            System.err.println("[MinimalSpoon] WARNING: Slice-only model is empty");
                        } else {
                            System.out.println("[MinimalSpoon] Slice-only model built with " + testTypes.size() + " types");
                        }
                    } catch (Throwable ignored) {}
                } catch (Throwable e2) {
                    System.err.println("[MinimalSpoon] Slice-only model building also failed: " + e2.getMessage());
                    e2.printStackTrace();
                    throw new RuntimeException("Could not build any model (context failed, slice-only also failed)", e2);
                }
            }
        } catch (StackOverflowError e) {
            System.err.println("[MinimalSpoon] StackOverflowError during model building - likely circular dependencies");
            System.err.println("[MinimalSpoon] Attempting to use partial model if available...");
            CtModel partial = launcher.getModel();
            Factory partialFactory = launcher.getFactory();
            
            if (partial != null && partialFactory != null) {
                try {
                    Collection<spoon.reflect.declaration.CtType<?>> testTypes = partial.getAllTypes();
                    if (testTypes != null && !testTypes.isEmpty()) {
                        System.err.println("[MinimalSpoon] Using partial model despite StackOverflowError (" + testTypes.size() + " types)");
                        model = partial;
                        factory = partialFactory;
                    } else {
                        throw new IllegalStateException("Partial model is empty");
                    }
                } catch (Throwable t) {
                    System.err.println("[MinimalSpoon] Partial model is invalid: " + t.getMessage());
                    throw new RuntimeException("Model building failed with StackOverflowError and no valid partial model", e);
                }
            } else {
                throw new RuntimeException("Model building failed with StackOverflowError and no partial model", e);
            }
        } catch (Throwable e) {
            System.err.println("[MinimalSpoon] Unexpected error during model building: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            // Try to get partial model
            CtModel partial = launcher.getModel();
            Factory partialFactory = launcher.getFactory();
            
            if (partial != null && partialFactory != null) {
                try {
                    Collection<spoon.reflect.declaration.CtType<?>> testTypes = partial.getAllTypes();
                    if (testTypes != null && !testTypes.isEmpty()) {
                        System.err.println("[MinimalSpoon] Using partial model despite error (" + testTypes.size() + " types)");
                        model = partial;
                        factory = partialFactory;
                    } else {
                        System.err.println("[MinimalSpoon] Partial model is empty, will fall back to slice-only");
                        model = null;
                        factory = null;
                    }
                } catch (Throwable t) {
                    System.err.println("[MinimalSpoon] Partial model is invalid: " + t.getMessage());
                    model = null;
                    factory = null;
                }
            } else {
                model = null;
                factory = null;
            }
            
            // If we still don't have a model, try slice-only
            if (model == null || factory == null) {
                System.err.println("[MinimalSpoon] Falling back to slice-only model after error...");
                usedContext = false;
                
                Launcher sliceOnly = new Launcher();
                var env2 = sliceOnly.getEnvironment();
                env2.setNoClasspath(env.getNoClasspath());
                env2.setComplianceLevel(env.getComplianceLevel());
                env2.setAutoImports(false);
                env2.setSourceOutputDirectory(slicedSrcDir.toFile());
                
                if (classpathJars != null && !classpathJars.isEmpty()) {
                    env2.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
                }
                
                sliceOnly.addInputResource(slicedSrcDir.toString());
                try {
                    sliceOnly.buildModel();
                    model = sliceOnly.getModel();
                    factory = sliceOnly.getFactory();
                    
                    if (model != null && factory != null) {
                        try {
                            Collection<spoon.reflect.declaration.CtType<?>> testTypes = model.getAllTypes();
                            if (testTypes != null && !testTypes.isEmpty()) {
                                System.out.println("[MinimalSpoon] Slice-only model built with " + testTypes.size() + " types");
                            } else {
                                System.err.println("[MinimalSpoon] WARNING: Slice-only model is also empty");
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable e2) {
                    System.err.println("[MinimalSpoon] Slice-only model building also failed: " + e2.getMessage());
                    e2.printStackTrace();
                }
            }
        }

        if (model == null || factory == null) {
            System.err.println("[MinimalSpoon] ERROR: Could not build any model");
            return 0;
        }

        // Verify model has types before collecting
        try {
            Collection<spoon.reflect.declaration.CtType<?>> allTypes = model.getAllTypes();
            System.out.println("[MinimalSpoon] Model verification: found " + 
                (allTypes != null ? allTypes.size() : 0) + " total types in model");
            if (allTypes != null && !allTypes.isEmpty()) {
                int sampleCount = 0;
                for (spoon.reflect.declaration.CtType<?> t : allTypes) {
                    if (sampleCount < 3) {
                        System.out.println("[MinimalSpoon] Sample type: " + 
                            (t.getQualifiedName() != null ? t.getQualifiedName() : "unknown"));
                        sampleCount++;
                    }
                }
            }
        } catch (Throwable e) {
            System.err.println("[MinimalSpoon] ERROR: Failed to get types from model: " + e.getMessage());
            e.printStackTrace();
        }

        // 5) Collect slice types (only types from slicedSrcDir)
        Set<String> sliceTypeFqns = computeSliceTypeFqnsFromModel(model, slicedSrcDir);
        System.out.println("[MinimalSpoon] Found " + sliceTypeFqns.size() + " slice types to process");
        
        // 6) Collect missing types and members (only from slice types)
        System.out.println("[MinimalSpoon] Collecting missing types and members from slice types only...");
        MinimalSpoonCollector collector = new MinimalSpoonCollector(factory, cfg);
        MinimalSpoonCollector.Result result = collector.collect(model, slicedSrcDir);
        
        System.out.println("[MinimalSpoon] Found " + result.missingTypes.size() + " missing types");
        System.out.println("[MinimalSpoon] Missing types: " + result.missingTypes);
        System.out.println("[MinimalSpoon] Found " + result.missingMembersByOwner.size() + " owners with missing members");
        System.out.println("[MinimalSpoon] Owners with missing members: " + result.missingMembersByOwner.keySet());
        for (Map.Entry<String, Set<MinimalSpoonCollector.MissingMember>> entry :
             result.missingMembersByOwner.entrySet()) {
            System.out.println("[MinimalSpoon] Owner " + entry.getKey() + " missing " + entry.getValue().size() + " members");
            if (entry.getValue().size() > 0) {
                System.out.println("[MinimalSpoon]   Sample members: " + 
                    entry.getValue().stream().limit(5)
                        .map(m -> m.simpleName + "(" + m.kind + ")")
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
        }

        // 7) Generate stubs (only for slice types)
        System.out.println("[MinimalSpoon] Generating stubs for slice types only...");
        MinimalSpoonStubber stubber = new MinimalSpoonStubber(factory, cfg);
        int created = stubber.apply(result, slicedSrcDir, sliceTypeFqns);

        // 8) Pretty-print only slice types to slicedSrcDir
        System.out.println("[MinimalSpoon] Pretty-printing slice types only to " + slicedSrcDir);
        prettyPrintSliceTypesOnly(factory, sliceTypeFqns, slicedSrcDir);

        System.out.println("[MinimalSpoon] Created/updated " + created + " stub types");
        return created;
    }

    /**
     * Determine Java compliance level from targetVersion or default to 8.
     */
    private int determineComplianceLevel(String targetVersion) {
        if (targetVersion == null || targetVersion.isEmpty()) {
            return 8; // Default to Java 8
        }
        
        // Parse version string like "1.8", "8", "11", "17", etc.
        try {
            String version = targetVersion.trim();
            if (version.startsWith("1.")) {
                version = version.substring(2);
            }
            int level = Integer.parseInt(version);
            // Spoon supports levels 1-21
            if (level >= 1 && level <= 21) {
                return level;
            }
        } catch (NumberFormatException e) {
            // Fall through to default
        }
        
        return 8; // Default to Java 8
    }
    
    /**
     * Compute FQNs from the sliced directory using JavaParser (simpler than Spoon's approach).
     * This is used to filter source root files that would cause duplicate type definitions.
     */
    private Set<String> computeSlicedTypeFqns(Path slicedSrcDir) {
        Set<String> fqns = new HashSet<>();
        try {
            Files.walk(slicedSrcDir)
                .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("package-info"))
                .filter(p -> !p.toString().contains("module-info"))
                .forEach(javaFile -> {
                    try {
                        fqns.addAll(computeFqnsForSourceFile(javaFile));
                    } catch (Throwable ignored) {
                        // Skip files we can't parse
                    }
                });
        } catch (java.io.IOException e) {
            System.err.println("[MinimalSpoon] Error computing slice FQNs: " + e.getMessage());
        }
        return fqns;
    }
    
    /**
     * Compute FQNs for a single source file using JavaParser.
     */
    private Set<String> computeFqnsForSourceFile(Path javaFile) {
        Set<String> fqns = new HashSet<>();
        try {
            JavaParser parser = new JavaParser(new ParserConfiguration());
            com.github.javaparser.ParseResult<CompilationUnit> result = parser.parse(javaFile);
            
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit cu = result.getResult().get();
                java.util.Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
                String packageName = pkg.map(p -> p.getNameAsString()).orElse("");
                
                // Find all types (top-level and nested)
                cu.findAll(TypeDeclaration.class).forEach(td -> {
                    String simpleName = td.getNameAsString();
                    
                    if (td.isTopLevelType()) {
                        // Top-level type: package.ClassName
                        String fqn = packageName.isEmpty()
                            ? simpleName
                            : packageName + "." + simpleName;
                        fqns.add(fqn);
                    } else {
                        // Nested type: find top-level ancestor and build Outer$Inner
                        TypeDeclaration<?> outer = td;
                        while (outer.getParentNode().isPresent() &&
                               outer.getParentNode().get() instanceof TypeDeclaration) {
                            outer = (TypeDeclaration<?>) outer.getParentNode().get();
                        }
                        
                        String outerName = outer.getNameAsString();
                        String nestedFqn = packageName.isEmpty()
                            ? outerName + "$" + simpleName
                            : packageName + "." + outerName + "$" + simpleName;
                        fqns.add(nestedFqn);
                    }
                });
            }
        } catch (Throwable ignored) {
            // Skip files we can't parse
        }
        return fqns;
    }
    
    /**
     * Add source roots with FQN filtering - exclude files that define types already in slice.
     */
    private void addSourceRootsWithFqnFilter(Launcher launcher, Set<String> slicedFqns) {
        java.util.concurrent.atomic.AtomicInteger addedFiles = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger skippedFiles = new java.util.concurrent.atomic.AtomicInteger(0);
        
        for (Path root : sourceRoots) {
            if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
                continue;
            }
            
            try {
                Files.walk(root)
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("package-info"))
                    .filter(p -> !p.toString().contains("module-info"))
                    .forEach(javaFile -> {
                        try {
                            Set<String> fileFqns = computeFqnsForSourceFile(javaFile);
                            
                            if (fileFqns.isEmpty()) {
                                // Can't parse - be conservative and add it
                                launcher.addInputResource(javaFile.toString());
                                addedFiles.incrementAndGet();
                            } else {
                                // Check if ALL FQNs in this file are already in slice
                                boolean allCovered = fileFqns.stream().allMatch(slicedFqns::contains);
                                
                                if (allCovered) {
                                    // Skip - slice defines these types
                                    skippedFiles.incrementAndGet();
                                } else {
                                    // Add - contains types not in slice (for context)
                                    launcher.addInputResource(javaFile.toString());
                                    addedFiles.incrementAndGet();
                                }
                            }
                        } catch (Throwable ignored) {
                            // Skip individual file errors
                        }
                    });
            } catch (java.io.IOException e) {
                System.err.println("[MinimalSpoon] Error walking source root " + root + ": " + e.getMessage());
            }
        }
        
        System.out.println("[MinimalSpoon] Source root filtering: added " + addedFiles.get() + " files, skipped " + skippedFiles.get() + " files (to avoid duplicates)");
    }
    
    /**
     * Compute FQNs of slice types from the model (types whose source file is in slicedSrcDir).
     */
    private Set<String> computeSliceTypeFqnsFromModel(CtModel model, Path slicedSrcDir) {
        Set<String> sliceFqns = new HashSet<>();
        Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
        
        try {
            Collection<CtType<?>> allTypes = model.getAllTypes();
            for (CtType<?> type : allTypes) {
                try {
                    spoon.reflect.cu.SourcePosition pos = type.getPosition();
                    if (pos != null && pos.getFile() != null) {
                        Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                        if (filePath.startsWith(sliceRoot)) {
                            String qn = type.getQualifiedName();
                            if (qn != null && !qn.isEmpty()) {
                                sliceFqns.add(qn);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            System.err.println("[MinimalSpoon] Error computing slice type FQNs: " + e.getMessage());
        }
        
        return sliceFqns;
    }
    
    /**
     * Pretty-print only slice types to the output directory, not all types in the model.
     * This is critical - without it, Spoon would print all 594 types instead of just slice types.
     */
    private void prettyPrintSliceTypesOnly(Factory factory, Set<String> sliceTypeFqns, Path slicedSrcDir) {
        if (sliceTypeFqns == null || sliceTypeFqns.isEmpty()) {
            System.out.println("[MinimalSpoon] No slice types to print");
            return;
        }
        
        int printed = 0;
        for (String sliceFqn : sliceTypeFqns) {
            try {
                CtType<?> type = factory.Type().get(sliceFqn);
                if (type == null) {
                    System.err.println("[MinimalSpoon] Warning: Slice type not found in model: " + sliceFqn);
                    continue;
                }
                
                // Get the compilation unit for this type
                spoon.reflect.declaration.CtCompilationUnit cu = factory.CompilationUnit().getOrCreate(type);
                if (cu == null) {
                    System.err.println("[MinimalSpoon] Warning: Could not get compilation unit for: " + sliceFqn);
                    continue;
                }
                
                // Convert FQN to file path (e.g., "com.example.Foo" -> "com/example/Foo.java")
                String fqn = type.getQualifiedName();
                if (fqn == null) continue;
                
                String relativePath = fqn.replace(".", "/") + ".java";
                Path outputPath = slicedSrcDir.resolve(relativePath);
                outputPath.getParent().toFile().mkdirs();
                
                // Use Spoon's prettyprint method to convert the compilation unit to a string
                String code = cu.prettyprint();
                
                // Write the code to the file
                Files.write(outputPath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                printed++;
            } catch (Throwable e) {
                System.err.println("[MinimalSpoon] Failed to print slice type " + sliceFqn + ": " + e.getMessage());
            }
        }
        
        if (printed > 0) {
            System.out.println("[MinimalSpoon] Printed " + printed + " slice type(s) to output directory");
        }
    }
}

