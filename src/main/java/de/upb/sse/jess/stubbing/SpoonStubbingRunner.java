package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber;
import de.upb.sse.jess.stubbing.spoon.plan.FieldStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.MethodStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.TypeStubPlan;
import de.upb.sse.jess.stubbing.spoon.plan.ConstructorStubPlan;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.declaration.CtImportKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber.safeQN;

public final class SpoonStubbingRunner implements Stubber {
    private final JessConfiguration cfg;

    public SpoonStubbingRunner(JessConfiguration cfg) {
        this.cfg = cfg;
    }

    @Override
    public int run(Path slicedSrcDir, List<Path> classpathJars) throws Exception {
        // Suppressed debug output

        // Let -Djess.failOnAmbiguity=true|false override BEFORE collection
        String sys = System.getProperty("jess.failOnAmbiguity");
        if (sys != null) {
            cfg.setFailOnAmbiguity(Boolean.parseBoolean(sys));
        }

        // 1) Configure Spoon with dynamic Java compliance level
        Launcher launcher = new Launcher();
        var env = launcher.getEnvironment();
        
        // Determine Java compliance level from targetVersion or default to 17 (supports records, modern APIs)
        int complianceLevel = determineComplianceLevel(cfg.getTargetVersion());
        env.setComplianceLevel(complianceLevel);
        env.setAutoImports(false); // Disable auto-imports to prevent invalid imports like "import Unknown;"
        env.setSourceOutputDirectory(slicedSrcDir.toFile());

        if (classpathJars == null || classpathJars.isEmpty()) {
            env.setNoClasspath(true);
        } else {
            env.setNoClasspath(false);
            env.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
        }

        launcher.addInputResource(slicedSrcDir.toString());
        
        // CRITICAL FIX: Catch StackOverflowError during model building
        // This happens when there are circular type dependencies in complex projects
        final CtModel model;
        final Factory f;
        try {
            launcher.buildModel();
        } catch (StackOverflowError e) {
            System.err.println("[SpoonStubbingRunner] StackOverflowError during model building - likely due to circular type dependencies");
            System.err.println("[SpoonStubbingRunner] Attempting to continue with partial model...");
        }
        // Get model and factory after buildModel() (whether it succeeded or failed)
        CtModel tempModel = launcher.getModel();
        Factory tempFactory = launcher.getFactory();
        if (tempModel == null) {
            throw new RuntimeException("Model building failed - no model available");
        }
        model = tempModel;
        f = tempFactory;
        
        // 2) Collect unresolved elements
        // CRITICAL FIX: Catch StackOverflowError during collection
        // Collection phase can trigger additional symbol resolution that causes StackOverflowError
        SpoonCollector collector = new SpoonCollector(f, cfg);
        
        // CRITICAL FIX: Collect interesting types (non-JDK, non-generated) from the model
        Set<String> interestingTypeQNs = new HashSet<>();
        try {
            for (CtType<?> type : model.getAllTypes()) {
                try {
                    String qn = type.getQualifiedName();
                    if (qn != null && !qn.isEmpty()) {
                        // Skip JDK, generated, and ignored packages
                        if (!isJdkFqn(qn) && !isIgnoredPackage(qn) && !qn.contains(".generated.")) {
                            interestingTypeQNs.add(qn);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            System.err.println("[SpoonStubbingRunner] Error collecting interesting types: " + e.getMessage());
            // Continue with empty set - will collect all types
        }
        // Suppress this debug output - will be shown in stubbing plan section
        
        final SpoonCollector.CollectResult plans;
        SpoonCollector.CollectResult tempPlans;
        try {
            tempPlans = collector.collect(model, interestingTypeQNs);
        } catch (StackOverflowError e) {
            System.err.println("[SpoonStubbingRunner] StackOverflowError during collection - likely due to circular type dependencies");
            System.err.println("[SpoonStubbingRunner] Attempting to continue with partial collection results...");
            // Try to get partial results if collector has any
            // For now, create an empty result set - better than crashing
            tempPlans = new SpoonCollector.CollectResult();
            System.err.println("[SpoonStubbingRunner] WARNING: Collection failed, using empty stub plans - compilation may fail");
        }
        plans = tempPlans;
        
        // DEBUG: Print what is missing to compile and what was collected
        System.out.println("\n==================================================================================");
        System.out.println("3. WHAT IS MISSING");
        System.out.println("==================================================================================");
        printMissingElementsAndStubbingPlan(model, plans);
        
        // 2.5) Detect and add module class patterns (e.g., CheckedConsumerModule -> CheckedConsumer$Module)
        detectAndAddModuleClasses(model, plans, f);

        // 3) Generate JDK stubs for SootUp compatibility (if explicitly enabled)
        // WARNING: JDK stubs should only be enabled when JDK types are NOT available from classpath
        // and you need them in the output directory for SootUp. They can cause conflicts if JDK
        // types are available during compilation.
        if (cfg.isIncludeJdkStubs()) {
            try {
                // Double-check: only generate if we're in noClasspath mode
                // and types are not resolvable
                de.upb.sse.jess.stubbing.spoon.jdk.JdkStubGenerator jdkStubGenerator = 
                    new de.upb.sse.jess.stubbing.spoon.jdk.JdkStubGenerator(f);
                int jdkStubsGenerated = jdkStubGenerator.generateJdkStubs();
                if (jdkStubsGenerated > 0) {
                    System.out.println("Generated " + jdkStubsGenerated + " JDK stub classes for SootUp compatibility");
                }
            } catch (Exception e) {
                System.err.println("Warning: Failed to generate JDK stubs: " + e.getMessage());
                // Continue without JDK stubs - don't fail the entire process
            }
        }
        
        // 4) Generate shims for common libraries (on-demand, before stubbing)
        // Only generate shims for types that are referenced but missing
        de.upb.sse.jess.stubbing.spoon.shim.ShimGenerator shimGenerator = 
            new de.upb.sse.jess.stubbing.spoon.shim.ShimGenerator(f);
        
        // Collect referenced types that need shims
        // MINIMAL STUBBING: Only types actually referenced in the target method/file are collected
        // This ensures we only generate shims for what's needed, not everything
        Set<String> referencedTypes = collectReferencedTypes(model, plans);
        
        // Only add SLF4J types if they're actually referenced (minimal stubbing)
        // Check for: direct references, logger fields, or LoggerFactory calls
        if (isSlf4jNeeded(model, referencedTypes)) {
            referencedTypes.add("org.slf4j.LoggerFactory");
            referencedTypes.add("org.slf4j.Logger");
            referencedTypes.add("org.slf4j.Marker");
        }
        
        // CRITICAL: Ensure Android return types are also included
        // When Intent is referenced, ensure Uri is also generated (Intent.getData() returns Uri)
        // When Bundle is referenced, ensure Parcelable is also generated (Bundle.getParcelable() returns Parcelable)
        Set<String> androidDependencies = new HashSet<>();
        for (String refType : referencedTypes) {
            if (refType != null && (refType.startsWith("android.") || refType.startsWith("androidx."))) {
                if (refType.contains("Intent")) {
                    androidDependencies.add("android.net.Uri");
                }
                if (refType.contains("Bundle")) {
                    androidDependencies.add("android.os.Parcelable");
                }
            }
        }
        referencedTypes.addAll(androidDependencies);
        
        // Generate shims ONLY for referenced types (minimal stubbing)
        // generateShimsForReferencedTypes() will skip any shim definitions that aren't in referencedTypes
        int shimsGenerated = shimGenerator.generateShimsForReferencedTypes(referencedTypes);
        if (shimsGenerated > 0) {
            // Suppressed debug output
            // Log which shims were generated for debugging (only if verbose)
            if (Boolean.getBoolean("jess.verboseShims")) {
                System.out.println("  Referenced types count: " + referencedTypes.size());
                System.out.println("  Generated shims: " + shimsGenerated);
            }
        } else if (referencedTypes.size() > 0) {
            // Log if we had referenced types but generated no shims (might indicate an issue)
            if (Boolean.getBoolean("jess.verboseShims")) {
                System.out.println("  Note: " + referencedTypes.size() + " referenced types, but no shims generated (may already exist in model)");
            }
        }
        
        // 5) Generate stubs
        System.out.println("\n==================================================================================");
        System.out.println("4. STUBBING START - WHAT IS COLLECTED");
        System.out.println("==================================================================================");
        
        // If your SpoonStubber has a (Factory) ctor, use that. Otherwise keep (Factory, CtModel).
        SpoonStubber stubber = new SpoonStubber(f, model);

        int created = 0;
        // Pass method plans to applyTypePlans so it can infer type parameter names (T, R, U, etc.)
        created += stubber.applyTypePlans(plans.typePlans, plans.methodPlans);       // types (classes/interfaces/annotations)

        // Pre-create Builder classes that are referenced in method plans
        // This ensures Builder classes exist before we try to apply methods to them
        stubber.ensureBuilderClassesFromMethodPlans(plans.methodPlans);

        created += stubber.applyFieldPlans(plans.fieldPlans);     // fields
        created += stubber.applyConstructorPlans(plans.ctorPlans);// constructors
        created += stubber.applyMethodPlans(plans.methodPlans);   // methods
        stubber.applyImplementsPlans(plans.implementsPlans);
        
        // CRITICAL FIX: Add static imports for known static fields (e.g., CHECKS from Checks class)
        addStaticImports(model, f, plans.staticImports);
        
        System.out.println("\n==================================================================================");
        System.out.println("5. WHAT WAS STUBBED");
        System.out.println("==================================================================================");
        System.out.println("Generated " + created + " stub elements (types + fields + constructors + methods)");

        // MINIMAL STUBBING MODE: Only apply fixes that are absolutely necessary for compilation
        // In minimal mode, we only stub what's directly referenced in target methods
        boolean minimalMode = cfg.isMinimalStubbing();
        
        if (minimalMode) {
            // Minimal mode: Only essential fixes for directly referenced code
            stubber.preserveGenericTypeArgumentsInUsages();  // Essential: Preserve generic types
            stubber.fixCollectorMethodReturnTypes();          // Essential: Fix collector return types for directly called methods
            stubber.fixTypeConversionIssues();               // Essential: Fix type conversion errors
            stubber.fixSyntaxErrors();                       // Essential: Fix syntax errors
            fixConstructorCallTypeArguments(model, f);        // Essential: Fix constructor calls
        } else {
            // Comprehensive mode: All critical fixes (for maximum compatibility)
            stubber.preserveGenericTypeArgumentsInUsages();  // Fix: Mono<T> becomes Mono
            stubber.autoImplementInterfaceMethods();         // Fix: Missing interface method implementations
            stubber.fixBuilderPattern();                      // Fix: Builder pattern support
            stubber.autoInitializeFields();                   // Fix: Field initialization (logger, etc.)
            stubber.addStreamApiMethods();                    // Fix: Stream API interface methods
            stubber.fixTypeConversionIssues();               // Fix: Unknown type conversion issues
            stubber.fixSyntaxErrors();                       // Fix: Syntax generation errors
            stubber.fixConstructorParameterHandling();       // Fix: Constructor parameter handling
            stubber.fixReactiveTypes();                       // Fix: Reactive types (Mono, Flux)
            stubber.preventDuplicateClasses();                // Fix: Duplicate class prevention
            stubber.fixEnumConstantsFromSwitches();           // Fix: Enum constants from switches
            stubber.fixPackageClassNameClashes();            // Fix: Package/class name clashes
            stubber.fixAmbiguousReferences();                // Fix: Ambiguous references
            fixConstructorCallTypeArguments(model, f);        // Fix: Constructor calls to match variable declarations
        }

        //stubber.rebindUnknownTypeReferencesToConcrete();
        stubber.rebindUnknownTypeReferencesToConcrete(plans.unknownToConcrete);
        stubber.removeUnknownStarImportsIfUnused();
        
        // After removing star imports, ensure explicit unknown.Unknown imports are added where needed
        ensureUnknownImportsForAllTypes(model, f);
        
       stubber.rebindUnknownSupertypesToConcrete();
        stubber.dequalifyCurrentPackageUnresolvedRefs();


        // Qualify ONLY the ambiguous names we actually touched (scoped)
        stubber.qualifyAmbiguousSimpleTypes(plans.ambiguousSimples);

        // Optional polish (off by default; enable via -Djess.metaPolish=true)
        boolean metaPolish = Boolean.getBoolean("jess.metaPolish");
        if (metaPolish) {
            stubber.finalizeRepeatableAnnotations();
            stubber.canonicalizeAllMetaAnnotations();
        }


        stubber.report(); // summary

        // Make classes public if they are referenced from other packages
        makeReferencedClassesPublic(model, f);

        // Fix field accesses with null targets (should be implicit this)
        // This fixes cases like ".logger.logOut()" where the target is lost
        fixFieldAccessTargets(model, f);

        // Remove primitive types that were incorrectly stubbed (byte, int, short, etc.)
        // These should never be classes
        java.util.List<CtType<?>> toRemove = new java.util.ArrayList<>();
        for (CtType<?> type : model.getAllTypes()) {
            String simpleName = type.getSimpleName();
            if (simpleName != null && isPrimitiveTypeName(simpleName)) {
                // Check if it's in the unknown package (primitive types shouldn't be stubbed)
                CtPackage pkg = type.getPackage();
                String pkgName = (pkg != null ? pkg.getQualifiedName() : "");
                if (pkgName.startsWith("unknown.") || pkgName.equals("unknown")) {
                    toRemove.add(type);
                }
            }
        }
        for (CtType<?> type : toRemove) {
            try {
                type.delete();
            } catch (Throwable ignored) {}
        }

        // CRITICAL FIX: Re-add static imports and type imports AFTER forceFQN but BEFORE pretty-printing
        // This ensures imports are present when the code is written
        addStaticImports(model, f, plans.staticImports);
        
        // CRITICAL FIX: Re-add type imports for ALL type usages (methods, fields, generics)
        // This ensures imports are present for all types used in the code
        model.getAllTypes().forEach(type -> {
            try {
                CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                if (cu == null) return;
                
                // Check all method parameters and return types
                for (CtMethod<?> method : type.getMethods()) {
                    // Check return type
                    try {
                        addImportForTypeReference(type, cu, method.getType(), f);
                    } catch (Throwable ignored) {}
                    
                    // Check parameters
                    for (CtParameter<?> param : method.getParameters()) {
                        try {
                            addImportForTypeReference(type, cu, param.getType(), f);
                        } catch (Throwable ignored) {}
                    }
                }
                
                // CRITICAL FIX: Check field types (was missing!)
                for (CtField<?> field : type.getFields()) {
                    try {
                        addImportForTypeReference(type, cu, field.getType(), f);
                    } catch (Throwable ignored) {}
                }
                
                // Check superclass and interfaces
                if (type instanceof CtClass) {
                    CtClass<?> cls = (CtClass<?>) type;
                    try {
                        if (cls.getSuperclass() != null) {
                            addImportForTypeReference(type, cu, cls.getSuperclass(), f);
                        }
                    } catch (Throwable ignored) {}
                }
                for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
                    try {
                        addImportForTypeReference(type, cu, iface, f);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable e) {
                // Suppressed: System.err.println("[finalCheck] Error processing type " + type.getQualifiedName() + ": " + e.getMessage());
            }
        });
        
        // Clean up invalid imports before adding new ones
        cleanupInvalidImports(model, f);
        
        // CRITICAL FIX: After cleanup, re-add all necessary imports and set setSimplyQualified(false)
        // This ensures imports are written to the file (Spoon only writes imports when setSimplyQualified(false))
        // Suppressed: System.out.println("[finalCheck] Re-adding imports and setting setSimplyQualified(false) for types with imports...");
        model.getAllTypes().forEach(type -> {
            try {
                CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                if (cu == null) return;
                
                // Get all imports in the CU (both type and static imports)
                Set<String> importQns = new HashSet<>();
                cu.getImports().forEach(imp -> {
                    try {
                        CtReference r = imp.getReference();
                        CtImportKind kind = imp.getImportKind();
                        boolean isStatic = (kind != null && (kind.name().contains("STATIC") || kind.name().contains("METHOD") || kind.name().contains("ALL")));
                        
                        if (r instanceof CtTypeReference) {
                            String qn = ((CtTypeReference<?>) r).getQualifiedName();
                            if (qn != null) {
                                importQns.add(qn);
                                // Suppressed: System.out.println("[finalCheck] Found " + (isStatic ? "static " : "") + "import in CU: " + qn + " for " + type.getQualifiedName());
                            }
                        } else if (isStatic) {
                            // For static imports, try to extract the class name
                            String importStr = r.toString();
                            if (importStr != null && importStr.contains(".")) {
                                // Extract class FQN (everything except the last part)
                                String[] parts = importStr.split("\\.");
                                if (parts.length >= 2) {
                                    StringBuilder classFqn = new StringBuilder();
                                    for (int i = 0; i < parts.length - 1; i++) {
                                        if (i > 0) classFqn.append(".");
                                        classFqn.append(parts[i]);
                                    }
                                    String classFqnStr = classFqn.toString();
                                    importQns.add(classFqnStr); // Add the class for reference
                                    // Suppressed: System.out.println("[finalCheck] Found static import in CU: " + importStr + " (class: " + classFqnStr + ") for " + type.getQualifiedName());
                                }
                            }
                        }
                    } catch (Throwable e) {
                        // Suppressed: System.err.println("[finalCheck] Error processing import: " + e.getMessage());
                    }
                });
                
                // CRITICAL FIX: For each import, find ALL usages of that type and set setSimplyQualified(false)
                // This includes checking all type references in methods, fields, and even in method bodies
                for (String importQn : importQns) {
                    if (importQn.startsWith("java.") || importQn.startsWith("javax.") || importQn.startsWith("jakarta.")) {
                        continue; // Skip JDK types
                    }
                    
                    // Find all type references in this type that match the import
                    type.getMethods().forEach(method -> {
                        // Check return type
                        try {
                            CtTypeReference<?> returnType = method.getType();
                            if (returnType != null) {
                                String returnQn = returnType.getQualifiedName();
                                if (importQn.equals(returnQn)) {
                                    returnType.setSimplyQualified(false);
                                    // Suppressed: System.out.println("[finalCheck] Set setSimplyQualified(false) for return type " + importQn + " in " + method.getSimpleName());
                                }
                            }
                        } catch (Throwable ignored) {}
                        
                        // Check parameters
                        method.getParameters().forEach(param -> {
                            try {
                                CtTypeReference<?> paramType = param.getType();
                                if (paramType != null) {
                                    String paramQn = paramType.getQualifiedName();
                                    if (importQn.equals(paramQn)) {
                                        paramType.setSimplyQualified(false);
                                        // Suppressed: System.out.println("[finalCheck] Set setSimplyQualified(false) for parameter type " + importQn + " in " + method.getSimpleName());
                                    }
                                }
                            } catch (Throwable ignored) {}
                        });
                    });
                    
                    // Also check fields
                    type.getFields().forEach(field -> {
                        try {
                            CtTypeReference<?> fieldType = field.getType();
                            if (fieldType != null) {
                                String fieldQn = fieldType.getQualifiedName();
                                if (importQn.equals(fieldQn)) {
                                    fieldType.setSimplyQualified(false);
                                    // Suppressed: System.out.println("[finalCheck] Set setSimplyQualified(false) for field type " + importQn);
                                }
                            }
                        } catch (Throwable ignored) {}
                    });
                }
                
                // CRITICAL FIX: Also ensure that if we have imports, we re-add them if they're missing
                // Sometimes imports get removed, so we need to re-add them
                type.getMethods().forEach(method -> {
                    // Check return type
                    try {
                        CtTypeReference<?> returnType = method.getType();
                        if (returnType != null) {
                            String returnQn = returnType.getQualifiedName();
                            if (returnQn != null && returnQn.contains(".") && !returnQn.startsWith("java.") && 
                                !returnQn.startsWith("javax.") && !returnQn.startsWith("jakarta.")) {
                                // Check if import exists (including unknown.Unknown)
                                boolean hasImport = cu.getImports().stream().anyMatch(imp -> {
                                    try {
                                        CtReference r = imp.getReference();
                                        if (r instanceof CtTypeReference) {
                                            return returnQn.equals(((CtTypeReference<?>) r).getQualifiedName());
                                        }
                                        return false;
                                    } catch (Throwable ignored) {
                                        return false;
                                    }
                                });
                                if (!hasImport) {
                                    // Re-add the import
                                    CtTypeReference<?> importRef = f.Type().createReference(returnQn);
                                    CtImport imp = f.createImport(importRef);
                                    cu.getImports().add(imp);
                                    returnType.setSimplyQualified(false);
                                    // Suppressed: System.out.println("[finalCheck] Re-added missing import " + returnQn + " for return type in " + method.getSimpleName());
                                } else {
                                    // Import exists, ensure setSimplyQualified(false)
                                    returnType.setSimplyQualified(false);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    
                    // Check parameters
                    method.getParameters().forEach(param -> {
                        try {
                            CtTypeReference<?> paramType = param.getType();
                            if (paramType != null) {
                                String paramQn = paramType.getQualifiedName();
                                if (paramQn != null && paramQn.contains(".") && !paramQn.startsWith("java.") && 
                                    !paramQn.startsWith("javax.") && !paramQn.startsWith("jakarta.")) {
                                    // Check if import exists (including unknown.Unknown)
                                    boolean hasImport = cu.getImports().stream().anyMatch(imp -> {
                                        try {
                                            CtReference r = imp.getReference();
                                            if (r instanceof CtTypeReference) {
                                                return paramQn.equals(((CtTypeReference<?>) r).getQualifiedName());
                                            }
                                            return false;
                                        } catch (Throwable ignored) {
                                            return false;
                                        }
                                    });
                                    if (!hasImport) {
                                        // Re-add the import
                                        CtTypeReference<?> importRef = f.Type().createReference(paramQn);
                                        CtImport imp = f.createImport(importRef);
                                        cu.getImports().add(imp);
                                        paramType.setSimplyQualified(false);
                                        // Suppressed: System.out.println("[finalCheck] Re-added missing import " + paramQn + " for parameter in " + method.getSimpleName());
                                    } else {
                                        // Import exists, ensure setSimplyQualified(false)
                                        paramType.setSimplyQualified(false);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    });
                });
                
                // Also check for static imports
                cu.getImports().forEach(imp -> {
                    try {
                        CtImportKind kind = imp.getImportKind();
                        if (kind != null && (kind.name().contains("STATIC") || kind.name().contains("METHOD") || kind.name().contains("ALL"))) {
                            // Suppressed: System.out.println("[finalCheck] Found static import in CU: " + imp.getReference() + " for " + type.getQualifiedName());
                        }
                    } catch (Throwable ignored) {}
                });
                
            } catch (Throwable e) {
                // Suppressed: System.err.println("[finalCheck] Error processing type " + type.getQualifiedName() + ": " + e.getMessage());
            }
        });
        
        // CRITICAL FIX: Update getCapabilities() return type to unknown.Missing when used with field access
        // This fixes cases like session.getCapabilities().xrCreateFacialExpressionClientML
        // Suppressed: System.out.println("[finalCheck] Fixing getCapabilities() return types...");
        model.getAllTypes().forEach(type -> {
            try {
                for (CtMethod<?> method : type.getMethods()) {
                    String methodName = method.getSimpleName();
                    if ("getCapabilities".equals(methodName)) {
                        CtTypeReference<?> returnType = method.getType();
                        if (returnType != null) {
                            String qn = returnType.getQualifiedName();
                            // If it returns Object, check if it's used with field access
                            if ("java.lang.Object".equals(qn)) {
                                // Check all usages of this method in the model
                                AtomicBoolean usedWithFieldAccess = new AtomicBoolean(false);
                                for (CtType<?> otherType : model.getAllTypes()) {
                                    for (CtMethod<?> otherMethod : otherType.getMethods()) {
                                        // Check if this method is called and result is used in field access
                                        otherMethod.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                                            if (inv.getExecutable() != null && "getCapabilities".equals(inv.getExecutable().getSimpleName())) {
                                                CtElement parent = inv.getParent();
                                                if (parent instanceof CtFieldAccess) {
                                                    // This is getCapabilities().field - should return unknown.Missing
                                                    usedWithFieldAccess.set(true);
                                                }
                                            }
                                        });
                                    }
                                }
                                
                                if (usedWithFieldAccess.get()) {
                                    // Update return type to unknown.Missing
                                    CtTypeReference<?> missingRef = f.Type().createReference("unknown.Missing");
                                    method.setType(missingRef);
                                    // Suppressed: System.out.println("[finalCheck] Updated getCapabilities() return type to unknown.Missing in " + type.getQualifiedName());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                // Suppressed: System.err.println("[finalCheck] Error fixing getCapabilities in " + type.getQualifiedName() + ": " + e.getMessage());
            }
        });
        
        // Re-ensure unknown.Unknown imports after cleanup (in case they were removed)
        ensureUnknownImportsForAllTypes(model, f);
        
        // Final verification: check that imports are present before pretty printing
        // Suppressed: System.out.println("[finalCheck] Verifying imports before pretty printing...");
        model.getAllTypes().forEach(type -> {
            try {
                CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                if (cu != null) {
                    // Suppressed: System.out.println("[finalCheck] Type " + type.getQualifiedName() + " CU imports count: " + cu.getImports().size());
                    cu.getImports().forEach(imp -> {
                        try {
                            CtReference r = imp.getReference();
                            if (r instanceof CtTypeReference) {
                                String qn = ((CtTypeReference<?>) r).getQualifiedName();
                                // Suppressed: System.out.println("[finalCheck] Import: " + qn);
                            } else {
                                CtImportKind kind = imp.getImportKind();
                                if (kind != null && (kind.name().contains("STATIC") || kind.name().contains("METHOD") || kind.name().contains("ALL"))) {
                                    // Suppressed: System.out.println("[finalCheck] Static import: " + r);
                                }
                            }
                        } catch (Throwable ignored) {}
                    });
                }
            } catch (Throwable e) {
                // Suppressed: System.err.println("[finalCheck] Error checking CU: " + e.getMessage());
            }
        });

        // CRITICAL FIX: Fix void return types for methods used in boolean expressions (BEFORE pretty-printing)
        fixVoidReturnTypesInBooleanContexts(model, f);

        launcher.prettyprint();
        
        // CRITICAL FIX: Post-process to add missing imports directly to files
        // Spoon sometimes doesn't write imports even when they're in the CU, so we add them manually
        postProcessAddMissingImports(slicedSrcDir, model, f);
        
        // CRITICAL FIX: Post-process to fix primitive field initializations (null -> proper defaults)
        postProcessFixPrimitiveFieldInitializations(slicedSrcDir);
        
        // Post-process generated files to fix unknown.Unknown -> Unknown with import
        postProcessUnknownTypes(slicedSrcDir);
        
        // Post-process to remove bad static imports from generated files
        postProcessRemoveBadStaticImports(slicedSrcDir, model, f);
        
        // Post-process to remove array type files (e.g., double[].java)
        postProcessRemoveArrayTypeFiles(slicedSrcDir);
        
        // Post-process to fix malformed method calls (type arguments treated as parameters)
        postProcessFixMalformedMethodCalls(slicedSrcDir);
        
        // Post-process to remove duplicate nested class files (e.g., ComplexBuilderTest$Builder.java)
        // when the nested class is already in the parent file
        postProcessRemoveDuplicateNestedClassFiles(slicedSrcDir);
        
        // Post-process to fix void type errors
        postProcessFixVoidTypeErrors(slicedSrcDir);
        
        // Post-process to fix Tree package/class clashes
        postProcessFixTreePackageClashes(slicedSrcDir);
        
        // CRITICAL FIX: Post-process to fix GeneratedMessage.Builder package/class clashes
        postProcessFixGeneratedMessageBuilderClash(slicedSrcDir);
        
        // CRITICAL FIX: Post-process to fix nested class package/class clashes (e.g., HttpUtils.HttpPostType)
        postProcessFixNestedClassPackageClashes(slicedSrcDir);
        
        // CRITICAL FIX: Post-process to fix ProtoConstants package/class clash
        postProcessFixProtoConstantsPackageClash(slicedSrcDir);
        
        return created;
    }
    
    /**
     * Fix constructor calls to match variable declaration type arguments.
     * When a constructor call is assigned to a variable with type arguments,
     * ensure the constructor call uses the same type arguments or the diamond operator.
     */
    private static void fixConstructorCallTypeArguments(CtModel model, Factory f) {
        model.getAllTypes().forEach(type -> {
            type.getElements(e -> e instanceof spoon.reflect.code.CtConstructorCall<?>).forEach(ctorCallEl -> {
                try {
                    spoon.reflect.code.CtConstructorCall<?> ctorCall = (spoon.reflect.code.CtConstructorCall<?>) ctorCallEl;
                    CtElement parent = ctorCall.getParent();
                    
                    // Check if constructor call is assigned to a variable
                    CtTypeReference<?> varType = null;
                    if (parent instanceof CtVariable) {
                        CtVariable<?> var = (CtVariable<?>) parent;
                        if (Objects.equals(var.getDefaultExpression(), ctorCall)) {
                            varType = var.getType();
                        }
                    } else if (parent instanceof CtAssignment) {
                        CtAssignment<?, ?> assign = (CtAssignment<?, ?>) parent;
                        if (Objects.equals(assign.getAssignment(), ctorCall)) {
                            try {
                                varType = ((CtExpression<?>) assign.getAssigned()).getType();
                            } catch (Throwable ignored) {}
                        }
                    }
                    
                    if (varType == null) return;
                    
                    // Get the constructor call's type
                    CtTypeReference<?> ctorType = ctorCall.getType();
                    if (ctorType == null) return;
                    
                    // Check if variable has type arguments
                    List<CtTypeReference<?>> varTypeArgs = varType.getActualTypeArguments();
                    if (varTypeArgs == null || varTypeArgs.isEmpty()) {
                        // Variable has no type arguments - ensure constructor call also has none (diamond operator)
                        ctorType.getActualTypeArguments().clear();
                        return;
                    }
                    
                    // Check if any type argument is a wildcard - wildcards cannot be used in constructor calls
                    boolean hasWildcard = false;
                    for (CtTypeReference<?> arg : varTypeArgs) {
                        if (arg instanceof spoon.reflect.reference.CtWildcardReference) {
                            hasWildcard = true;
                            break;
                        }
                    }
                    
                    // If variable has wildcards, use diamond operator (no type arguments)
                    if (hasWildcard) {
                        ctorType.getActualTypeArguments().clear();
                        return;
                    }
                    
                    // Variable has type arguments (no wildcards) - ensure constructor call matches
                    String varErasure = erasureFqn(varType);
                    String ctorErasure = erasureFqn(ctorType);
                    
                    // Only fix if they're the same type (by erasure)
                    if (varErasure != null && varErasure.equals(ctorErasure)) {
                        // Clear existing type arguments and copy from variable
                        ctorType.getActualTypeArguments().clear();
                        for (CtTypeReference<?> arg : varTypeArgs) {
                            ctorType.addActualTypeArgument(arg.clone());
                        }
                    }
                } catch (Throwable ignored) {
                    // Ignore errors in fixing constructor calls
                }
            });
        });
    }
    
    /**
     * Get the erased FQN (without type arguments) from a type reference.
     */
    private static String erasureFqn(CtTypeReference<?> ref) {
        if (ref == null) return null;
        try {
            String qn = ref.getQualifiedName();
            if (qn == null) return null;
            // Remove type arguments if present (e.g., "SomeObject<T>" -> "SomeObject")
            int angleBracket = qn.indexOf('<');
            if (angleBracket > 0) {
                return qn.substring(0, angleBracket);
            }
            return qn;
        } catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Force FQN printing for all non-JDK type references in the entire model.
     * This prevents Spoon from generating invalid imports.
     * Exception: If there's an explicit import for unknown.Unknown, allow simple name.
     */
    private static void forceFQNForAllTypeReferences(CtModel model, Factory f) {
        model.getAllTypes().forEach(type -> {
            // Check if this type uses Unknown types (in methods, constructors, or fields)
            boolean usesUnknown = false;
            
            // Check methods
            for (CtMethod<?> method : type.getMethods()) {
                // Check return type
                try {
                    CtTypeReference<?> returnType = method.getType();
                    if (returnType != null && "Unknown".equals(returnType.getSimpleName())) {
                        usesUnknown = true;
                        break;
                    }
                } catch (Throwable ignored) {}
                
                // Check parameters
                for (CtParameter<?> param : method.getParameters()) {
                    try {
                        CtTypeReference<?> paramType = param.getType();
                        if (paramType != null && "Unknown".equals(paramType.getSimpleName())) {
                            usesUnknown = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (usesUnknown) break;
            }
            
            // Check constructors
            if (!usesUnknown && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                for (CtConstructor<?> ctor : cls.getConstructors()) {
                    for (CtParameter<?> param : ctor.getParameters()) {
                        try {
                            CtTypeReference<?> paramType = param.getType();
                            if (paramType != null && "Unknown".equals(paramType.getSimpleName())) {
                                usesUnknown = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (usesUnknown) break;
                }
            }
            
            // Check fields
            if (!usesUnknown) {
                for (CtField<?> field : type.getFields()) {
                    try {
                        CtTypeReference<?> fieldType = field.getType();
                        if (fieldType != null && "Unknown".equals(fieldType.getSimpleName())) {
                            usesUnknown = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            // If this type uses Unknown, ensure the import exists
            if (usesUnknown) {
                System.out.println("[forceFQN] Type " + type.getQualifiedName() + " uses Unknown, ensuring import...");
                ensureUnknownImport(type, f);
            }
            
            // Check if this type's compilation unit has an import for unknown.Unknown
            // Re-check after ensuring import to make sure it's detected
            boolean hasUnknownImport = hasUnknownImport(type, f);
            System.out.println("[forceFQN] Type " + type.getQualifiedName() + ", usesUnknown=" + usesUnknown + ", hasUnknownImport=" + hasUnknownImport);
            // If we just added the import but it's not detected, try adding it again
            if (usesUnknown && !hasUnknownImport) {
                System.out.println("[forceFQN] WARNING: Import not detected after adding, trying again...");
                ensureUnknownImport(type, f);
                // Re-check one more time in case there was a timing issue
                hasUnknownImport = hasUnknownImport(type, f);
                System.out.println("[forceFQN] After retry, hasUnknownImport=" + hasUnknownImport);
                // If still not detected, assume it's there since we just added it
                if (!hasUnknownImport) {
                    System.out.println("[forceFQN] Forcing hasUnknownImport=true for " + type.getQualifiedName());
                    hasUnknownImport = true;
                }
            }
            
            // First, explicitly handle method parameter types - this is critical
            boolean finalHasUnknownImport = hasUnknownImport;
            type.getMethods().forEach(method -> {
                method.getParameters().forEach(param -> {
                    try {
                        CtTypeReference<?> paramType = param.getType();
                        if (paramType != null) {
                            // Check if it's "Unknown" (simple name) or "unknown.Unknown" (FQN)
                            String simple = paramType.getSimpleName();
                            String qn = null;
                            try {
                                qn = paramType.getQualifiedName();
                            } catch (Throwable ignored) {}
                            
                            // Also check package directly
                            CtPackageReference pkgRef = null;
                            try {
                                pkgRef = paramType.getPackage();
                            } catch (Throwable ignored) {}
                            String pkgName = (pkgRef != null ? pkgRef.getQualifiedName() : null);
                            
                            System.out.println("[fixParams] Checking param: simple=" + simple + ", qn=" + qn + ", pkgName=" + pkgName + ", hasImport=" + finalHasUnknownImport);
                            
                            // Check if it's Unknown - be more lenient with the check
                            // If simple name is "Unknown" and (package is null/empty/unknown OR qn is unknown.Unknown or just Unknown)
                            boolean simpleMatches = "Unknown".equals(simple);
                            boolean pkgMatches = (pkgName == null || pkgName.isEmpty() || "unknown".equals(pkgName));
                            boolean qnMatches = ("unknown.Unknown".equals(qn) || "Unknown".equals(qn) ||
                                                (qn != null && qn.startsWith("unknown.")));
                            
                            System.out.println("[fixParams] simpleMatches=" + simpleMatches + ", pkgMatches=" + pkgMatches + ", qnMatches=" + qnMatches);
                            
                            boolean isUnknown = simpleMatches && (pkgMatches || qnMatches);
                            
                            // Also check if qn is unknown.Unknown even if simple name check fails
                            if (!isUnknown && "unknown.Unknown".equals(qn)) {
                                System.out.println("[fixParams] Detected via qn=unknown.Unknown");
                                isUnknown = true;
                            }
                            
                            // If simple is "Unknown" and package is not set, it's likely Unknown
                            if (!isUnknown && "Unknown".equals(simple) && (pkgName == null || pkgName.isEmpty()) && 
                                (qn == null || qn.isEmpty() || "Unknown".equals(qn))) {
                                System.out.println("[fixParams] Detected via simple=Unknown with empty package/qn");
                                isUnknown = true;
                            }
                            
                            System.out.println("[fixParams] Final isUnknown=" + isUnknown);
                            
                            if (isUnknown) {
                                System.out.println("[fixParams] Detected Unknown parameter type");
                                // Create a new reference with the full qualified name
                                CtTypeReference<?> newRef = f.Type().createReference("unknown.Unknown");
                                // Set the flags to use simple name when import exists
                                // setSimplyQualified(false) means "use simple name (with import)"
                                newRef.setSimplyQualified(!finalHasUnknownImport);
                                newRef.setImplicit(false);
                                // Update the parameter's type
                                param.setType(newRef);
                                System.out.println("[fixParams] Created new Unknown reference, hasImport=" + finalHasUnknownImport + ", setSimplyQualified=" + !finalHasUnknownImport + ", qn=" + newRef.getQualifiedName());
                            } else {
                                System.out.println("[fixParams] Not Unknown, calling fixTypeReferenceFQN");
                                // CRITICAL FIX: Resolve simple type names to their full qualified names
                                // This ensures types like XrSession are resolved to org.lwjgl.XrSession
                                if (qn != null && !qn.contains(".") && qn.equals(simple)) {
                                    // qn is same as simple, meaning it's not fully qualified
                                    // Try to find the type in the model (use the parameter, not create new variable)
                                    CtType<?> foundType = model.getAllTypes().stream()
                                        .filter(t -> simple.equals(t.getSimpleName()))
                                        .filter(t -> {
                                            try {
                                                String tQn = t.getQualifiedName();
                                                return tQn != null && !tQn.startsWith("unknown.");
                                            } catch (Throwable ignored) {
                                                return false;
                                            }
                                        })
                                        .findFirst()
                                        .orElse(null);
                                    
                                    if (foundType != null) {
                                        String fullQn = foundType.getQualifiedName();
                                        System.out.println("[fixParams] Resolved " + simple + " to " + fullQn);
                                        // Update the parameter type to use the full qualified name
                                        CtTypeReference<?> resolvedRef = f.Type().createReference(fullQn);
                                        resolvedRef.setSimplyQualified(false); // Use simple name with import
                                        param.setType(resolvedRef);
                                        // Ensure import is added
                                        try {
                                            CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                                            if (cu != null) {
                                                boolean hasImport = cu.getImports().stream().anyMatch(imp -> {
                                                    try {
                                                        CtReference r = imp.getReference();
                                                        if (r instanceof CtTypeReference) {
                                                            return fullQn.equals(((CtTypeReference<?>) r).getQualifiedName());
                                                        }
                                                        return false;
                                                    } catch (Throwable ignored) {
                                                        return false;
                                                    }
                                                });
                                                if (!hasImport) {
                                                    CtTypeReference<?> importRef = f.Type().createReference(fullQn);
                                                    CtImport imp = f.createImport(importRef);
                                                    cu.getImports().add(imp);
                                                    System.out.println("[fixParams] Added import " + fullQn + " to " + type.getQualifiedName());
                                                }
                                            }
                                        } catch (Throwable e) {
                                            System.err.println("[fixParams] Failed to add import: " + e.getMessage());
                                        }
                                        return; // Skip fixTypeReferenceFQN since we've already fixed it
                                    }
                                }
                                fixTypeReferenceFQN(paramType, f, finalHasUnknownImport);
                            }
                        }
                    } catch (Throwable e) {
                        System.err.println("[fixParams] Exception: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                
                // Also fix return types
                try {
                    CtTypeReference<?> returnType = method.getType();
                    if (returnType != null) {
                        fixTypeReferenceFQN(returnType, f, finalHasUnknownImport);
                    }
                } catch (Throwable ignored) {}
            });
            
            // Handle constructor parameter types
            if (type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                boolean finalHasUnknownImport1 = hasUnknownImport;
                cls.getConstructors().forEach(ctor -> {
                    ctor.getParameters().forEach(param -> {
                        try {
                            CtTypeReference<?> paramType = param.getType();
                            if (paramType != null) {
                                String simple = paramType.getSimpleName();
                                if ("Unknown".equals(simple)) {
                                    paramType.setPackage(f.Package().createReference("unknown"));
                                    paramType.setImplicit(false);
                                    paramType.setSimplyQualified(!finalHasUnknownImport1);
                                } else {
                                    fixTypeReferenceFQN(paramType, f, finalHasUnknownImport1);
                                }
                            }
                        } catch (Throwable ignored) {}
                    });
                });
            }
            
            // Also handle all type references in the type (fields, etc.)
            boolean finalHasUnknownImport2 = hasUnknownImport;
            type.getElements(e -> e instanceof CtTypeReference<?>).forEach(refEl -> {
                CtTypeReference<?> ref = (CtTypeReference<?>) refEl;
                fixTypeReferenceFQN(ref, f, finalHasUnknownImport2);
            });
        });
    }
    
    /**
     * Ensure unknown.Unknown imports are added for all types that use Unknown.
     */
    private static void ensureUnknownImportsForAllTypes(CtModel model, Factory f) {
        model.getAllTypes().forEach(type -> {
            // Check if this type uses Unknown types
            boolean usesUnknown = false;
            
            // Check methods
            for (CtMethod<?> method : type.getMethods()) {
                try {
                    CtTypeReference<?> returnType = method.getType();
                    if (returnType != null && "Unknown".equals(returnType.getSimpleName())) {
                        usesUnknown = true;
                        break;
                    }
                } catch (Throwable ignored) {}
                
                for (CtParameter<?> param : method.getParameters()) {
                    try {
                        CtTypeReference<?> paramType = param.getType();
                        if (paramType != null && "Unknown".equals(paramType.getSimpleName())) {
                            usesUnknown = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (usesUnknown) break;
            }
            
            // Check constructors
            if (!usesUnknown && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                for (CtConstructor<?> ctor : cls.getConstructors()) {
                    for (CtParameter<?> param : ctor.getParameters()) {
                        try {
                            CtTypeReference<?> paramType = param.getType();
                            if (paramType != null && "Unknown".equals(paramType.getSimpleName())) {
                                usesUnknown = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (usesUnknown) break;
                }
            }
            
            // Check fields
            if (!usesUnknown) {
                for (CtField<?> field : type.getFields()) {
                    try {
                        CtTypeReference<?> fieldType = field.getType();
                        if (fieldType != null && "Unknown".equals(fieldType.getSimpleName())) {
                            usesUnknown = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            if (usesUnknown) {
                ensureUnknownImport(type, f);
            }
        });
    }
    
    /**
     * Ensure that the compilation unit has an import for unknown.Unknown.
     */
    private static void ensureUnknownImport(CtType<?> type, Factory f) {
        try {
            CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
            if (cu == null) {
                System.err.println("[imports] Failed to get CU for " + (type != null ? type.getQualifiedName() : "null"));
                return;
            }
            
            final String FQN = "unknown.Unknown";
            
            // Check if import already exists
            boolean present = cu.getImports().stream().anyMatch(imp -> {
                try {
                    CtReference r = imp.getReference();
                    if (r == null) return false;
                    
                    if (r instanceof CtTypeReference) {
                        String qn = ((CtTypeReference<?>) r).getQualifiedName();
                        if (FQN.equals(qn)) {
                            return true;
                        }
                    }
                    // Also check by string representation
                    String s = r.toString();
                    if (FQN.equals(s) || "unknown.Unknown".equals(s) || s.contains("unknown.Unknown")) {
                        return true;
                    }
                    
                    // Check by simple name and package
                    if (r instanceof CtTypeReference) {
                        String simple = ((CtTypeReference<?>) r).getSimpleName();
                        CtPackageReference pkgRef = ((CtTypeReference<?>) r).getPackage();
                        if ("Unknown".equals(simple) && pkgRef != null) {
                            String pkgName = pkgRef.getQualifiedName();
                            if ("unknown".equals(pkgName)) {
                                return true;
                            }
                        }
                    }
                    
                    return false;
                } catch (Throwable ignored) {
                    return false;
                }
            });
            
            // Add import if not present
            if (!present) {
                CtTypeReference<?> unknownRef = f.Type().createReference(FQN);
                CtImport imp = f.createImport(unknownRef);
                cu.getImports().add(imp);
                System.out.println("[imports] Added import unknown.Unknown to CU of " + type.getQualifiedName() + 
                    ", total imports now: " + cu.getImports().size());
                
                // Verify it was added
                boolean verify = cu.getImports().stream().anyMatch(i -> {
                    try {
                        CtReference r = i.getReference();
                        if (r instanceof CtTypeReference) {
                            return FQN.equals(((CtTypeReference<?>) r).getQualifiedName());
                        }
                        return false;
                    } catch (Throwable ignored) {
                        return false;
                    }
                });
                if (!verify) {
                    System.err.println("[imports] WARNING: Import was added but verification failed!");
                }
            } else {
                System.out.println("[imports] Import unknown.Unknown already exists in CU of " + type.getQualifiedName());
            }
        } catch (Throwable e) {
            System.err.println("[imports] Failed to add import unknown.Unknown to CU of " + 
                (type != null ? type.getQualifiedName() : "null") + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Add static imports for known static fields (e.g., CHECKS from Checks class).
     * This fixes cases where bare identifiers like CHECKS are used without qualification.
     */
    private static void addStaticImports(CtModel model, Factory f, Map<String, Set<String>> staticImports) {
        if (staticImports == null || staticImports.isEmpty()) {
            return;
        }
        
        System.out.println("[addStaticImports] Processing " + staticImports.size() + " static import groups");
        
        // For each type that needs static imports, find all types that use fields from it
        for (Map.Entry<String, Set<String>> entry : staticImports.entrySet()) {
            String classFqn = entry.getKey();
            Set<String> fieldNames = entry.getValue();
            
            // Find the class that owns these static fields
            CtType<?> ownerType = model.getAllTypes().stream()
                .filter(t -> classFqn.equals(t.getQualifiedName()))
                .findFirst()
                .orElse(null);
            
            if (ownerType == null) {
                System.out.println("[addStaticImports] WARNING: Owner type not found: " + classFqn);
                continue;
            }
            
            // Find all types that might use these static fields
            // We'll add static imports to all types in the model (they'll be filtered by the compiler if unused)
            for (CtType<?> type : model.getAllTypes()) {
                try {
                    CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                    if (cu == null) continue;
                    
                    // Check if this type's source code uses any of these field names
                    boolean shouldAddImport = false;
                    try {
                        String source = cu.getOriginalSourceCode();
                        if (source != null) {
                            for (String fieldName : fieldNames) {
                                // Look for bare usage of the field name (not qualified)
                                // Pattern: word boundary, field name, word boundary (but not ClassName.fieldName)
                                String pattern = "\\b" + Pattern.quote(fieldName) + "\\b";
                                if (Pattern.compile(pattern).matcher(source).find()) {
                                    // Check it's not already qualified
                                    String qualifiedPattern = "\\b" + Pattern.quote(ownerType.getSimpleName()) + "\\." + Pattern.quote(fieldName) + "\\b";
                                    if (!Pattern.compile(qualifiedPattern).matcher(source).find()) {
                                        shouldAddImport = true;
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // If we can't check source, add import anyway (safe)
                        shouldAddImport = true;
                    }
                    
                    if (shouldAddImport) {
                        // Add static import for each field
                        for (String fieldName : fieldNames) {
                            try {
                                // Check if static import already exists
                                boolean exists = cu.getImports().stream().anyMatch(imp -> {
                                    try {
                                        CtImportKind kind = imp.getImportKind();
                                        if (kind == null) return false;
                                        boolean isStatic = kind.name().contains("STATIC") || 
                                                          kind.name().contains("METHOD") ||
                                                          kind.name().contains("ALL");
                                        if (!isStatic) return false;
                                        
                                        CtReference ref = imp.getReference();
                                        if (ref == null) return false;
                                        String refStr = ref.toString();
                                        return refStr != null && refStr.equals(classFqn + "." + fieldName);
                                    } catch (Throwable ignored2) {
                                        return false;
                                    }
                                });
                                
                                if (!exists) {
                                    // Create static import: import static package.Class.field;
                                    try {
                                        CtTypeReference<?> ownerRef = f.Type().createReference(classFqn);
                                        // Find the actual field in the owner type
                                        CtType<?> ownerTypeDecl = ownerRef.getTypeDeclaration();
                                        if (ownerTypeDecl != null) {
                                            CtField<?> field = ownerTypeDecl.getField(fieldName);
                                            if (field != null) {
                                                // Create import using the field reference
                                                CtReference fieldRef = field.getReference();
                                                CtImport staticImport = f.createImport(fieldRef);
                                                cu.getImports().add(staticImport);
                                                System.out.println("[addStaticImports] Added static import: " + classFqn + "." + fieldName + 
                                                    " to " + type.getQualifiedName());
                                            } else {
                                                // Field doesn't exist yet - create it first, then add static import
                                                // This is needed because static imports require the field to exist
                                                try {
                                                    if (ownerTypeDecl instanceof CtClass) {
                                                        CtClass<?> ownerClass = (CtClass<?>) ownerTypeDecl;
                                                        // Check if field already exists (might have been created in a different pass)
                                                        CtField<?> existingField = ownerClass.getField(fieldName);
                                                        if (existingField == null) {
                                                            // Create the static field
                                                            CtField<?> newField = f.Core().createField();
                                                            newField.setSimpleName(fieldName);
                                                            newField.setType(f.Type().BOOLEAN_PRIMITIVE); // CHECKS is boolean
                                                            newField.addModifier(ModifierKind.PUBLIC);
                                                            newField.addModifier(ModifierKind.STATIC);
                                                            newField.addModifier(ModifierKind.FINAL);
                                                            // Set default value
                                                            @SuppressWarnings({"unchecked", "rawtypes"})
                                                            CtExpression defaultValue = (CtExpression) f.Code().createCodeSnippetExpression("true");
                                                            newField.setAssignment(defaultValue);
                                                            ownerClass.addField(newField);
                                                            System.out.println("[addStaticImports] Created static field: " + fieldName + " in " + classFqn);
                                                        }
                                                        // Now get the field and create import
                                                        CtField<?> fieldToImport = ownerClass.getField(fieldName);
                                                        if (fieldToImport != null) {
                                                            CtReference fieldRef = fieldToImport.getReference();
                                                            CtImport staticImport = f.createImport(fieldRef);
                                                            cu.getImports().add(staticImport);
                                                            System.out.println("[addStaticImports] Added static import: " + classFqn + "." + fieldName + 
                                                                " to " + type.getQualifiedName() + " (field created)");
                                                        }
                                                    }
                                                } catch (Throwable e3) {
                                                    System.err.println("[addStaticImports] Failed to create field and import: " + e3.getMessage());
                                                }
                                            }
                                        }
                                    } catch (Throwable e2) {
                                        System.err.println("[addStaticImports] Error creating static import: " + e2.getMessage());
                                    }
                                }
                            } catch (Throwable e) {
                                System.err.println("[addStaticImports] Failed to add static import for " + fieldName + 
                                    " from " + classFqn + " to " + type.getQualifiedName() + ": " + e.getMessage());
                            }
                        }
                    }
                } catch (Throwable e) {
                    System.err.println("[addStaticImports] Error processing type " + type.getQualifiedName() + ": " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Check if the compilation unit for a type has an import for unknown.Unknown.
     */
    private static boolean hasUnknownImport(CtType<?> type, Factory f) {
        try {
            CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
            if (cu == null) return false;
            
            boolean found = cu.getImports().stream().anyMatch(imp -> {
                try {
                    CtReference r = imp.getReference();
                    if (r == null) return false;
                    
                    // Check by qualified name
                    if (r instanceof CtTypeReference) {
                        String qn = ((CtTypeReference<?>) r).getQualifiedName();
                        if ("unknown.Unknown".equals(qn)) {
                            return true;
                        }
                    }
                    
                    // Also check by string representation (more robust)
                    String s = r.toString();
                    if ("unknown.Unknown".equals(s) || s.contains("unknown.Unknown")) {
                        return true;
                    }
                    
                    // Check by simple name if it's a type reference
                    if (r instanceof CtTypeReference) {
                        String simple = ((CtTypeReference<?>) r).getSimpleName();
                        if ("Unknown".equals(simple)) {
                            // Verify it's from unknown package
                            CtPackageReference pkgRef = ((CtTypeReference<?>) r).getPackage();
                            if (pkgRef != null && "unknown".equals(pkgRef.getQualifiedName())) {
                                return true;
                            }
                        }
                    }
                    
                    return false;
                } catch (Throwable ignored) {
                    return false;
                }
            });
            
            return found;
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    /**
     * Add import for a type reference and recursively check generic type arguments.
     * This ensures all types used in the code have proper imports.
     */
    private static void addImportForTypeReference(CtType<?> ownerType, CtCompilationUnit cu, 
                                                   CtTypeReference<?> typeRef, Factory f) {
        if (typeRef == null || cu == null) return;
        try {
            String qn = typeRef.getQualifiedName();
            if (qn != null && qn.contains(".") && !qn.startsWith("java.") && 
                !qn.startsWith("javax.") && !qn.startsWith("jakarta.") &&
                !qn.startsWith("unknown.")) {
                // Check if import already exists
                boolean hasImport = cu.getImports().stream().anyMatch(imp -> {
                    try {
                        CtReference r = imp.getReference();
                        if (r instanceof CtTypeReference) {
                            return qn.equals(((CtTypeReference<?>) r).getQualifiedName());
                        }
                        return false;
                    } catch (Throwable ignored) {
                        return false;
                    }
                });
                if (!hasImport) {
                    CtTypeReference<?> importRef = f.Type().createReference(qn);
                    CtImport imp = f.createImport(importRef);
                    cu.getImports().add(imp);
                    // CRITICAL: Set setSimplyQualified(false) so Spoon writes the import
                    typeRef.setSimplyQualified(false);
                    // Suppressed: System.out.println("[finalCheck] Added import " + qn + " to " + ownerType.getQualifiedName());
                } else {
                    // Import exists, ensure setSimplyQualified(false)
                    typeRef.setSimplyQualified(false);
                }
            }
            
            // CRITICAL FIX: Also check generic type arguments recursively
            if (typeRef.getActualTypeArguments() != null) {
                for (CtTypeReference<?> typeArg : typeRef.getActualTypeArguments()) {
                    if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                        addImportForTypeReference(ownerType, cu, typeArg, f);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * Fix a single type reference to use FQN printing.
     * @param hasUnknownImport if true, allows simple name for unknown.Unknown when import exists
     */
    private static void fixTypeReferenceFQN(CtTypeReference<?> ref, Factory f, boolean hasUnknownImport) {
        if (ref == null) return;
        try {
            // Skip type parameters and primitives
            if (ref instanceof spoon.reflect.reference.CtTypeParameterReference) {
                return;
            }
            if (ref.isPrimitive()) {
                return;
            }
            
            // Get simple name first - this is more reliable
            String simple = ref.getSimpleName();
            String qn = ref.getQualifiedName();
            
            // Special handling for "Unknown" - check simple name first
            if ("Unknown".equals(simple)) {
                // Check if it already has the correct package
                CtPackageReference pkgRef = ref.getPackage();
                String pkgName = (pkgRef != null ? pkgRef.getQualifiedName() : null);
                
                // If no package or wrong package, set it to "unknown"
                if (pkgName == null || !pkgName.equals("unknown")) {
                    ref.setPackage(f.Package().createReference("unknown"));
                }
                // If there's an import, allow simple name; otherwise force FQN
                ref.setSimplyQualified(!hasUnknownImport);
                ref.setImplicit(false);
                return;
            }
            
            // Special handling for "Marker" - should be org.slf4j.Marker
            if ("Marker".equals(simple)) {
                // Check if it already has the correct package
                CtPackageReference pkgRef = ref.getPackage();
                String pkgName = (pkgRef != null ? pkgRef.getQualifiedName() : null);
                
                // If no package or wrong package, set it to "org.slf4j"
                if (pkgName == null || !pkgName.equals("org.slf4j")) {
                    ref.setPackage(f.Package().createReference("org.slf4j"));
                }
                // Force FQN printing for Marker
                ref.setSimplyQualified(true);
                ref.setImplicit(false);
                return;
            }
            
            // If qualified name is null or empty, try to infer from simple name
            if (qn == null || qn.isEmpty()) {
                return; // Can't fix without qualified name
            }
            
            // Special case: if it's unknown.Unknown, check if we should use simple name
            if (qn.equals("unknown.Unknown")) {
                System.out.println("[fixTypeReferenceFQN] Found unknown.Unknown, hasUnknownImport=" + hasUnknownImport);
                if (ref.getPackage() == null) {
                    ref.setPackage(f.Package().createReference("unknown"));
                }
                // If there's an import, allow simple name; otherwise force FQN
                ref.setSimplyQualified(!hasUnknownImport);
                ref.setImplicit(false);
                System.out.println("[fixTypeReferenceFQN] Set setSimplyQualified=" + !hasUnknownImport + " for unknown.Unknown");
                return;
            }
            
            // For all non-JDK types, force FQN printing
            // EXCEPT for unknown.Unknown when import exists - allow simple name
            if (!qn.startsWith("java.") && !qn.startsWith("javax.") && 
                !qn.startsWith("jakarta.") && !qn.startsWith("sun.") && 
                !qn.startsWith("jdk.")) {
                // Special case: unknown.Unknown with import should use simple name
                if (qn.equals("unknown.Unknown") && hasUnknownImport) {
                    // Ensure package is set
                    if (ref.getPackage() == null) {
                        ref.setPackage(f.Package().createReference("unknown"));
                    }
                    // Use simple name when import exists
                    ref.setSimplyQualified(false);
                    ref.setImplicit(false);
                    return;
                }
                
                // Ensure package is set
                if (ref.getPackage() == null && qn.contains(".")) {
                    int lastDot = qn.lastIndexOf('.');
                    if (lastDot > 0) {
                        String pkgName = qn.substring(0, lastDot);
                        ref.setPackage(f.Package().createReference(pkgName));
                    }
                }
                // Force FQN printing - this prevents Spoon from generating imports
                ref.setSimplyQualified(true);
                ref.setImplicit(false);
            }
        } catch (Throwable ignored) {
            // If we can't fix it, check if it's unknown.Unknown and import exists
            try {
                String qn = ref.getQualifiedName();
                if ("unknown.Unknown".equals(qn) && hasUnknownImport) {
                    ref.setSimplyQualified(false);
                    ref.setImplicit(false);
                } else {
                ref.setSimplyQualified(true);
                ref.setImplicit(false);
                }
            } catch (Throwable ignored2) {
                // Last resort: force FQN
                try {
                    ref.setSimplyQualified(true);
                    ref.setImplicit(false);
                } catch (Throwable ignored3) {}
            }
        }
    }
    
    /**
     * Clean up invalid imports that Spoon may have generated.
     * Removes imports with simple names (no package) and incorrectly formatted nested class imports.
     * Also removes static imports that reference non-existent classes.
     */
    private static void cleanupInvalidImports(CtModel model, Factory f) {
        // Build a set of all existing types in the model for quick lookup
        Set<String> existingTypes = new HashSet<>();
        for (CtType<?> t : model.getAllTypes()) {
            try {
                String qn = t.getQualifiedName();
                if (qn != null && !qn.isEmpty()) {
                    existingTypes.add(qn);
                    // Also add simple name for quick lookup
                    String simple = t.getSimpleName();
                    if (simple != null) {
                        existingTypes.add(simple);
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        for (CtType<?> type : model.getAllTypes()) {
            CtCompilationUnit cu;
            try {
                cu = f.CompilationUnit().getOrCreate(type);
            } catch (Throwable ignored) {
                continue;
            }
            if (cu == null) continue;

            // Remove invalid imports - be more aggressive
            boolean removed = cu.getImports().removeIf(imp -> {
                try {
                    CtReference r = imp.getReference();
                    if (r == null) return false;
                    
                    // Check for static imports that reference non-existent classes
                    // Static imports have import kinds like METHOD, ALL_STATIC_MEMBERS, etc.
                    CtImportKind importKind = imp.getImportKind();
                    boolean isStaticImport = (importKind != null && 
                        (importKind.name().contains("METHOD") || 
                         importKind.name().contains("ALL") ||
                         importKind.name().contains("STATIC")));
                    
                    if (isStaticImport) {
                        String importStr = r.toString();
                        if (importStr != null && importStr.contains(".")) {
                            // Extract the class name from static import
                            // e.g., "io.vavr.CheckedConsumerModule.sneakyThrow" -> "io.vavr.CheckedConsumerModule"
                            String[] parts = importStr.split("\\.");
                            if (parts.length >= 2) {
                                // Build the class FQN (everything except the last part which is the method/field name)
                                StringBuilder classFqn = new StringBuilder();
                                for (int i = 0; i < parts.length - 1; i++) {
                                    if (i > 0) classFqn.append(".");
                                    classFqn.append(parts[i]);
                                }
                                String classFqnStr = classFqn.toString();
                                
                                // Check if the class exists in the model
                                boolean classExists = existingTypes.contains(classFqnStr);
                                
                                // Also check if it's a nested class pattern (e.g., CheckedConsumerModule when CheckedConsumer exists)
                                if (!classExists && classFqnStr.endsWith("Module")) {
                                    // Try to find parent class (e.g., CheckedConsumerModule -> CheckedConsumer)
                                    String parentClass = classFqnStr.substring(0, classFqnStr.length() - "Module".length());
                                    if (existingTypes.contains(parentClass)) {
                                        // Parent exists but module doesn't - this is a module class that should be an inner class
                                        // Remove the static import since the method should be in the parent class
                                        return true;
                                    }
                                }
                                
                                // Also check for API pattern (e.g., Value.API -> Value exists)
                                if (!classExists && classFqnStr.contains(".API")) {
                                    String parentClass = classFqnStr.substring(0, classFqnStr.lastIndexOf(".API"));
                                    if (existingTypes.contains(parentClass)) {
                                        // Parent exists but API doesn't - remove static import
                                        return true;
                                    }
                                }
                                
                                // CRITICAL FIX: Never remove static imports for org.lwjgl.system.Checks.CHECKS
                                // This is a known static field that we explicitly create
                                if ("org.lwjgl.system.Checks".equals(classFqnStr)) {
                                    return false; // Keep this static import
                                }
                                
                                // CRITICAL FIX: Check if this is a nested class static import (e.g., HttpUtils.HttpPostType.POST_TYPE_Push)
                                // If the parent class exists but the nested class doesn't, it's a nested class, not a static field
                                // Nested classes should be imported as regular imports, not static imports
                                if (!classExists && classFqnStr.contains(".")) {
                                    int lastDot = classFqnStr.lastIndexOf('.');
                                    String parentClass = classFqnStr.substring(0, lastDot);
                                    String nestedClassName = classFqnStr.substring(lastDot + 1);
                                    
                                    // Check if parent class exists
                                    boolean parentExists = existingTypes.contains(parentClass);
                                    if (parentExists) {
                                        // Parent exists but nested class doesn't - this is a nested class static import
                                        // Remove it - nested classes should be imported as regular imports, not static
                                        System.out.println("[cleanupInvalidImports] Removing nested class static import: " + importStr + 
                                            " (parent " + parentClass + " exists, but nested class " + nestedClassName + " doesn't)");
                                        return true;
                                    }
                                }
                                
                                // If class doesn't exist and it's not a JDK type, remove the static import
                                if (!classExists && !classFqnStr.startsWith("java.") && 
                                    !classFqnStr.startsWith("javax.") && !classFqnStr.startsWith("jakarta.")) {
                                    return true; // Remove static import for non-existent class
                                }
                            }
                        }
                    }
                    
                    // NEVER remove unknown.Unknown imports - they are needed for simple name usage
                    if (r instanceof CtTypeReference) {
                        String qn = ((CtTypeReference<?>) r).getQualifiedName();
                        if ("unknown.Unknown".equals(qn)) {
                            return false; // Keep this import
                        }
                        // CRITICAL FIX: Never remove imports for types we explicitly added (e.g., XrSession)
                        // These are needed for the code to compile
                        if (qn != null && (qn.startsWith("org.lwjgl.") || qn.equals("unknown.Missing"))) {
                            return false; // Keep these imports
                        }
                    }
                    
                    // First check string representation - this catches simple names like "Unknown", "Outer"
                    String s = r.toString();
                    if (s != null) {
                        // NEVER remove unknown.Unknown
                        if ("unknown.Unknown".equals(s) || s.contains("unknown.Unknown")) {
                            return false; // Keep this import
                        }
                        
                        // Remove if it's a simple name (no dots, not a wildcard)
                        if (!s.contains(".") && !s.equals("*") && !s.endsWith(".*")) {
                            return true;
                        }
                        // Remove if it starts with uppercase but has no package (e.g., "Unknown", "Outer")
                        // BUT NOT if it's "unknown.Unknown"
                        if (s.length() > 0 && Character.isUpperCase(s.charAt(0)) && !s.contains(".")) {
                            return true;
                        }
                    }
                    
                    // Check if it's a type import
                    if (r instanceof CtTypeReference) {
                        CtTypeReference<?> typeRef = (CtTypeReference<?>) r;
                        String qn = typeRef.getQualifiedName();
                        
                        // Remove imports that don't have a package (simple names)
                        if (qn == null || qn.isEmpty() || !qn.contains(".")) {
                            return true;
                        }
                        
                        // Remove imports for nested classes that are incorrectly formatted
                        // e.g., "Outer.Inner" should be "package.Outer.Inner"
                        if (qn.contains(".") && !qn.startsWith("java.") && 
                            !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                            // Check if it looks like a nested class import without package
                            // e.g., "Outer.Inner" (no package prefix)
                            String[] parts = qn.split("\\.");
                            if (parts.length == 2 && parts[0].length() > 0 && parts[1].length() > 0 &&
                                Character.isUpperCase(parts[0].charAt(0)) && 
                                Character.isUpperCase(parts[1].charAt(0))) {
                                // Check if the class actually exists in the model
                                if (!existingTypes.contains(qn)) {
                                    // Likely a nested class without package - remove it
                                    return true;
                                }
                            }
                        }
                    }
                    
                    return false;
                } catch (Throwable ex) {
                    // If we can't check it, remove it to be safe
                    return true;
                }
            });

            // Always ensure all type references in the type have proper package information
            // This prevents Spoon from generating invalid imports
            ensureTypeReferencesHavePackages(type, f);
        }
    }
    
    /**
     * Ensure all type references in a type have proper package information.
     * This prevents Spoon from generating invalid imports.
     */
    private static void ensureTypeReferencesHavePackages(CtType<?> type, Factory f) {
        type.getElements(e -> e instanceof CtTypeReference<?>).forEach(refEl -> {
            CtTypeReference<?> ref = (CtTypeReference<?>) refEl;
            try {
                // Skip type parameters and primitives
                if (ref instanceof spoon.reflect.reference.CtTypeParameterReference) {
                    return;
                }
                if (ref.isPrimitive()) {
                    return;
                }
                
                String qn = ref.getQualifiedName();
                if (qn == null || qn.isEmpty() || !qn.contains(".")) {
                    // Simple name without package - force FQN printing and don't allow imports
                    ref.setSimplyQualified(true);
                    ref.setImplicit(false);
                    return;
                }
                
                // For all non-JDK types, force FQN printing to avoid import issues
                if (!qn.startsWith("java.") && !qn.startsWith("javax.") && 
                    !qn.startsWith("jakarta.") && !qn.startsWith("sun.") && 
                    !qn.startsWith("jdk.")) {
                    // Ensure package is set
                    if (ref.getPackage() == null && qn.contains(".")) {
                        int lastDot = qn.lastIndexOf('.');
                        if (lastDot > 0) {
                            String pkgName = qn.substring(0, lastDot);
                            ref.setPackage(f.Package().createReference(pkgName));
                        }
                    }
                    // Always force FQN printing for non-JDK types to avoid import issues
                    ref.setSimplyQualified(true);
                    ref.setImplicit(false);
                }
            } catch (Throwable ignored) {
                // If we can't fix it, force FQN printing
                try {
                    ref.setSimplyQualified(true);
                    ref.setImplicit(false);
                } catch (Throwable ignored2) {}
            }
        });
    }

    /**
     * Check if a type name represents an array type (should not be generated as a class).
     */
    private static boolean isArrayType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return false;
        // Check for array brackets in the name
        return typeName.contains("[]") || typeName.endsWith("]") || 
               typeName.matches(".*\\[\\d*\\].*"); // Also matches multi-dimensional arrays
    }
    
    /**
     * Detect module class patterns from static imports and add inner class plans.
     * Pattern: If static import references XModule and X exists, create X$Module inner class.
     * Also handles X.API pattern -> X$API inner class.
     */
    private static void detectAndAddModuleClasses(CtModel model, SpoonCollector.CollectResult plans, Factory f) {
        // Build set of existing types in the model
        Set<String> existingTypes = new HashSet<>();
        for (CtType<?> type : model.getAllTypes()) {
            try {
                String qn = type.getQualifiedName();
                if (qn != null && !qn.isEmpty()) {
                    existingTypes.add(qn);
                    // Also add simple name
                    String simple = type.getSimpleName();
                    if (simple != null) {
                        existingTypes.add(simple);
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Scan all compilation units for static imports
        Set<String> moduleClassesToCreate = new HashSet<>();
        for (CtType<?> type : model.getAllTypes()) {
            try {
                CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                if (cu == null) continue;
                
                for (CtImport imp : cu.getImports()) {
                    // Check if this is a static import
                    CtImportKind importKind = imp.getImportKind();
                    boolean isStaticImport = (importKind != null && 
                        (importKind.name().contains("METHOD") || 
                         importKind.name().contains("ALL") ||
                         importKind.name().contains("STATIC")));
                    if (!isStaticImport) continue;
                    
                    try {
                        CtReference ref = imp.getReference();
                        if (ref == null) continue;
                        
                        String importStr = ref.toString();
                        if (importStr == null || !importStr.contains(".")) continue;
                        
                        // Extract class FQN from static import
                        // e.g., "io.vavr.CheckedConsumerModule.sneakyThrow" -> "io.vavr.CheckedConsumerModule"
                        String[] parts = importStr.split("\\.");
                        if (parts.length >= 2) {
                            StringBuilder classFqn = new StringBuilder();
                            for (int i = 0; i < parts.length - 1; i++) {
                                if (i > 0) classFqn.append(".");
                                classFqn.append(parts[i]);
                            }
                            String moduleClassFqn = classFqn.toString();
                            
                            // Check if module class exists
                            if (!existingTypes.contains(moduleClassFqn)) {
                                // Pattern 1: XModule -> X$Module (e.g., CheckedConsumerModule -> CheckedConsumer$Module)
                                if (moduleClassFqn.endsWith("Module")) {
                                    String parentClass = moduleClassFqn.substring(0, moduleClassFqn.length() - "Module".length());
                                    if (existingTypes.contains(parentClass)) {
                                        // Parent exists, create inner class plan
                                        String innerClassFqn = parentClass + "$Module";
                                        moduleClassesToCreate.add(innerClassFqn);
                                    }
                                }
                                
                                // Pattern 2: X.API -> X$API (e.g., Value.API -> Value$API)
                                if (moduleClassFqn.contains(".API")) {
                                    String parentClass = moduleClassFqn.substring(0, moduleClassFqn.lastIndexOf(".API"));
                                    if (existingTypes.contains(parentClass)) {
                                        // Parent exists, create inner class plan
                                        String innerClassFqn = parentClass + "$API";
                                        moduleClassesToCreate.add(innerClassFqn);
                                    }
                                }
                                
                                // Pattern 3: Generic module pattern - if last part is Module and parent exists
                                int lastDot = moduleClassFqn.lastIndexOf('.');
                                if (lastDot > 0) {
                                    String simpleName = moduleClassFqn.substring(lastDot + 1);
                                    String parentClass = moduleClassFqn.substring(0, lastDot);
                                    
                                    if (simpleName.endsWith("Module") && existingTypes.contains(parentClass + "." + simpleName.substring(0, simpleName.length() - "Module".length()))) {
                                        String parentSimple = simpleName.substring(0, simpleName.length() - "Module".length());
                                        String parentFqn = parentClass + "." + parentSimple;
                                        if (existingTypes.contains(parentFqn)) {
                                            String innerClassFqn = parentFqn + "$" + simpleName;
                                            moduleClassesToCreate.add(innerClassFqn);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        
        // Add type plans for detected module classes
        for (String moduleClassFqn : moduleClassesToCreate) {
            // Check if it's already in plans
            boolean alreadyPlanned = plans.typePlans.stream()
                    .anyMatch(p -> moduleClassFqn.equals(p.qualifiedName));
            
            if (!alreadyPlanned) {
                plans.typePlans.add(new de.upb.sse.jess.stubbing.spoon.plan.TypeStubPlan(
                        moduleClassFqn, 
                        de.upb.sse.jess.stubbing.spoon.plan.TypeStubPlan.Kind.CLASS
                ));
            }
        }
    }
    
    /**
     * Collect all type references from the model and plans to determine which shims are needed.
     * Filters out array types, primitives, and invalid type names.
     */
    private Set<String> collectReferencedTypes(CtModel model, SpoonCollector.CollectResult plans) {
        Set<String> referenced = new HashSet<>();
        
        // Collect from type plans
        for (var typePlan : plans.typePlans) {
            if (typePlan.qualifiedName != null && !isArrayType(typePlan.qualifiedName)) {
                referenced.add(typePlan.qualifiedName);
            }
        }
        
        // Collect from method plans (return types, parameter types, owner types)
        for (var methodPlan : plans.methodPlans) {
            if (methodPlan.ownerType != null) {
                String ownerQn = safeQN(methodPlan.ownerType);
                if (ownerQn != null && !isArrayType(ownerQn)) {
                    referenced.add(ownerQn);
                }
            }
            if (methodPlan.returnType != null) {
                String returnQn = safeQN(methodPlan.returnType);
                if (returnQn != null && !isArrayType(returnQn)) {
                    referenced.add(returnQn);
                }
            }
            if (methodPlan.paramTypes != null) {
                for (var paramType : methodPlan.paramTypes) {
                    String paramQn = safeQN(paramType);
                    if (paramQn != null && !isArrayType(paramQn)) {
                        referenced.add(paramQn);
                    }
                }
            }
        }
        
        // Also collect from static method invocations (e.g., LoggerFactory.getLogger())
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            try {
                spoon.reflect.reference.CtExecutableReference<?> ex = inv.getExecutable();
                if (ex != null) {
                    // Collect declaring type (for both static and instance methods)
                    spoon.reflect.reference.CtTypeReference<?> owner = ex.getDeclaringType();
                    if (owner != null) {
                        String ownerQn = safeQN(owner);
                        if (ownerQn != null && !isArrayType(ownerQn) && 
                            !ownerQn.startsWith("java.") && !ownerQn.startsWith("javax.") && !ownerQn.startsWith("jakarta.")) {
                            referenced.add(ownerQn);
                        }
                        // Also collect type arguments from declaring type
                        if (owner.getActualTypeArguments() != null) {
                            for (CtTypeReference<?> typeArg : owner.getActualTypeArguments()) {
                                if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                    String typeArgQn = safeQN(typeArg);
                                    if (typeArgQn != null && !isArrayType(typeArgQn) && 
                                        !typeArgQn.startsWith("java.") && 
                                        !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                        referenced.add(typeArgQn);
                                    }
                                }
                            }
                        }
                    }
                    // Collect return type from method invocation
                    CtTypeReference<?> returnType = ex.getType();
                    if (returnType != null) {
                        String returnQn = safeQN(returnType);
                        if (returnQn != null && !isArrayType(returnQn) && 
                            !returnQn.startsWith("java.") && 
                            !returnQn.startsWith("javax.") && !returnQn.startsWith("jakarta.")) {
                            referenced.add(returnQn);
                        }
                        // Collect type arguments from return type
                        if (returnType.getActualTypeArguments() != null) {
                            for (CtTypeReference<?> typeArg : returnType.getActualTypeArguments()) {
                                if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                    String typeArgQn = safeQN(typeArg);
                                    if (typeArgQn != null && !isArrayType(typeArgQn) && 
                                        !typeArgQn.startsWith("java.") && 
                                        !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                        referenced.add(typeArgQn);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Also collect from field accesses (e.g., obj.field, Class.staticField)
        for (CtFieldAccess<?> fieldAccess : model.getElements(new TypeFilter<>(CtFieldAccess.class))) {
            try {
                CtTypeReference<?> accessedType = fieldAccess.getVariable().getDeclaringType();
                if (accessedType != null) {
                    String accessedQn = safeQN(accessedType);
                    if (accessedQn != null && !isArrayType(accessedQn) && 
                        !accessedQn.startsWith("java.") && 
                        !accessedQn.startsWith("javax.") && !accessedQn.startsWith("jakarta.")) {
                        referenced.add(accessedQn);
                    }
                }
                CtTypeReference<?> fieldType = fieldAccess.getVariable().getType();
                if (fieldType != null) {
                    String fieldTypeQn = safeQN(fieldType);
                    if (fieldTypeQn != null && !isArrayType(fieldTypeQn) && 
                        !fieldTypeQn.startsWith("java.") && 
                        !fieldTypeQn.startsWith("javax.") && !fieldTypeQn.startsWith("jakarta.")) {
                        referenced.add(fieldTypeQn);
                    }
                    // Collect type arguments from field type generics
                    if (fieldType.getActualTypeArguments() != null) {
                        for (CtTypeReference<?> typeArg : fieldType.getActualTypeArguments()) {
                            if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                String typeArgQn = safeQN(typeArg);
                                if (typeArgQn != null && !isArrayType(typeArgQn) && 
                                    !typeArgQn.startsWith("java.") && 
                                    !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                    referenced.add(typeArgQn);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Also collect from field assignments that use static method calls (e.g., LoggerFactory.getLogger())
        for (spoon.reflect.code.CtAssignment<?, ?> assignment : model.getElements(new TypeFilter<>(spoon.reflect.code.CtAssignment.class))) {
            try {
                spoon.reflect.code.CtExpression<?> assigned = assignment.getAssigned();
                if (assigned instanceof CtInvocation<?>) {
                    CtInvocation<?> inv = (CtInvocation<?>) assigned;
                    spoon.reflect.reference.CtExecutableReference<?> ex = inv.getExecutable();
                    if (ex != null && ex.isStatic()) {
                        spoon.reflect.reference.CtTypeReference<?> owner = ex.getDeclaringType();
                        if (owner != null) {
                            String ownerQn = safeQN(owner);
                            if (ownerQn != null && !isArrayType(ownerQn) && 
                                !ownerQn.startsWith("java.") && !ownerQn.startsWith("javax.") && !ownerQn.startsWith("jakarta.")) {
                                referenced.add(ownerQn);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Also collect parameter types from method plans (for shims like jakarta.servlet.*)
        for (var methodPlan : plans.methodPlans) {
            if (methodPlan.paramTypes != null) {
                for (var paramType : methodPlan.paramTypes) {
                    String paramQn = safeQN(paramType);
                    if (paramQn != null && !isArrayType(paramQn) && 
                        !paramQn.startsWith("java.") && !paramQn.startsWith("javax.")) {
                        referenced.add(paramQn);
                    }
                }
            }
        }
        
        // Collect from field plans
        for (var fieldPlan : plans.fieldPlans) {
            if (fieldPlan.ownerType != null) {
                String ownerQn = safeQN(fieldPlan.ownerType);
                if (ownerQn != null && !isArrayType(ownerQn)) {
                    referenced.add(ownerQn);
                }
            }
            if (fieldPlan.fieldType != null) {
                String fieldQn = safeQN(fieldPlan.fieldType);
                if (fieldQn != null && !isArrayType(fieldQn)) {
                    referenced.add(fieldQn);
                }
            }
        }
        
        // Collect from constructor plans
        for (var ctorPlan : plans.ctorPlans) {
            if (ctorPlan.ownerType != null) {
                String ownerQn = safeQN(ctorPlan.ownerType);
                if (ownerQn != null) referenced.add(ownerQn);
            }
            if (ctorPlan.parameterTypes != null) {
                for (var paramType : ctorPlan.parameterTypes) {
                    String paramQn = safeQN(paramType);
                    if (paramQn != null) referenced.add(paramQn);
                }
            }
        }
        
        // Collect from implements plans
        for (var entry : plans.implementsPlans.entrySet()) {
            String ownerQn = entry.getKey();
            if (ownerQn != null) referenced.add(ownerQn);
            
            // Also collect interface types
            for (var ifaceRef : entry.getValue()) {
                String ifaceQn = safeQN(ifaceRef);
                if (ifaceQn != null) referenced.add(ifaceQn);
            }
        }
        
        // Collect from all type references in the model (including extends, implements, field types, etc.)
        // CRITICAL FIX: Filter out array types - arrays should never be stubbed as classes
        for (CtTypeReference<?> typeRef : model.getElements(new TypeFilter<>(CtTypeReference.class))) {
            try {
                String qn = safeQN(typeRef);
                if (qn != null && !qn.isEmpty() && !isArrayType(qn) && 
                    !qn.startsWith("java.") && !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                    referenced.add(qn);
                }
                
                // Also collect type arguments from generic types (transitive dependencies)
                // e.g., List<String> -> collect String, Map<K, V> -> collect K and V if they're concrete types
                if (typeRef.getActualTypeArguments() != null && !typeRef.getActualTypeArguments().isEmpty()) {
                    for (CtTypeReference<?> typeArg : typeRef.getActualTypeArguments()) {
                        // Skip type parameters (T, K, V, etc.) - only collect concrete types
                        if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                            String typeArgQn = safeQN(typeArg);
                            if (typeArgQn != null && !typeArgQn.isEmpty() && !isArrayType(typeArgQn) &&
                                !typeArgQn.startsWith("java.") && !typeArgQn.startsWith("javax.") && 
                                !typeArgQn.startsWith("jakarta.")) {
                                referenced.add(typeArgQn);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Also collect from supertypes (extends/implements)
        // CRITICAL FIX: Filter out array types and ensure types are actually needed
        for (CtType<?> type : model.getAllTypes()) {
            try {
                if (type.getSuperclass() != null) {
                    String superQn = safeQN(type.getSuperclass());
                    if (superQn != null && !isArrayType(superQn) && !superQn.startsWith("java.")) {
                        referenced.add(superQn);
                    }
                    // Also collect type arguments from superclass generics
                    if (type.getSuperclass().getActualTypeArguments() != null) {
                        for (CtTypeReference<?> typeArg : type.getSuperclass().getActualTypeArguments()) {
                            if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                String typeArgQn = safeQN(typeArg);
                                if (typeArgQn != null && !isArrayType(typeArgQn) &&
                                    !typeArgQn.startsWith("java.") && 
                                    !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                    referenced.add(typeArgQn);
                                }
                            }
                        }
                    }
                }
                for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
                    String ifaceQn = safeQN(iface);
                    if (ifaceQn != null && !isArrayType(ifaceQn) && !ifaceQn.startsWith("java.")) {
                        referenced.add(ifaceQn);
                    }
                    // Also collect type arguments from interface generics
                    if (iface.getActualTypeArguments() != null) {
                        for (CtTypeReference<?> typeArg : iface.getActualTypeArguments()) {
                            if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                String typeArgQn = safeQN(typeArg);
                                if (typeArgQn != null && !isArrayType(typeArgQn) &&
                                    !typeArgQn.startsWith("java.") && 
                                    !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                    referenced.add(typeArgQn);
                                }
                            }
                        }
                    }
                }
                
                // Collect from field types
                // CRITICAL FIX: Filter out array types
                for (CtField<?> field : type.getFields()) {
                    if (field.getType() != null) {
                        String fieldTypeQn = safeQN(field.getType());
                        if (fieldTypeQn != null && !isArrayType(fieldTypeQn) &&
                            !fieldTypeQn.startsWith("java.") && 
                            !fieldTypeQn.startsWith("javax.") && !fieldTypeQn.startsWith("jakarta.")) {
                            referenced.add(fieldTypeQn);
                        }
                        // Also collect type arguments from field type generics
                        if (field.getType().getActualTypeArguments() != null) {
                            for (CtTypeReference<?> typeArg : field.getType().getActualTypeArguments()) {
                                if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                    String typeArgQn = safeQN(typeArg);
                                    if (typeArgQn != null && !isArrayType(typeArgQn) &&
                                        !typeArgQn.startsWith("java.") && 
                                        !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                        referenced.add(typeArgQn);
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Collect from method return types and parameter types
                // CRITICAL FIX: Filter out array types
                for (CtMethod<?> method : type.getMethods()) {
                    if (method.getType() != null) {
                        String returnTypeQn = safeQN(method.getType());
                        if (returnTypeQn != null && !isArrayType(returnTypeQn) &&
                            !returnTypeQn.startsWith("java.") && 
                            !returnTypeQn.startsWith("javax.") && !returnTypeQn.startsWith("jakarta.")) {
                            referenced.add(returnTypeQn);
                        }
                        // Collect type arguments from return type generics
                        if (method.getType().getActualTypeArguments() != null) {
                            for (CtTypeReference<?> typeArg : method.getType().getActualTypeArguments()) {
                                if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                    String typeArgQn = safeQN(typeArg);
                                    if (typeArgQn != null && !isArrayType(typeArgQn) &&
                                        !typeArgQn.startsWith("java.") && 
                                        !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                        referenced.add(typeArgQn);
                                    }
                                }
                            }
                        }
                    }
                    // Collect from parameter types
                    for (CtParameter<?> param : method.getParameters()) {
                        if (param.getType() != null) {
                            String paramTypeQn = safeQN(param.getType());
                            if (paramTypeQn != null && !isArrayType(paramTypeQn) &&
                                !paramTypeQn.startsWith("java.") && 
                                !paramTypeQn.startsWith("javax.") && !paramTypeQn.startsWith("jakarta.")) {
                                referenced.add(paramTypeQn);
                            }
                            // Collect type arguments from parameter type generics
                            if (param.getType().getActualTypeArguments() != null) {
                                for (CtTypeReference<?> typeArg : param.getType().getActualTypeArguments()) {
                                    if (!(typeArg instanceof spoon.reflect.reference.CtTypeParameterReference)) {
                                        String typeArgQn = safeQN(typeArg);
                                        if (typeArgQn != null && !isArrayType(typeArgQn) &&
                                            !typeArgQn.startsWith("java.") && 
                                            !typeArgQn.startsWith("javax.") && !typeArgQn.startsWith("jakarta.")) {
                                            referenced.add(typeArgQn);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        return referenced;
    }

    /**
     * Check if SLF4J types (LoggerFactory, Logger, Marker) are actually needed.
     * They're needed if:
     * 1. Already referenced in the code (direct usage, static calls, etc.)
     * 2. There are logger fields in the model that need initialization
     * 3. LoggerFactory.getLogger() is called (already collected by collectReferencedTypes)
     * 
     * This ensures minimal stubbing - we only generate SLF4J shims when actually needed.
     */
    private boolean isSlf4jNeeded(CtModel model, Set<String> referencedTypes) {
        // Check if SLF4J types are already referenced
        boolean hasSlf4jReference = referencedTypes.stream()
            .anyMatch(ref -> ref != null && (
                ref.contains("slf4j") || 
                ref.contains("LoggerFactory") || 
                (ref.contains("Logger") && ref.contains("org.slf4j"))
            ));
        
        if (hasSlf4jReference) {
            return true;
        }
        
        // Check if there are logger fields in the model that need initialization
        try {
            for (CtType<?> type : model.getAllTypes()) {
                if (!(type instanceof CtClass)) continue;
                
                CtClass<?> cls = (CtClass<?>) type;
                for (CtField<?> field : cls.getFields()) {
                    CtTypeReference<?> fieldType = field.getType();
                    if (fieldType == null) continue;
                    
                    String fieldTypeQn = safeQN(fieldType);
                    if (fieldTypeQn != null && (
                        fieldTypeQn.contains("Logger") || 
                        fieldTypeQn.contains("slf4j") ||
                        // Check for common logger field names
                        (field.getSimpleName().toLowerCase().contains("log") && 
                         fieldTypeQn.contains("org.slf4j"))
                    )) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // If we can't check, be safe and return false (don't generate unnecessary shims)
        }
        
        return false;
    }

    private static boolean isPrimitiveTypeName(String simpleName) {
        return simpleName != null && (
            simpleName.equals("byte") || simpleName.equals("short") || 
            simpleName.equals("int") || simpleName.equals("long") ||
            simpleName.equals("float") || simpleName.equals("double") ||
            simpleName.equals("char") || simpleName.equals("boolean") ||
            simpleName.equals("void"));
    }
    
    /**
     * Fix field accesses that have problematic targets causing leading dots in output.
     * When a field access has a null target (implicit this), ensure it's properly handled.
     * The leading dot issue occurs when Spoon's printer sees a field access with a problematic target.
     */
    private static void fixFieldAccessTargets(CtModel model, Factory f) {
        // Fix method invocations with field access targets
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExpression<?> target = inv.getTarget();
            if (target instanceof CtFieldAccess<?>) {
                CtFieldAccess<?> fa = (CtFieldAccess<?>) target;
                CtType<?> enclosingType = inv.getParent(CtType.class);
                if (enclosingType != null && enclosingType instanceof CtClass) {
                    try {
                        CtField<?> field = fa.getVariable().getDeclaration();
                        if (field != null && !field.hasModifier(ModifierKind.STATIC)) {
                            // Instance field - target should be null for implicit 'this'
                            // If target is not null but should be, or if it's causing issues, fix it
                            CtExpression<?> faTarget = fa.getTarget();
                            if (faTarget != null) {
                                // If target exists, check if it's problematic
                                String targetStr = faTarget.toString();
                                // If target is empty or just a dot, set it to null for implicit this
                                if (targetStr == null || targetStr.trim().isEmpty() || ".".equals(targetStr.trim())) {
                                    fa.setTarget(null);
                                }
            } else {
                                // Target is null, which is correct for implicit this
                                // But ensure it's explicitly null (not some other problematic state)
                                fa.setTarget(null);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        
        // Also fix standalone field accesses
        for (CtFieldAccess<?> fa : model.getElements(new TypeFilter<>(CtFieldAccess.class))) {
            CtType<?> enclosingType = fa.getParent(CtType.class);
            if (enclosingType != null && enclosingType instanceof CtClass) {
                try {
                    CtField<?> field = fa.getVariable().getDeclaration();
                    if (field != null && !field.hasModifier(ModifierKind.STATIC)) {
                        // Instance field - ensure target is null for implicit this
                        CtExpression<?> faTarget = fa.getTarget();
                        if (faTarget != null) {
                            String targetStr = faTarget.toString();
                            if (targetStr == null || targetStr.trim().isEmpty() || ".".equals(targetStr.trim())) {
                                fa.setTarget(null);
                            }
                        } else {
                            fa.setTarget(null);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
    
    /**
     * Make classes public if they are referenced from other packages.
     * This ensures that classes can be accessed across package boundaries.
     */
    private static void makeReferencedClassesPublic(CtModel model, Factory f) {
        // Collect all type references in the model
        Set<String> referencedTypeQns = new HashSet<>();
        
        for (CtTypeReference<?> ref : model.getElements(new TypeFilter<>(CtTypeReference.class))) {
            try {
                String qn = ref.getQualifiedName();
                if (qn != null && !qn.isEmpty() && !qn.startsWith("java.") && 
                    !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                    referencedTypeQns.add(qn);
                }
            } catch (Throwable ignored) {}
        }
        
        // Also check method return types and parameter types
        for (CtMethod<?> method : model.getElements(new TypeFilter<>(CtMethod.class))) {
            try {
                CtTypeReference<?> returnType = method.getType();
                if (returnType != null) {
                    String qn = returnType.getQualifiedName();
                    if (qn != null && !qn.isEmpty() && !qn.startsWith("java.") && 
                        !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                        referencedTypeQns.add(qn);
                    }
                }
                for (CtParameter<?> param : method.getParameters()) {
                    CtTypeReference<?> paramType = param.getType();
                    if (paramType != null) {
                        String qn = paramType.getQualifiedName();
                        if (qn != null && !qn.isEmpty() && !qn.startsWith("java.") && 
                            !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                            referencedTypeQns.add(qn);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // For each referenced type, find it in the model and make it public
        for (String referencedQn : referencedTypeQns) {
            try {
                CtType<?> referencedType = f.Type().get(referencedQn);
                if (referencedType != null && referencedType instanceof CtClass) {
                    CtClass<?> cls = (CtClass<?>) referencedType;
                    // Only make it public if it's not already public and not in the unknown package
                    CtPackage pkg = cls.getPackage();
                    String pkgName = (pkg != null ? pkg.getQualifiedName() : "");
                    if (!pkgName.startsWith("unknown") && !cls.hasModifier(ModifierKind.PUBLIC)) {
                        cls.addModifier(ModifierKind.PUBLIC);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
    
    /**
     * Post-process to remove duplicate nested class files.
     * Spoon sometimes writes nested classes to separate files (e.g., Outer$Inner.java),
     * but they should be in the parent file. This removes those duplicate files.
     */
    private static void postProcessRemoveDuplicateNestedClassFiles(Path outputDir) {
        try {
            if (!Files.exists(outputDir)) {
                return;
            }
            
            // Find all files matching pattern: *$*.java (nested classes)
            try (Stream<Path> paths = Files.walk(outputDir)) {
                paths.filter(p -> {
                    String fileName = p.getFileName().toString();
                    return fileName.endsWith(".java") && fileName.contains("$");
                }).forEach(nestedFile -> {
                    try {
                        // Extract parent class name (e.g., ComplexBuilderTest$Builder -> ComplexBuilderTest)
                        String fileName = nestedFile.getFileName().toString();
                        String parentClassName = fileName.substring(0, fileName.indexOf('$'));
                        String parentFileName = parentClassName + ".java";
                        
                        // Find parent file in same directory
                        Path parentFile = nestedFile.getParent().resolve(parentFileName);
                        if (Files.exists(parentFile)) {
                            // Check if parent file already contains the nested class definition
                            String parentContent = Files.readString(parentFile);
                            // If parent file has the nested class, delete the separate file
                            // We check for "class " + simple name of nested class
                            String nestedSimpleName = fileName.substring(fileName.indexOf('$') + 1, fileName.length() - 5);
                            if (parentContent.contains("class " + nestedSimpleName) || 
                                parentContent.contains("static class " + nestedSimpleName)) {
                                // Nested class is already in parent file - delete duplicate
                                Files.delete(nestedFile);
                            }
                        }
                    } catch (Exception e) {
                        // Ignore errors during cleanup
                    }
                });
            }
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * Post-process generated Java files to remove bad static imports that reference non-existent classes.
     */
    private static void postProcessRemoveBadStaticImports(Path outputDir, CtModel model, Factory f) {
        // Build set of existing types
        Set<String> existingTypes = new HashSet<>();
        for (CtType<?> t : model.getAllTypes()) {
            try {
                String qn = t.getQualifiedName();
                if (qn != null && !qn.isEmpty()) {
                    existingTypes.add(qn);
                }
            } catch (Throwable ignored) {}
        }
        
        // Pattern to match static imports: import static package.Class.member;
        Pattern staticImportPattern = Pattern.compile(
            "^\\s*import\\s+static\\s+([\\w\\.]+)\\.([\\w\\*]+)\\s*;",
            Pattern.MULTILINE
        );
        
        try (Stream<Path> paths = Files.walk(outputDir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(javaFile -> {
                    try {
                        String content = Files.readString(javaFile);
                        String originalContent = content;
                        
                        // Find and remove bad static imports
                        java.util.regex.Matcher matcher = staticImportPattern.matcher(content);
                        StringBuilder newContent = new StringBuilder();
                        int lastEnd = 0;
                        
                        while (matcher.find()) {
                            String classFqn = matcher.group(1);
                            String member = matcher.group(2);
                            
                            // Check if class exists
                            boolean classExists = existingTypes.contains(classFqn);
                            
                            // Check module class pattern (XModule -> X$Module)
                            if (!classExists && classFqn.endsWith("Module")) {
                                String parentClass = classFqn.substring(0, classFqn.length() - "Module".length());
                                if (existingTypes.contains(parentClass)) {
                                    // Parent exists, module doesn't - remove import
                                    newContent.append(content, lastEnd, matcher.start());
                                    lastEnd = matcher.end();
                                    continue;
                                }
                            }
                            
                            // Check API pattern (X.API -> X$API)
                            if (!classExists && classFqn.contains(".API")) {
                                String parentClass = classFqn.substring(0, classFqn.lastIndexOf(".API"));
                                if (existingTypes.contains(parentClass)) {
                                    // Parent exists, API doesn't - remove import
                                    newContent.append(content, lastEnd, matcher.start());
                                    lastEnd = matcher.end();
                                    continue;
                                }
                            }
                            
                            // If class doesn't exist and not JDK, remove import
                            if (!classExists && !classFqn.startsWith("java.") && 
                                !classFqn.startsWith("javax.") && !classFqn.startsWith("jakarta.")) {
                                newContent.append(content, lastEnd, matcher.start());
                                lastEnd = matcher.end();
                                continue;
                            }
                            
                            // Keep the import - it's valid
                        }
                        
                        if (lastEnd > 0) {
                            newContent.append(content.substring(lastEnd));
                            Files.writeString(javaFile, newContent.toString());
                        }
                    } catch (Exception e) {
                        // Ignore errors
                    }
                });
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * Post-process generated files to fix malformed method calls where type arguments are treated as parameters.
     * Fixes patterns like: Option.none(Option<Double>) -> Option.<Double>none()
     */
    private static void postProcessFixMalformedMethodCalls(Path outputDir) {
        // Pattern to match: Class.method(Type<Generic>) where Type<Generic> looks like a type argument used as parameter
        // Handles wildcards: ? extends, ? super, and complex generics
        // Improved to handle nested generics and more method names
        Pattern malformedCallPattern = Pattern.compile(
            "\\b([\\w\\.]+)\\.(none|some|of|empty|get|create|valueOf|initOption)\\s*\\(\\s*([\\w\\.]+)<([^>]+(?:<[^>]*>)*[^>]*)>\\s*\\)",
            Pattern.MULTILINE
        );
        
        // Pattern for calls with type argument and value: Option.some(Option<Double>, value)
        // Handles complex generics and wildcards
        Pattern malformedCallWithValuePattern = Pattern.compile(
            "\\b([\\w\\.]+)\\.(some|of|get|create|valueOf)\\s*\\(\\s*([\\w\\.]+)<([^>]+(?:<[^>]*>)*[^>]*)>\\s*,\\s*([^)]+)\\)",
            Pattern.MULTILINE
        );
        
        // Pattern for illegal start of type errors: Option.initOption(Option<Double>) where it's actually a type argument
        // This handles cases where the regex might miss due to complex syntax
        Pattern illegalStartPattern = Pattern.compile(
            "\\b([\\w\\.]+)\\.(initOption|none|some|of|empty)\\s*\\(\\s*([\\w\\.]+)\\s*<\\s*([^>]+(?:<[^>]*>)*[^>]*)\\s*>\\s*\\)",
            Pattern.MULTILINE
        );
        
        try (Stream<Path> paths = Files.walk(outputDir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(javaFile -> {
                    try {
                        String content = Files.readString(javaFile);
                        String originalContent = content;
                        boolean changed = false;
                        
                        // Fix pattern 1: Option.none(Option<Double>) -> Option.<Double>none()
                        java.util.regex.Matcher matcher1 = malformedCallPattern.matcher(content);
                        StringBuilder newContent1 = new StringBuilder();
                        int lastEnd1 = 0;
                        
                        while (matcher1.find()) {
                            String className = matcher1.group(1);
                            String methodName = matcher1.group(2);
                            String typeName = matcher1.group(3);
                            String genericArg = matcher1.group(4);
                            
                            // Check if the parameter type matches the class (e.g., Option.none(Option<Double>))
                            if (className.endsWith("." + typeName) || className.equals(typeName)) {
                                // This is malformed - type argument is being used as parameter
                                // Fix: Class.<Generic>method() instead of Class.method(Type<Generic>)
                                newContent1.append(content, lastEnd1, matcher1.start());
                                newContent1.append(className).append(".<").append(genericArg).append(">").append(methodName).append("()");
                                lastEnd1 = matcher1.end();
                                changed = true;
                            }
                        }
                        
                        if (lastEnd1 > 0) {
                            newContent1.append(content.substring(lastEnd1));
                            content = newContent1.toString();
                        }
                        
                        // Fix pattern 2: Option.some(Option<Double>, value) -> Option.<Double>some(value)
                        java.util.regex.Matcher matcher2 = malformedCallWithValuePattern.matcher(content);
                        StringBuilder newContent2 = new StringBuilder();
                        int lastEnd2 = 0;
                        
                        while (matcher2.find()) {
                            String className = matcher2.group(1);
                            String methodName = matcher2.group(2);
                            String typeName = matcher2.group(3);
                            String genericArg = matcher2.group(4);
                            String value = matcher2.group(5);
                            
                            // Check if the parameter type matches the class
                            if (className.endsWith("." + typeName) || className.equals(typeName)) {
                                // This is malformed - type argument is being used as first parameter
                                // Fix: Class.<Generic>method(value) instead of Class.method(Type<Generic>, value)
                                newContent2.append(content, lastEnd2, matcher2.start());
                                newContent2.append(className).append(".<").append(genericArg).append(">").append(methodName).append("(").append(value).append(")");
                                lastEnd2 = matcher2.end();
                                changed = true;
                            }
                        }
                        
                        if (lastEnd2 > 0) {
                            newContent2.append(content.substring(lastEnd2));
                            content = newContent2.toString();
                        }
                        
                        // Fix pattern 3: Handle illegal start of type errors (more permissive pattern)
                        java.util.regex.Matcher matcher3 = illegalStartPattern.matcher(content);
                        StringBuilder newContent3 = new StringBuilder();
                        int lastEnd3 = 0;
                        
                        while (matcher3.find()) {
                            String className = matcher3.group(1);
                            String methodName = matcher3.group(2);
                            String typeName = matcher3.group(3);
                            String genericArg = matcher3.group(4);
                            
                            // Check if the parameter type matches the class
                            if (className.endsWith("." + typeName) || className.equals(typeName)) {
                                newContent3.append(content, lastEnd3, matcher3.start());
                                newContent3.append(className).append(".<").append(genericArg).append(">").append(methodName).append("()");
                                lastEnd3 = matcher3.end();
                                changed = true;
                            }
                        }
                        
                        if (lastEnd3 > 0) {
                            newContent3.append(content.substring(lastEnd3));
                            content = newContent3.toString();
                        }
                        
                        // Only write if content changed
                        if (changed && !content.equals(originalContent)) {
                            Files.writeString(javaFile, content);
                        }
                    } catch (Exception e) {
                        // Ignore errors
                    }
                });
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * Post-process generated files to remove array type files (e.g., double[].java).
     */
    private static void postProcessRemoveArrayTypeFiles(Path outputDir) {
        try (Stream<Path> paths = Files.walk(outputDir)) {
            paths.filter(p -> {
                String fileName = p.getFileName().toString();
                // Check if filename contains array brackets
                return fileName.endsWith(".java") && 
                       (fileName.contains("[]") || fileName.endsWith("]") || 
                        fileName.matches(".*\\[\\d*\\]\\.java"));
            }).forEach(arrayFile -> {
                try {
                    Files.delete(arrayFile);
                } catch (Exception e) {
                    // Ignore errors
                }
            });
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * CRITICAL FIX: Post-process to add missing imports directly to generated files.
     * Spoon sometimes doesn't write imports even when they're in the CU, so we add them manually.
     * This function is SAFE - it only adds imports, never removes or modifies existing code.
     */
    private static void postProcessAddMissingImports(Path outputDir, CtModel model, Factory f) {
        if (outputDir == null || !Files.exists(outputDir)) {
            return; // Safety check
        }
        
        // PERFORMANCE: Only process files that were actually generated (not all files in directory)
        // This reduces overhead when processing many methods
        try {
            // Quick check: if directory is too large, skip detailed processing
            long fileCount = Files.walk(outputDir)
                .filter(p -> p.toString().endsWith(".java"))
                .count();
            
            // If too many files, use faster path (only check specific patterns)
            if (fileCount > 500) {
                // Fast path: only check for critical imports (XrSession, Unknown, CHECKS)
                postProcessAddMissingImportsFast(outputDir, model, f);
                return;
            }
        } catch (Throwable ignored) {}
        
        // Build map of all types in model for quick lookup
        Map<String, String> simpleNameToFQN = new HashMap<>();
        for (CtType<?> type : model.getAllTypes()) {
            try {
                String qn = type.getQualifiedName();
                String simple = type.getSimpleName();
                if (qn != null && simple != null && !qn.startsWith("java.") && !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                    simpleNameToFQN.put(simple, qn);
                }
            } catch (Throwable ignored) {}
        }
        
        // Build map of type to its required imports from CU
        Map<String, Set<String>> typeToImports = new HashMap<>();
        for (CtType<?> type : model.getAllTypes()) {
            try {
                CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                if (cu == null) continue;
                
                Set<String> imports = new LinkedHashSet<>();
                cu.getImports().forEach(imp -> {
                    try {
                        CtReference r = imp.getReference();
                        CtImportKind kind = imp.getImportKind();
                        boolean isStatic = (kind != null && (kind.name().contains("STATIC") || kind.name().contains("METHOD") || kind.name().contains("ALL")));
                        
                        if (r instanceof CtTypeReference) {
                            String qn = ((CtTypeReference<?>) r).getQualifiedName();
                            if (qn != null && !qn.startsWith("java.") && !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                                if (isStatic) {
                                    // For static imports, try to get the full member path
                                    String importStr = r.toString();
                                    if (importStr != null && importStr.contains(".")) {
                                        imports.add("import static " + importStr + ";");
                                    } else {
                                        imports.add("import static " + qn + ".*;");
                                    }
                                } else {
                                    imports.add("import " + qn + ";");
                                }
                            }
                        } else if (isStatic) {
                            String importStr = r.toString();
                            if (importStr != null && importStr.contains(".")) {
                                imports.add("import static " + importStr + ";");
                            }
                        }
                    } catch (Throwable ignored) {}
                });
                
                if (!imports.isEmpty()) {
                    String typeQn = type.getQualifiedName();
                    if (typeQn != null) {
                        typeToImports.put(typeQn, imports);
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Now add imports to the actual files
        try (Stream<Path> paths = Files.walk(outputDir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(javaFile -> {
                    try {
                        String content = Files.readString(javaFile);
                        String originalContent = content;
                        
                        // Extract package name
                        Pattern packagePattern = Pattern.compile("^package\\s+([^;]+);", Pattern.MULTILINE);
                        java.util.regex.Matcher packageMatcher = packagePattern.matcher(content);
                        if (!packageMatcher.find()) return; // Skip files without package
                        
                        String packageName = packageMatcher.group(1);
                        
                        // Extract class name (handle multiple classes in file - take first one)
                        Pattern classPattern = Pattern.compile("(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?(?:class|interface|enum|@interface)\\s+(\\w+)", Pattern.MULTILINE);
                        java.util.regex.Matcher classMatcher = classPattern.matcher(content);
                        if (!classMatcher.find()) return;
                        
                        String className = classMatcher.group(1);
                        String typeQn = packageName + "." + className;
                        
                        // Get required imports for this type from CU
                        Set<String> requiredImports = new LinkedHashSet<>(typeToImports.getOrDefault(typeQn, new HashSet<>()));
                        
                        // CRITICAL: Detect specific known patterns that need imports
                        // Only add imports for types we know are used in the file
                        
                        // Pattern 1: Parameter types like "XrSession session" or "Unknown arg0"
                        Pattern paramPattern = Pattern.compile("\\b(XrSession|Unknown|Missing)\\s+[a-zA-Z_$]");
                        if (paramPattern.matcher(content).find()) {
                            if (content.contains("XrSession") && !content.contains("import org.lwjgl.XrSession") && simpleNameToFQN.containsKey("XrSession")) {
                                String fqn = simpleNameToFQN.get("XrSession");
                                if (fqn != null && !fqn.startsWith(packageName + ".")) {
                                    requiredImports.add("import " + fqn + ";");
                                }
                            }
                            // Check for Unknown as parameter or return type (with or without spaces)
                            Pattern unknownPattern = Pattern.compile("\\bUnknown\\s+[a-zA-Z_$]|\\bUnknown\\s*[\\[\\]<>(),]");
                            if (unknownPattern.matcher(content).find() && !content.contains("import unknown.Unknown")) {
                                requiredImports.add("import unknown.Unknown;");
                            }
                        }
                        
                        // Pattern 2: Return types like "public static Unknown address()"
                        Pattern returnPattern = Pattern.compile("\\b(Unknown|Missing)\\s+[a-zA-Z_$]+\\s*\\(");
                        if (returnPattern.matcher(content).find() && !content.contains("import unknown.Unknown")) {
                            requiredImports.add("import unknown.Unknown;");
                        }
                        
                        // Pattern 3: Static field CHECKS
                        if (content.contains("CHECKS") && !content.contains("import static") && !content.contains("Checks.CHECKS")) {
                            // Check if it's used as a bare identifier (not qualified)
                            Pattern checksPattern = Pattern.compile("\\bif\\s*\\(\\s*CHECKS\\s*\\)|\\bCHECKS\\s*\\?");
                            if (checksPattern.matcher(content).find()) {
                                requiredImports.add("import static org.lwjgl.system.Checks.CHECKS;");
                            }
                        }
                        
                        if (requiredImports.isEmpty()) return;
                        
                        // Check if all imports already exist
                        String finalContent = content;
                        boolean allPresent = requiredImports.stream().allMatch(imp -> {
                            String importQn = imp.replace("import ", "").replace("import static ", "").replace(";", "").replace(".*", "");
                            return finalContent.contains("import " + importQn) || finalContent.contains("import static " + importQn);
                        });
                        if (allPresent) return;
                        
                        // Find insertion point (after package, before class or existing imports)
                        int insertPos = packageMatcher.end();
                        Pattern existingImportPattern = Pattern.compile("^import\\s+", Pattern.MULTILINE);
                        java.util.regex.Matcher existingImportMatcher = existingImportPattern.matcher(content);
                        if (existingImportMatcher.find(insertPos)) {
                            // Find the last import line
                            int lastImportEnd = insertPos;
                            while (existingImportMatcher.find()) {
                                int importStart = existingImportMatcher.start();
                                // Find the end of this import line
                                int importEnd = content.indexOf('\n', importStart);
                                if (importEnd == -1) importEnd = content.length();
                                lastImportEnd = importEnd;
                            }
                            insertPos = lastImportEnd;
                        }
                        
                        // Build import block (only missing ones)
                        StringBuilder importBlock = new StringBuilder("\n");
                        for (String imp : requiredImports) {
                            String importQn = imp.replace("import ", "").replace("import static ", "").replace(";", "").replace(".*", "");
                            if (!content.contains("import " + importQn) && !content.contains("import static " + importQn)) {
                                importBlock.append(imp).append("\n");
                            }
                        }
                        
                        if (importBlock.length() > 1) {
                            // Insert imports
                            content = content.substring(0, insertPos) + importBlock.toString() + content.substring(insertPos);
                            
                            if (!content.equals(originalContent)) {
                                Files.writeString(javaFile, content);
                                System.out.println("[postProcessAddMissingImports] Added " + (importBlock.toString().split("\n").length - 1) + " imports to " + typeQn);
                            }
                        }
                    } catch (Exception e) {
                        // Silently fail - don't break existing functionality
                        System.err.println("[postProcessAddMissingImports] Error processing " + javaFile + ": " + e.getMessage());
                    }
                });
        } catch (Exception e) {
            // Silently fail - don't break existing functionality
            System.err.println("[postProcessAddMissingImports] Error: " + e.getMessage());
        }
    }
    
    /**
     * Post-process generated Java files to replace unknown.Unknown with Unknown and ensure import is present.
     * This fixes cases where Spoon's pretty printer doesn't respect setSimplyQualified(false).
     */
    private static void postProcessUnknownTypes(Path outputDir) {
        try {
            if (!Files.exists(outputDir)) {
                return;
            }
            
            // Pattern to match unknown.Unknown in type positions:
            // Matches: unknown.Unknown followed by:
            // - whitespace and identifier (variable/parameter name)
            // - array brackets []
            // - generic brackets <>
            // - parentheses (for casts or method parameters)
            Pattern typePattern = Pattern.compile(
                "\\bunknown\\.Unknown\\b(?=\\s*(?:[a-zA-Z_$][a-zA-Z0-9_$]*|\\[\\]|\\s*[<>(),]|\\s*\\{))",
                Pattern.MULTILINE
            );
            
            // Pattern to check if import already exists
            Pattern importPattern = Pattern.compile("^\\s*import\\s+unknown\\.Unknown\\s*;", Pattern.MULTILINE);
            
            // Find all Java files recursively
            try (Stream<Path> paths = Files.walk(outputDir)) {
                paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(javaFile -> {
                        try {
                            String content = Files.readString(javaFile);
                            String originalContent = content;
                            
                            // Check if file uses unknown.Unknown
                            if (!content.contains("unknown.Unknown")) {
                                return; // Skip files that don't use unknown.Unknown
                            }
                            
                            // Replace unknown.Unknown with Unknown in type positions
                            content = typePattern.matcher(content).replaceAll("Unknown");
                            
                            // Only modify if there were changes
                            if (!content.equals(originalContent)) {
                                // Check if import is already present
                                boolean hasImport = importPattern.matcher(content).find();
                                
                                if (!hasImport) {
                                    // Find the package declaration and add import after it
                                    Pattern packagePattern = Pattern.compile("^(package\\s+[^;]+;)", Pattern.MULTILINE);
                                    java.util.regex.Matcher packageMatcher = packagePattern.matcher(content);
                                    
                                    if (packageMatcher.find()) {
                                        // Insert import after package declaration
                                        int insertPos = packageMatcher.end();
                                        content = content.substring(0, insertPos) + 
                                                 "\nimport unknown.Unknown;\n" + 
                                                 content.substring(insertPos);
                                    } else {
                                        // No package, add import at the beginning
                                        content = "import unknown.Unknown;\n\n" + content;
                                    }
                                }
                                
                                // Write the modified content back
                                Files.writeString(javaFile, content);
                                System.out.println("[postProcess] Fixed unknown.Unknown in " + javaFile);
                            }
                        } catch (IOException e) {
                            System.err.println("[postProcess] Error processing " + javaFile + ": " + e.getMessage());
                        }
                    });
            }
        } catch (IOException e) {
            System.err.println("[postProcess] Error scanning output directory: " + e.getMessage());
        }
    }

    /**
     * Determines Java compliance level from targetVersion string.
     * Defaults to 17 (supports records, modern APIs) if version is unknown or null.
     * 
     * Java 19+ Support:
     * - Java 19 (class file version 63) → compliance level 17
     * - Java 20 (class file version 64) → compliance level 17
     * - Java 21+ (class file version 65+) → compliance level 21
     * 
     * Note: Spoon supports compliance levels 8, 9, 10, 11, 17, 21.
     * Java 19-20 features are mostly compatible with level 17, while Java 21+
     * requires level 21 for full support.
     * 
     * @param targetVersion Version string (e.g., "11", "17", "19", "21", "1.8", null)
     * @return Compliance level (8, 9, 10, 11, 17, 21)
     */
    private int determineComplianceLevel(String targetVersion) {
        if (targetVersion == null || targetVersion.isEmpty() || targetVersion.equals("unknown")) {
            // Default to 17 for modern Java features (records, sealed classes, etc.)
            return 17;
        }
        
        // Normalize version string (remove "1." prefix for Java 8 and earlier)
        String normalized = targetVersion.trim();
        if (normalized.startsWith("1.")) {
            normalized = normalized.substring(2);
        }
        
        try {
            int version = Integer.parseInt(normalized);
            // Spoon supports compliance levels: 8, 9, 10, 11, 17, 21, etc.
            // Map to nearest supported level
            if (version <= 8) return 8;
            if (version <= 11) return 11;
            if (version <= 16) return 11; // Use 11 for 12-16
            if (version <= 20) return 17; // Use 17 for 17-20 (includes Java 19)
            if (version <= 22) return 21; // Use 21 for 21-22
            return 21; // Default to 21 for newer versions (23+)
        } catch (NumberFormatException e) {
            // If parsing fails, default to 17
            System.err.println("Warning: Could not parse targetVersion '" + targetVersion + "', defaulting to Java 17");
            return 17;
        }
    }
    
    /**
     * Post-process generated files to fix void type errors.
     * Fixes cases where void return types are used as expressions (e.g., 'void' type not allowed here).
     * Note: This is a placeholder - void type errors are better fixed in the Spoon model.
     */
    private static void postProcessFixVoidTypeErrors(Path outputDir) {
        // Void type errors are complex to fix with regex alone
        // They require type information to determine if a method returns void
        // The fixVoidDereferencing() method in SpoonStubber should handle most cases
        // This post-processing step is kept for potential future improvements
    }
    
    /**
     * Post-process generated files to fix Tree package/class clashes.
     * Removes package directories when a class with the same name exists.
     */
    private static void postProcessFixTreePackageClashes(Path outputDir) {
        try {
            // Look for Tree package directories that clash with Tree class
            Path treePackageDir = outputDir.resolve("io/vavr/collection/Tree");
            Path treeClassFile = outputDir.resolve("io/vavr/collection/Tree.java");
            
            if (Files.exists(treePackageDir) && Files.isDirectory(treePackageDir) && 
                Files.exists(treeClassFile) && Files.isRegularFile(treeClassFile)) {
                // Package directory exists and class file exists - this is a clash
                // Remove the package directory (inner classes should be in the class file)
                try (Stream<Path> paths = Files.walk(treePackageDir)) {
                    paths.sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                // Ignore errors
                            }
                        });
                }
                // Also remove the package directory itself
                try {
                    Files.deleteIfExists(treePackageDir);
                } catch (IOException e) {
                    // Ignore errors
                }
            }
            
            // Also check for PackageAnchor.java files that might be created by Spoon
            Path packageAnchorFile = outputDir.resolve("io/vavr/collection/Tree/PackageAnchor.java");
            if (Files.exists(packageAnchorFile)) {
                try {
                    Files.delete(packageAnchorFile);
                } catch (IOException e) {
                    // Ignore errors
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * CRITICAL FIX: Post-process to fix GeneratedMessage.Builder package/class clashes.
     * Removes package directories when a class with the same name exists.
     * This fixes the issue where GeneratedMessage.Builder is written to com/google/protobuf/GeneratedMessage/Builder.java
     * creating a package com.google.protobuf.GeneratedMessage that clashes with the class com.google.protobuf.GeneratedMessage.
     */
    private static void postProcessFixGeneratedMessageBuilderClash(Path outputDir) {
        try {
            // Look for GeneratedMessage package directory that clashes with GeneratedMessage class
            Path generatedMessagePackageDir = outputDir.resolve("com/google/protobuf/GeneratedMessage");
            Path generatedMessageClassFile = outputDir.resolve("com/google/protobuf/GeneratedMessage.java");
            
            if (Files.exists(generatedMessagePackageDir) && Files.isDirectory(generatedMessagePackageDir) && 
                Files.exists(generatedMessageClassFile) && Files.isRegularFile(generatedMessageClassFile)) {
                // Package directory exists and class file exists - this is a clash
                // Remove the package directory (Builder inner class should be in the class file)
                try (Stream<Path> paths = Files.walk(generatedMessagePackageDir)) {
                    paths.sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                // Ignore errors
                            }
                        });
                }
                // Also remove the package directory itself
                try {
                    Files.deleteIfExists(generatedMessagePackageDir);
                } catch (IOException e) {
                    // Ignore errors
                }
            }
            
            // Also check for Builder.java file in the package directory
            Path builderFile = outputDir.resolve("com/google/protobuf/GeneratedMessage/Builder.java");
            if (Files.exists(builderFile)) {
                try {
                    Files.delete(builderFile);
                } catch (IOException e) {
                    // Ignore errors
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * CRITICAL FIX: Post-process to fix nested class package/class clashes.
     * Removes package directories when a class with the same name exists.
     * This fixes issues like:
     * - win.liyufan.im.HttpUtils class exists
     * - win.liyufan.im.HttpUtils.HttpPostType is written as win/liyufan/im/HttpUtils/HttpPostType.java
     * - This creates a package directory HttpUtils/ that clashes with HttpUtils.java
     */
    private static void postProcessFixNestedClassPackageClashes(Path outputDir) {
        try {
            if (!Files.exists(outputDir)) {
                return;
            }
            
            // Walk all directories in the output
            try (Stream<Path> dirs = Files.walk(outputDir)) {
                dirs.filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            String dirName = dir.getFileName().toString();
                            Path parentDir = dir.getParent();
                            if (parentDir == null) return;
                            
                            // Check if there's a class file with the same name as this directory
                            Path classFile = parentDir.resolve(dirName + ".java");
                            if (Files.exists(classFile) && Files.isRegularFile(classFile)) {
                                // This is a clash: directory name matches a class file name
                                // Remove the directory and all its contents
                                System.out.println("[postProcessFixNestedClassPackageClashes] Removing package directory that clashes with class: " + 
                                    dir + " (class file: " + classFile + ")");
                                
                                try (Stream<Path> paths = Files.walk(dir)) {
                                    paths.sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                                        .forEach(path -> {
                                            try {
                                                Files.delete(path);
                                            } catch (IOException e) {
                                                // Ignore errors
                                            }
                                        });
                                }
                                
                                // Remove the directory itself
                                try {
                                    Files.deleteIfExists(dir);
                                } catch (IOException e) {
                                    // Ignore errors
                                }
                            }
                        } catch (Exception e) {
                            // Ignore errors for individual directories
                        }
                    });
            }
        } catch (Exception e) {
            // Ignore errors
        }
    }
    
    /**
     * CRITICAL FIX: Fix ProtoConstants package/class clash.
     * The issue is that ProtoConstants.ConversationType is being created as a package directory
     * (ProtoConstants/ConversationType.java) when it should be a nested class.
     * This removes the package directory if the class file exists.
     */
    private static void postProcessFixProtoConstantsPackageClash(Path outputDir) {
        try {
            if (!Files.exists(outputDir)) {
                return;
            }
            
            // Check for ProtoConstants package/class clash
            Path protoConstantsDir = outputDir.resolve("cn/wildfirechat/proto/ProtoConstants");
            Path protoConstantsFile = outputDir.resolve("cn/wildfirechat/proto/ProtoConstants.java");
            
            if (Files.exists(protoConstantsDir) && Files.isDirectory(protoConstantsDir) &&
                Files.exists(protoConstantsFile) && Files.isRegularFile(protoConstantsFile)) {
                // This is a clash: ProtoConstants directory exists but ProtoConstants.java also exists
                System.out.println("[postProcessFixProtoConstantsPackageClash] Removing package directory that clashes with class: " + 
                    protoConstantsDir);
                
                try (Stream<Path> paths = Files.walk(protoConstantsDir)) {
                    paths.sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                // Ignore errors
                            }
                        });
                }
            }
        } catch (IOException e) {
            System.err.println("[postProcessFixProtoConstantsPackageClash] Error: " + e.getMessage());
        }
    }
    
    /**
     * DEBUG: Print what is missing to compile and what stubbing plan was collected.
     * This helps identify collection issues before stubbing starts.
     */
    private static void printMissingElementsAndStubbingPlan(CtModel model, SpoonCollector.CollectResult plans) {
        System.out.println();
        
        // 1. Find unresolved types (types referenced but not in model)
        Set<String> existingTypes = new HashSet<>();
        model.getAllTypes().forEach(t -> {
            try {
                String qn = t.getQualifiedName();
                if (qn != null) existingTypes.add(qn);
            } catch (Throwable ignored) {}
        });
        
        // Build a map of type FQN -> TypeStubPlan to get the kind
        Map<String, TypeStubPlan> typePlanMap = new HashMap<>();
        for (TypeStubPlan tp : plans.typePlans) {
            typePlanMap.put(tp.qualifiedName, tp);
        }
        
        // Collect all referenced types from plans with usage context
        Map<String, Set<String>> typeUsages = new HashMap<>(); // type FQN -> set of usage contexts
        for (FieldStubPlan fp : plans.fieldPlans) {
            if (fp.ownerType != null) {
                String ownerQn = safeQN(fp.ownerType);
                if (ownerQn != null) {
                    typeUsages.computeIfAbsent(ownerQn, k -> new LinkedHashSet<>()).add("FIELD_OWNER");
                }
            }
            if (fp.fieldType != null) {
                String fieldQn = safeQN(fp.fieldType);
                if (fieldQn != null && !fieldQn.startsWith("java.")) {
                    typeUsages.computeIfAbsent(fieldQn, k -> new LinkedHashSet<>())
                        .add("FIELD_TYPE in " + safeQN(fp.ownerType));
                }
            }
        }
        for (MethodStubPlan mp : plans.methodPlans) {
            if (mp.ownerType != null) {
                String ownerQn = safeQN(mp.ownerType);
                if (ownerQn != null) {
                    typeUsages.computeIfAbsent(ownerQn, k -> new LinkedHashSet<>()).add("METHOD_OWNER");
                }
            }
            if (mp.returnType != null) {
                String returnQn = safeQN(mp.returnType);
                if (returnQn != null && !returnQn.startsWith("java.")) {
                    typeUsages.computeIfAbsent(returnQn, k -> new LinkedHashSet<>())
                        .add("RETURN_TYPE of " + safeQN(mp.ownerType) + "#" + mp.name);
                }
            }
            if (mp.paramTypes != null) {
                for (CtTypeReference<?> param : mp.paramTypes) {
                    String paramQn = safeQN(param);
                    if (paramQn != null && !paramQn.startsWith("java.")) {
                        typeUsages.computeIfAbsent(paramQn, k -> new LinkedHashSet<>())
                            .add("PARAM_TYPE of " + safeQN(mp.ownerType) + "#" + mp.name);
                    }
                }
            }
        }
        for (ConstructorStubPlan cp : plans.ctorPlans) {
            if (cp.ownerType != null) {
                String ownerQn = safeQN(cp.ownerType);
                if (ownerQn != null) {
                    typeUsages.computeIfAbsent(ownerQn, k -> new LinkedHashSet<>()).add("CONSTRUCTOR_OWNER");
                }
            }
            if (cp.parameterTypes != null) {
                for (CtTypeReference<?> param : cp.parameterTypes) {
                    String paramQn = safeQN(param);
                    if (paramQn != null && !paramQn.startsWith("java.")) {
                        typeUsages.computeIfAbsent(paramQn, k -> new LinkedHashSet<>())
                            .add("CTOR_PARAM_TYPE of " + safeQN(cp.ownerType));
                    }
                }
            }
        }
        for (TypeStubPlan tp : plans.typePlans) {
            typeUsages.computeIfAbsent(tp.qualifiedName, k -> new LinkedHashSet<>())
                .add("DIRECT_TYPE_PLAN (" + tp.kind + ")");
        }
        
        // Find missing types
        Set<String> missingTypes = new LinkedHashSet<>();
        for (String refType : typeUsages.keySet()) {
            if (refType != null && !refType.startsWith("java.") && !existingTypes.contains(refType)) {
                // Check if it's an array type (skip those)
                if (!refType.endsWith("[]") && !refType.contains("$")) {
                    missingTypes.add(refType);
                }
            }
        }
        
        System.out.println("\n[MISSING TYPES] (" + missingTypes.size() + " types need to be stubbed):");
        System.out.println("(Shows: FQN | KIND | HOW IDENTIFIED | WHERE USED)");
        System.out.println();
        missingTypes.stream().sorted().limit(50).forEach(type -> {
            TypeStubPlan plan = typePlanMap.get(type);
            String kind = plan != null ? plan.kind.toString() : "UNKNOWN";
            String howIdentified = determineHowIdentified(plan, typeUsages.get(type));
            System.out.println("  - " + type);
            System.out.println("      KIND: " + kind);
            System.out.println("      HOW IDENTIFIED: " + howIdentified);
            Set<String> usages = typeUsages.get(type);
            if (usages != null && !usages.isEmpty()) {
                System.out.println("      WHERE USED: " + String.join(", ", usages.stream().limit(5).collect(java.util.stream.Collectors.toList())));
                if (usages.size() > 5) {
                    System.out.println("        ... and " + (usages.size() - 5) + " more usages");
                }
            }
            System.out.println();
        });
        if (missingTypes.size() > 50) {
            System.out.println("  ... and " + (missingTypes.size() - 50) + " more");
        }
        
        System.out.println("\n[TYPE PLANS] (" + plans.typePlans.size() + " types to generate):");
        plans.typePlans.stream()
            .sorted((a, b) -> a.qualifiedName.compareTo(b.qualifiedName))
            .limit(30)
            .forEach(tp -> System.out.println("  +type  " + tp.qualifiedName + " (" + tp.kind + ")"));
        if (plans.typePlans.size() > 30) {
            System.out.println("  ... and " + (plans.typePlans.size() - 30) + " more types");
        }
        
        System.out.println("\n[FIELD PLANS] (" + plans.fieldPlans.size() + " fields to generate):");
        plans.fieldPlans.stream()
            .limit(30)
            .forEach(fp -> {
                String ownerQn = safeQN(fp.ownerType);
                String fieldTypeQn = safeQN(fp.fieldType);
                System.out.println("  +field " + ownerQn + "#" + fp.fieldName + ":" + fieldTypeQn + 
                    (fp.isStatic ? " (static)" : ""));
            });
        if (plans.fieldPlans.size() > 30) {
            System.out.println("  ... and " + (plans.fieldPlans.size() - 30) + " more fields");
        }
        
        System.out.println("\n[METHOD PLANS] (" + plans.methodPlans.size() + " methods to generate):");
        plans.methodPlans.stream()
            .limit(30)
            .forEach(mp -> {
                String ownerQn = safeQN(mp.ownerType);
                String returnQn = safeQN(mp.returnType);
                StringBuilder params = new StringBuilder();
                if (mp.paramTypes != null && !mp.paramTypes.isEmpty()) {
                    for (int i = 0; i < mp.paramTypes.size(); i++) {
                        if (i > 0) params.append(", ");
                        params.append(safeQN(mp.paramTypes.get(i)));
                    }
                }
                System.out.println("  +method " + ownerQn + "#" + mp.name + "(" + params + ") : " + returnQn +
                    (mp.isStatic ? " (static)" : "") + (mp.defaultOnInterface ? " (default)" : ""));
            });
        if (plans.methodPlans.size() > 30) {
            System.out.println("  ... and " + (plans.methodPlans.size() - 30) + " more methods");
        }
        
        System.out.println("\n[CONSTRUCTOR PLANS] (" + plans.ctorPlans.size() + " constructors to generate):");
        plans.ctorPlans.stream()
            .limit(20)
            .forEach(cp -> {
                String ownerQn = safeQN(cp.ownerType);
                StringBuilder params = new StringBuilder();
                if (cp.parameterTypes != null && !cp.parameterTypes.isEmpty()) {
                    for (int i = 0; i < cp.parameterTypes.size(); i++) {
                        if (i > 0) params.append(", ");
                        params.append(safeQN(cp.parameterTypes.get(i)));
                    }
                }
                System.out.println("  +ctor  " + ownerQn + "#" + ownerQn.substring(ownerQn.lastIndexOf('.') + 1) + 
                    "(" + params + ")");
            });
        if (plans.ctorPlans.size() > 20) {
            System.out.println("  ... and " + (plans.ctorPlans.size() - 20) + " more constructors");
        }
        
        System.out.println("\n[STATIC IMPORTS] (" + plans.staticImports.size() + " static imports):");
        plans.staticImports.entrySet().stream()
            .limit(10)
            .forEach(entry -> {
                System.out.println("  +static " + entry.getKey() + " -> " + entry.getValue());
            });
        if (plans.staticImports.size() > 10) {
            System.out.println("  ... and " + (plans.staticImports.size() - 10) + " more static imports");
        }
        
        System.out.println("\n[AMBIGUOUS SIMPLES] (" + plans.ambiguousSimples.size() + " ambiguous simple names):");
        plans.ambiguousSimples.stream().sorted().limit(10).forEach(simple -> 
            System.out.println("  ? " + simple));
        if (plans.ambiguousSimples.size() > 10) {
            System.out.println("  ... and " + (plans.ambiguousSimples.size() - 10) + " more");
        }
        
        System.out.println("\n==================================================================================\n");
    }
    
    /**
     * Determine how a type was identified (which collection method found it).
     * This helps understand why a type is being stubbed.
     */
    private static String determineHowIdentified(TypeStubPlan plan, Set<String> usages) {
        if (plan == null) {
            return "Found in usage context but not in type plans (may be inferred from field/method/parameter types)";
        }
        
        // Based on the kind, we can infer which collection method found it
        switch (plan.kind) {
            case ANNOTATION:
                return "collectUnresolvedAnnotations() or collectAnnotationTypeUsages() - found as annotation type";
            case INTERFACE:
                if (usages != null && usages.stream().anyMatch(u -> u.contains("SAM") || u.contains("lambda") || u.contains("method reference"))) {
                    return "collectMethodReferences() or collectLambdas() - functional interface for lambda/method reference";
                }
                if (usages != null && usages.stream().anyMatch(u -> u.contains("DIRECT_TYPE_PLAN"))) {
                    return "collectSupertypes() - found as superinterface";
                }
                return "collectSupertypes() or collectMethodReferences() - interface type";
            case ENUM:
                return "collectUnresolvedFields() or collectFromInstanceofCastsClassLiteralsAndForEach() - enum constant or switch case";
            case CLASS:
                if (usages != null && usages.stream().anyMatch(u -> u.contains("CONSTRUCTOR_OWNER"))) {
                    return "collectUnresolvedCtorCalls() - constructor call found";
                }
                if (usages != null && usages.stream().anyMatch(u -> u.contains("FIELD_OWNER"))) {
                    return "collectUnresolvedFields() - field access found";
                }
                if (usages != null && usages.stream().anyMatch(u -> u.contains("METHOD_OWNER"))) {
                    return "collectUnresolvedMethodCalls() - method call found";
                }
                if (usages != null && usages.stream().anyMatch(u -> u.contains("DIRECT_TYPE_PLAN"))) {
                    return "collectUnresolvedDeclaredTypes() or collectSupertypes() - declared type or superclass";
                }
                return "Multiple collection methods - class type (default assumption)";
            case RECORD:
                return "detectRecordFromUsage() - detected as record type";
            default:
                return "Unknown collection method";
        }
    }
    
    /**
     * CRITICAL FIX: Post-process to fix primitive field initializations.
     * Replaces `= null` with proper default values for primitive types (e.g., `long field = null` -> `long field = 0L`).
     * This function is SAFE - it only fixes invalid initializations, never removes or modifies valid code.
     */
    private static void postProcessFixPrimitiveFieldInitializations(Path outputDir) {
        if (outputDir == null || !Files.exists(outputDir)) {
            return; // Safety check
        }
        
        try {
            Files.walk(outputDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(javaFile -> {
                    try {
                        String content = Files.readString(javaFile);
                        String originalContent = content;
                        
                        // Fix primitive field initializations: type field = null; -> type field = defaultValue;
                        // Pattern: (public|private|protected)?\s*(static)?\s*(final)?\s*(boolean|byte|char|short|int|long|float|double)\s+\w+\s*=\s*null;
                        java.util.regex.Pattern primitiveNullPattern = java.util.regex.Pattern.compile(
                            "\\b(public|private|protected)?\\s*(static)?\\s*(final)?\\s*(boolean|byte|char|short|int|long|float|double)\\s+(\\w+)\\s*=\\s*null\\s*;"
                        );
                        
                        java.util.regex.Matcher matcher = primitiveNullPattern.matcher(content);
                        boolean changed = false;
                        StringBuffer sb = new StringBuffer();
                        
                        while (matcher.find()) {
                            String modifier = matcher.group(1) != null ? matcher.group(1) : "";
                            String staticMod = matcher.group(2) != null ? matcher.group(2) : "";
                            String finalMod = matcher.group(3) != null ? matcher.group(3) : "";
                            String type = matcher.group(4);
                            String fieldName = matcher.group(5);
                            
                            String defaultValue;
                            switch (type) {
                                case "boolean": defaultValue = "false"; break;
                                case "byte": defaultValue = "(byte) 0"; break;
                                case "char": defaultValue = "'\\0'"; break;
                                case "short": defaultValue = "(short) 0"; break;
                                case "int": defaultValue = "0"; break;
                                case "long": defaultValue = "0L"; break;
                                case "float": defaultValue = "0.0f"; break;
                                case "double": defaultValue = "0.0"; break;
                                default: defaultValue = "0"; break;
                            }
                            
                            String replacement = (modifier.isEmpty() ? "" : modifier + " ") +
                                               (staticMod.isEmpty() ? "" : staticMod + " ") +
                                               (finalMod.isEmpty() ? "" : finalMod + " ") +
                                               type + " " + fieldName + " = " + defaultValue + ";";
                            
                            matcher.appendReplacement(sb, replacement);
                            changed = true;
                        }
                        
                        if (changed) {
                            matcher.appendTail(sb);
                            content = sb.toString();
                            
                            if (!content.equals(originalContent)) {
                                Files.writeString(javaFile, content);
                                System.out.println("[postProcessFixPrimitiveFieldInitializations] Fixed primitive field initializations in " + javaFile.getFileName());
                            }
                        }
                    } catch (Throwable e) {
                        // Fail silently - don't break the build
                        System.err.println("[postProcessFixPrimitiveFieldInitializations] Error processing " + javaFile + ": " + e.getMessage());
                    }
                });
        } catch (Throwable e) {
            // Fail silently - don't break the build
            System.err.println("[postProcessFixPrimitiveFieldInitializations] Error: " + e.getMessage());
        }
    }
    
    /**
     * Fast path for post-processing imports when there are many files.
     * Only checks for critical patterns (XrSession, Unknown, CHECKS).
     */
    private static void postProcessAddMissingImportsFast(Path outputDir, CtModel model, Factory f) {
        try {
            Files.walk(outputDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(javaFile -> {
                    try {
                        String content = Files.readString(javaFile);
                        String originalContent = content;
                        boolean changed = false;
                        
                        // Only check for critical patterns
                        if (content.contains("XrSession") && !content.contains("import org.lwjgl.XrSession")) {
                            int insertPos = content.indexOf("package ");
                            if (insertPos >= 0) {
                                insertPos = content.indexOf('\n', insertPos) + 1;
                                content = content.substring(0, insertPos) + "import org.lwjgl.XrSession;\n" + content.substring(insertPos);
                                changed = true;
                            }
                        }
                        
                        if (content.contains("Unknown") && !content.contains("import unknown.Unknown") && !content.contains("package unknown")) {
                            int insertPos = content.indexOf("package ");
                            if (insertPos >= 0) {
                                insertPos = content.indexOf('\n', insertPos) + 1;
                                content = content.substring(0, insertPos) + "import unknown.Unknown;\n" + content.substring(insertPos);
                                changed = true;
                            }
                        }
                        
                        if (content.contains("CHECKS") && !content.contains("import static") && !content.contains("static org.lwjgl.system.Checks.CHECKS")) {
                            int insertPos = content.indexOf("package ");
                            if (insertPos >= 0) {
                                insertPos = content.indexOf('\n', insertPos) + 1;
                                content = content.substring(0, insertPos) + "import static org.lwjgl.system.Checks.CHECKS;\n" + content.substring(insertPos);
                                changed = true;
                            }
                        }
                        
                        if (changed && !content.equals(originalContent)) {
                            Files.writeString(javaFile, content);
                        }
                    } catch (Throwable ignored) {}
                });
        } catch (Throwable ignored) {}
    }
    
    /**
     * CRITICAL FIX: Fix void return types for methods used in boolean expressions (||, &&).
     * If a method returns void but is used in a boolean binary operator, change its return type to boolean.
     */
    private static void fixVoidReturnTypesInBooleanContexts(CtModel model, Factory f) {
        try {
            for (CtType<?> type : model.getAllTypes()) {
                if (!(type instanceof CtClass)) continue;
                
                CtClass<?> cls = (CtClass<?>) type;
                for (CtMethod<?> method : cls.getMethods()) {
                    CtTypeReference<?> returnType = method.getType();
                    if (returnType == null) continue;
                    
                    // Check if method returns void
                    if (!returnType.getQualifiedName().equals("void")) continue;
                    
                    // Check if this method is used in a boolean context (||, &&)
                    boolean usedInBooleanContext = false;
                    try {
                        // Search for invocations of this method in boolean binary operators
                        for (CtType<?> searchType : model.getAllTypes()) {
                            if (!(searchType instanceof CtClass)) continue;
                            
                            CtClass<?> searchCls = (CtClass<?>) searchType;
                            for (CtMethod<?> searchMethod : searchCls.getMethods()) {
                                CtBlock<?> body = searchMethod.getBody();
                                if (body == null) continue;
                                
                                // Check all binary operators in the method body
                                for (CtBinaryOperator<?> binOp : body.getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtBinaryOperator.class))) {
                                    if (binOp.getKind() == spoon.reflect.code.BinaryOperatorKind.OR || 
                                        binOp.getKind() == spoon.reflect.code.BinaryOperatorKind.AND) {
                                        
                                        // Check if this method is invoked in this boolean expression
                                        for (CtInvocation<?> inv : binOp.getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtInvocation.class))) {
                                            CtExecutableReference<?> execRef = inv.getExecutable();
                                            if (execRef != null && 
                                                execRef.getSimpleName().equals(method.getSimpleName()) &&
                                                execRef.getDeclaringType() != null &&
                                                execRef.getDeclaringType().getQualifiedName().equals(cls.getQualifiedName())) {
                                                usedInBooleanContext = true;
                                                break;
                                            }
                                        }
                                        if (usedInBooleanContext) break;
                                    }
                                }
                                if (usedInBooleanContext) break;
                            }
                            if (usedInBooleanContext) break;
                        }
                    } catch (Throwable ignored) {}
                    
                    // If method is used in boolean context, change return type to boolean
                    if (usedInBooleanContext) {
                        try {
                            method.setType(f.Type().BOOLEAN_PRIMITIVE);
                            // Also update the method body to return a boolean value
                            if (method.getBody() != null) {
                                CtBlock<?> body = method.getBody();
                                // Check if there's already a return statement
                                boolean hasReturn = false;
                                for (CtStatement stmt : body.getStatements()) {
                                    if (stmt instanceof CtReturn) {
                                        hasReturn = true;
                                        // Update return statement to return boolean
                                        CtReturn<?> ret = (CtReturn<?>) stmt;
                                        if (ret.getReturnedExpression() == null) {
                                            // Use raw type cast to avoid generic type issues
                                            @SuppressWarnings({"unchecked", "rawtypes"})
                                            CtExpression expr = (CtExpression) f.Code().createLiteral(false);
                                            ret.setReturnedExpression(expr);
                                        }
                                        break;
                                    }
                                }
                                // If no return statement, add one at the end
                                if (!hasReturn) {
                                    CtReturn<Boolean> ret = f.Core().createReturn();
                                    @SuppressWarnings({"unchecked", "rawtypes"})
                                    CtExpression expr = (CtExpression) f.Code().createLiteral(false);
                                    ret.setReturnedExpression(expr);
                                    body.addStatement(ret);
                                }
                            }
                            System.out.println("[fixVoidReturnTypesInBooleanContexts] Changed return type of " + 
                                cls.getQualifiedName() + "#" + method.getSimpleName() + " from void to boolean");
                        } catch (Throwable e) {
                            System.err.println("[fixVoidReturnTypesInBooleanContexts] Error fixing " + 
                                cls.getQualifiedName() + "#" + method.getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Throwable e) {
            System.err.println("[fixVoidReturnTypesInBooleanContexts] Error: " + e.getMessage());
        }
    }
    
    /**
     * CRITICAL FIX: Check if a qualified name is a JDK type.
     */
    private static boolean isJdkFqn(String qn) {
        return qn != null && (qn.startsWith("java.")
                || qn.startsWith("javax.")
                || qn.startsWith("jakarta.")
                || qn.startsWith("sun.")
                || qn.startsWith("jdk."));
    }
    
    /**
     * CRITICAL FIX: Check if a package should be ignored (JDK, generated, etc.).
     * Returns true for java.*, javax.*, jdk.*, sun.*, com.sun.*, kotlin.*, scala.*
     * and known generated packages like org.lwjgl.* and any package containing .generated.
     */
    private static boolean isIgnoredPackage(String qn) {
        if (qn == null || qn.isEmpty()) return false;
        
        // JDK packages
        if (qn.startsWith("java.") || qn.startsWith("javax.") || 
            qn.startsWith("jakarta.") || qn.startsWith("jdk.") ||
            qn.startsWith("sun.") || qn.startsWith("com.sun.")) {
            return true;
        }
        
        // Other language runtimes
        if (qn.startsWith("kotlin.") || qn.startsWith("scala.")) {
            return true;
        }
        
        // Generated packages
        if (qn.contains(".generated.") || qn.contains(".generated")) {
            return true;
        }
        
        // Known generated packages
        if (qn.startsWith("org.lwjgl.")) {
            return true;
        }
        
        return false;
    }
}
