// de/upb/sse/jess/stubbing/SpoonStubbingRunner.java
package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.CompilerInvoker;
import de.upb.sse.jess.Jess;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector.CollectResult;
import de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber;
import de.upb.sse.jess.stubbing.spoon.SpoonModelSlicer;
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
     * Strategy A (model-from-gen): Build model from slicedSrcDir only.
     * Strategy B (model-from-source-roots): Build model from source roots, then apply SpoonModelSlicer.
     * 
     * @param slicedSrcDir The directory containing sliced code (gen/)
     * @param classpathJars Classpath JARs for compilation
     * @param descriptor SliceDescriptor describing the slice (null for backward compatibility)
     * @return Number of stubs created
     */
    public int run(Path slicedSrcDir, List<Path> classpathJars, SliceDescriptor descriptor) throws Exception {
        System.out.println("\n>> Using stubber: Spoon Based Stubber" );
        
        // STRATEGY A: Try building model from sliced directory only (cheap, local)
        System.out.println("[SpoonStubbingRunner] Strategy A: Building model from slice directory only...");
        StubbingResult resultA = tryStubbingWithSliceOnly(slicedSrcDir, classpathJars, descriptor);
        
        // If Strategy A succeeded, we're done
        if (resultA.compilationSuccess) {
            System.out.println("[SpoonStubbingRunner] Strategy A succeeded!");
            return resultA.stubsCreated;
        }
        
        // STRATEGY B: If Strategy A failed AND descriptor is available AND source roots are available,
        // build model from source roots and apply SpoonModelSlicer
        if (descriptor != null && !sourceRoots.isEmpty()) {
            System.out.println("[SpoonStubbingRunner] Strategy A failed, trying Strategy B (full-context model + slicing)...");
            // CRITICAL: Clean gen/ directory before Strategy B to remove incorrect stubs from Strategy A
            // Strategy A may have written incorrect stubs (e.g., methods returning void instead of proper types)
            // that will cause compilation conflicts when Strategy B tries to compile
            try {
                java.io.File genDir = slicedSrcDir.toFile();
                if (genDir.exists()) {
                    System.out.println("[SpoonStubbingRunner] Cleaning gen/ directory before Strategy B to remove incorrect stubs from Strategy A...");
                    de.upb.sse.jess.util.FileUtil.deleteRecursively(genDir);
                    genDir.mkdirs(); // Recreate empty directory
                    // Note: Slice types from JavaParser will be re-written by Strategy B via prettyPrintSliceTypesOnly
                }
            } catch (Exception e) {
                System.err.println("[SpoonStubbingRunner] WARNING: Failed to clean gen/ directory before Strategy B: " + e.getMessage());
                // Continue anyway - Strategy B will overwrite files, but old incorrect stubs might remain
            }
            StubbingResult resultB = tryStubbingWithFullContext(slicedSrcDir, classpathJars, descriptor);
            return resultB.stubsCreated;
        } else {
            System.out.println("[SpoonStubbingRunner] Strategy A failed, but Strategy B not available (descriptor=" + 
                    (descriptor != null ? "available" : "null") + ", sourceRoots=" + sourceRoots.size() + ")");
            return resultA.stubsCreated;
        }
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
     * Strategy A: Build model from slice directory only (no context/source roots).
     * This is the first attempt - always uses only slice directory regardless of source roots availability.
     * @param slicedSrcDir The slice directory (gen/)
     * @param classpathJars Classpath JARs
     * @param descriptor SliceDescriptor (optional, for future use)
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

        // 6) Collect unresolved elements and generate stubs, then compile (generic, path-agnostic)
        // Strategy A: preserve existing JavaParser slice files (don't overwrite)
        return performStubbing(model, f, slicedSrcDir, launcher, classpathJars, descriptor, true);
    }
    
    /**
     * Strategy B: Build model from source roots, then apply SpoonModelSlicer to keep only slice-relevant elements.
     * This is the fallback when Strategy A fails.
     * 
     * IMPORTANT: This does NOT add slicedSrcDir to the model. It builds from source roots only,
     * then slices the model using SliceDescriptor, then writes output to slicedSrcDir.
     * 
     * @param slicedSrcDir Output directory (where to write the final code)
     * @param classpathJars Classpath JARs
     * @param descriptor SliceDescriptor describing what to keep
     * @return StubbingResult with number of stubs created and compilation success status
     */
    private StubbingResult tryStubbingWithFullContext(Path slicedSrcDir, List<Path> classpathJars, SliceDescriptor descriptor) throws Exception {
        System.out.println("[SpoonStubbingRunner] Strategy B: Building full model from source roots, then slicing...");
        
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

        // 2) Add source roots ONLY (do NOT add slicedSrcDir - this is the key difference)
        if (!sourceRoots.isEmpty()) {
            System.out.println("[SpoonStubbingRunner] Adding source roots (NOT adding slice directory)...");
            for (Path root : sourceRoots) {
                if (root != null && Files.exists(root) && Files.isDirectory(root)) {
                    launcher.addInputResource(root.toString());
                    System.out.println("[SpoonStubbingRunner] Added source root: " + root);
                }
            }
        } else {
            System.err.println("[SpoonStubbingRunner] ERROR: Strategy B requires source roots but none are available");
            return new StubbingResult(0, false);
        }

        // 3) Build full model from source roots
        CtModel model = null;
        Factory f = null;
        
        try {
            System.out.println("[SpoonStubbingRunner] Building full model from source roots...");
            launcher.buildModel();
            model = launcher.getModel();
            f = launcher.getFactory();
            
            if (model == null || f == null) {
                throw new IllegalStateException("Model is null after build");
            }
            
            // Verify model has types
            Collection<CtType<?>> testTypes = safeGetAllTypes(model);
            if (testTypes == null || testTypes.isEmpty()) {
                System.err.println("[SpoonStubbingRunner] WARNING: Full model built but contains 0 types");
                return new StubbingResult(0, false);
            }
            
            System.out.println("[SpoonStubbingRunner] Full model built successfully with " + testTypes.size() + " types");
        } catch (ModelBuildingException e) {
            System.err.println("[SpoonStubbingRunner] ModelBuildingException building full model: " + e.getMessage());
            // Try partial model
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
                        return new StubbingResult(0, false);
                    }
                } catch (Throwable t) {
                    return new StubbingResult(0, false);
                }
            } else {
                return new StubbingResult(0, false);
            }
        } catch (Exception e) {
            System.err.println("[SpoonStubbingRunner] Error building full model: " + e.getMessage());
            e.printStackTrace();
            return new StubbingResult(0, false);
        }
        
        if (model == null || f == null) {
            System.err.println("[SpoonStubbingRunner] ERROR: Could not build full model");
            return new StubbingResult(0, false);
        }

        // 4) Apply SpoonModelSlicer to keep only slice-relevant elements
        System.out.println("[SpoonStubbingRunner] Applying SpoonModelSlicer to keep only slice-relevant elements...");
        SpoonModelSlicer slicer = new SpoonModelSlicer();
        slicer.sliceModel(model, descriptor);
        
        // 5) Collect unresolved elements and generate stubs, then compile (generic, path-agnostic)
        // Strategy B: can overwrite slice files (we build from source roots, not from existing slice)
        return performStubbing(model, f, slicedSrcDir, launcher, classpathJars, descriptor, false);
    }
    
    /**
     * OLD METHOD - DEPRECATED: Try stubbing with context (source roots added to model).
     * This method is kept for backward compatibility but should not be used in the new pipeline.
     * The new Strategy B uses tryStubbingWithFullContext instead.
     * @deprecated Use tryStubbingWithFullContext instead
     */
    @Deprecated
    private StubbingResult tryStubbingWithContext(Path slicedSrcDir, List<Path> classpathJars) throws Exception {
        System.out.println("[SpoonStubbingRunner] Attempting stubbing with context (source roots)...");
        
        // Compute FQNs from slice directory first (to filter duplicates)
        Set<String> slicedFqns = computeSlicedTypeFqns(slicedSrcDir);
        System.out.println("[SpoonStubbingRunner] Found " + slicedFqns.size() + " FQNs in slice directory");
        
        // Track excluded classes across retries
        Set<String> excludedFqns = new HashSet<>();
        int maxRetries = 3;
        int attempt = 0;
        
        while (attempt < maxRetries) {
            attempt++;
            if (attempt > 1) {
                System.out.println("[SpoonStubbingRunner] Retry attempt " + attempt + " (excluding " + excludedFqns.size() + " duplicate classes)...");
            }
            
            // 1) Configure Spoon for Java 11 (fresh launcher each attempt)
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
            
            // 2) Add source roots with FQN filtering (exclude files that define types already in slice OR excluded)
            Set<String> fqnsToExclude = new HashSet<>(slicedFqns);
            fqnsToExclude.addAll(excludedFqns);
            
            if (!sourceRoots.isEmpty() && !fqnsToExclude.isEmpty()) {
                System.out.println("[SpoonStubbingRunner] Adding source roots with FQN filtering...");
                addSourceRootsWithFqnFilter(launcher, fqnsToExclude);
            } else if (!sourceRoots.isEmpty()) {
                System.out.println("[SpoonStubbingRunner] Warning: Could not compute slice FQNs, adding all source roots (may cause duplicates)");
                for (Path root : sourceRoots) {
                    if (root != null && Files.exists(root) && Files.isDirectory(root)) {
                        launcher.addInputResource(root.toString());
                        System.out.println("[SpoonStubbingRunner] Added source root: " + root);
                    }
                }
            }

            // 3) Always add the sliced directory last (takes precedence)
            launcher.addInputResource(slicedSrcDir.toString());
            System.out.println("[SpoonStubbingRunner] Added slice directory: " + slicedSrcDir);

            // 4) Build model with context
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
                        System.err.println("[SpoonStubbingRunner] WARNING: Model built but contains 0 types");
                        model = null;
                        f = null;
                    } else {
                        System.out.println("[SpoonStubbingRunner] Model built successfully with " + testTypes.size() + " types (with context)");
                    }
                } catch (StackOverflowError e) {
                    System.err.println("[SpoonStubbingRunner] StackOverflowError checking model types");
                    model = null;
                    f = null;
                }
            } catch (ModelBuildingException e) {
                System.err.println("[SpoonStubbingRunner] ModelBuildingException on attempt " + attempt + ": " + e.getMessage());
                
                // Extract duplicate class names from exception
                Set<String> duplicates = extractDuplicateClasses(e, slicedFqns);
                if (!duplicates.isEmpty()) {
                    System.err.println("[SpoonStubbingRunner] Detected " + duplicates.size() + " duplicate classes: " + duplicates);
                    excludedFqns.addAll(duplicates);
                    
                    if (attempt < maxRetries) {
                        System.err.println("[SpoonStubbingRunner] Will retry with excluded classes...");
                        continue; // Retry with excluded classes
                    } else {
                        System.err.println("[SpoonStubbingRunner] Max retries reached, giving up");
                        return new StubbingResult(0, false);
                    }
                } else {
                    // Can't extract duplicates, try partial model
                    System.err.println("[SpoonStubbingRunner] Could not extract duplicate classes, trying partial model...");
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
                                if (attempt < maxRetries) {
                                    continue; // Retry
                                } else {
                                    return new StubbingResult(0, false);
                                }
                            }
                        } catch (Throwable t) {
                            if (attempt < maxRetries) {
                                continue; // Retry
                            } else {
                                return new StubbingResult(0, false);
                            }
                        }
                    } else {
                        if (attempt < maxRetries) {
                            continue; // Retry
                        } else {
                            return new StubbingResult(0, false);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[SpoonStubbingRunner] Error building model with context on attempt " + attempt + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    // Try to extract duplicates from any exception message
                    Set<String> duplicates = extractDuplicateClassesFromMessage(e.getMessage(), slicedFqns);
                    if (!duplicates.isEmpty()) {
                        excludedFqns.addAll(duplicates);
                        System.err.println("[SpoonStubbingRunner] Extracted " + duplicates.size() + " duplicate classes from error message, retrying...");
                        continue;
                    }
                }
                if (attempt >= maxRetries) {
                    throw new RuntimeException("Could not build model even with context after " + maxRetries + " attempts", e);
                }
                continue; // Retry
            }
            
            // If we got here, model building succeeded
            if (model != null && f != null) {
                // 5) Collect unresolved elements and generate stubs, then compile
                // Note: This is the deprecated path, so we pass null for descriptor (backward compatibility)
                // Deprecated method - use preserveExistingSliceFiles=false for backward compatibility
                // (old behavior allowed overwriting)
                return performStubbing(model, f, slicedSrcDir, launcher, classpathJars, null, false);
            }
        }
        
        // If we exhausted all retries
        System.err.println("[SpoonStubbingRunner] ERROR: Could not build model even with context after " + maxRetries + " attempts");
        return new StubbingResult(0, false);
    }
    
    /**
     * Extract duplicate class names from ModelBuildingException.
     * Looks for patterns like "class X already exists" or "duplicate class X".
     */
    private Set<String> extractDuplicateClasses(ModelBuildingException e, Set<String> slicedFqns) {
        Set<String> duplicates = new HashSet<>();
        String message = e.getMessage();
        if (message == null) {
            return duplicates;
        }
        
        // Try to extract from exception message
        duplicates.addAll(extractDuplicateClassesFromMessage(message, slicedFqns));
        
        // Also check cause
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) {
            duplicates.addAll(extractDuplicateClassesFromMessage(cause.getMessage(), slicedFqns));
        }
        
        return duplicates;
    }
    
    /**
     * Extract duplicate class names from an error message string.
     */
    private Set<String> extractDuplicateClassesFromMessage(String message, Set<String> slicedFqns) {
        Set<String> duplicates = new HashSet<>();
        if (message == null) {
            return duplicates;
        }
        
        // Pattern 1: "class X already exists" or "type X already exists"
        java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile(
            "(?:class|type)\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\s+already\\s+exists",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher1 = pattern1.matcher(message);
        while (matcher1.find()) {
            String className = matcher1.group(1);
            // Only add if it's not already in slice (we want to exclude it from context)
            if (!slicedFqns.contains(className)) {
                duplicates.add(className);
            }
        }
        
        // Pattern 2: "duplicate class X" or "duplicate type X"
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile(
            "duplicate\\s+(?:class|type)\\s+([a-zA-Z_][a-zA-Z0-9_.]*)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher2 = pattern2.matcher(message);
        while (matcher2.find()) {
            String className = matcher2.group(1);
            if (!slicedFqns.contains(className)) {
                duplicates.add(className);
            }
        }
        
        return duplicates;
    }
    
    /**
     * Perform the actual stubbing: collect unresolved elements and generate stubs, then compile.
     * This is shared between slice-only and context attempts.
     * @return StubbingResult with number of stubs created and compilation success status
     */
    /**
     * Perform stubbing: collect missing elements, generate stubs, print to disk, and compile.
     * 
     * @param preserveExistingSliceFiles If true (Strategy A), do not overwrite existing .java files
     *                                   in slicedSrcDir that were written by JavaParser.
     *                                   If false (Strategy B), allow overwriting (we build from source roots).
     */
    private StubbingResult performStubbing(CtModel model, Factory f, Path slicedSrcDir, Launcher launcher, List<Path> classpathJars, SliceDescriptor descriptor, boolean preserveExistingSliceFiles) throws Exception {
        // Get slice type FQNs from descriptor (if available) or compute from model
        Set<String> sliceTypeFqns;
        if (descriptor != null) {
            sliceTypeFqns = descriptor.sliceTypeFqns;
            System.out.println("[SpoonStubbingRunner] Using " + sliceTypeFqns.size() + " slice types from SliceDescriptor");
        } else {
            // Fallback: compute from model (backward compatibility)
            sliceTypeFqns = computeSliceTypeFqns(model, slicedSrcDir);
            System.out.println("[SpoonStubbingRunner] Computed " + sliceTypeFqns.size() + " slice types from model (fallback)");
        }
        
        // Create collector with descriptor (path-agnostic)
        SpoonCollector collector = new SpoonCollector(f, cfg, descriptor);
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

        // 7) Generate stubs (separate handlers per kind) - path-agnostic
        SpoonStubber stubber = new SpoonStubber(f, cfg, descriptor, plans.annotationAttributes);
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
        // IMPORTANT:
        // In Strategy A (slice-only), the JavaParser-generated slice in slicedSrcDir is canonical.
        // Do not overwrite existing slice .java files with Spoon's view of those types.
        // Only add new stub types here.
        // In Strategy B (full context), we build from source roots and can overwrite.
        var env = launcher.getEnvironment();
        env.setPrettyPrinterCreator(() -> new DefaultJavaPrettyPrinter(env));
        prettyPrintSliceTypesOnly(f, sliceTypeFqns, slicedSrcDir, preserveExistingSliceFiles);
        
        // 9) Also write stub types to disk (types that were created but are not slice types)
        Set<String> stubTypeFqns = stubber.getCreatedTypes();
        stubTypeFqns.removeAll(sliceTypeFqns); // Remove slice types (already written)
        prettyPrintStubTypes(f, stubTypeFqns, slicedSrcDir);
        
        // 10) Compile the generated stubs
        System.out.println("[SpoonStubbingRunner] Compiling generated stubs...");
        boolean compilationSuccess = compileStubs(slicedSrcDir, classpathJars);
        
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
     * Add source roots with FQN filtering - exclude files that define types already in slice or excluded.
     * This prevents duplicate type definitions while still providing context for type resolution.
     * More aggressive: skips files that contain ANY excluded type (not just ALL).
     */
    private void addSourceRootsWithFqnFilter(Launcher launcher, Set<String> fqnsToExclude) {
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
                                // Check if ANY FQN in this file is in the exclusion list
                                // More aggressive: skip file if it contains ANY excluded type
                                boolean hasExcluded = fileFqns.stream().anyMatch(fqnsToExclude::contains);
                                
                                if (hasExcluded) {
                                    // Skip - file contains at least one excluded type (would cause duplicates)
                                    skippedFiles.incrementAndGet();
                                } else {
                                    // Add - contains only types not in exclusion list (for context/resolution)
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
     * 
     * @param preserveExistingFiles If true, skip printing types whose .java files already exist
     *                              (preserves JavaParser's canonical slice in Strategy A).
     *                              If false, allow overwriting (Strategy B builds from source roots).
     */
    private void prettyPrintSliceTypesOnly(Factory f, Set<String> sliceTypeFqns, Path slicedSrcDir, boolean preserveExistingFiles) {
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
                
                // IMPORTANT: In Strategy A, preserve existing JavaParser slice files.
                // JavaParser's TypeExtractor writes the original sliced code, which is the source of truth.
                // If we overwrite it with Spoon's model version, we might introduce errors
                // (e.g., incorrect return types inferred from an incomplete slice-only model).
                // Strategy B can overwrite because it builds from full source roots.
                if (preserveExistingFiles && Files.exists(outputPath)) {
                    // File already exists from JavaParser slicing - don't overwrite it
                    // The original slice is more accurate than what Spoon might infer from an incomplete model
                    continue;
                }
                
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
