package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Objects;
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
        System.out.println("\n>> Using stubber: Spoon Based Stubber");

        // Let -Djess.failOnAmbiguity=true|false override BEFORE collection
        String sys = System.getProperty("jess.failOnAmbiguity");
        if (sys != null) {
            cfg.setFailOnAmbiguity(Boolean.parseBoolean(sys));
        }

        // 1) Configure Spoon for Java 11
        Launcher launcher = new Launcher();
        var env = launcher.getEnvironment();
        env.setComplianceLevel(11);
        env.setAutoImports(false); // Disable auto-imports to prevent invalid imports like "import Unknown;"
        env.setSourceOutputDirectory(slicedSrcDir.toFile());

        if (classpathJars == null || classpathJars.isEmpty()) {
            env.setNoClasspath(true);
        } else {
            env.setNoClasspath(false);
            env.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
        }

        launcher.addInputResource(slicedSrcDir.toString());
        launcher.buildModel();

        // 2) Collect unresolved elements
        CtModel model = launcher.getModel();
        Factory f = launcher.getFactory();
        SpoonCollector collector = new SpoonCollector(f, cfg);
        SpoonCollector.CollectResult plans = collector.collect(model);

        // 3) Generate shims for common libraries (on-demand, before stubbing)
        // Only generate shims for types that are referenced but missing
        de.upb.sse.jess.stubbing.spoon.shim.ShimGenerator shimGenerator = 
            new de.upb.sse.jess.stubbing.spoon.shim.ShimGenerator(f);
        
        // Collect referenced types that need shims
        Set<String> referencedTypes = collectReferencedTypes(model, plans);
        int shimsGenerated = shimGenerator.generateShimsForReferencedTypes(referencedTypes);
        if (shimsGenerated > 0) {
            System.out.println("Generated " + shimsGenerated + " shim classes for common libraries");
        }
        
        // 4) Generate stubs
        // If your SpoonStubber has a (Factory) ctor, use that. Otherwise keep (Factory, CtModel).
        SpoonStubber stubber = new SpoonStubber(f, model);

        int created = 0;
        // Pass method plans to applyTypePlans so it can infer type parameter names (T, R, U, etc.)
        created += stubber.applyTypePlans(plans.typePlans, plans.methodPlans);       // types (classes/interfaces/annotations)

        created += stubber.applyFieldPlans(plans.fieldPlans);     // fields
        created += stubber.applyConstructorPlans(plans.ctorPlans);// constructors
        created += stubber.applyMethodPlans(plans.methodPlans);   // methods
        stubber.applyImplementsPlans(plans.implementsPlans);

        // CRITICAL FIXES: Apply all critical fixes after initial stubbing
        stubber.preserveGenericTypeArgumentsInUsages();  // Fix: Mono<T> becomes Mono
        stubber.autoImplementInterfaceMethods();         // Fix: Missing interface method implementations
        stubber.fixBuilderPattern();                      // Fix: Builder pattern support
        stubber.autoInitializeFields();                   // Fix: Field initialization (logger, etc.)
        stubber.addStreamApiMethods();                    // Fix: Stream API interface methods
        stubber.fixTypeConversionIssues();               // Fix: Unknown type conversion issues
        stubber.fixSyntaxErrors();                       // Fix: Syntax generation errors
        fixConstructorCallTypeArguments(model, f);        // Fix: Constructor calls to match variable declarations

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

        // Force FQN printing for all non-JDK type references to prevent invalid imports
        forceFQNForAllTypeReferences(model, f);
        
        // Clean up invalid imports before pretty printing
        cleanupInvalidImports(model, f);
        
        // Re-ensure unknown.Unknown imports after cleanup (in case they were removed)
        ensureUnknownImportsForAllTypes(model, f);
        
        // Final verification: check that imports are present before pretty printing
        System.out.println("[finalCheck] Verifying imports before pretty printing...");
        model.getAllTypes().forEach(type -> {
            boolean hasUnknown = type.getMethods().stream().anyMatch(m -> 
                m.getParameters().stream().anyMatch(p -> {
                    try {
                        String simple = p.getType().getSimpleName();
                        return "Unknown".equals(simple);
                    } catch (Throwable ignored) {
                        return false;
                    }
                })
            );
            if (hasUnknown) {
                boolean hasImport = hasUnknownImport(type, f);
                System.out.println("[finalCheck] Type " + type.getQualifiedName() + " uses Unknown, hasImport=" + hasImport);
                if (!hasImport) {
                    System.out.println("[finalCheck] WARNING: Import missing, adding it now...");
                    ensureUnknownImport(type, f);
                } else {
                    // Verify the import is actually in the CU
                    try {
                        CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
                        if (cu != null) {
                            System.out.println("[finalCheck] CU imports count: " + cu.getImports().size());
                            cu.getImports().forEach(imp -> {
                                try {
                                    CtReference r = imp.getReference();
                                    if (r instanceof CtTypeReference) {
                                        String qn = ((CtTypeReference<?>) r).getQualifiedName();
                                        System.out.println("[finalCheck] Import: " + qn);
                                    }
                                } catch (Throwable ignored) {}
                            });
                        }
                    } catch (Throwable e) {
                        System.err.println("[finalCheck] Error checking CU: " + e.getMessage());
                    }
                }
            }
        });

        launcher.prettyprint();
        
        // Post-process generated files to fix unknown.Unknown -> Unknown with import
        postProcessUnknownTypes(slicedSrcDir);
        
        // Post-process to remove duplicate nested class files (e.g., ComplexBuilderTest$Builder.java)
        // when the nested class is already in the parent file
        postProcessRemoveDuplicateNestedClassFiles(slicedSrcDir);
        
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
     */
    private static void cleanupInvalidImports(CtModel model, Factory f) {
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
                    
                    // NEVER remove unknown.Unknown imports - they are needed for simple name usage
                    if (r instanceof CtTypeReference) {
                        String qn = ((CtTypeReference<?>) r).getQualifiedName();
                        if ("unknown.Unknown".equals(qn)) {
                            return false; // Keep this import
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
                                // Likely a nested class without package - remove it
                                return true;
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
     * Collect all type references from the model and plans to determine which shims are needed.
     */
    private Set<String> collectReferencedTypes(CtModel model, SpoonCollector.CollectResult plans) {
        Set<String> referenced = new HashSet<>();
        
        // Collect from type plans
        for (var typePlan : plans.typePlans) {
            if (typePlan.qualifiedName != null) {
                referenced.add(typePlan.qualifiedName);
            }
        }
        
        // Collect from method plans (return types, parameter types, owner types)
        for (var methodPlan : plans.methodPlans) {
            if (methodPlan.ownerType != null) {
                String ownerQn = safeQN(methodPlan.ownerType);
                if (ownerQn != null) referenced.add(ownerQn);
            }
            if (methodPlan.returnType != null) {
                String returnQn = safeQN(methodPlan.returnType);
                if (returnQn != null) referenced.add(returnQn);
            }
            if (methodPlan.paramTypes != null) {
                for (var paramType : methodPlan.paramTypes) {
                    String paramQn = safeQN(paramType);
                    if (paramQn != null) referenced.add(paramQn);
                }
            }
        }
        
        // Collect from field plans
        for (var fieldPlan : plans.fieldPlans) {
            if (fieldPlan.ownerType != null) {
                String ownerQn = safeQN(fieldPlan.ownerType);
                if (ownerQn != null) referenced.add(ownerQn);
            }
            if (fieldPlan.fieldType != null) {
                String fieldQn = safeQN(fieldPlan.fieldType);
                if (fieldQn != null) referenced.add(fieldQn);
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
        
        // The existing code already collects all type references, so *Grpc should be included
        
        // Collect from all type references in the model
        for (CtTypeReference<?> typeRef : model.getElements(new TypeFilter<>(CtTypeReference.class))) {
            try {
                String qn = safeQN(typeRef);
                if (qn != null && !qn.isEmpty() && !qn.startsWith("java.") && 
                    !qn.startsWith("javax.") && !qn.startsWith("jakarta.")) {
                    referenced.add(qn);
                }
            } catch (Throwable ignored) {}
        }
        
        return referenced;
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
}
