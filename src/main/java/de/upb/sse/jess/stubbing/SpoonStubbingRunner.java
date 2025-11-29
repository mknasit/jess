// de/upb/sse/jess/stubbing/SpoonStubbingRunner.java
package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector.CollectResult;
import de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber;
import de.upb.sse.jess.stubbing.spoon.plan.TypeStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.MethodStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.FieldStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.ConstructorStubPlan;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

public final class SpoonStubbingRunner implements Stubber {
    private final JessConfiguration cfg;
    private final List<Path> sourceRoots;

    public SpoonStubbingRunner(JessConfiguration cfg) {
        this.cfg = cfg;
        this.sourceRoots = cfg.getSourceRoots() != null 
            ? cfg.getSourceRoots() 
            : java.util.Collections.emptyList();
    }

    @Override
    public int run(Path slicedSrcDir, List<Path> classpathJars) throws Exception {
        System.out.println("\n>> Using stubber: Spoon Based Stubber" );
        // 1) Configure Spoon for Java 11
        Launcher launcher = new Launcher();
        var env = launcher.getEnvironment();
        env.setComplianceLevel(11);
        env.setAutoImports(true);
        env.setSourceOutputDirectory(slicedSrcDir.toFile());

        if (classpathJars == null || classpathJars.isEmpty()) {
            env.setNoClasspath(true);
        } else {
            env.setNoClasspath(false);
            env.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
        }

        // 2) Compute FQNs from slice directory first (to filter duplicates)
        Set<String> slicedFqns = computeSlicedTypeFqns(slicedSrcDir);
        System.out.println("[SpoonStubbingRunner] Found " + slicedFqns.size() + " FQNs in slice directory");
        
        // 3) Add source roots with FQN filtering (exclude files that define types already in slice)
        // This prevents duplicate type definitions that can cause model building to fail
        // while still providing context for type resolution
        if (!sourceRoots.isEmpty() && !slicedFqns.isEmpty()) {
            System.out.println("[SpoonStubbingRunner] Adding source roots with FQN filtering...");
            addSourceRootsWithFqnFilter(launcher, slicedFqns);
        } else if (!sourceRoots.isEmpty()) {
            // Fallback: if we couldn't compute slice FQNs, add all source roots (conservative)
            System.out.println("[SpoonStubbingRunner] Warning: Could not compute slice FQNs, adding all source roots (may cause duplicates)");
            for (Path root : sourceRoots) {
                if (root != null && Files.exists(root) && Files.isDirectory(root)) {
                    launcher.addInputResource(root.toString());
                    System.out.println("[SpoonStubbingRunner] Added source root: " + root);
                }
            }
        }

        // 4) Always add the sliced directory last (takes precedence, and this is what we process)
        launcher.addInputResource(slicedSrcDir.toString());
        System.out.println("[SpoonStubbingRunner] Added slice directory: " + slicedSrcDir);

        // 5) Build model, tolerate partial result
        CtModel model = null;
        Factory f = null;
        
        try {
            System.out.println("[SpoonStubbingRunner] Building model with context...");
            launcher.buildModel();
            model = launcher.getModel();
            f = launcher.getFactory();
            
            if (model == null || f == null) {
                throw new IllegalStateException("Model is null after build");
            }
            
            // Verify model actually has types
            try {
                Collection<CtType<?>> testTypes = safeGetAllTypes(model);
                if (testTypes == null || testTypes.isEmpty()) {
                    System.err.println("[SpoonStubbingRunner] WARNING: Model built but contains 0 types - will fall back to slice-only model");
                    model = null;
                    f = null;
        } else {
                    System.out.println("[SpoonStubbingRunner] Model built successfully with " + testTypes.size() + " types");
                }
        } catch (StackOverflowError e) {
                System.err.println("[SpoonStubbingRunner] StackOverflowError checking model types - model may be corrupted, will fall back to slice-only");
                model = null;
                f = null;
            }
        } catch (ModelBuildingException e) {
            System.err.println("[SpoonStubbingRunner] ModelBuildingException: " + e.getMessage());
            // Try to get partial model
            CtModel partial = launcher.getModel();
            Factory partialFactory = launcher.getFactory();
            
            if (partial != null && partialFactory != null) {
                try {
                    Collection<CtType<?>> testTypes = safeGetAllTypes(partial);
                    if (testTypes != null && !testTypes.isEmpty()) {
                        System.err.println("[SpoonStubbingRunner] Using partial model despite errors (" + testTypes.size() + " types)");
                        model = partial;
                        f = partialFactory;
        } else {
                        model = null;
                        f = null;
                    }
                } catch (Throwable t) {
                    System.err.println("[SpoonStubbingRunner] Partial model is invalid: " + t.getMessage());
                    model = null;
                    f = null;
                }
        } else {
                model = null;
                f = null;
            }
            
            // If we still don't have a model, try slice-only
            if (model == null || f == null) {
                System.err.println("[SpoonStubbingRunner] No valid model available; falling back to slice-only model.");
                
                Launcher sliceOnly = new Launcher();
                var env2 = sliceOnly.getEnvironment();
                env2.setNoClasspath(env.getNoClasspath());
                env2.setComplianceLevel(env.getComplianceLevel());
                env2.setAutoImports(true); // Match the original environment setting
                env2.setSourceOutputDirectory(slicedSrcDir.toFile());
                
                if (classpathJars != null && !classpathJars.isEmpty()) {
                    env2.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
                }
                
                sliceOnly.addInputResource(slicedSrcDir.toString());
                try {
                    sliceOnly.buildModel();
                    model = sliceOnly.getModel();
                    f = sliceOnly.getFactory();
                    
                    if (model != null && f != null) {
                        try {
                            Collection<CtType<?>> testTypes = safeGetAllTypes(model);
                            if (testTypes != null && !testTypes.isEmpty()) {
                                System.out.println("[SpoonStubbingRunner] Slice-only model built with " + testTypes.size() + " types");
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable e2) {
                    System.err.println("[SpoonStubbingRunner] Slice-only model building also failed: " + e2.getMessage());
                    throw new RuntimeException("Could not build any model (context failed, slice-only also failed)", e2);
                }
            }
        } catch (StackOverflowError e) {
            System.err.println("[SpoonStubbingRunner] StackOverflowError during model building - likely circular dependencies");
            // Try to get partial model
            CtModel partial = launcher.getModel();
            Factory partialFactory = launcher.getFactory();
            
            if (partial != null && partialFactory != null) {
                try {
                    Collection<CtType<?>> testTypes = safeGetAllTypes(partial);
                    if (testTypes != null && !testTypes.isEmpty()) {
                        System.err.println("[SpoonStubbingRunner] Using partial model despite StackOverflowError (" + testTypes.size() + " types)");
                        model = partial;
                        f = partialFactory;
                    } else {
                        throw new RuntimeException("Model building failed with StackOverflowError and no valid partial model", e);
                    }
                } catch (Throwable t) {
                    throw new RuntimeException("Model building failed with StackOverflowError and no valid partial model", e);
                }
            } else {
                throw new RuntimeException("Model building failed with StackOverflowError and no partial model", e);
                }
            } catch (Throwable e) {
            System.err.println("[SpoonStubbingRunner] Unexpected error during model building: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            // Try to get partial model
            CtModel partial = launcher.getModel();
            Factory partialFactory = launcher.getFactory();
            
            if (partial != null && partialFactory != null) {
                try {
                    Collection<CtType<?>> testTypes = safeGetAllTypes(partial);
                    if (testTypes != null && !testTypes.isEmpty()) {
                        System.err.println("[SpoonStubbingRunner] Using partial model despite error (" + testTypes.size() + " types)");
                        model = partial;
                        f = partialFactory;
                                } else {
                        throw new RuntimeException("Could not build any model", e);
                    }
                } catch (Throwable t) {
                    throw new RuntimeException("Could not build any model", e);
                }
                                    } else {
                throw new RuntimeException("Could not build any model", e);
            }
        }
        
        if (model == null || f == null) {
            System.err.println("[SpoonStubbingRunner] ERROR: Could not build any model");
            return 0;
        }

        // 6) Collect unresolved elements (only from slice types)
        
        // Compute slice type FQNs (types from slicedSrcDir only)
        Set<String> sliceTypeFqns = computeSliceTypeFqns(model, slicedSrcDir);
        System.out.println("[SpoonStubbingRunner] Found " + sliceTypeFqns.size() + " slice types to process");
        
        SpoonCollector collector = new SpoonCollector(f, cfg, slicedSrcDir, sliceTypeFqns);
        CollectResult plans = collector.collect(model);

        // Print collection results (what was detected as missing)
        System.out.println("\n==================================================================================");
        System.out.println("COLLECTION RESULTS - What Was Detected as Missing (Before Stubbing)");
        System.out.println("==================================================================================");
        
        System.out.println("\n1. MISSING TYPES (Detected by Collection):");
        System.out.println("   Total: " + plans.typePlans.size());
        if (!plans.typePlans.isEmpty()) {
            // Group by kind for better readability
            Map<TypeStubPlan.Kind, List<TypeStubPlan>> byKind = new java.util.LinkedHashMap<>();
            for (TypeStubPlan tp : plans.typePlans) {
                byKind.computeIfAbsent(tp.kind, k -> new ArrayList<>()).add(tp);
            }
            for (Map.Entry<TypeStubPlan.Kind, List<TypeStubPlan>> entry : byKind.entrySet()) {
                System.out.println("   " + entry.getKey() + " (" + entry.getValue().size() + "):");
                for (TypeStubPlan tp : entry.getValue()) {
                    System.out.println("     • " + tp.qualifiedName);
                }
            }
        } else {
            System.out.println("   (none)");
        }
        
        System.out.println("\n2. MISSING FIELDS (Detected by Collection):");
        System.out.println("   Total: " + plans.fieldPlans.size());
        if (!plans.fieldPlans.isEmpty()) {
            for (FieldStubPlan fp : plans.fieldPlans) {
                String owner = fp.ownerType != null ? fp.ownerType.getQualifiedName() : "unknown";
                String fieldType = fp.fieldType != null ? fp.fieldType.getQualifiedName() : "unknown";
                System.out.println("     • " + owner + "." + fp.fieldName + " : " + fieldType + 
                    (fp.isStatic ? " [static]" : ""));
            }
        } else {
            System.out.println("   (none)");
        }
        
        System.out.println("\n3. MISSING CONSTRUCTORS (Detected by Collection):");
        System.out.println("   Total: " + plans.ctorPlans.size());
        if (!plans.ctorPlans.isEmpty()) {
            for (ConstructorStubPlan cp : plans.ctorPlans) {
                String owner = cp.ownerType != null ? cp.ownerType.getQualifiedName() : "unknown";
                String params = cp.parameterTypes.isEmpty() ? "" : cp.parameterTypes.stream()
                    .map(pt -> pt != null ? pt.getQualifiedName() : "unknown")
                    .collect(java.util.stream.Collectors.joining(", "));
                System.out.println("     • " + owner + "(" + params + ")");
            }
        } else {
            System.out.println("   (none)");
        }
        
        System.out.println("\n4. MISSING METHODS (Detected by Collection):");
        System.out.println("   Total: " + plans.methodPlans.size());
        if (!plans.methodPlans.isEmpty()) {
            for (MethodStubPlan mp : plans.methodPlans) {
                String owner = mp.ownerType != null ? mp.ownerType.getQualifiedName() : "unknown";
                String returnType = mp.returnType != null ? mp.returnType.getQualifiedName() : "void";
                String params = mp.paramTypes.isEmpty() ? "" : mp.paramTypes.stream()
                    .map(pt -> pt != null ? pt.getQualifiedName() : "unknown")
                    .collect(java.util.stream.Collectors.joining(", "));
                String modifiers = (mp.isStatic ? "static " : "") + mp.visibility.name().toLowerCase();
                System.out.println("     • " + modifiers + " " + returnType + " " + owner + "." + mp.name + "(" + params + ")");
            }
        } else {
            System.out.println("   (none)");
        }
        
        System.out.println("\n==================================================================================");
        System.out.println("STUB PLANS GENERATED (Based on Collection Results Above)");
        System.out.println("==================================================================================");
        System.out.println("These plans will be used to generate stubs:");
        System.out.println("  • " + plans.typePlans.size() + " type plans");
        System.out.println("  • " + plans.fieldPlans.size() + " field plans");
        System.out.println("  • " + plans.ctorPlans.size() + " constructor plans");
        System.out.println("  • " + plans.methodPlans.size() + " method plans");
        System.out.println("==================================================================================\n");

        // 7) Generate stubs (separate handlers per kind)
        SpoonStubber stubber = new SpoonStubber(f, slicedSrcDir, sliceTypeFqns);
        int created = 0;
        created += stubber.applyTypePlans(plans.typePlans);// types (classes/interfaces/annotations)
        created += stubber.applyFieldPlans(plans.fieldPlans);         // fields
        created += stubber.applyConstructorPlans(plans.ctorPlans);    // constructors
        created += stubber.applyMethodPlans(plans.methodPlans);
        stubber.finalizeRepeatableAnnotations();

        stubber.canonicalizeAllMetaAnnotations();
        stubber.dequalifyCurrentPackageUnresolvedRefs();
        stubber.qualifyAmbiguousSimpleTypes();           // NEW pass

        stubber.report();                                             // nice summary

        // 8) Pretty-print slice types AND stub types (use default printer; safer with JDK11 snippets)
        env.setPrettyPrinterCreator(() -> new DefaultJavaPrettyPrinter(env));
        prettyPrintSliceTypesOnly(f, sliceTypeFqns, slicedSrcDir);
        
        // 9) Also write stub types to disk (types that were created but are not slice types)
        Set<String> stubTypeFqns = stubber.getCreatedTypes();
        stubTypeFqns.removeAll(sliceTypeFqns); // Remove slice types (already written)
        prettyPrintStubTypes(f, stubTypeFqns, slicedSrcDir);
        
        return created;
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
            System.err.println("[SpoonStubbingRunner] Error computing slice FQNs: " + e.getMessage());
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
     * This prevents duplicate type definitions while still providing context for type resolution.
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
                                // Can't parse - be conservative and add it (might be needed for context)
                                launcher.addInputResource(javaFile.toString());
                                addedFiles.incrementAndGet();
                            } else {
                                // Check if ALL FQNs in this file are already in slice
                                boolean allCovered = fileFqns.stream().allMatch(slicedFqns::contains);
                                
                                if (allCovered) {
                                    // Skip - slice defines these types (would cause duplicates)
                                    skippedFiles.incrementAndGet();
                                            } else {
                                    // Add - contains types not in slice (for context/resolution)
                                    launcher.addInputResource(javaFile.toString());
                                    addedFiles.incrementAndGet();
                                }
                            }
                } catch (Throwable ignored) {
                            // Skip individual file errors
                        }
                    });
            } catch (java.io.IOException e) {
                System.err.println("[SpoonStubbingRunner] Error walking source root " + root + ": " + e.getMessage());
            }
        }
        
        System.out.println("[SpoonStubbingRunner] Source root filtering: added " + addedFiles.get() + " files, skipped " + skippedFiles.get() + " files (to avoid duplicates)");
    }
    
    /**
     * Get only slice types from the model (types whose source file is in slicedSrcDir).
     * This is critical for performance - avoids processing all 594 types when we only need 3.
     * Uses direct FQN lookup when possible to avoid getAllTypes().
     */
    private Collection<CtType<?>> getSliceTypes(CtModel model, Factory f, Path slicedSrcDir, Set<String> sliceTypeFqns) {
        List<CtType<?>> sliceTypes = new ArrayList<>();
        
        if (sliceTypeFqns != null && !sliceTypeFqns.isEmpty()) {
            // OPTIMIZATION: Use direct FQN lookup instead of getAllTypes()
            for (String fqn : sliceTypeFqns) {
                try {
                    CtType<?> type = f.Type().get(fqn);
                    if (type != null) {
                        sliceTypes.add(type);
                    }
                } catch (Throwable ignored) {
                    // Skip types we can't get
                }
            }
            
            // If we found all types via FQN lookup, return early
            if (sliceTypes.size() == sliceTypeFqns.size()) {
                return sliceTypes;
            }
        }
        
        // Fallback: check file paths (only if FQN lookup didn't find all types)
        Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
        try {
            Collection<CtType<?>> allTypes = safeGetAllTypes(model);
            for (CtType<?> type : allTypes) {
                try {
                    // Skip if already found via FQN lookup
                    String qn = type.getQualifiedName();
                    if (qn != null && sliceTypeFqns != null && sliceTypeFqns.contains(qn)) {
                        continue; // Already added
                    }
                    
                    // Check file path
                    spoon.reflect.cu.SourcePosition pos = type.getPosition();
                    if (pos != null && pos.getFile() != null) {
                        Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                        if (filePath.startsWith(sliceRoot)) {
                            sliceTypes.add(type);
                        }
                    }
                } catch (Throwable ignored) {
                    // Skip types we can't process
                }
            }
        } catch (Throwable e) {
            System.err.println("[SpoonStubbingRunner] Error getting slice types: " + e.getMessage());
        }
        
        return sliceTypes;
    }
    
    /**
     * Compute FQNs of slice types from the model (types whose source file is in slicedSrcDir).
     * Uses safe wrapper to handle StackOverflowError.
     */
    private Set<String> computeSliceTypeFqns(CtModel model, Path slicedSrcDir) {
        Set<String> sliceFqns = new HashSet<>();
        Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
        
        try {
            Collection<CtType<?>> allTypes = safeGetAllTypes(model);
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
        } catch (Throwable ignored) {
                    // Skip types we can't process
                }
            }
        } catch (Throwable e) {
            System.err.println("[SpoonStubbingRunner] Error computing slice type FQNs: " + e.getMessage());
        }
        
        return sliceFqns;
    }
    
    /**
     * Safely get all types from model, handling StackOverflowError.
     */
    private Collection<CtType<?>> safeGetAllTypes(CtModel model) {
        try {
            return model.getAllTypes();
        } catch (StackOverflowError e) {
            System.err.println("[SpoonStubbingRunner] StackOverflowError getting all types - likely circular dependencies");
            System.err.println("[SpoonStubbingRunner] Returning empty collection - some slice types may be missing");
            return Collections.emptyList();
        } catch (Throwable e) {
            System.err.println("[SpoonStubbingRunner] Error getting all types: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Pretty-print only slice types to the sliced directory.
     */
    private void prettyPrintSliceTypesOnly(Factory f, Set<String> sliceTypeFqns, Path slicedSrcDir) {
        if (sliceTypeFqns == null || sliceTypeFqns.isEmpty()) {
            System.out.println("[SpoonStubbingRunner] No slice types to print");
                return;
            }
            
        int printed = 0;
        Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
        
        for (String sliceFqn : sliceTypeFqns) {
            try {
                CtType<?> type = f.Type().get(sliceFqn);
                if (type == null) {
                    System.err.println("[SpoonStubbingRunner] Warning: Slice type not found in model: " + sliceFqn);
                                    continue;
                }
                
                // Get the compilation unit for this type
                spoon.reflect.declaration.CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                if (cu == null) {
                    System.err.println("[SpoonStubbingRunner] Warning: Could not get compilation unit for: " + sliceFqn);
                                    continue;
                }
                
                // Determine output path based on package and type name
                String pkg = type.getPackage() != null ? type.getPackage().getQualifiedName() : "";
                String simpleName = type.getSimpleName();
                String relativePath;
                
                if (pkg.isEmpty()) {
                    relativePath = simpleName + ".java";
                                    } else {
                    relativePath = pkg.replace(".", "/") + "/" + simpleName + ".java";
                }
                
                Path outputPath = slicedSrcDir.resolve(relativePath);
                outputPath.getParent().toFile().mkdirs();
                
                // Use Spoon's prettyprint method to convert the compilation unit to a string
                String code = cu.prettyprint();
                
                // Write the code to the file
                Files.write(outputPath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                printed++;
                    } catch (Throwable e) {
                System.err.println("[SpoonStubbingRunner] Failed to print slice type " + sliceFqn + ": " + e.getMessage());
            }
        }
        
        if (printed > 0) {
            System.out.println("[SpoonStubbingRunner] Printed " + printed + " slice type(s) to output directory");
        }
    }
    
    /**
     * Pretty-print stub types (types created by stubber but not slice types) to the sliced directory.
     * Nested types are handled automatically by Spoon - they're included in the outer class's compilation unit.
     */
    private void prettyPrintStubTypes(Factory f, Set<String> stubTypeFqns, Path slicedSrcDir) {
        if (stubTypeFqns == null || stubTypeFqns.isEmpty()) {
            return; // No stub types to print
        }
        
        // Filter out nested types (they'll be included in their outer class's file)
        Set<String> topLevelStubTypes = new LinkedHashSet<>();
        for (String stubFqn : stubTypeFqns) {
            if (!stubFqn.contains("$")) {
                topLevelStubTypes.add(stubFqn);
            }
        }
        
        int printed = 0;
        
        for (String stubFqn : topLevelStubTypes) {
            try {
                CtType<?> type = f.Type().get(stubFqn);
                if (type == null) {
                    // Type might not exist if something went wrong
                    continue;
                }
                
                // Skip if this is a nested type (shouldn't happen after filtering, but be safe)
                if (type.getDeclaringType() != null) {
                    continue; // Nested types are handled by their outer class
                }
                
                // Get the compilation unit for this type (includes nested types)
                spoon.reflect.declaration.CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                if (cu == null) {
                    System.err.println("[SpoonStubbingRunner] Warning: Could not get compilation unit for stub: " + stubFqn);
                    continue;
                }
                
                // Determine output path based on package and type name
                String pkg = type.getPackage() != null ? type.getPackage().getQualifiedName() : "";
                String simpleName = type.getSimpleName();
                String relativePath;
                
                // Top-level type
                if (pkg.isEmpty()) {
                    relativePath = simpleName + ".java";
                } else {
                    relativePath = pkg.replace(".", "/") + "/" + simpleName + ".java";
                }
                
                Path outputPath = slicedSrcDir.resolve(relativePath);
                outputPath.getParent().toFile().mkdirs();
                
                // Use Spoon's prettyprint method to convert the compilation unit to a string
                String code = cu.prettyprint();
                
                // Write the code to the file
                Files.write(outputPath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                printed++;
            } catch (Throwable e) {
                System.err.println("[SpoonStubbingRunner] Failed to print stub type " + stubFqn + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        if (printed > 0) {
            System.out.println("[SpoonStubbingRunner] Printed " + printed + " stub type(s) to output directory");
        }
    }
}
