// de/upb/sse/jess/stubbing/SpoonStubbingRunner.java
package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.CompilerInvoker;
import de.upb.sse.jess.Jess;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector.CollectResult;
import de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber;
import de.upb.sse.jess.stubbing.spoon.context.ContextIndex;
import de.upb.sse.jess.stubbing.spoon.context.SourceRootsContextIndex;
import de.upb.sse.jess.stubbing.spoon.plan.TypeStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.MethodStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.FieldStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.ConstructorStubPlan;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtAnnotationType;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.factory.Factory;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Objects;

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
        // Delegating wrapper: call new overload with null descriptor (backward compatibility)
        return run(slicedSrcDir, classpathJars, null);
    }
    
    /**
     * Run stubbing with SliceDescriptor support.
     * 
     * Builds Spoon model from slicedSrcDir (gen/) ONLY - slice is canonical.
     * 
     * @param slicedSrcDir The directory containing sliced code (gen/)
     * @param classpathJars Classpath JARs for compilation
     * @param descriptor SliceDescriptor describing the slice (null for backward compatibility)
     * @return Number of stubs created
     */
    public int run(Path slicedSrcDir, List<Path> classpathJars, SliceDescriptor descriptor) throws Exception {
        System.out.println("\n>> Using stubber: Spoon Based Stubber" );
        
        // Build model from slice directory only (gen/ is canonical)
        System.out.println("[SpoonStubbingRunner] Building model from slice directory only...");
        StubbingResult result = tryStubbingWithSliceOnly(slicedSrcDir, classpathJars, descriptor);
        
        return result.stubsCreated;
    }
    
    /**
     * Result of a stubbing attempt, including whether compilation succeeded.
     */
    private static class StubbingResult {
        final int stubsCreated;
        final boolean compilationSuccess;
        
        StubbingResult(int stubsCreated, boolean compilationSuccess) {
            this.stubsCreated = stubsCreated;
            this.compilationSuccess = compilationSuccess;
        }
    }
    
    /**
     * Build model from slice directory only (gen/ is canonical).
     * @param slicedSrcDir The slice directory (gen/)
     * @param classpathJars Classpath JARs
     * @param descriptor SliceDescriptor (optional metadata)
     * @return StubbingResult with number of stubs created and compilation success status
     */
    private StubbingResult tryStubbingWithSliceOnly(Path slicedSrcDir, List<Path> classpathJars, SliceDescriptor descriptor) throws Exception {
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
        
        // 3) DO NOT add source roots - use only slice directory for this attempt
        // (This is the key: ignore source roots even if available)
        System.out.println("[SpoonStubbingRunner] Using only slice directory (source roots ignored for this attempt)");

        // 4) Add the sliced directory
        launcher.addInputResource(slicedSrcDir.toString());
        System.out.println("[SpoonStubbingRunner] Added slice directory: " + slicedSrcDir);

        // 5) Build model with only slice directory
        CtModel model = null;
        Factory f = null;
        
        try {
            System.out.println("[SpoonStubbingRunner] Building slice-only model...");
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
                    System.out.println("[SpoonStubbingRunner] Slice-only model built successfully with " + testTypes.size() + " types");
                }
        } catch (StackOverflowError e) {
                System.err.println("[SpoonStubbingRunner] StackOverflowError checking model types - model may be corrupted");
                model = null;
                f = null;
            }
        } catch (ModelBuildingException e) {
            System.err.println("[SpoonStubbingRunner] ModelBuildingException building slice-only model: " + e.getMessage());
            // Try to get partial model
            CtModel partial = launcher.getModel();
            Factory partialFactory = launcher.getFactory();
            
            if (partial != null && partialFactory != null) {
                try {
                    Collection<CtType<?>> testTypes = safeGetAllTypes(partial);
                    if (testTypes != null && !testTypes.isEmpty()) {
                        System.err.println("[SpoonStubbingRunner] Using partial slice-only model despite errors (" + testTypes.size() + " types)");
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
            // Slice-only model building failed - return failure (will be retried with context in run())
            System.err.println("[SpoonStubbingRunner] ERROR: Could not build slice-only model");
            return new StubbingResult(0, false);
        }

        // 6) Collect unresolved elements and generate stubs, then compile
        // Preserve existing JavaParser slice files (don't overwrite)
        // Pass slicedSrcDir so collector/stubber can use path-based slice detection
        return performStubbing(model, f, slicedSrcDir, launcher, classpathJars, descriptor);
    }
    
    /**
     * Perform stubbing: collect missing elements, generate stubs, print to disk, and compile.
     * 
     * Preserves existing JavaParser slice files (don't overwrite).
     */
    private StubbingResult performStubbing(CtModel model, Factory f, Path slicedSrcDir, Launcher launcher, List<Path> classpathJars, SliceDescriptor descriptor) throws Exception {
        // Get slice type FQNs from descriptor (if available and non-empty) or treat all model types as slice types
        Set<String> sliceTypeFqns;
        boolean hasDescriptorSlice =
            descriptor != null &&
            descriptor.sliceTypeFqns != null &&
            !descriptor.sliceTypeFqns.isEmpty();
        
        if (hasDescriptorSlice) {
            sliceTypeFqns = descriptor.sliceTypeFqns;
            System.out.println("[SpoonStubbingRunner] Using " + sliceTypeFqns.size() + " slice types from SliceDescriptor");
        } else {
            // Fallback: treat all types in the model as slice types (no filtering by descriptor)
            sliceTypeFqns = safeGetAllTypes(model).stream()
                .map(CtType::getQualifiedName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            System.out.println("[SpoonStubbingRunner] No slice types in descriptor; using all " +
                sliceTypeFqns.size() + " model types as slice types");
        }
        
        // Build ContextIndex from source roots if available (lightweight, for tie-breaking only)
        ContextIndex contextIndex = null;
        if (!sourceRoots.isEmpty()) {
            System.out.println("[SpoonStubbingRunner] Building ContextIndex from source roots...");
            System.out.println("[SpoonStubbingRunner] Source roots: " + sourceRoots);
            try {
                contextIndex = new SourceRootsContextIndex(sourceRoots);
                System.out.println("[SpoonStubbingRunner] ✓ ContextIndex built successfully - will be used for tie-breaking");
            } catch (Throwable e) {
                System.err.println("[SpoonStubbingRunner] ✗ Warning: Failed to build ContextIndex: " + e.getMessage());
                System.err.println("[SpoonStubbingRunner] Continuing without ContextIndex (best-effort)");
                // Continue without ContextIndex (best-effort)
            }
        } else {
            System.out.println("[SpoonStubbingRunner] No source roots available - ContextIndex will NOT be created");
            System.out.println("[SpoonStubbingRunner] Stubbing will proceed without context-based tie-breaking");
        }
        
        // Create collector with descriptor AND slicedSrcDir for path-based slice detection
        // Pass ContextIndex for tie-breaking (type ambiguity, owner resolution)
        if (contextIndex != null) {
            System.out.println("[SpoonStubbingRunner] Passing ContextIndex to SpoonCollector for tie-breaking");
        } else {
            System.out.println("[SpoonStubbingRunner] SpoonCollector will run without ContextIndex");
        }
        SpoonCollector collector = new SpoonCollector(f, cfg, descriptor, slicedSrcDir, contextIndex);
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

        // 7) Generate stubs (separate handlers per kind) - pass slicedSrcDir for path-based slice detection
        SpoonStubber stubber = new SpoonStubber(f, cfg, descriptor, plans.annotationAttributes, slicedSrcDir);
        int created = 0;
        created += stubber.applyTypePlans(plans.typePlans);// types (classes/interfaces/annotations)
        created += stubber.applyFieldPlans(plans.fieldPlans);         // fields
        created += stubber.applyConstructorPlans(plans.ctorPlans);    // constructors
        created += stubber.applyMethodPlans(plans.methodPlans);
        stubber.finalizeRepeatableAnnotations();

        stubber.canonicalizeAllMetaAnnotations();
        stubber.dequalifyCurrentPackageUnresolvedRefs();
        stubber.qualifyAmbiguousSimpleTypes();           // NEW pass
        stubber.ensureUnknownPackageImports();           // Ensure imports for unknown.* types

        stubber.report();                                             // nice summary

        // 8) Pretty-print slice types AND stub types (use default printer; safer with JDK11 snippets)
        // IMPORTANT: The JavaParser-generated slice in slicedSrcDir is canonical.
        // Do not overwrite existing slice .java files with Spoon's view of those types.
        // Only add new stub types here.
        // Get set of modified slice types (types that had members added)
        Set<String> modifiedSliceTypes = stubber.getModifiedSliceTypeFqns();
        var env = launcher.getEnvironment();
        env.setPrettyPrinterCreator(() -> new DefaultJavaPrettyPrinter(env));
        prettyPrintSliceTypesOnly(f, sliceTypeFqns, slicedSrcDir, modifiedSliceTypes);
        
        // 9) Also write stub types to disk (types that were created but are not slice types)
        Set<String> stubTypeFqns = stubber.getCreatedTypes();
        stubTypeFqns.removeAll(sliceTypeFqns); // Remove slice types (already written)
        prettyPrintStubTypes(f, stubTypeFqns, slicedSrcDir);
        
        // 10) Compile the generated stubs
        System.out.println("[SpoonStubbingRunner] Compiling generated stubs...");
        boolean compilationSuccess = compileStubs(slicedSrcDir, classpathJars);
        
        // Summary: ContextIndex usage
        System.out.println("\n==================================================================================");
        System.out.println("CONTEXTINDEX SUMMARY");
        System.out.println("==================================================================================");
        if (contextIndex != null) {
            System.out.println("✓ ContextIndex was created and used for tie-breaking");
            System.out.println("  - Type canonicalization: Look for '[ContextIndex] Canonicalized' messages above");
            System.out.println("  - Owner resolution: Look for '[ContextIndex] ✓ Found method' messages above");
        } else {
            System.out.println("✗ ContextIndex was NOT created (no source roots or build failed)");
            System.out.println("  - Stubbing proceeded without context-based tie-breaking");
        }
        System.out.println("==================================================================================\n");
        
        if (compilationSuccess) {
            System.out.println("[SpoonStubbingRunner] Compilation succeeded!");
        } else {
            System.out.println("[SpoonStubbingRunner] Compilation failed");
        }
        
        return new StubbingResult(created, compilationSuccess);
    }
    
    /**
     * Compile the stubbed code in the sliced source directory.
     * @param slicedSrcDir The directory containing source files to compile
     * @param classpathJars JAR files for classpath
     * @return true if compilation succeeded, false otherwise
     */
    private boolean compileStubs(Path slicedSrcDir, List<Path> classpathJars) {
        try {
            String classOutput = Jess.CLASS_OUTPUT; // "output"
            String targetVersion = cfg.getTargetVersion();
            
            CompilerInvoker compiler = new CompilerInvoker(targetVersion, true); // silent compilation
            CompilerInvoker.CompilationResult result = compiler.compileFile(
                java.util.List.of(slicedSrcDir.toString()), 
                classOutput
            );
            
            if (!result.success && result.errorMessages != null && !result.errorMessages.isEmpty()) {
                // Print errors for debugging
                System.err.println("[SpoonStubbingRunner] Compilation errors:");
                System.err.println(result.errorMessages);
            }
            
            return result.success;
        } catch (Exception e) {
            System.err.println("[SpoonStubbingRunner] Error during compilation: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
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
     * 
     * Preserves existing JavaParser slice files (don't overwrite) unless they were modified.
     * 
     * @param modifiedSliceTypes Set of slice type FQNs that were modified (had members added)
     */
    private void prettyPrintSliceTypesOnly(Factory f, Set<String> sliceTypeFqns, Path slicedSrcDir, Set<String> modifiedSliceTypes) {
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
                
                // IMPORTANT: Preserve existing JavaParser slice files unless they were actually modified.
                // JavaParser's TypeExtractor writes the original sliced code, which is the source of truth.
                // If we overwrite it with Spoon's model version, we might introduce errors
                // (e.g., incorrect return types inferred from an incomplete slice-only model).
                if (Files.exists(outputPath)) {
                    // File exists - check if it was actually modified by stubber
                    if (modifiedSliceTypes == null || !modifiedSliceTypes.contains(sliceFqn)) {
                        // File exists and was NOT modified - preserve it (don't overwrite)
                        System.out.println("[SpoonStubbingRunner] Preserving existing slice file: " + outputPath + " (no modifications)");
                        continue;
                    } else {
                        // File exists and WAS modified - re-write it with added members
                        System.out.println("[SpoonStubbingRunner] Re-writing slice type " + sliceFqn + 
                            " with added members (was modified by stubber)");
                    }
                }
                // File doesn't exist - print it (new stub file)
                
                // Use Spoon's prettyprint method to convert the compilation unit to a string
                String code = cu.prettyprint();
                
                // Write the code to the file (only if it doesn't already exist)
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
                // IMPORTANT: For context types, we need to ensure the compilation unit includes the type
                // If the type is from a source file, its compilation unit might be separate
                spoon.reflect.declaration.CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
            if (cu == null) {
                    System.err.println("[SpoonStubbingRunner] Warning: Could not get compilation unit for stub: " + stubFqn);
                continue;
            }
            
            // DEBUG: Check what's in the compilation unit
            System.out.println("[SpoonStubbingRunner] DEBUG: Compilation unit for " + stubFqn + " has " + cu.getDeclaredTypes().size() + " declared types");
            System.out.println("[SpoonStubbingRunner] DEBUG: Type is in CU: " + cu.getDeclaredTypes().contains(type));
            
            // For context types, ensure the type is in the compilation unit
            // The type might be from a source file with a different compilation unit
            if (!cu.getDeclaredTypes().contains(type)) {
                try {
                    cu.addDeclaredType(type);
                    System.out.println("[SpoonStubbingRunner] DEBUG: Added type to compilation unit");
                } catch (Throwable e) {
                    System.err.println("[SpoonStubbingRunner] Warning: Could not add type to compilation unit: " + e.getMessage());
                }
            }
            
            // DEBUG: After adding, check methods again
            if (type instanceof CtInterface) {
                CtInterface<?> itf = (CtInterface<?>) type;
                System.out.println("[SpoonStubbingRunner] DEBUG: Interface has " + itf.getMethods().size() + " methods in type");
                System.out.println("[SpoonStubbingRunner] DEBUG: CU declared types: " + cu.getDeclaredTypes().size());
                for (CtType<?> declaredType : cu.getDeclaredTypes()) {
                    if (declaredType instanceof CtInterface) {
                        CtInterface<?> cuItf = (CtInterface<?>) declaredType;
                        System.out.println("[SpoonStubbingRunner] DEBUG: CU interface " + cuItf.getQualifiedName() + " has " + cuItf.getMethods().size() + " methods");
                    }
                }
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
                
                // DEBUG: Show what's being written for stub types
                System.out.println("\n==================================================================================");
                System.out.println("STUB TYPE BEING WRITTEN: " + stubFqn);
                System.out.println("==================================================================================");
                System.out.println("File: " + outputPath);
                System.out.println("Type: " + (type instanceof CtClass ? "CLASS" : type instanceof CtInterface ? "INTERFACE" : type instanceof CtAnnotationType ? "ANNOTATION" : "UNKNOWN"));
                if (type instanceof CtClass) {
                    CtClass<?> cls = (CtClass<?>) type;
                    System.out.println("Methods: " + cls.getMethods().size());
                    System.out.println("Fields: " + cls.getFields().size());
                    System.out.println("Constructors: " + cls.getConstructors().size());
                    if (cls.getMethods().size() > 0) {
                        System.out.println("Method names: " + cls.getMethods().stream()
                            .map(m -> {
                                String params = m.getParameters().stream()
                                    .map(p -> p.getType().getSimpleName())
                                    .collect(java.util.stream.Collectors.joining(", "));
                                return m.getSimpleName() + "(" + params + ")";
                            })
                            .collect(java.util.stream.Collectors.joining(", ")));
                    }
                } else if (type instanceof CtInterface) {
                    CtInterface<?> itf = (CtInterface<?>) type;
                    System.out.println("Methods: " + itf.getMethods().size());
                    if (itf.getMethods().size() > 0) {
                        System.out.println("Method names: " + itf.getMethods().stream()
                            .map(m -> {
                                String params = m.getParameters().stream()
                                    .map(p -> p.getType().getSimpleName())
                                    .collect(java.util.stream.Collectors.joining(", "));
                                return m.getSimpleName() + "(" + params + ")";
                            })
                            .collect(java.util.stream.Collectors.joining(", ")));
                    }
                }
                System.out.println("\n--- CODE BEING WRITTEN ---");
                System.out.println(code);
                System.out.println("--- END CODE ---\n");
                
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
