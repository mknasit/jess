package de.upb.sse.jess.stubbing.spoon.collector;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.exceptions.AmbiguityException;
import de.upb.sse.jess.stubbing.SliceDescriptor;
import de.upb.sse.jess.stubbing.spoon.plan.*;
import de.upb.sse.jess.stubbing.spoon.context.ContextIndex;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtImportKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.visitor.Filter;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtUnaryOperator;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Collects all stub plans (types, fields, constructors, methods) needed to make a sliced set of
 * Java sources compile, under Spoon --no-classpath scenarios.
 *
 * NOTE: Names, signatures, and logic are intentionally preserved; this version only restructures
 * and documents the code for clarity and maintainability.
 */
public final class SpoonCollector {

    /* ======================================================================
     *                               NESTED TYPES
     * ====================================================================== */

    /** Aggregates all plans found during collection. */
    public final class CollectResult {
        public final List<TypeStubPlan> typePlans = new ArrayList<>();
        public final List<FieldStubPlan> fieldPlans = new ArrayList<>();
        public final List<ConstructorStubPlan> ctorPlans = new ArrayList<>();
        public final List<MethodStubPlan> methodPlans = new ArrayList<>();
        
        // Track FQNs to avoid duplicate type plans
        private final Set<String> typePlanFqns = new LinkedHashSet<>();

        // RULE 4: Track annotation attributes (annotation FQN -> attribute name -> attribute type)
        public final Map<String, Map<String, String>> annotationAttributes = new HashMap<>();
        
        // BUG CLASS 1 FIX: Store factory reference for checking if types exist in model
        private Factory factory;
        
        public void setFactory(Factory factory) {
            this.factory = factory;
        }

        /**
         * Add a type plan only if it hasn't been added before (by FQN).
         * Also checks if a type with the same simple name already exists in a known package,
         * and if so, skips adding the unknown package version.
         * 
         * BUG CLASS 1 FIX: Also checks if the type already exists in the model before adding as missing.
         */
        public void addTypePlanIfNew(TypeStubPlan plan, Factory factory) {
            String fqn = plan.qualifiedName;
            if (fqn == null || typePlanFqns.contains(fqn)) {
                return; // Already added or invalid
            }

            // INVARIANT: All TypeStubPlans must go through canonicalizeTypeFqn + canonicalizeNestedTypeFqn
            // Canonicalize FQN using ContextIndex if available (access outer class method)
            String canonicalFqn = canonicalizeTypeFqn(fqn, null);
            if (canonicalFqn == null) {
                canonicalFqn = fqn; // Fallback
            }
            
            // Apply nested type canonicalization (convert Outer.Inner to Outer$Inner if Outer is a TYPE)
            // Pass typePlanFqns so nested types can be detected when both Outer and Inner are planned in same run
            canonicalFqn = canonicalizeNestedTypeFqn(canonicalFqn, typePlanFqns);
            if (canonicalFqn == null) {
                // Primitive/void - do not stub
                return;
            }
            
            // INVARIANT VERIFICATION: Ensure canonicalization was applied
            assert canonicalFqn.equals(fqn) || canonicalFqn.contains("$") || 
                   !fqn.contains(".") || canonicalFqn.contains("unknown") :
                   "INVARIANT VIOLATION: TypeStubPlan FQN must be canonicalized before adding";
            
            // Check if canonicalized FQN already exists
            if (typePlanFqns.contains(canonicalFqn)) {
                return; // Already added (possibly with different FQN)
            }

            // BUG CLASS 1 FIX: Check if type already exists in the model (e.g., org.lwjgl.system.Checks)
            // If it exists, it's not missing - don't add it as a missing type plan
            Factory checkFactory = factory != null ? factory : this.factory;
            if (checkFactory != null) {
                try {
                    CtType<?> existingType = checkFactory.Type().get(canonicalFqn);
                    if (existingType != null) {
                        debugLog("Type " + canonicalFqn + " already exists in model, skipping missing type plan");
                        return; // Type exists - not missing
                    }
                } catch (Throwable ignored) {
                    // If we can't check, proceed with adding the plan
                }
            }

            // CRITICAL FIX: If this is an unknown package type, check if a type with the same
            // simple name already exists in the model or in planned types. If so, skip adding the unknown version.
            String unknownPackage = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
            if (canonicalFqn.startsWith(unknownPackage + ".")) {
                String simpleName = canonicalFqn.substring(unknownPackage.length() + 1);
                
                // Check 1: Check if any existing type plan has the same simple name but in a known package
                for (String existingFqn : typePlanFqns) {
                    if (existingFqn != null && !existingFqn.startsWith(unknownPackage + ".")) {
                        String existingSimple = existingFqn.substring(existingFqn.lastIndexOf('.') + 1);
                        if (simpleName.equals(existingSimple)) {
                            // A type with the same simple name already exists in a known package
                            // Skip adding the unknown package version
                            debugLog("Skipping " + canonicalFqn + " - " + existingFqn + " already planned");
                            return;
                        }
                    }
                }
                
                // Check 2: Check if a type with the same simple name exists in the model (e.g., slice types)
                // This prevents creating unknown.FieldAccess3 when FieldAccess3 already exists in the slice
                // Reuse checkFactory from above (already defined at line 106)
                if (checkFactory != null) {
                    try {
                        Collection<CtType<?>> allTypes = safeGetAllTypes(checkFactory.getModel());
                        for (CtType<?> existingType : allTypes) {
                            if (existingType != null) {
                                String existingSimple = existingType.getSimpleName();
                                String existingFqn = existingType.getQualifiedName();
                                // Check if simple name matches and it's not in unknown package
                                if (simpleName.equals(existingSimple) && 
                                    existingFqn != null && 
                                    !existingFqn.startsWith(unknownPackage + ".")) {
                                    // A type with the same simple name already exists in the model
                                    // Skip adding the unknown package version
                                    debugLog("Skipping " + canonicalFqn + 
                                        " - type with same simple name exists in model: " + existingFqn);
                                    return;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // If we can't check the model, proceed (best-effort)
                    }
                }
            }

            // IMPROVEMENT 7: Use ContextIndex.typeKindOf to choose stub kind more accurately
            TypeStubPlan.Kind finalKind = plan.kind;
            if (contextIndex != null) {
                Optional<ContextIndex.TypeKind> contextKind = contextIndex.typeKindOf(canonicalFqn);
                if (contextKind.isPresent()) {
                    // Map ContextIndex.TypeKind to TypeStubPlan.Kind
                    switch (contextKind.get()) {
                        case CLASS:
                            finalKind = TypeStubPlan.Kind.CLASS;
                            break;
                        case INTERFACE:
                            finalKind = TypeStubPlan.Kind.INTERFACE;
                            break;
                        case ENUM:
                            finalKind = TypeStubPlan.Kind.ENUM;
                            break;
                        case ANNOTATION:
                            finalKind = TypeStubPlan.Kind.ANNOTATION;
                            break;
                    }
                    System.out.println("[SpoonCollector] [ContextIndex] Using type kind " + finalKind + " for " + canonicalFqn + " (from context)");
                }
            }
            
            // Create new plan with canonicalized FQN and determined kind
            TypeStubPlan canonicalPlan = new TypeStubPlan(canonicalFqn, finalKind);
            typePlanFqns.add(canonicalFqn);
            typePlans.add(canonicalPlan);
        }
        
        /**
         * Legacy method for backward compatibility (deprecated).
         * @deprecated Use {@link #addTypePlanIfNew(TypeStubPlan, Factory)} instead
         */
        @Deprecated
        public void addTypePlanIfNew(TypeStubPlan plan) {
            // Call with null factory (will skip model check but still do canonicalization)
            addTypePlanIfNew(plan, null);
        }
    }

    /* ======================================================================
     *                               FIELDS
     * ====================================================================== */

    private final Factory f;
    private final JessConfiguration cfg;
    private final SliceDescriptor descriptor;  // Optional metadata
    private final java.nio.file.Path slicedSrcDir;  // Primary source of truth for slice detection
    private final Set<String> sliceTypeFqns;  // Cached from descriptor for convenience
    private final ContextIndex contextIndex;  // Optional lightweight context for tie-breaking

    // Control debug spam - set to true only for detailed debugging
    private static final boolean DEBUG_VERBOSE = false;
    
    private void debugLog(String message) {
        if (DEBUG_VERBOSE) {
            System.out.println("[SpoonCollector] DEBUG: " + message);
        }
    }
    
    // Helper to conditionally log debug messages
    private void debugLogIf(boolean condition, String message) {
        if (condition && DEBUG_VERBOSE) {
            System.out.println("[SpoonCollector] DEBUG: " + message);
        }
    }

    // Centralized unknown type FQN constant. (Do not rename or remove.)
    private static final String UNKNOWN_TYPE_FQN = de.upb.sse.jess.generation.unknown.UnknownType.CLASS;
    // Centralized unknown package constant
    private static final String UNKNOWN_PACKAGE = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;

    /* ======================================================================
     *                             CONSTRUCTION
     * ====================================================================== */

    /**
     * Primary constructor: uses path-based slice detection (primary) + descriptor (secondary).
     * @param f Factory
     * @param cfg Configuration
     * @param descriptor SliceDescriptor (optional metadata, not primary source of truth)
     * @param slicedSrcDir Path to slice directory (gen/) - primary source of truth for slice detection
     * @param contextIndex Optional ContextIndex for tie-breaking (type ambiguity, owner resolution)
     */
    public SpoonCollector(Factory f, JessConfiguration cfg, SliceDescriptor descriptor, java.nio.file.Path slicedSrcDir, ContextIndex contextIndex) {
        this.f = f;
        this.cfg = cfg;
        this.descriptor = descriptor;
        this.slicedSrcDir = slicedSrcDir;
        this.sliceTypeFqns = descriptor != null && descriptor.sliceTypeFqns != null ? descriptor.sliceTypeFqns : new HashSet<>();
        this.contextIndex = contextIndex;
        // DEBUG: Log the config value to verify it's being passed correctly
        System.out.println("[SpoonCollector] Constructor: failOnAmbiguity = " + (cfg != null ? cfg.isFailOnAmbiguity() : "null config"));
    }
    
    /**
     * Constructor without ContextIndex (backward compatibility).
     * @param f Factory
     * @param cfg Configuration
     * @param descriptor SliceDescriptor (optional metadata, not primary source of truth)
     * @param slicedSrcDir Path to slice directory (gen/) - primary source of truth for slice detection
     */
    public SpoonCollector(Factory f, JessConfiguration cfg, SliceDescriptor descriptor, java.nio.file.Path slicedSrcDir) {
        this(f, cfg, descriptor, slicedSrcDir, null);
    }
    
    /**
     * Legacy constructor for backward compatibility (deprecated).
     * @deprecated Use {@link #SpoonCollector(Factory, JessConfiguration, SliceDescriptor, Path, ContextIndex)} instead
     */
    @Deprecated
    public SpoonCollector(Factory f, JessConfiguration cfg, SliceDescriptor descriptor) {
        this(f, cfg, descriptor, null, null);
    }
    
    /**
     * Legacy constructor for backward compatibility (deprecated).
     * @deprecated Use {@link #SpoonCollector(Factory, JessConfiguration, SliceDescriptor)} instead
     */
    @Deprecated
    public SpoonCollector(Factory f, JessConfiguration cfg, java.nio.file.Path slicedSrcDir, Set<String> sliceTypeFqns) {
        // Delegate to descriptor-based constructor with null descriptor (process everything)
        this(f, cfg, null);
    }

    /**
     * Legacy constructor for backward compatibility (deprecated).
     * @deprecated Use {@link #SpoonCollector(Factory, JessConfiguration, SliceDescriptor)} instead
     */
    @Deprecated
    public SpoonCollector(Factory f, JessConfiguration cfg) {
        this(f, cfg, null, new HashSet<>());
    }

    /**
     * Canonicalize a type FQN using ContextIndex for tie-breaking.
     * 
     * If fqn is "unknown.X" OR bare "X" (no dot), query contextIndex.lookupBySimpleName("X"):
     * - If exactly one anchored candidate exists -> return it.
     * - If multiple candidates -> prefer one matching common imports of the usage site if available; otherwise keep current fqn.
     * 
     * If contextIndex is null -> keep existing behavior (return fqn as-is).
     * 
     * @param fqn The type FQN to canonicalize
     * @param usageSite Optional element where this type is used (for import-based disambiguation)
     * @return Canonicalized FQN
     */
    private String canonicalizeTypeFqn(String fqn, CtElement usageSite) {
        if (fqn == null || fqn.isEmpty() || contextIndex == null) {
            if (contextIndex == null && (fqn != null && !fqn.isEmpty())) {
                // Only log once per run, not for every call - this would be too verbose
                // ContextIndex usage is logged at the start
            }
            return fqn;
        }
        
        String unknownPackage = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
        String simpleName;
        boolean isUnknownPackage = fqn.startsWith(unknownPackage + ".");
        boolean isBareName = !fqn.contains(".");
        
        if (isUnknownPackage) {
            simpleName = fqn.substring(unknownPackage.length() + 1);
        } else if (isBareName) {
            simpleName = fqn;
        } else {
            // Already fully qualified and not in unknown package - keep as-is
            return fqn;
        }
        
        // Query context index
        Set<String> candidates = contextIndex.lookupBySimpleName(simpleName);
        if (candidates.isEmpty()) {
            // No candidates found - keep current FQN
            return fqn;
        }
        
        if (candidates.size() == 1) {
            // Exactly one candidate - use it
            String candidate = candidates.iterator().next();
            System.out.println("[SpoonCollector] [ContextIndex] Canonicalized " + fqn + " -> " + candidate + " (single candidate from context)");
            // VERIFICATION ASSERTION: Log canonicalization success (unknown.X or bare X -> real FQN)
            if (isUnknownPackage || isBareName) {
                System.out.println("[SpoonCollector] [ContextIndex] ✓ VERIFIED: Canonicalized " + fqn + " -> " + candidate);
            }
            return candidate;
        }
        
        // Multiple candidates - try to disambiguate using imports from usage site
        if (usageSite != null) {
            try {
                CtCompilationUnit cu = usageSite.getParent(CtCompilationUnit.class);
                if (cu != null) {
                    // Check imports to see if any candidate matches
                    for (CtImport imp : cu.getImports()) {
                        if (imp.getImportKind() == CtImportKind.TYPE) {
                            try {
                                CtReference ref = imp.getReference();
                                if (ref instanceof CtTypeReference) {
                                    String importFqn = ((CtTypeReference<?>) ref).getQualifiedName();
                                    if (candidates.contains(importFqn)) {
                                        System.out.println("[SpoonCollector] [ContextIndex] Canonicalized " + fqn + " -> " + importFqn + " (matches import from context)");
                                        return importFqn;
                                    }
                                }
                            } catch (Throwable ignored) {
                                // Skip invalid imports
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Can't check imports - fall through
            }
        }
        
        // Multiple candidates but can't disambiguate - keep current FQN
        System.out.println("[SpoonCollector] [ContextIndex] Multiple candidates for " + fqn + ": " + candidates + ", keeping original (cannot disambiguate)");
        return fqn;
    }
    
    /**
     * Canonicalize a nested type FQN by detecting if a prefix refers to a TYPE (not a package).
     * 
     * Rules:
     * - If fqn is null/empty -> return as is.
     * - If fqn is primitive or void -> return null (do not stub).
     * - If fqn contains '$' already -> keep as is (treat as nested marker).
     * - If fqn contains inner separator '.' (like Outer.Inner or A.B.C.Inner):
     *   - Try to detect whether some prefix of the name refers to a TYPE (not a package).
     *   - Detection order:
     *     1) Check gen-model known top-level types (use Spoon model: f.Type().getAll() or a precomputed Set of model FQNs).
     *     2) Check planned types (if plannedTypes set is provided) - for cases where Outer and Outer.Inner are both planned in same run.
     *     3) Else if contextIndex != null: check contextIndex.typeExists(prefixFqn) (or equivalent) to confirm prefix is a TYPE.
     *   - Choose the LONGEST prefix that exists as a TYPE.
     *   - If found, convert the remainder part by replacing '.' with '$':
     *     prefix + "$" + remainderWithDotsReplaced
     *   - Example: com.google.protobuf.GeneratedMessage.Builder
     *     if com.google.protobuf.GeneratedMessage exists as TYPE
     *     -> com.google.protobuf.GeneratedMessage$Builder
     *   - If no prefix is found -> keep original (treat as normal package/type).
     * 
     * @param fqn The type FQN to canonicalize for nested types
     * @param plannedTypes Optional set of already-planned type FQNs (for detecting nested types when both Outer and Inner are planned in same run)
     * @return Canonicalized FQN (with $ for nested types), or null if primitive/void, or original if no nesting detected
     */
    public String canonicalizeNestedTypeFqn(String fqn) {
        return canonicalizeNestedTypeFqn(fqn, null);
    }
    
    /**
     * Canonicalize a nested type FQN with optional planned types set.
     * 
     * @param fqn The type FQN to canonicalize for nested types
     * @param plannedTypes Optional set of already-planned type FQNs (for detecting nested types when both Outer and Inner are planned in same run)
     * @return Canonicalized FQN (with $ for nested types), or null if primitive/void, or original if no nesting detected
     */
    public String canonicalizeNestedTypeFqn(String fqn, Set<String> plannedTypes) {
        // If fqn is null/empty -> return as is
        if (fqn == null || fqn.isEmpty()) {
            return fqn;
        }
        
        // If fqn is primitive or void -> return null (do not stub)
        String trimmed = fqn.trim();
        if (trimmed.equals("void") || trimmed.equals("boolean") || trimmed.equals("byte") ||
            trimmed.equals("short") || trimmed.equals("int") || trimmed.equals("long") ||
            trimmed.equals("char") || trimmed.equals("float") || trimmed.equals("double")) {
            return null; // Do not stub primitives/void
        }
        
        // If fqn contains '$' already -> keep as is (treat as nested marker)
        if (fqn.contains("$")) {
            return fqn;
        }
        
        // If fqn doesn't contain '.' -> no nesting possible, keep as is
        if (!fqn.contains(".")) {
            return fqn;
        }
        
        // Try to detect if some prefix refers to a TYPE (not a package)
        // We'll check from longest to shortest prefix
        String[] parts = fqn.split("\\.");
        if (parts.length < 2) {
            return fqn; // Need at least 2 parts for nesting
        }
        
        // Build prefixes from longest to shortest (excluding the last part which is the inner type)
        // e.g., "com.google.protobuf.GeneratedMessage.Builder" -> check:
        //   "com.google.protobuf.GeneratedMessage" (longest)
        //   "com.google.protobuf" (shorter)
        //   "com.google" (even shorter)
        //   "com" (shortest)
        String longestTypePrefix = null;
        int longestPrefixLength = 0;
        
        // Check from longest prefix down to shortest
        for (int i = parts.length - 1; i >= 1; i--) {
            // Build prefix: parts[0] + "." + parts[1] + ... + parts[i-1]
            StringBuilder prefixBuilder = new StringBuilder(parts[0]);
            for (int j = 1; j < i; j++) {
                prefixBuilder.append(".").append(parts[j]);
            }
            String prefixFqn = prefixBuilder.toString();
            
            // Check if this prefix exists as a TYPE
            boolean isType = false;
            
            // Detection order 1: Check gen-model known top-level types
            try {
                CtType<?> existingType = f.Type().get(prefixFqn);
                if (existingType != null) {
                    isType = true;
                }
            } catch (Throwable ignored) {
                // Type doesn't exist in model
            }
            
            // Detection order 2: Check planned types (for cases where Outer and Outer.Inner are both planned in same run)
            if (!isType && plannedTypes != null && !plannedTypes.isEmpty()) {
                if (plannedTypes.contains(prefixFqn)) {
                    isType = true;
                }
            }
            
            // Detection order 3: If contextIndex != null, check contextIndex
            if (!isType && contextIndex != null) {
                try {
                    Optional<ContextIndex.TypeKind> kind = contextIndex.typeKindOf(prefixFqn);
                    if (kind.isPresent()) {
                        isType = true;
                    }
                } catch (Throwable ignored) {
                    // ContextIndex doesn't know about it
                }
            }
            
            // If we found a TYPE prefix, use the longest one
            if (isType && i > longestPrefixLength) {
                longestTypePrefix = prefixFqn;
                longestPrefixLength = i;
            }
        }
        
        // If we found a TYPE prefix, convert to nested format
        if (longestTypePrefix != null) {
            // Build the remainder (parts after the prefix)
            StringBuilder remainderBuilder = new StringBuilder(parts[longestPrefixLength]);
            for (int i = longestPrefixLength + 1; i < parts.length; i++) {
                remainderBuilder.append("$").append(parts[i]);
            }
            String nestedFqn = longestTypePrefix + "$" + remainderBuilder.toString();
            
            // Log the canonicalization
            System.out.println("[SpoonCollector] VERIFIED: Canonicalized nested type " + fqn + " -> " + nestedFqn + " (outer is a known TYPE)");
            
            return nestedFqn;
        }
        
        // No TYPE prefix found -> keep original (treat as normal package/type)
        return fqn;
    }
    
    /**
     * Check if an element is from the slice (should be processed).
     * PRIMARY: Uses file path under slicedSrcDir (gen/).
     * SECONDARY: Uses SliceDescriptor.sliceTypeFqns as additional confirmation.
     * 
     * An element is considered from the slice if:
     * 1. Its source file is under slicedSrcDir (gen/), OR
     * 2. Its type's FQN is in the slice descriptor (if descriptor available)
     * 
     * If slicedSrcDir is null and descriptor is null/empty, conservatively process everything.
     */
    private boolean isFromSlice(CtElement element) {
        CtType<?> type = element.getParent(CtType.class);
        if (type == null && element instanceof CtType) {
            type = (CtType<?>) element;
        }
        
        if (type == null) {
            // Can't determine - conservatively process it
            return true;
        }
        
        // PRIMARY CHECK: Use source position path (file path under gen/)
        if (slicedSrcDir != null) {
            try {
                spoon.reflect.cu.SourcePosition pos = type.getPosition();
                if (pos != null && pos.getFile() != null) {
                    java.nio.file.Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                    java.nio.file.Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
                    if (filePath.startsWith(sliceRoot)) {
                        return true;  // File is in gen/ - definitely slice type
                    }
                }
            } catch (Throwable ignored) {
                // If we can't check path, fall through to descriptor check
            }
        }
        
        // SECONDARY CHECK: Use descriptor.sliceTypeFqns as additional confirmation
        if (descriptor != null && descriptor.sliceTypeFqns != null && !descriptor.sliceTypeFqns.isEmpty()) {
            String typeFqn = type.getQualifiedName();
            if (typeFqn != null && descriptor.isSliceType(typeFqn)) {
                return true;  // FQN is in descriptor - treat as slice type
            }
        }
        
        // If no path match and no descriptor match, it's not from slice
        // But if we have no way to determine (no slicedSrcDir, no descriptor), be conservative
        if (slicedSrcDir == null && (descriptor == null || descriptor.sliceTypeFqns == null || descriptor.sliceTypeFqns.isEmpty())) {
            return true;  // No slice info available - process everything (backward compat)
        }
        
        return false;
    }

    /* ======================================================================
     *                                DRIVER
     * ====================================================================== */

    /**
     * Main entry: scan the model and produce an aggregated set of stub plans.
     */
    public CollectResult collect(CtModel model) {
        CollectResult result = new CollectResult();
        result.setFactory(f); // BUG CLASS 1 FIX: Provide factory for type existence checks

        // --- order matters: normalize constants BEFORE collecting types ---
        normalizeUnresolvedConstants(model);

        // --- order matters only for readability; each pass is independent ---
        collectUnresolvedFields(model, result);
        // NEW: collect field writes (x.f = 1;)
        collectUnresolvedFieldWrites(model, result);
        // NEW: collect method references (Type::method, Type::new)
        collectMethodReferences(model, result);
        // NEW: collect bare unknown simple names (like 'g' in 'int a = util() + g;')
        collectBareUnknownSimpleNames(model, result);
        collectUnresolvedCtorCalls(model, result);
        collectUnresolvedMethodCalls(model, result);
        collectUnresolvedAnnotations(model, result);
        // NEW: collect try-with-resources (ensure AutoCloseable + close())
        collectTryWithResources(model, result);

        collectExceptionTypes(model, result);
        collectSupertypes(model, result);

        collectFromInstanceofCastsClassLiteralsAndForEach(model, result);
        collectUnresolvedDeclaredTypes(model, result);
        collectAnnotationTypeUsages(model, result);
        collectOverloadGaps(model, result);

        // NEW: ensure static imports (fields / on-demand) get minimal stubs
        collectStaticImports(model, result);

        seedOnDemandImportAnchors(model, result);
        seedExplicitTypeImports(model, result);

        // Ensure owners exist for any planned members / references discovered above.
        // All type plans must go through addTypePlanIfNew() to enforce nested canonicalization and dedup
        for (TypeStubPlan plan : ownersNeedingTypes(result)) {
            result.addTypePlanIfNew(plan, f);
        }

        // POST-PROCESSING: Remove duplicate unknown package types that have known package equivalents
        removeDuplicateUnknownPackageTypes(result);

        return result;
    }

    /* ======================================================================
     *                              FIELDS PASS
     * ====================================================================== */

    /**
     * Collect field stubs from unresolved field accesses (including static and instance cases).
     */
    private void collectUnresolvedFields(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        List<CtFieldAccess<?>> unresolved = getElementsFromSliceTypes(model, (CtFieldAccess<?> fa) -> {
            var ref = fa.getVariable();
            if (ref == null) return true;
            // Check if unresolved, but catch StackOverflowError to avoid infinite loops
            try {
                return ref.getDeclaration() == null;
            } catch (StackOverflowError | OutOfMemoryError e) {
                // If we get stack overflow, assume unresolved to avoid infinite loops
                return true;
            } catch (Throwable ignored) {
                // Other errors - assume resolved to be safe
                return false;
            }
        });

        for (CtFieldAccess<?> fa : unresolved) {
            // Ignore class-literals like Foo.class (modeled as a field 'class').
            String vname = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : null);
            if ("class".equals(vname) && fa.getTarget() instanceof CtTypeAccess) continue;

            // Ignore enum constants inside annotations.
            if (fa.getParent(CtAnnotation.class) != null) continue;
            
            // NEW: Skip bare field accesses (no target) - these are handled by collectBareUnknownSimpleNames
            // This prevents creating unknown.g types for bare names like 'g'
            if (fa.getTarget() == null) {
                // This is a bare field access (like 'g' not 'obj.g' or 'Type.g')
                // Skip it here - collectBareUnknownSimpleNames will handle it
                continue;
            }

            // Get field name early for debug logging
            String fieldName = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "f");

            // RULE 5: For standalone field statements (like "so.size;"), check ambiguity if failOnAmbiguity is true
            // If failOnAmbiguity is false, skip them (they're not used, so no stub needed)
            boolean isStandalone = isStandaloneFieldStatement(fa);
            if (DEBUG_VERBOSE) {
                System.out.println("[SpoonCollector] DEBUG: isStandaloneFieldStatement(" + fieldName + ") = " + isStandalone);
            }
            if (isStandalone) {
                CtElement parent = fa.getParent();
                System.out.println("[SpoonCollector] DEBUG: Parent type: " + (parent != null ? parent.getClass().getSimpleName() : "null"));
                if (parent != null) {
                    try {
                        CtRole role = fa.getRoleInParent();
                        System.out.println("[SpoonCollector] DEBUG: Role in parent: " + role);
                    } catch (Throwable e) {
                        System.out.println("[SpoonCollector] DEBUG: Could not get role: " + e.getMessage());
                    }
                }
            }
            if (isStandalone && !cfg.isFailOnAmbiguity()) {
                continue; // Skip standalone field statements when ambiguity is allowed
            }
            // If isStandalone && failOnAmbiguity is true, continue processing to check for ambiguity

            boolean isStatic = fa.getTarget() instanceof CtTypeAccess<?>;

            // DEBUG: Log field access details
            if (DEBUG_VERBOSE) {
                System.out.println("[SpoonCollector] DEBUG: Collecting field access: " + fieldName);
            }
            if (fa.getTarget() != null) {
                if (DEBUG_VERBOSE) {
                    System.out.println("[SpoonCollector] DEBUG: Field target type: " + fa.getTarget().getClass().getSimpleName());
                }
                if (fa.getTarget() instanceof CtVariableRead) {
                    CtVariableRead<?> vr = (CtVariableRead<?>) fa.getTarget();
                    if (vr.getVariable() != null) {
                        try {
                            CtTypeReference<?> varType = vr.getVariable().getType();
                            debugLog("Variable type (from declaration): " + safeQN(varType));
                        } catch (Throwable e) {
                            debugLog("Could not get variable type: " + e.getMessage());
                        }
                    }
                }
                try {
                    CtTypeReference<?> targetType = fa.getTarget().getType();
                    debugLog("Target expression type: " + safeQN(targetType));
                } catch (Throwable e) {
                    debugLog("Could not get target type: " + e.getMessage());
                }
            }

            CtTypeReference<?> rawOwner = resolveOwnerTypeFromFieldAccess(fa);
            debugLog("Raw owner from resolveOwnerTypeFromFieldAccess: " + safeQN(rawOwner));

            // CRITICAL FIX: If the type is already fully qualified, check multiple sources before routing
            // Don't route it through chooseOwnerPackage which might incorrectly route it to unknown package
            String rawOwnerQn = safeQN(rawOwner);
            CtTypeReference<?> ownerRef = null;
            if (rawOwnerQn != null && rawOwnerQn.contains(".") && !rawOwnerQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                // Priority 1: Check if this type is in the slice - if so, use it directly
                if (descriptor != null && descriptor.isSliceType(rawOwnerQn)) {
                    System.out.println("[SpoonCollector] DEBUG: Type is in slice, using directly: " + rawOwnerQn);
                    ownerRef = rawOwner;
                } else {
                    // Priority 2: Check if it's a parameter type in the current method
                    // Use Spoon's model to get package information from parameter types
                    boolean isParameterType = false;
                    try {
                        CtMethod<?> currentMethod = fa.getParent(CtMethod.class);
                        if (currentMethod != null) {
                            for (CtParameter<?> param : currentMethod.getParameters()) {
                                CtTypeReference<?> paramType = param.getType();
                                if (paramType != null) {
                                    // Try multiple ways to get the qualified name from Spoon's model
                                    String paramTypeQn = null;
                                    try {
                                        paramTypeQn = paramType.getQualifiedName();
                                    } catch (Throwable ignored) {}

                                    if (paramTypeQn == null || paramTypeQn.isEmpty()) {
                                        // Try to construct from package + simple name
                                        try {
                                            String pkg = paramType.getPackage() != null ? paramType.getPackage().getQualifiedName() : "";
                                            String simple = paramType.getSimpleName();
                                            if (pkg != null && !pkg.isEmpty() && simple != null && !simple.isEmpty()) {
                                                paramTypeQn = pkg + "." + simple;
                                            }
                                        } catch (Throwable ignored) {}
                                    }

                                    // Also try safeQN as fallback
                                    if (paramTypeQn == null || paramTypeQn.isEmpty()) {
                                        paramTypeQn = safeQN(paramType);
                                    }

                                    debugLog("Checking parameter type: " + paramTypeQn + " vs " + rawOwnerQn);

                                    // Check exact match first
                                    if (rawOwnerQn != null && paramTypeQn != null && rawOwnerQn.equals(paramTypeQn)) {
                                        debugLog("Type is a parameter type in current method (exact match), using directly: " + rawOwnerQn);
                                        ownerRef = rawOwner;
                                        isParameterType = true;
                                        break;
                                    }

                                    // Also check if simple names match and rawOwnerQn is fully qualified
                                    // This handles cases where paramType.getQualifiedName() returns just the simple name
                                    if (rawOwnerQn != null && paramTypeQn != null && rawOwnerQn.contains(".")) {
                                        String rawOwnerSimple = rawOwnerQn.substring(rawOwnerQn.lastIndexOf('.') + 1);
                                        String paramTypeSimple = paramType.getSimpleName();
                                        if (rawOwnerSimple.equals(paramTypeSimple) && rawOwnerSimple.equals(paramTypeQn)) {
                                            // Simple names match and paramTypeQn is just the simple name
                                            // This means paramType is the same type, just without package info
                                            debugLog("Type is a parameter type in current method (simple name match), using directly: " + rawOwnerQn);
                                            ownerRef = rawOwner;
                                            isParameterType = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {
                        debugLog("Exception checking parameter types: " + e.getMessage());
                        e.printStackTrace();
                    }

                    if (!isParameterType) {
                        // Priority 3: Check if it exists in model
                        try {
                            CtType<?> typeInModel = f.Type().get(rawOwnerQn);
                            if (typeInModel != null) {
                                debugLog("Type exists in model, using directly: " + rawOwnerQn);
                                ownerRef = rawOwner;
                            } else {
                                // Type doesn't exist - route through chooseOwnerPackage
                                debugLog("Type not in model, routing through chooseOwnerPackage");
                                ownerRef = chooseOwnerPackage(rawOwner, fa);
                            }
                        } catch (Throwable e) {
                            // If we can't check, route through chooseOwnerPackage
                            debugLog("Exception checking model, routing through chooseOwnerPackage: " + e.getMessage());
                            ownerRef = chooseOwnerPackage(rawOwner, fa);
                        }
                    }
                }
            } else {
                // Not fully qualified or in unknown package - route through chooseOwnerPackage
                ownerRef = chooseOwnerPackage(rawOwner, fa);
            }

            // IMPROVEMENT 8: Use ContextIndex.fieldExists + getSuperTypes for field owner resolution
            // If owner is still unknown or missing, try ContextIndex
            if (contextIndex != null && (ownerRef == null || (UNKNOWN_PACKAGE + ".Missing").equals(safeQN(ownerRef)))) {
                // Try to find field in super types of enclosing class
                try {
                    CtClass<?> enclosingClass = fa.getParent(CtClass.class);
                    if (enclosingClass != null) {
                        String declaringTypeFqn = safeQN(enclosingClass.getReference());
                        if (declaringTypeFqn != null && !declaringTypeFqn.isEmpty()) {
                            // Use getSuperTypes which includes superclass chain + interfaces
                            List<String> superTypes = contextIndex.getSuperTypes(declaringTypeFqn);
                            List<String> chainToCheck = new ArrayList<>();
                            chainToCheck.add(declaringTypeFqn);
                            chainToCheck.addAll(superTypes);
                            
                            System.out.println("[SpoonCollector] [ContextIndex] Checking super types for field " + fieldName + " starting from " + declaringTypeFqn);
                            
                            // Walk the chain to find the first type where the field exists
                            for (String typeFqn : chainToCheck) {
                                if (contextIndex.fieldExists(typeFqn, fieldName)) {
                                    ownerRef = f.Type().createReference(typeFqn);
                                    ownerRef = chooseOwnerPackage(ownerRef, fa);
                                    System.out.println("[SpoonCollector] [ContextIndex] ✓ Found field " + fieldName + " on " + typeFqn + " (via super types)");
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Best-effort: continue with existing ownerRef
                }
            }
            
            // Ensure ownerRef is initialized (fallback)
            if (ownerRef == null) {
                ownerRef = chooseOwnerPackage(rawOwner, fa);
            }
            debugLog("Final owner: " + safeQN(ownerRef));
            if (isJdkType(ownerRef)) continue;

            // Check if this is a constant-like field (Owner.CONSTANT pattern)
            boolean isConstantLike = isConstantLikeName(fieldName);
            debugLog("Field " + fieldName + " - isStatic: " + isStatic + ", isConstantLike: " + isConstantLike);
            if (isConstantLike && isStatic) {
                // This is likely Owner.CONSTANT pattern - determine owner type kind
                TypeStubPlan.Kind ownerKind = determineOwnerTypeKind(ownerRef, fieldName, fa, model);
                // Ensure owner type exists with the determined kind
                out.addTypePlanIfNew(new TypeStubPlan(ownerRef.getQualifiedName(), ownerKind), f);
            }

            CtTypeReference<?> fieldType = null;

            // CRITICAL FIX 1: Check if field exists in the model/slice and get its actual type
            // This uses the context that's available - the actual source files!
            // Try multiple ways to get the type from the model:
            // 1. Try getTypeDeclaration() (works if type is already resolved)
            // 2. Try f.Type().get(fqn) (works if type exists in model even if not resolved)
            // 3. Try searching all types in model (fallback)
            if (fieldName != null && ownerRef != null) {
                String ownerFqn = safeQN(ownerRef);
                debugLog("Checking if field " + fieldName + " exists in type " + ownerFqn);

                CtType<?> ownerTypeDecl = null;
                try {
                    // Method 1: Try getTypeDeclaration() first
                    ownerTypeDecl = ownerRef.getTypeDeclaration();
                    debugLog("getTypeDeclaration() returned: " + (ownerTypeDecl != null ? ownerTypeDecl.getQualifiedName() : "null"));
                } catch (StackOverflowError | OutOfMemoryError e) {
                    debugLog("StackOverflowError in getTypeDeclaration()");
                } catch (Throwable e) {
                    debugLog("Exception in getTypeDeclaration(): " + e.getMessage());
                }

                // Method 2: If getTypeDeclaration() failed, try direct lookup by FQN
                if (ownerTypeDecl == null && ownerFqn != null && !ownerFqn.isEmpty() && !ownerFqn.startsWith(UNKNOWN_PACKAGE + ".")) {
                    try {
                        ownerTypeDecl = f.Type().get(ownerFqn);
                        debugLog("f.Type().get(" + ownerFqn + ") returned: " + (ownerTypeDecl != null ? ownerTypeDecl.getQualifiedName() : "null"));
                    } catch (StackOverflowError | OutOfMemoryError e) {
                        debugLog("StackOverflowError in f.Type().get()");
                    } catch (Throwable e) {
                        debugLog("Exception in f.Type().get(): " + e.getMessage());
                    }
                }

                // Method 3: If still not found, search all types in model (slower but comprehensive)
                if (ownerTypeDecl == null && ownerFqn != null && !ownerFqn.isEmpty() && !ownerFqn.startsWith(UNKNOWN_PACKAGE + ".")) {
                    try {
                        Collection<CtType<?>> allTypes = safeGetAllTypes(f.getModel());
                        String simpleName = ownerFqn.substring(ownerFqn.lastIndexOf('.') + 1);
                        for (CtType<?> t : allTypes) {
                            if (t != null && simpleName.equals(t.getSimpleName())) {
                                String tQn = t.getQualifiedName();
                                if (tQn != null && tQn.equals(ownerFqn)) {
                                    ownerTypeDecl = t;
                                    debugLog("Found type in model by searching: " + tQn);
                                    break;
                                }
                            }
                        }
                    } catch (StackOverflowError | OutOfMemoryError e) {
                        debugLog("StackOverflowError searching all types");
                    } catch (Throwable e) {
                        debugLog("Exception searching all types: " + e.getMessage());
                    }
                }

                // Now try to get the field from the type
                if (ownerTypeDecl != null) {
                    try {
                        CtField<?> actualField = ownerTypeDecl.getField(fieldName);
                        if (actualField != null) {
                            CtTypeReference<?> actualFieldType = actualField.getType();
                            if (actualFieldType != null) {
                                String actualTypeQn = safeQN(actualFieldType);
                                debugLog("Field " + fieldName + " exists in model, actual type: " + actualTypeQn);
                                fieldType = actualFieldType;
                            } else {
                                debugLog("Field " + fieldName + " found but type is null");
                            }
                        } else {
                            debugLog("Field " + fieldName + " not found in type " + ownerTypeDecl.getQualifiedName());
                        }
                    } catch (StackOverflowError | OutOfMemoryError e) {
                        debugLog("StackOverflowError getting field from type");
                    } catch (Throwable e) {
                        debugLog("Exception getting field from type: " + e.getMessage());
                    }
                } else {
                    debugLog("Could not find type " + ownerFqn + " in model");
                }
            }

            // CRITICAL FIX 2: For static field accesses that are constant-like (enum constants),
            // the field type should be the owner type (the enum type itself), not Unknown
            // Also handle cases like PUSH_SERVER_Exception where it's mostly uppercase but has some lowercase
            if (fieldType == null && isStatic) {
                // Check if it looks like an enum constant:
                // - Starts with uppercase letter
                // - Contains underscores (common in enum constants)
                // - Mostly uppercase (at least 70% uppercase characters, excluding underscores)
                boolean looksLikeEnumConstant = false;
                if (fieldName != null && fieldName.length() > 1) {
                    long upperCount = fieldName.chars().filter(Character::isUpperCase).count();
                    long lowerCount = fieldName.chars().filter(Character::isLowerCase).count();
                    long totalLetters = upperCount + lowerCount;
                    boolean hasUnderscores = fieldName.contains("_");
                    boolean startsWithUpper = Character.isUpperCase(fieldName.charAt(0));

                    // If it has underscores and starts with uppercase, it's likely an enum constant
                    // Or if it's mostly uppercase (at least 70%)
                    looksLikeEnumConstant = (hasUnderscores && startsWithUpper) ||
                        (totalLetters > 0 && (upperCount * 100 / totalLetters) >= 70);
                }

                if (looksLikeEnumConstant || isConstantLike) {
                    // For enum constants like EventType.PUSH_SERVER_Exception,
                    // the field type is EventType (the owner type), not Unknown
                    debugLog("Static constant-like field, using owner type as field type: " + safeQN(ownerRef));
                    fieldType = ownerRef;
                }
            }

            // Fallback: infer from usage context
            if (fieldType == null) {
                debugLog("Field " + fieldName + " - NOT using owner type (isStatic: " + isStatic + ", isConstantLike: " + isConstantLike + "), inferring from usage");
                debugLog("isStandalone = " + isStandalone + " (computed earlier)");
                fieldType = inferFieldTypeFromUsage(fa);
                debugLog("inferFieldTypeFromUsage returned: " + (fieldType != null ? safeQN(fieldType) : "null"));
            }

            // CRITICAL FIX: For standalone field statements, ensure we don't use incorrect type inference
            // Spoon's fa.getType() can return wrong types (like enclosing class) for unresolved fields
            // Also check if fieldType matches the enclosing class name (even if isStandalone detection failed)
            if (fieldType != null) {
                String fieldTypeQn = safeQN(fieldType);
                String fieldTypeSimple = fieldType.getSimpleName();
                CtType<?> enclosingType = fa.getParent(CtType.class);
                if (enclosingType != null && fieldTypeSimple != null) {
                    String enclosingQn = safeQN(enclosingType.getReference());
                    String enclosingSimple = enclosingType.getSimpleName();
                    // Check both exact FQN match and simple name match (to catch unknown.FieldAccess3 vs FieldAccess3)
                    // This is a safety check even if isStandalone detection failed
                    if ((fieldTypeQn != null && fieldTypeQn.equals(enclosingQn)) ||
                        (enclosingSimple != null && fieldTypeSimple.equals(enclosingSimple))) {
                        debugLog("Field type incorrectly inferred as enclosing class " + 
                            (fieldTypeQn != null && fieldTypeQn.equals(enclosingQn) ? fieldTypeQn : fieldTypeSimple) + 
                            " (isStandalone=" + isStandalone + "), treating as null");
                        fieldType = null; // Reset to null so ambiguity check runs
                    }
                }
            }

            if (fieldType == null) {
                debugLog("fieldType is null, failOnAmbiguity = " + cfg.isFailOnAmbiguity());
                if (cfg.isFailOnAmbiguity()) {
                    String ownerQN = ownerRef != null ? ownerRef.getQualifiedName() : "<unknown>";
                    String simple = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "<missing>");
                    String errorMsg = "Ambiguous field (no usable type context): " + ownerQN + "#" + simple;
                    debugLog("Throwing AmbiguityException: " + errorMsg);
                    AmbiguityException ex = new AmbiguityException(errorMsg);
                    debugLog("Exception created, about to throw: " + ex.getClass().getName());
                    throw ex;
                }
                debugLog("Not throwing exception (failOnAmbiguity=false), using Unknown type");
                fieldType = f.Type().createReference(UNKNOWN_TYPE_FQN);
            }

            out.fieldPlans.add(new FieldStubPlan(ownerRef, fieldName, fieldType, isStatic));
        }
    }

    /**
     * Collect field write stubs from unresolved field writes (x.f = 1; Type.F = ...).
     * Handles assignments to unresolved fields.
     */
    private void collectUnresolvedFieldWrites(CtModel model, CollectResult out) {
        // Get all CtFieldWrite elements from slice types
        List<CtFieldWrite<?>> unresolved = getElementsFromSliceTypes(model, (CtFieldWrite<?> fw) -> {
            var ref = fw.getVariable();
            if (ref == null) return true;
            // Check if unresolved, but catch StackOverflowError to avoid infinite loops
            try {
                return ref.getDeclaration() == null;
            } catch (StackOverflowError | OutOfMemoryError e) {
                return true; // Assume unresolved
            } catch (Throwable ignored) {
                return false; // Assume resolved
            }
        });

        for (CtFieldWrite<?> fw : unresolved) {
            String fieldName = (fw.getVariable() != null ? fw.getVariable().getSimpleName() : "f");
            
            // Determine owner and static status
            CtTypeReference<?> ownerRef = null;
            boolean isStatic = false;
            
            CtExpression<?> target = fw.getTarget();
            if (target instanceof CtTypeAccess) {
                // Static field write: Type.F = ...
                CtTypeAccess<?> typeAccess = (CtTypeAccess<?>) target;
                ownerRef = typeAccess.getAccessedType();
                isStatic = true;
            } else if (target != null) {
                // Instance field write: x.f = ...
                // Reuse owner resolution logic from field reads
                ownerRef = resolveOwnerTypeFromFieldAccess(fw);
                if (ownerRef == null) {
                    // Fallback: try to infer from target type
                    try {
                        CtTypeReference<?> targetType = target.getType();
                        if (targetType != null) {
                            ownerRef = targetType;
                        }
                    } catch (Throwable ignored) {}
                }
                isStatic = false;
            }
            
            if (ownerRef == null) {
                continue; // Can't determine owner
            }
            
            // Canonicalize owner FQN
            String ownerFqn = safeQN(ownerRef);
            if (ownerFqn == null) {
                continue;
            }
            ownerFqn = canonicalizeNestedTypeFqn(ownerFqn, out.typePlanFqns);
            if (ownerFqn == null) {
                continue; // Primitive/void
            }
            ownerRef = f.Type().createReference(ownerFqn);
            
            // Infer field type from assignment value (lightweight)
            // CtFieldWrite extends CtAssignment, cast to access getAssignment()
            CtExpression<?> assignedValue = ((CtAssignment<?, ?>) fw).getAssignment();
            CtTypeReference<?> fieldType = inferFieldTypeFromAssignmentValue(assignedValue);
            
            // Ensure owner type is planned
            TypeStubPlan ownerPlan = new TypeStubPlan(ownerFqn, TypeStubPlan.Kind.CLASS);
            out.addTypePlanIfNew(ownerPlan, f);
            
            // Create field plan
            out.fieldPlans.add(new FieldStubPlan(ownerRef, fieldName, fieldType, isStatic));
        }
    }
    
    /**
     * Infer field type from assignment value (lightweight inference).
     * Returns Object/unknown if inference fails.
     */
    private CtTypeReference<?> inferFieldTypeFromAssignmentValue(CtExpression<?> value) {
        if (value == null) {
            return f.Type().createReference("java.lang.Object");
        }
        
        // Integer literal -> int
        if (value instanceof CtLiteral) {
            CtLiteral<?> lit = (CtLiteral<?>) value;
            Object val = lit.getValue();
            if (val instanceof Integer || val instanceof Long || val instanceof Short || val instanceof Byte) {
                return f.Type().createReference("int");
            } else if (val instanceof String) {
                return f.Type().createReference("java.lang.String");
            } else if (val instanceof Boolean) {
                return f.Type().createReference("boolean");
            } else if (val instanceof Character) {
                return f.Type().createReference("char");
            } else if (val instanceof Float || val instanceof Double) {
                return f.Type().createReference("double");
            }
        }
        
        // Class literal -> Class<?>
        if (value instanceof CtTypeAccess) {
            return f.Type().createReference("java.lang.Class");
        }
        
        // Try to get type from expression
        try {
            CtTypeReference<?> exprType = value.getType();
            if (exprType != null) {
                return exprType;
            }
        } catch (Throwable ignored) {}
        
        // Fallback to Object
        return f.Type().createReference("java.lang.Object");
    }

    /**
     * Collect method reference stubs (Type::method, obj::method, Type::new).
     * Handles method references like Type::m, obj::m, Type::new.
     */
    private void collectMethodReferences(CtModel model, CollectResult out) {
        // Get all CtExecutableReferenceExpression elements from slice types
        List<CtExecutableReferenceExpression<?, ?>> methodRefs = getElementsFromSliceTypes(model, 
            (CtExecutableReferenceExpression<?, ?> mref) -> {
                // Check if executable reference is unresolved
                CtExecutableReference<?> execRef = mref.getExecutable();
                if (execRef == null) return true;
                try {
                    return execRef.getDeclaration() == null;
                } catch (StackOverflowError | OutOfMemoryError e) {
                    return true; // Assume unresolved
                } catch (Throwable ignored) {
                    return false; // Assume resolved
                }
            });

        for (CtExecutableReferenceExpression<?, ?> mref : methodRefs) {
            CtExecutableReference<?> execRef = mref.getExecutable();
            if (execRef == null) {
                continue;
            }
            
            // Determine owner
            CtTypeReference<?> ownerRef = null;
            boolean isStatic = false;
            
            CtExpression<?> receiver = mref.getTarget();
            if (receiver instanceof CtTypeAccess) {
                // Type::method or Type::new
                CtTypeAccess<?> typeAccess = (CtTypeAccess<?>) receiver;
                ownerRef = typeAccess.getAccessedType();
                isStatic = true;
            } else if (receiver != null) {
                // obj::method
                try {
                    ownerRef = receiver.getType();
                    isStatic = false;
                } catch (Throwable ignored) {
                    continue; // Can't determine owner
                }
            } else {
                continue; // No receiver
            }
            
            if (ownerRef == null) {
                continue;
            }
            
            // Canonicalize owner FQN
            String ownerFqn = safeQN(ownerRef);
            if (ownerFqn == null) {
                continue;
            }
            ownerFqn = canonicalizeNestedTypeFqn(ownerFqn, out.typePlanFqns);
            if (ownerFqn == null) {
                continue; // Primitive/void
            }
            ownerRef = f.Type().createReference(ownerFqn);
            
            // Determine kind: constructor reference (Type::new) or method reference
            String methodName = execRef.getSimpleName();
            boolean isConstructor = "new".equals(methodName);
            
            // Ensure owner type is planned
            TypeStubPlan ownerPlan = new TypeStubPlan(ownerFqn, TypeStubPlan.Kind.CLASS);
            out.addTypePlanIfNew(ownerPlan, f);
            
            if (isConstructor) {
                // Constructor reference: Type::new
                out.ctorPlans.add(new ConstructorStubPlan(ownerRef, new ArrayList<>()));
            } else {
                // Method reference: Type::method or obj::method
                // Use Object as return type (lightweight, no heavy inference)
                CtTypeReference<?> returnType = f.Type().createReference("java.lang.Object");
                // Empty param list (method references don't specify params)
                List<CtTypeReference<?>> paramTypes = new ArrayList<>();
                List<CtTypeReference<?>> thrownTypes = new ArrayList<>();
                out.methodPlans.add(new MethodStubPlan(ownerRef, methodName, returnType, paramTypes, isStatic, 
                    MethodStubPlan.Visibility.PUBLIC, thrownTypes));
            }
        }
    }

    /**
     * Collect try-with-resources requirements (ensure AutoCloseable + close()).
     * For each resource type R in try-with-resources, ensures R implements AutoCloseable and has close().
     */
    private void collectTryWithResources(CtModel model, CollectResult out) {
        // Get all CtTry elements from slice types
        List<CtTry> tryBlocks = getElementsFromSliceTypes(model, (CtTry t) -> true);
        
        for (CtTry tryBlock : tryBlocks) {
            // Get resource variables from try-with-resources using getElements()
            // Resources are stored as CtLocalVariable in the try block
            List<CtLocalVariable<?>> resources = tryBlock.getElements(new TypeFilter<>(CtLocalVariable.class));
            if (resources == null || resources.isEmpty()) {
                continue; // Not a try-with-resources or no resources
            }
            
            // Filter to only resource variables (those declared in try-with-resources)
            // In Spoon, resource variables are direct children of CtTry
            List<CtLocalVariable<?>> resourceVars = new ArrayList<>();
            for (CtLocalVariable<?> var : resources) {
                // Check if this variable is a resource (parent is CtTry)
                if (var.getParent() instanceof CtTry) {
                    resourceVars.add(var);
                }
            }
            
            if (resourceVars.isEmpty()) {
                continue; // Not a try-with-resources
            }
            
            for (CtLocalVariable<?> resource : resourceVars) {
                CtTypeReference<?> resourceType = resource.getType();
                if (resourceType == null) {
                    continue;
                }
                
                String resourceTypeFqn = safeQN(resourceType);
                if (resourceTypeFqn == null) {
                    continue;
                }
                
                // Skip JDK types (they already implement AutoCloseable)
                if (resourceTypeFqn.startsWith("java.") || resourceTypeFqn.startsWith("javax.")) {
                    continue;
                }
                
                // Canonicalize resource type FQN
                String canonicalResourceTypeFqn = canonicalizeNestedTypeFqn(resourceTypeFqn, out.typePlanFqns);
                if (canonicalResourceTypeFqn == null) {
                    continue; // Primitive/void
                }
                
                // Ensure resource type is planned
                TypeStubPlan resourcePlan = new TypeStubPlan(canonicalResourceTypeFqn, TypeStubPlan.Kind.CLASS);
                out.addTypePlanIfNew(resourcePlan, f);
                
                // Mark that this type needs AutoCloseable interface
                // We'll handle this in the stub generator by ensuring the type implements AutoCloseable
                // For now, just ensure the type exists and has close() method
                
                final String finalResourceTypeFqn = canonicalResourceTypeFqn; // Make final for lambda
                CtTypeReference<?> ownerRef = f.Type().createReference(finalResourceTypeFqn);
                
                // Ensure close():void method exists (required by AutoCloseable)
                // Check if close() method plan already exists
                boolean hasCloseMethod = out.methodPlans.stream()
                    .anyMatch(mp -> "close".equals(mp.name) && 
                                   finalResourceTypeFqn.equals(safeQN(mp.ownerType)));
                
                if (!hasCloseMethod) {
                    // Add close() method plan
                    CtTypeReference<?> voidType = f.Type().createReference("void");
                    List<CtTypeReference<?>> emptyParams = new ArrayList<>();
                    List<CtTypeReference<?>> thrownTypes = new ArrayList<>();
                    // close() may throw Exception (AutoCloseable contract) - add Exception to thrown types
                    thrownTypes.add(f.Type().createReference("java.lang.Exception"));
                    out.methodPlans.add(new MethodStubPlan(ownerRef, "close", voidType, emptyParams, false,
                        MethodStubPlan.Visibility.PUBLIC, thrownTypes));
                }
            }
        }
    }

    /**
     * Collect bare unknown simple names (like 'g' in 'int a = util() + g;').
     * 
     * This is a fallback rule: if a simple name has no qualifier, is not ALL_CAPS,
     * has no resolved declaration, and no existing resolution found it, then:
     * 1. Infer its type from usage context
     * 2. Create a field stub on the enclosing class with that type
     */
    private void collectBareUnknownSimpleNames(CtModel model, CollectResult out) {
        Collection<CtType<?>> sliceTypes = getSliceTypes(model);
        
        // Track fields we've already planned to avoid duplicates
        Set<String> existingFieldKeys = new HashSet<>();
        for (FieldStubPlan p : out.fieldPlans) {
            String ownerFqn = null;
            try {
                if (p.ownerType != null) ownerFqn = p.ownerType.getQualifiedName();
            } catch (Throwable ignored) {}
            if (ownerFqn != null) {
                existingFieldKeys.add(ownerFqn + "#" + p.fieldName);
            }
        }
        
        // Track inferred types per name to handle conflicts
        Map<String, CtTypeReference<?>> nameToInferredType = new HashMap<>();
        
        for (CtType<?> sliceType : sliceTypes) {
            try {
                // Find all variable reads and field reads in this type
                List<CtVariableRead<?>> varReads = sliceType.getElements(new TypeFilter<CtVariableRead<?>>(CtVariableRead.class));
                List<CtFieldRead<?>> fieldReads = sliceType.getElements(new TypeFilter<CtFieldRead<?>>(CtFieldRead.class));
                
                for (CtVariableRead<?> vr : varReads) {
                    processBareSimpleName(vr, sliceType, out, existingFieldKeys, nameToInferredType);
                }
                
                for (CtFieldRead<?> fr : fieldReads) {
                    processBareSimpleName(fr, sliceType, out, existingFieldKeys, nameToInferredType);
                }
            } catch (Throwable ignored) {
                // Skip types we can't process
            }
        }
    }
    
    /**
     * Process a potential bare unknown simple name (variable read or field read).
     */
    private void processBareSimpleName(CtExpression<?> expr, CtType<?> sliceType, 
                                       CollectResult out, Set<String> existingFieldKeys,
                                       Map<String, CtTypeReference<?>> nameToInferredType) {
        try {
            // Get the variable reference
            spoon.reflect.reference.CtVariableReference<?> varRef = null;
            String name = null;
            
            if (expr instanceof CtVariableRead) {
                CtVariableRead<?> vr = (CtVariableRead<?>) expr;
                varRef = vr.getVariable();
                name = varRef != null ? varRef.getSimpleName() : null;
            } else if (expr instanceof CtFieldRead) {
                CtFieldRead<?> fr = (CtFieldRead<?>) expr;
                varRef = fr.getVariable();
                name = varRef != null ? varRef.getSimpleName() : null;
            }
            
            if (name == null || name.isEmpty()) return;
            
            // Condition 1: Must not be ALL_CAPS (those are handled by constant normalization)
            if (isConstantLikeName(name)) return;
            
            // Condition 2: Must be unresolved (no declaration)
            boolean isUnresolved = false;
            try {
                if (varRef != null) {
                    isUnresolved = (varRef.getDeclaration() == null);
                }
            } catch (StackOverflowError | OutOfMemoryError e) {
                isUnresolved = true; // Assume unresolved on stack overflow
            } catch (Throwable ignored) {
                return; // If we can't check, skip it
            }
            
            if (!isUnresolved) return;
            
            // Condition 3: Must have no target (bare name, not obj.name or Type.name)
            // For CtVariableRead, there's no target by definition
            // For CtFieldRead, check if target is null
            if (expr instanceof CtFieldRead) {
                CtFieldRead<?> fr = (CtFieldRead<?>) expr;
                if (fr.getTarget() != null) {
                    // Has a target, so it's not a bare name - skip
                    return;
                }
            }
            
            // Condition 4: Try existing resolution mechanisms first
            // Check if resolveOwnerTypeFromFieldAccess can find an owner
            if (expr instanceof CtFieldRead) {
                CtFieldRead<?> fr = (CtFieldRead<?>) expr;
                CtTypeReference<?> owner = resolveOwnerTypeFromFieldAccess(fr);
                if (owner != null) {
                    String ownerQn = safeQN(owner);
                    // If we found a real owner (not unknown package), skip this fallback
                    if (ownerQn != null && !ownerQn.isEmpty() && 
                        !ownerQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                        return; // Existing resolution found something
                    }
                }
            }
            
            // Check if it's a known type
            try {
                CtType<?> knownType = f.Type().get(name);
                if (knownType != null) {
                    return; // It's a type, not a field
                }
            } catch (Throwable ignored) {}
            
            // Check if it could be resolved from static imports
            // If there's a static import that matches this name, skip the fallback
            try {
                CtType<?> type = expr.getParent(CtType.class);
                if (type != null) {
                    SourcePosition pos = type.getPosition();
                    if (pos != null) {
                        Object cuObj = pos.getCompilationUnit();
                        if (cuObj != null) {
                            java.util.Collection<CtImport> imports = null;
                            if (cuObj instanceof CtCompilationUnit) {
                                imports = ((CtCompilationUnit) cuObj).getImports();
                            } else {
                                try {
                                    java.lang.reflect.Method getImports = cuObj.getClass().getMethod("getImports");
                                    Object importsObj = getImports.invoke(cuObj);
                                    if (importsObj instanceof java.util.Collection) {
                                        @SuppressWarnings("unchecked")
                                        java.util.Collection<CtImport> importsCast = (java.util.Collection<CtImport>) importsObj;
                                        imports = importsCast;
                                    }
                                } catch (Throwable ignored) {}
                            }
                            
                            if (imports != null) {
                                for (CtImport imp : imports) {
                                    CtReference ref = imp.getReference();
                                    if (ref == null) continue;
                                    
                                    // Check for static field import matching this name
                                    if (ref instanceof CtFieldReference) {
                                        CtFieldReference<?> fr = (CtFieldReference<?>) ref;
                                        if (name.equals(fr.getSimpleName())) {
                                            // Found a static import with this name - skip fallback
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            
            // All conditions met - this is a bare unknown simple name
            // Infer expected type from context
            CtTypeReference<?> expectedType = inferExpectedType(expr);
            
            // Handle type conflicts: if we've seen this name before with a different type,
            // default to unknown.Unknown
            if (nameToInferredType.containsKey(name)) {
                CtTypeReference<?> previousType = nameToInferredType.get(name);
                if (previousType != null && expectedType != null) {
                    String prevQn = safeQN(previousType);
                    String currQn = safeQN(expectedType);
                    if (prevQn != null && currQn != null && !prevQn.equals(currQn)) {
                        // Conflict - use unknown
                        expectedType = f.Type().createReference(UNKNOWN_TYPE_FQN);
                    }
                } else if (previousType == null && expectedType != null) {
                    // First time we have a type, use it
                    nameToInferredType.put(name, expectedType);
                } else {
                    // Keep previous or use unknown
                    expectedType = previousType != null ? previousType : f.Type().createReference(UNKNOWN_TYPE_FQN);
                }
            } else {
                // First time seeing this name
                if (expectedType == null) {
                    expectedType = f.Type().createReference(UNKNOWN_TYPE_FQN);
                }
                nameToInferredType.put(name, expectedType);
            }
            
            // Find enclosing type
            CtType<?> enclosingType = expr.getParent(CtType.class);
            if (enclosingType == null) {
                enclosingType = sliceType; // Fallback to slice type
            }
            
            if (enclosingType == null) return;
            
            // Determine if static
            boolean isStatic = isInStaticContext(expr, enclosingType);
            
            // Check for duplicates
            String ownerFqn = enclosingType.getQualifiedName();
            if (ownerFqn == null) return;
            
            String key = ownerFqn + "#" + name;
            if (existingFieldKeys.contains(key)) {
                return; // Already planned
            }
            
            // Check if field already exists in the model
            try {
                CtField<?> existingField = enclosingType.getField(name);
                if (existingField != null) {
                    return; // Field already exists
                }
            } catch (Throwable ignored) {}
            
            // Create field plan
            CtTypeReference<?> ownerRef = f.Type().createReference(ownerFqn);
            CtTypeReference<?> fieldType = expectedType != null ? expectedType : f.Type().createReference(UNKNOWN_TYPE_FQN);
            
            out.fieldPlans.add(new FieldStubPlan(ownerRef, name, fieldType, isStatic));
            existingFieldKeys.add(key);
            
        } catch (StackOverflowError | OutOfMemoryError e) {
            // Skip on stack overflow
            return;
        } catch (Throwable ignored) {
            // Skip on other errors
        }
    }
    
    /**
     * Check if an expression is in a static context (static method or static field initializer).
     */
    private boolean isInStaticContext(CtExpression<?> expr, CtType<?> enclosingType) {
        try {
            CtElement parent = expr.getParent();
            while (parent != null && parent != enclosingType) {
                if (parent instanceof CtMethod) {
                    CtMethod<?> method = (CtMethod<?>) parent;
                    return method.hasModifier(ModifierKind.STATIC);
                }
                if (parent instanceof CtField) {
                    CtField<?> field = (CtField<?>) parent;
                    return field.hasModifier(ModifierKind.STATIC);
                }
                parent = parent.getParent();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Returns true when a field access is used as a bare statement, e.g., {@code x.f;}.
     */
    private boolean isStandaloneFieldStatement(CtFieldAccess<?> fa) {
        CtElement p = fa.getParent();
        if (p == null) {
            debugLog("isStandaloneFieldStatement - parent is null");
            return false;
        }

        debugLog("isStandaloneFieldStatement - parent type: " + p.getClass().getSimpleName());
        
        if (p instanceof CtAssignment
                || p instanceof CtVariable
                || p instanceof CtReturn
                || p instanceof CtIf
                || p instanceof CtWhile
                || p instanceof CtDo
                || p instanceof CtFor
                || p instanceof CtConditional
                || p instanceof CtInvocation
                || p instanceof CtArrayAccess) {
            debugLog("isStandaloneFieldStatement - parent is excluded type, returning false");
            return false;
        }

        try {
            if (p instanceof CtBlock) {
                CtRole role = fa.getRoleInParent();
                debugLog("isStandaloneFieldStatement - parent is CtBlock, role: " + role);
                if (role == CtRole.STATEMENT) {
                    debugLog("isStandaloneFieldStatement - detected as standalone statement");
                    return true;
                }
            }
        } catch (Throwable e) {
            debugLog("isStandaloneFieldStatement - exception checking role: " + e.getMessage());
        }

        boolean result = !(p instanceof CtExpression);
        debugLog("isStandaloneFieldStatement - final check: !(p instanceof CtExpression) = " + result);
        return result;
    }

    /**
     * Resolve owner type of a field access from its target (handles static, arrays, and instance).
     */
    private CtTypeReference<?> resolveOwnerTypeFromFieldAccess(CtFieldAccess<?> fa) {
        if (fa.getTarget() instanceof CtTypeAccess) {
            return ((CtTypeAccess<?>) fa.getTarget()).getAccessedType();
        }

        if (fa.getTarget() instanceof CtArrayAccess) {
            CtArrayAccess<?, ?> aa = (CtArrayAccess<?, ?>) fa.getTarget();

            // a) variable array type -> component
            CtTypeReference<?> arrType = null;
            try { arrType = ((CtExpression<?>) aa.getTarget()).getType(); } catch (Throwable ignored) {}
            CtTypeReference<?> elem = componentOf(arrType);
            if (elem != null) {
                CtTypeReference<?> owner = chooseOwnerPackage(elem, fa);
                if (owner != null) return owner;
            }

            // b) type of the array access itself (often element type)
            try {
                CtTypeReference<?> t = ((CtExpression<?>) aa).getType();
                if (t != null) {
                    CtTypeReference<?> owner = chooseOwnerPackage(t, fa);
                    if (owner != null) return owner;
                }
            } catch (Throwable ignored) {}
        }

        if (fa.getTarget() != null) {
            // For variable references (parameters, local variables), try to get declared type first
            if (fa.getTarget() instanceof CtVariableRead) {
                CtVariableRead<?> vr = (CtVariableRead<?>) fa.getTarget();
                try {
                    if (vr.getVariable() != null && vr.getVariable().getType() != null) {
                        CtTypeReference<?> declaredType = vr.getVariable().getType();
                        String declaredQn = safeQN(declaredType);
                        debugLog("resolveOwnerTypeFromFieldAccess - VariableRead found");
                        debugLog("  Variable name: " + (vr.getVariable().getSimpleName()));
                        debugLog("  Declared type QN (from safeQN): " + declaredQn);
                        debugLog("  Declared type simple name: " + declaredType.getSimpleName());
                        debugLog("  Declared type package: " + (declaredType.getPackage() != null ? declaredType.getPackage().getQualifiedName() : "null"));

                        // Try to get qualified name from declaration if available
                        try {
                            CtType<?> typeDecl = declaredType.getTypeDeclaration();
                            if (typeDecl != null) {
                                String declQn = typeDecl.getQualifiedName();
                                System.out.println("[SpoonCollector] DEBUG:   Type declaration found, QN: " + declQn);
                                if (declQn != null && declQn.contains(".") && !declQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                                    CtTypeReference<?> base = componentOf(declaredType);
                                    if (base != null) {
                                        String baseQn = safeQN(base);
                                        if (baseQn != null && baseQn.contains(".") && !baseQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                                            System.out.println("[SpoonCollector] DEBUG: Using component type from declaration: " + baseQn);
                                            return base;
                                        }
                                    }
                                    System.out.println("[SpoonCollector] DEBUG: Using type from declaration: " + declQn);
                                    return f.Type().createReference(declQn);
                                }
                            } else {
                                debugLog("  Type declaration is null");
                            }
                        } catch (StackOverflowError | OutOfMemoryError e) {
                            System.out.println("[SpoonCollector] DEBUG: StackOverflowError getting type declaration: " + e.getMessage());
                        } catch (Throwable e) {
                            System.out.println("[SpoonCollector] DEBUG: Exception getting type declaration: " + e.getMessage());
                        }

                        // If the declared type is already fully qualified, use it directly
                        // This prevents routing qualified types to unknown package
                        if (declaredQn != null && declaredQn.contains(".") && !declaredQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                            System.out.println("[SpoonCollector] DEBUG: Declared type is fully qualified, using directly: " + declaredQn);
                            CtTypeReference<?> base = componentOf(declaredType);
                            if (base != null) {
                                String baseQn = safeQN(base);
                                if (baseQn != null && baseQn.contains(".") && !baseQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                                    System.out.println("[SpoonCollector] DEBUG: Using component type: " + baseQn);
                                    return base;
                                }
                            }
                            System.out.println("[SpoonCollector] DEBUG: Returning declared type: " + declaredQn);
                            return declaredType;
                        }
                        // If not fully qualified, use chooseOwnerPackage to resolve it
                        debugLog("Declared type not fully qualified, routing through chooseOwnerPackage");
                        CtTypeReference<?> base = componentOf(declaredType);
                        CtTypeReference<?> result = (base != null ? chooseOwnerPackage(base, fa) : chooseOwnerPackage(declaredType, fa));
                        System.out.println("[SpoonCollector] DEBUG: Result from chooseOwnerPackage: " + safeQN(result));
                        return result;
                    }
                } catch (StackOverflowError | OutOfMemoryError e) {
                    System.out.println("[SpoonCollector] DEBUG: StackOverflowError in resolveOwnerTypeFromFieldAccess: " + e.getMessage());
                    // Avoid infinite loops
                } catch (Throwable e) {
                    System.out.println("[SpoonCollector] DEBUG: Exception in resolveOwnerTypeFromFieldAccess: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // Fallback: try to get type from expression
            try {
                CtTypeReference<?> t = fa.getTarget().getType();
                if (t != null) {
                    String tQn = safeQN(t);
                    // If the type is already fully qualified, use it directly
                    if (tQn != null && tQn.contains(".") && !tQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                        CtTypeReference<?> base = componentOf(t);
                        if (base != null) {
                            String baseQn = safeQN(base);
                            if (baseQn != null && baseQn.contains(".") && !baseQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                                return base;
                            }
                        }
                        return t;
                    }
                CtTypeReference<?> base = componentOf(t);
                return (base != null ? chooseOwnerPackage(base, fa) : chooseOwnerPackage(t, fa));
                }
            } catch (StackOverflowError | OutOfMemoryError e) {
                // Avoid infinite loops
            } catch (Throwable ignored) {}
        }

        String simple = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "Owner");
        return f.Type().createReference(UNKNOWN_PACKAGE + "." + simple);
    }

    /**
     * For array type references, returns the component element type; otherwise null.
     */
    private CtTypeReference<?> componentOf(CtTypeReference<?> t) {
        if (t == null) return null;
        try {
            if (t instanceof CtArrayTypeReference) return ((CtArrayTypeReference<?>) t).getComponentType();
            if (t.isArray()) {
                String qn = t.getQualifiedName();
                if (qn != null && qn.endsWith("[]")) {
                    String base = qn.substring(0, qn.indexOf('['));
                    return f.Type().createReference(base);
                }
            }
        } catch (Throwable ignored) { /* best-effort */ }
        return null;
    }

    /**
     * Infer a field's type from its usage context (assignment, declaration, return, invocation, etc.).
     * Returns null if no usage context is available (e.g., standalone statements).
     */
    private CtTypeReference<?> inferFieldTypeFromUsage(CtFieldAccess<?> fa) {
        CtElement parent = fa.getParent();
        
        // CRITICAL: For standalone field statements (like "so.size;"), there's no usage context
        // Don't try to infer type from getType() as it may return incorrect types (like enclosing class)
        boolean isStandalone = isStandaloneFieldStatement(fa);
        debugLog("inferFieldTypeFromUsage - isStandalone = " + isStandalone);
        if (isStandalone) {
            debugLog("inferFieldTypeFromUsage - standalone field statement detected, returning null");
            return null; // No usage context for standalone statements
        }
        
        debugLog("inferFieldTypeFromUsage - parent type: " + (parent != null ? parent.getClass().getSimpleName() : "null"));

        if (parent instanceof CtAssignment) {
            CtAssignment<?, ?> as = (CtAssignment<?, ?>) parent;
            if (Objects.equals(as.getAssignment(), fa)) {
                try { return ((CtExpression<?>) as.getAssigned()).getType(); } catch (Throwable ignored) {}
            }
            if (Objects.equals(as.getAssigned(), fa)) {
                try { return ((CtExpression<?>) as.getAssignment()).getType(); } catch (Throwable ignored) {}
            }
        }

        if (parent instanceof CtVariable) return ((CtVariable<?>) parent).getType();

        if (parent instanceof CtIf || parent instanceof CtWhile || parent instanceof CtDo
                || parent instanceof CtFor || parent instanceof CtConditional) {
            return f.Type().BOOLEAN_PRIMITIVE;
        }

        if (parent instanceof CtArrayAccess) {
            CtArrayAccess<?, ?> aa = (CtArrayAccess<?, ?>) parent;
            if (Objects.equals(aa.getIndexExpression(), fa)) return f.Type().INTEGER_PRIMITIVE;

            if (Objects.equals(aa.getTarget(), fa)) {
                CtTypeReference<?> elementType = deduceArrayElementType(aa);
                CtTypeReference<?> base = (elementType != null ? elementType : f.Type().OBJECT);
                return f.Type().createArrayReference(base);
            }
        }

        if (parent instanceof CtReturn) {
            CtMethod<?> m = parent.getParent(CtMethod.class);
            if (m != null) return m.getType();
        }

        if (parent instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) parent;
            List<CtExpression<?>> args = inv.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (Objects.equals(args.get(i), fa)) {
                    try {
                        CtExecutableReference<?> ex = inv.getExecutable();
                        if (ex != null && ex.getParameters().size() > i) return ex.getParameters().get(i);
                    } catch (Throwable ignored) {}
                    break;
                }
            }
        }
        
        // CRITICAL: Never use fa.getType() as a fallback for unresolved fields
        // Spoon's getType() can return incorrect types (like the enclosing class) for unresolved fields
        // This is especially problematic for standalone statements like "so.size;"
        // If we reach here, there's no usable context, so return null
        debugLog("inferFieldTypeFromUsage - no usable context found, returning null");
        return null;
    }

    /**
     * Deduce element type for an array access expression from its parent context.
     */
    private CtTypeReference<?> deduceArrayElementType(CtArrayAccess<?, ?> aa) {
        CtElement gp = aa.getParent();

        if (gp instanceof CtVariable && Objects.equals(((CtVariable<?>) gp).getDefaultExpression(), aa)) {
            return ((CtVariable<?>) gp).getType();
        } else if (gp instanceof CtAssignment && Objects.equals(((CtAssignment<?, ?>) gp).getAssignment(), aa)) {
            try { return ((CtExpression<?>) ((CtAssignment<?, ?>) gp).getAssigned()).getType(); } catch (Throwable ignored) {}
        } else if (gp instanceof CtReturn) {
            CtMethod<?> m = gp.getParent(CtMethod.class);
            if (m != null) return m.getType();
        } else if (gp instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) gp;
            List<CtExpression<?>> args = inv.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (Objects.equals(args.get(i), aa)) {
                    try {
                        CtExecutableReference<?> ex = inv.getExecutable();
                        if (ex != null && ex.getParameters().size() > i) return ex.getParameters().get(i);
                    } catch (Throwable ignored) {}
                    break;
                }
            }
        }
        return null;
    }

    /* ======================================================================
     *                           CONSTRUCTORS PASS
     * ====================================================================== */


    private void collectUnresolvedCtorCalls(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        var unresolved = getElementsFromSliceTypes(model, (CtConstructorCall<?> cc) -> {
            var ex = cc.getExecutable();
            if (ex == null) return true;
            // Check if unresolved, but catch StackOverflowError to avoid infinite loops
            try {
                return ex.getDeclaration() == null;
            } catch (StackOverflowError | OutOfMemoryError e) {
                // If we get stack overflow, assume unresolved to avoid infinite loops
                return true;
            } catch (Throwable ignored) {
                // Other errors - assume resolved to be safe
                return false;
            }
        });

        for (CtConstructorCall<?> cc : unresolved) {
            CtTypeReference<?> rawOwner = cc.getType();

            // ----- NEW: detect member-inner creation on any CtConstructorCall -----
            CtExpression<?> targetExpr = null;
            try {
                // Spoon 10.x: CtConstructorCall has getTarget()
                targetExpr = (CtExpression<?>) cc.getClass().getMethod("getTarget").invoke(cc);
            } catch (Throwable ignore) {
                // Older Spoon variants: anonymous/inner had getEnclosingExpression()
                try {
                    targetExpr = (CtExpression<?>) cc.getClass()
                            .getMethod("getEnclosingExpression")
                            .invoke(cc);
                } catch (Throwable ignored) {}
            }

            if (targetExpr != null) {
                CtTypeReference<?> outerT = null;
                try { outerT = targetExpr.getType(); } catch (Throwable ignored) {}

                if (outerT != null) {
                    // resolve simple 'Outer' using explicit single-type imports (and star) first
                    CtTypeReference<?> resolvedOuter = chooseOwnerPackage(outerT, cc);
                    String outerFqn = safeQN(resolvedOuter);
                    if (outerFqn.isEmpty()) outerFqn = resolvedOuter.getQualifiedName();

                    String innerSimple = rawOwner.getSimpleName();
                    CtTypeReference<?> memberOwner = f.Type().createReference(outerFqn + "$" + innerSimple);

                    List<CtTypeReference<?>> ps =
                            inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());

                    out.addTypePlanIfNew(new TypeStubPlan(outerFqn, TypeStubPlan.Kind.CLASS), f);
                    out.addTypePlanIfNew(new TypeStubPlan(outerFqn + "$" + innerSimple, TypeStubPlan.Kind.CLASS), f);
                    out.ctorPlans.add(new ConstructorStubPlan(memberOwner, ps));
                    continue; // handled -> do NOT fall back to generic path
                }
            }
            // ---------------------------------------------------------------------

            CtTypeReference<?> owner = chooseOwnerPackage(rawOwner, cc);
            if (isJdkType(owner)) continue;

                collectTypeRefDeep(cc, rawOwner, out);
                List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());

            // Preserve nested owners ($) if present
            String ownerFqn = nestedAwareFqnOf(owner);
            out.ctorPlans.add(new ConstructorStubPlan(f.Type().createReference(ownerFqn), ps));
        }

    }


    /* ======================================================================
     *                             METHODS PASS
     * ====================================================================== */

    /**
     * Collect method stubs from unresolved invocations (incl. super calls and visibility).
     */
    private void collectUnresolvedMethodCalls(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        List<CtInvocation<?>> allInvocations = getElementsFromSliceTypes(model, (CtInvocation<?> inv) -> true);
        debugLog("Found " + allInvocations.size() + " total method invocations in slice types");

        // RULE 3: Track method usage patterns for side-effect-only detection
        // Map: method signature -> list of invocations
        Map<String, List<CtInvocation<?>>> methodInvocations = new HashMap<>();

        List<CtInvocation<?>> unresolved = new ArrayList<>();
        for (CtInvocation<?> inv : allInvocations) {
            var ex = inv.getExecutable();
            // Check if unresolved, but catch StackOverflowError to avoid infinite loops
            boolean isUnresolved = false;
            if (ex == null) {
                isUnresolved = true;
            } else {
                try {
                    isUnresolved = (ex.getDeclaration() == null);
                } catch (StackOverflowError | OutOfMemoryError e) {
                    // If we get stack overflow, assume unresolved to avoid infinite loops
                    isUnresolved = true;
                } catch (Throwable ignored) {
                    // Other errors - assume resolved to be safe
                    isUnresolved = false;
                }
            }

            if (!isUnresolved) {
                // DEBUG: Log resolved method calls to understand what Spoon is seeing
                try {
                    String methodName = ex != null ? ex.getSimpleName() : "unknown";
                    CtTypeReference<?> owner = resolveOwnerTypeFromInvocation(inv);
                    String ownerQn = owner != null ? owner.getQualifiedName() : "unknown";
                    debugLog("Resolved method call: " + ownerQn + "." + methodName + " (declaration exists)");
                } catch (Throwable ignored) {}
            } else {
                unresolved.add(inv);
                // Track invocation for side-effect-only detection
                try {
                    String methodName = ex != null ? ex.getSimpleName() : "unknown";
                    CtTypeReference<?> owner = resolveOwnerTypeFromInvocation(inv);
                    String ownerQn = owner != null ? owner.getQualifiedName() : "unknown";
                    String methodSig = ownerQn + "#" + methodName;
                    methodInvocations.computeIfAbsent(methodSig, k -> new ArrayList<>()).add(inv);
                    debugLog("Unresolved method call: " + ownerQn + "." + methodName + " (no declaration)");
                } catch (Throwable ignored) {}
            }
        }

        debugLog("Found " + unresolved.size() + " unresolved method invocations");

        for (CtInvocation<?> inv : unresolved) {
            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : "m");

            // Model constructor call via <init> as ctor plan.
            if ("<init>".equals(name)) {
                CtTypeReference<?> ownerForCtor = chooseOwnerPackage(resolveOwnerTypeFromInvocation(inv), inv);
                if (!isJdkType(ownerForCtor)) {
                    List<CtTypeReference<?>> ps = inferParamTypesFromCall(ex, inv.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(ownerForCtor, ps));
                }
                continue;
            }

                CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
            CtTypeReference<?> owner = chooseOwnerPackage(rawOwner, inv);

            Boolean defaultOnIface = false;
            CtExpression<?> tgt = inv.getTarget();
            boolean implicitThis = (tgt == null) || (tgt instanceof spoon.reflect.code.CtThisAccess<?>);
            
            // BUG CLASS 2 FIX: For unqualified method invocations (no explicit target), check superclasses
            // Example: getProject() in Ant tasks should be attached to org.apache.tools.ant.Task, not the task class
            if (implicitThis) {
                CtClass<?> enclosingClass = inv.getParent(CtClass.class);
                if (enclosingClass != null) {
                    String declaringTypeFqn = safeQN(enclosingClass.getReference());
                    
                    // IMPROVEMENT 8: Use ContextIndex.getSuperTypes (includes interfaces) and methodExists with param names
                    // First infer parameter types to get arity and param simple names
                    List<CtTypeReference<?>> inferredParamTypes = inferParamTypesFromCall(ex, inv.getArguments());
                    int arity = inferredParamTypes.size();
                    
                    // Extract param simple names for more precise matching
                    List<String> paramSimpleNames = new ArrayList<>();
                    for (CtTypeReference<?> paramType : inferredParamTypes) {
                        try {
                            String simpleName = paramType.getSimpleName();
                            if (simpleName != null && !simpleName.isEmpty()) {
                                paramSimpleNames.add(simpleName);
                            } else {
                                paramSimpleNames.add("Object"); // Fallback
                            }
                        } catch (Throwable ignored) {
                            paramSimpleNames.add("Object"); // Fallback
                        }
                    }
                    
                    if (contextIndex != null && declaringTypeFqn != null && !declaringTypeFqn.isEmpty()) {
                        // Use getSuperTypes which includes superclass chain + interfaces
                        List<String> superTypes = contextIndex.getSuperTypes(declaringTypeFqn);
                        // Also include the declaring type itself
                        List<String> chainToCheck = new ArrayList<>();
                        chainToCheck.add(declaringTypeFqn);
                        chainToCheck.addAll(superTypes);
                        
                        System.out.println("[SpoonCollector] [ContextIndex] Checking super types for method " + name + "(" + arity + " params) starting from " + declaringTypeFqn);
                        if (!superTypes.isEmpty()) {
                            System.out.println("[SpoonCollector] [ContextIndex] Super types: " + superTypes);
                        }
                        
                        // Walk the chain to find the first type where the method exists
                        // Try with param simple names first (more precise), then fallback to arity
                        for (String typeFqn : chainToCheck) {
                            boolean found = false;
                            if (!paramSimpleNames.isEmpty()) {
                                found = contextIndex.methodExists(typeFqn, name, paramSimpleNames);
                            }
                            if (!found) {
                                found = contextIndex.methodExists(typeFqn, name, arity);
                            }
                            if (found) {
                                owner = f.Type().createReference(typeFqn);
                                owner = chooseOwnerPackage(owner, inv);
                                System.out.println("[SpoonCollector] [ContextIndex] ✓ Found method " + name + " on " + typeFqn + " (via super types)");
                                // VERIFICATION ASSERTION: Log interface owner resolution
                                if (contextIndex != null) {
                                    Optional<ContextIndex.TypeKind> kind = contextIndex.typeKindOf(typeFqn);
                                    if (kind.isPresent() && kind.get() == ContextIndex.TypeKind.INTERFACE) {
                                        System.out.println("[SpoonCollector] [ContextIndex] ✓ VERIFIED: Method owner resolved to INTERFACE " + typeFqn);
                                    }
                                }
                                break;
                            }
                        }
                    } else if (contextIndex == null) {
                        // ContextIndex not available - this is logged once at start, but we can note it here for debugging
                        // (commented out to avoid spam - already logged at start)
                    }
                    
                    // Fallback: First check superclass (for methods like getProject() on Ant Task)
                    if (owner == null || (UNKNOWN_PACKAGE + ".Missing").equals(safeQN(owner))) {
                        try {
                            CtTypeReference<?> superClass = enclosingClass.getSuperclass();
                            if (superClass != null && !isJdkType(superClass)) {
                                String superQn = safeQN(superClass);
                                // Use superclass if it's not java.lang.Object and not a JDK type
                                if (superQn != null && !superQn.equals("java.lang.Object") && 
                                    !superQn.startsWith("java.lang.")) {
                                    owner = chooseOwnerPackage(superClass, inv);
                                    System.out.println("[SpoonCollector] DEBUG: Attaching unqualified method " + name + 
                                        " to superclass " + superQn + " instead of " + safeQN(enclosingClass.getReference()));
                                }
                            }
                        } catch (Throwable ignored) {
                            // If we can't check superclass, fall through to interface check
                        }
                    }
                    
                    // Then check interfaces (existing logic)
                    if (owner == null || (UNKNOWN_PACKAGE + ".Missing").equals(safeQN(owner))) {
                        List<CtTypeReference<?>> nonJdkIfaces = enclosingClass.getSuperInterfaces()
                                .stream()
                                    .filter(Objects::nonNull)
                                    .filter(ifr -> {
                                        String qn = safeQN(ifr);
                                        return !(qn.startsWith("java.") || qn.startsWith("javax.")
                                                || qn.startsWith("jakarta.") || qn.startsWith("sun.")
                                                || qn.startsWith("jdk."));
                                    })
                                .collect(java.util.stream.Collectors.toList());

                            if (nonJdkIfaces.size() == 1) {
                                owner = chooseOwnerPackage(nonJdkIfaces.get(0), inv);
                                defaultOnIface = true;
                            }
                        // if 0 or >1, keep the original owner heuristic (usually the class)
                    }
                }
            }

            if (owner == null || (UNKNOWN_PACKAGE + ".Missing").equals(safeQN(owner)) || inv.getTarget() == null) {
                CtTypeReference<?> fromStatic = resolveOwnerFromStaticImports(inv, name);
                if (fromStatic != null) owner = chooseOwnerPackage(fromStatic, inv);
            }

            if (isJdkType(owner)) continue;



            boolean isStatic = inv.getTarget() instanceof CtTypeAccess<?>;
            boolean isSuperCall = inv.getTarget() instanceof CtSuperAccess<?>;

            CtTypeReference<?> returnType = inferReturnTypeFromContext(inv);
            if (returnType == null && isStandaloneInvocation(inv)) {
                returnType = f.Type().VOID_PRIMITIVE;
            }

// If there is no explicit target but we resolved it via static import, mark static.
            if (!isStatic && inv.getTarget() == null) {
                if (resolveOwnerFromStaticImports(inv, name) != null) isStatic = true;
            }

            // DEBUG: Log method call details for parameter type inference
            String methodName = (ex != null ? ex.getSimpleName() : "unknown");
            System.out.println("[SpoonCollector] DEBUG: Inferring parameter types for method: " + methodName);
            debugLog("Method has " + (inv.getArguments() != null ? inv.getArguments().size() : 0) + " arguments");
            if (inv.getArguments() != null) {
                for (int i = 0; i < inv.getArguments().size(); i++) {
                    CtExpression<?> arg = inv.getArguments().get(i);
                    System.out.println("[SpoonCollector] DEBUG:   Argument " + i + ": " + arg.getClass().getSimpleName());
                    if (arg instanceof CtFieldAccess) {
                        CtFieldAccess<?> faArg = (CtFieldAccess<?>) arg;
                        String fieldName = faArg.getVariable() != null ? faArg.getVariable().getSimpleName() : "unknown";
                        System.out.println("[SpoonCollector] DEBUG:     Field access: " + fieldName);
                        if (faArg.getTarget() instanceof CtTypeAccess) {
                            CtTypeAccess<?> ta = (CtTypeAccess<?>) faArg.getTarget();
                            System.out.println("[SpoonCollector] DEBUG:     Owner type: " + safeQN(ta.getAccessedType()));
                        }
                    }
                }
            }
            List<CtTypeReference<?>> paramTypes = inferParamTypesFromCall(ex, inv.getArguments());
            debugLog("Inferred " + paramTypes.size() + " parameter types:");
            for (int i = 0; i < paramTypes.size(); i++) {
                System.out.println("[SpoonCollector] DEBUG:   Param " + i + ": " + safeQN(paramTypes.get(i)));
            }

            // Detect varargs pattern: if we have 3+ arguments and this is a logging method or similar,
            // convert to varargs (String, Object...) instead of (String, Object, Object, Object)
            boolean shouldUseVarargs = false;
            if (paramTypes.size() >= 3) {
                // Check if first param is String (common for logging methods)
                if (paramTypes.size() > 0 && paramTypes.get(0) != null) {
                    String firstParamQn = safeQN(paramTypes.get(0));
                    if ("java.lang.String".equals(firstParamQn)) {
                        // Likely a logging method - use varargs for remaining params
                        shouldUseVarargs = true;
                    }
                }
                // Also check method name patterns
                if (name != null && (name.equals("info") || name.equals("debug") || name.equals("warn")
                        || name.equals("error") || name.equals("trace") || name.equals("log"))) {
                    shouldUseVarargs = true;
                }
            }

            // Convert to varargs if detected
            if (shouldUseVarargs && paramTypes.size() > 1) {
                // Keep first param, convert rest to Object... (varargs)
                List<CtTypeReference<?>> varargsParams = new ArrayList<>();
                varargsParams.add(paramTypes.get(0)); // Keep first (usually String)
                // Last param becomes Object... (varargs)
                CtTypeReference<?> varargsType = f.Type().createReference("java.lang.Object");
                varargsParams.add(varargsType);
                paramTypes = varargsParams;
            }

            List<CtTypeReference<?>> fromRef = (ex != null ? ex.getParameters() : Collections.emptyList());
            boolean refSane = fromRef != null && !fromRef.isEmpty() && fromRef.stream().allMatch(this::isSaneType);
            // RULE 5: Don't throw on null args even in strict mode - treat as unknown.Unknown
            // Removed: throw on null args - always treat null as unknown.Unknown

            // RULE 3: Check if method is side-effect-only (all calls are standalone, result never used)
            String methodSig = (owner != null ? owner.getQualifiedName() : "unknown") + "#" + name;
            List<CtInvocation<?>> allCallsToThisMethod = methodInvocations.getOrDefault(methodSig, Collections.emptyList());
            boolean isSideEffectOnly = !allCallsToThisMethod.isEmpty();
            if (isSideEffectOnly) {
                // Check if all calls are standalone (not used in chain or assignment)
                for (CtInvocation<?> call : allCallsToThisMethod) {
                    CtElement parent = call.getParent();
                    // If used in chain (m().something()) or assignment/var, it's not side-effect-only
                    if (parent instanceof CtInvocation) {
                        CtInvocation<?> outerInv = (CtInvocation<?>) parent;
                        // Check if this call is the target of another invocation (chained)
                        if (outerInv.getTarget() == call) {
                            isSideEffectOnly = false;
                            break;
                        }
                    }
                    if (parent instanceof CtVariable || parent instanceof CtAssignment) {
                        isSideEffectOnly = false;
                        break;
                    }
                    if (parent instanceof CtReturn) {
                        isSideEffectOnly = false;
                        break;
                    }
                    // NEW: If used in any expression context (binary ops, unary ops, etc.), it's not side-effect-only
                    if (parent instanceof CtBinaryOperator || parent instanceof CtUnaryOperator ||
                        parent instanceof CtArrayAccess || parent instanceof CtConditional) {
                        isSideEffectOnly = false;
                        break;
                    }
                }
                if (isSideEffectOnly) {
                    debugLog("Detected side-effect-only method: " + methodSig + " → return void");
                    returnType = f.Type().VOID_PRIMITIVE;
                }
            }

            if (returnType == null) returnType = f.Type().VOID_PRIMITIVE;

            MethodStubPlan.Visibility vis = isSuperCall ? MethodStubPlan.Visibility.PROTECTED
                    : MethodStubPlan.Visibility.PUBLIC;

            // Mirror enclosing throws on super-calls.
            List<CtTypeReference<?>> thrown =
                    isSuperCall
                            ? new ArrayList<>(
                            Optional.ofNullable(inv.getParent(CtMethod.class))
                            .map(CtMethod::getThrownTypes)
                            .orElse(Collections.emptySet()))
                            : Collections.emptyList();

            out.methodPlans.add(new MethodStubPlan(owner, name, returnType, paramTypes, isStatic, vis, thrown,defaultOnIface));
        }
    }



    /**
     * Resolve the *owner* type for a method invocation (handles static, field receiver, generic).
     */
    private CtTypeReference<?> resolveOwnerTypeFromInvocation(CtInvocation<?> inv) {
        // 1) Static call: TypeName.m(...)
        if (inv.getTarget() instanceof CtTypeAccess) {
            return ((CtTypeAccess<?>) inv.getTarget()).getAccessedType();
        }

        // 2) Prefer declared type of a field access receiver, if present.
        if (inv.getTarget() instanceof CtFieldAccess) {
            CtFieldAccess<?> fa = (CtFieldAccess<?>) inv.getTarget();

            // Best: the type of the field access expression itself
            try {
                CtTypeReference<?> t = fa.getType();
                if (t != null) return t;
            } catch (Throwable ignored) {}

            // Fallback: field reference’s declared type
            try {
                if (fa.getVariable() != null && fa.getVariable().getType() != null)
                    return fa.getVariable().getType();
            } catch (Throwable ignored) { }

            // Last: the target expression type (e.g., this)
            try {
                if (fa.getTarget() != null && fa.getTarget().getType() != null)
                    return fa.getTarget().getType();
            } catch (Throwable ignored) {}
        }


        // 3) Generic expression type.
        if (inv.getTarget() != null) {
            try { return inv.getTarget().getType(); } catch (Throwable ignored) {}
        }

        // 4) Static-import fallback: use declaring type if present on the executable.
        if (inv.getExecutable() != null && inv.getExecutable().getDeclaringType() != null) {
            return inv.getExecutable().getDeclaringType();
        }

        // 5) Fallback.
        return f.Type().createReference(UNKNOWN_PACKAGE + ".Missing");
    }

    /**
     * Infer a method invocation's return type from surrounding context (assignment, arg, concat, etc.).
     * Includes heuristics for static factory methods and builder patterns.
     */
    private CtTypeReference<?> inferReturnTypeFromContext(CtInvocation<?> inv) {
        CtElement p = inv.getParent();

        // RULE 1: Static factory method detection: Type x = Type.m(...) → return Type
        boolean isStatic = inv.getTarget() instanceof CtTypeAccess<?>;
        if (isStatic) {
            CtTypeReference<?> ownerType = resolveOwnerTypeFromInvocation(inv);
            if (ownerType != null && !isJdkType(ownerType)) {
                // Check if this is used in assignment/var: Type x = Type.m(...)
                if (p instanceof CtVariable) {
                    CtVariable<?> var = (CtVariable<?>) p;
                    if (Objects.equals(var.getDefaultExpression(), inv)) {
                        CtTypeReference<?> varType = var.getType();
                        if (varType != null) {
                            String ownerQn = safeQN(ownerType);
                            String varQn = safeQN(varType);
                            // If LHS type matches owner type (static factory pattern)
                            if (ownerQn != null && varQn != null && ownerQn.equals(varQn)) {
                                System.out.println("[SpoonCollector] DEBUG: Detected static factory pattern: " + ownerQn + "." +
                                    (inv.getExecutable() != null ? inv.getExecutable().getSimpleName() : "m") + " → return " + ownerQn);
                                return ownerType;
                            }
                        }
                    }
                }
                if (p instanceof CtAssignment) {
                    CtAssignment<?, ?> as = (CtAssignment<?, ?>) p;
                    if (Objects.equals(as.getAssignment(), inv)) {
                        try {
                            CtTypeReference<?> assignedType = ((CtExpression<?>) as.getAssigned()).getType();
                            if (assignedType != null) {
                                String ownerQn = safeQN(ownerType);
                                String assignedQn = safeQN(assignedType);
                                if (ownerQn != null && assignedQn != null && ownerQn.equals(assignedQn)) {
                                    System.out.println("[SpoonCollector] DEBUG: Detected static factory pattern in assignment: " + ownerQn);
                                    return ownerType;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }

        // RULE 2: Builder build() method detection: Outer x = builder.build() → return Outer
        CtExecutableReference<?> ex = inv.getExecutable();
        if (ex != null && "build".equals(ex.getSimpleName())) {
            CtTypeReference<?> ownerType = resolveOwnerTypeFromInvocation(inv);
            if (ownerType != null && !isJdkType(ownerType)) {
                String ownerQn = safeQN(ownerType);
                if (ownerQn != null && ownerQn.endsWith(".Builder")) {
                    // Owner is Outer.Builder, check if LHS is Outer
                    String outerQn = ownerQn.substring(0, ownerQn.length() - 7); // Remove ".Builder"
                    if (p instanceof CtVariable) {
                        CtVariable<?> var = (CtVariable<?>) p;
                        if (Objects.equals(var.getDefaultExpression(), inv)) {
                            CtTypeReference<?> varType = var.getType();
                            if (varType != null) {
                                String varQn = safeQN(varType);
                                if (varQn != null && varQn.equals(outerQn)) {
                                    System.out.println("[SpoonCollector] DEBUG: Detected builder build() pattern: " + ownerQn + ".build() → return " + outerQn);
                                    return f.Type().createReference(outerQn);
                                }
                            }
                        }
                    }
                    if (p instanceof CtAssignment) {
                        CtAssignment<?, ?> as = (CtAssignment<?, ?>) p;
                        if (Objects.equals(as.getAssignment(), inv)) {
                            try {
                                CtTypeReference<?> assignedType = ((CtExpression<?>) as.getAssigned()).getType();
                                if (assignedType != null) {
                                    String assignedQn = safeQN(assignedType);
                                    if (assignedQn != null && assignedQn.equals(outerQn)) {
                                        System.out.println("[SpoonCollector] DEBUG: Detected builder build() pattern in assignment: " + outerQn);
                                        return f.Type().createReference(outerQn);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }

        // Standard context-based inference - check direct parent first
        if (p instanceof CtVariable && Objects.equals(((CtVariable<?>) p).getDefaultExpression(), inv)) {
            return ((CtVariable<?>) p).getType();
        }
        if (p instanceof CtAssignment && Objects.equals(((CtAssignment<?, ?>) p).getAssignment(), inv)) {
            try { return ((CtExpression<?>) ((CtAssignment<?, ?>) p).getAssigned()).getType(); } catch (Throwable ignored) {}
        }
        if (p instanceof CtReturn) {
            CtMethod<?> m = p.getParent(CtMethod.class);
            if (m != null) return m.getType();
        }

        // For binary operators, check if the binary operator itself is in a variable/assignment context
        if (p instanceof CtBinaryOperator) {
            CtBinaryOperator<?> bo = (CtBinaryOperator<?>) p;
            
            // First, check if the binary operator is in a variable initialization or assignment
            CtElement boParent = bo.getParent();
            if (boParent instanceof CtVariable) {
                CtVariable<?> var = (CtVariable<?>) boParent;
                if (Objects.equals(var.getDefaultExpression(), bo)) {
                    CtTypeReference<?> varType = var.getType();
                    if (varType != null) {
                        return varType;
                    }
                }
            }
            if (boParent instanceof CtAssignment) {
                CtAssignment<?, ?> as = (CtAssignment<?, ?>) boParent;
                if (Objects.equals(as.getAssignment(), bo)) {
                    try {
                        CtTypeReference<?> assignedType = ((CtExpression<?>) as.getAssigned()).getType();
                        if (assignedType != null) {
                            return assignedType;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            // If not in variable/assignment context, infer from binary operator itself
            BinaryOperatorKind kind = bo.getKind();
            CtExpression<?> other =
                    Objects.equals(bo.getLeftHandOperand(), inv)
                            ? bo.getRightHandOperand()
                            : bo.getLeftHandOperand();
            
            // String concatenation (PLUS with string operand)
            if (kind == BinaryOperatorKind.PLUS && isStringy(other)) {
                return f.Type().STRING;
            }
            
            // For arithmetic operators (+, -, *, /, %), infer numeric type from other operand
            if (kind == BinaryOperatorKind.PLUS || kind == BinaryOperatorKind.MINUS ||
                kind == BinaryOperatorKind.MUL || kind == BinaryOperatorKind.DIV ||
                kind == BinaryOperatorKind.MOD) {
                if (other != null) {
                    try {
                        CtTypeReference<?> otherType = other.getType();
                        if (otherType != null && otherType.isPrimitive()) {
                            String qn = otherType.getQualifiedName();
                            if (qn != null && (qn.equals("int") || qn.equals("long") || 
                                qn.equals("double") || qn.equals("float") || qn.equals("short") || 
                                qn.equals("byte"))) {
                                return otherType;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                // Default to int for arithmetic operations
                return f.Type().INTEGER_PRIMITIVE;
            }
        }

        if (p instanceof CtInvocation) {
            CtInvocation<?> outer = (CtInvocation<?>) p;
            int idx = -1;
            List<CtExpression<?>> args = outer.getArguments();
            for (int i = 0; i < args.size(); i++) {
                if (Objects.equals(args.get(i), inv)) { idx = i; break; }
            }
            if (idx >= 0) {
                CtExecutableReference<?> outerEx = outer.getExecutable();
                if (outerEx != null && outerEx.getParameters().size() > idx) {
                    CtTypeReference<?> t = outerEx.getParameters().get(idx);
                    if (isSaneType(t)) return t;
                }
                List<CtTypeReference<?>> inferred =
                        inferParamTypesFromCall(outer.getExecutable(), outer.getArguments());
                if (idx < inferred.size() && inferred.get(idx) != null) return inferred.get(idx);
                return f.Type().createReference(de.upb.sse.jess.generation.unknown.UnknownType.CLASS);
            }
        }

        try { return inv.getType(); } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Returns true if the given expression is string-like (literal or typed as java.lang.String).
     */
    private boolean isStringy(CtExpression<?> e) {
        if (e == null) return false;
        if (e instanceof CtLiteral) {
            Object v = ((CtLiteral<?>) e).getValue();
            if (v instanceof String) return true;
        }
        try {
            CtTypeReference<?> t = e.getType();
            return t != null && "java.lang.String".equals(t.getQualifiedName());
        } catch (StackOverflowError | OutOfMemoryError e2) {
            // Avoid infinite loops from type resolution
            return false;
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * Infer parameter types for a call: prefer executable signature if sane; otherwise derive from args.
     */
    private List<CtTypeReference<?>> inferParamTypesFromCall(CtExecutableReference<?> ex,
                                                             List<CtExpression<?>> args) {
        // CRITICAL FIX: If executable is unresolved or parameter count doesn't match argument count,
        // prioritize inferring from arguments rather than using executable's parameters
        boolean useExecutableParams = false;
        if (ex != null) {
            try {
                // Check if executable is resolved (has declaration)
                if (ex.getDeclaration() != null) {
                    List<CtTypeReference<?>> fromRef = ex.getParameters();
        if (fromRef != null && !fromRef.isEmpty() && fromRef.stream().allMatch(this::isSaneType)) {
                        // Only use executable params if count matches argument count
                        int argCount = (args != null ? args.size() : 0);
                        if (fromRef.size() == argCount) {
                            System.out.println("[SpoonCollector] DEBUG: Using executable parameters (count matches): " + fromRef.size());
            return fromRef;
                        } else {
                            debugLog("Executable param count (" + fromRef.size() + ") != arg count (" + argCount + "), inferring from arguments");
                        }
                    }
                } else {
                    debugLog("Executable is unresolved, inferring from arguments");
                }
            } catch (Throwable e) {
                System.out.println("[SpoonCollector] DEBUG: Exception checking executable, inferring from arguments: " + e.getMessage());
            }
        }

        // Infer from arguments (either executable is unresolved, or param count doesn't match)
        if (args == null || args.isEmpty()) {
            return Collections.emptyList();
        }
        List<CtTypeReference<?>> inferred = args.stream().map(this::paramTypeOrObject).collect(Collectors.toList());
        debugLog("Inferred " + inferred.size() + " parameter types from arguments");
        return inferred;
    }

    /**
     * Returns true if a type ref is usable (non-null, non-nulltype, not bare simple "Unknown").
     */
    private boolean isSaneType(CtTypeReference<?> t) {
        if (t == null) return false;
        String qn = t.getQualifiedName();
        if (qn == null || "null".equals(qn) || qn.contains("NullType")) return false;
        if (!qn.contains(".") && "Unknown".equals(t.getSimpleName())) return false;
        return true;
    }

    /* ======================================================================
     *                         ANNOTATIONS
     * ====================================================================== */

    // SpoonCollector.java
    private void collectUnresolvedAnnotations(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        // Walk all annotation occurrences in the slice
        for (CtAnnotation<?> ann : getElementsFromSliceTypes(model, (CtAnnotation<?> a) -> true)) {
            CtTypeReference<?> t = ann.getAnnotationType();
            if (t == null) continue;
            // already resolved? skip
            try { if (t.getDeclaration() != null) continue; } catch (Throwable ignored) {}

            // JDK annotations? skip
            if (isJdkType(t)) continue;

            // Decide the package for this unresolved annotation via your existing heuristic
            CtTypeReference<?> resolved = chooseOwnerPackage(t, ann);
            if (resolved == null) continue;
            String annFqn = resolved.getQualifiedName();
            out.addTypePlanIfNew(new TypeStubPlan(annFqn, TypeStubPlan.Kind.ANNOTATION), f);

            // RULE 4: Collect annotation attributes from usage
            Map<String, String> attributes = out.annotationAttributes.computeIfAbsent(annFqn, k -> new HashMap<>());
            for (Map.Entry<String, CtExpression> entry : ann.getValues().entrySet()) {
                String attrName = entry.getKey();
                CtExpression<?> attrValue = entry.getValue();

                // Infer type from literal value (super simple)
                String attrType = null;
                if (attrValue instanceof CtLiteral) {
                    Object literalValue = ((CtLiteral<?>) attrValue).getValue();
                    if (literalValue instanceof Integer || literalValue instanceof Long ||
                        literalValue instanceof Short || literalValue instanceof Byte) {
                        attrType = "int";
                    } else if (literalValue instanceof Float || literalValue instanceof Double) {
                        attrType = "double";
                    } else if (literalValue instanceof Boolean) {
                        attrType = "boolean";
                    } else if (literalValue instanceof String) {
                        attrType = "java.lang.String";
                    } else if (literalValue instanceof Character) {
                        attrType = "char";
                    }
                }

                // Only add if we inferred a type (skip complex expressions)
                if (attrType != null) {
                    attributes.put(attrName, attrType);
                    System.out.println("[SpoonCollector] DEBUG: Collected annotation attribute: " + annFqn + "." + attrName + " : " + attrType);
                }
            }

            // If the same unresolved annotation appears more than once on the SAME element => repeatable
                CtElement owner = ann.getAnnotatedElement();
                if (owner != null) {
                    long sameCount = owner.getAnnotations().stream()
                            .map(a -> {
                                CtTypeReference<?> rt = a.getAnnotationType();
                                return (rt == null ? "" : safeQN(chooseOwnerPackage(rt, a)));
                            })
                            .filter(fqn -> annFqn.equals(fqn))
                            .count();

                    if (sameCount >= 2) {
                        String pkg = annFqn.substring(0, annFqn.lastIndexOf('.'));
                        String simple = annFqn.substring(annFqn.lastIndexOf('.') + 1);
                        // container naming: Tag -> Tags
                        String containerSimple = simple.endsWith("s") ? (simple + "es") : (simple + "s");
                        String containerFqn = pkg + "." + containerSimple;
                    out.addTypePlanIfNew(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION), f);
                }
            }
        }
    }

    private void collectAnnotationTypeUsages(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        for (CtAnnotation<?> a : getElementsFromSliceTypes(model, (CtAnnotation<?> x) -> true)) {
            CtTypeReference<?> at = a.getAnnotationType();
            if (at == null) continue;

            // If simple name, try to resolve via explicit import in this CU
            String qn = safeQN(at);
            if (!qn.contains(".")) {
                CtTypeReference<?> resolved = resolveFromExplicitTypeImports(a, at.getSimpleName());
                if (resolved != null) {
                    out.addTypePlanIfNew(new TypeStubPlan(resolved.getQualifiedName(), TypeStubPlan.Kind.ANNOTATION), f);
                    // Also plan container (Tag -> Tags) in same pkg, as annotation
                    String pkg = resolved.getPackage() == null ? "" : resolved.getPackage().getQualifiedName();
                    String simple = resolved.getSimpleName();
                    String containerSimple = simple.endsWith("s") ? simple + "es" : simple + "s";
                    String containerFqn = (pkg.isEmpty() ? containerSimple : pkg + "." + containerSimple);
                    out.addTypePlanIfNew(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION), f);
                    continue;
                }
            }

            // Otherwise, keep whatever we have if it's non-JDK
            if (!isJdkType(at)) {
                out.addTypePlanIfNew(new TypeStubPlan((qn.isEmpty() ? UNKNOWN_PACKAGE + "." + at.getSimpleName() : qn),
                        TypeStubPlan.Kind.ANNOTATION), f);
                // Plan container in same package
                String pkg = at.getPackage() == null ? "" : at.getPackage().getQualifiedName();
                String simple = at.getSimpleName();
                String containerSimple = simple.endsWith("s") ? simple + "es" : simple + "s";
                String containerFqn = (pkg.isEmpty() ? containerSimple : pkg + "." + containerSimple);
                out.addTypePlanIfNew(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION), f);
            }
        }
    }

    /* ======================================================================
     *                         EXCEPTIONS / THROWS PASS
     * ====================================================================== */

    /**
     * Collect exception types from throws, catch, and throw sites.
     */
    private void collectExceptionTypes(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        // methods: throws
        List<CtMethod<?>> methods = getElementsFromSliceTypes(model, (CtMethod<?> mm) -> true);
        for (CtMethod<?> m : methods) {
            for (CtTypeReference<?> t : m.getThrownTypes()) {
                if (t == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(t, m);
                if (isJdkType(owner)) continue;
                out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
            }
        }

        // ctors: throws
        List<CtConstructor<?>> ctors = getElementsFromSliceTypes(model, (CtConstructor<?> cc) -> true);
        for (CtConstructor<?> c : ctors) {
            for (CtTypeReference<?> t : c.getThrownTypes()) {
                if (t == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(t, c);
                if (isJdkType(owner)) continue;
                out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
            }
        }

        // catch (single & multi)
        List<CtCatch> catches = getElementsFromSliceTypes(model, (CtCatch k) -> true);
        for (CtCatch cat : catches) {
            var par = cat.getParameter();
            if (par == null) continue;

            List<CtTypeReference<?>> types = new ArrayList<>();
            if (par.getMultiTypes() != null && !par.getMultiTypes().isEmpty()) {
                types.addAll(par.getMultiTypes());
            } else if (par.getType() != null) {
                types.add(par.getType());
            }

            for (CtTypeReference<?> raw : types) {
                if (raw == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(raw, cat);
                if (isJdkType(owner)) continue;
                out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
            }
        }

        // throw statements
        List<CtThrow> throwsList = getElementsFromSliceTypes(model, (CtThrow th) -> true);
        for (CtThrow thr : throwsList) {
            CtExpression<?> ex = thr.getThrownExpression();
            if (ex instanceof CtConstructorCall) {
                CtConstructorCall<?> cc = (CtConstructorCall<?>) ex;
                CtTypeReference<?> owner = chooseOwnerPackage(cc.getType(), thr);
                if (!isJdkType(owner)) {
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                    List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(owner, ps));
                }
            } else if (ex != null) {
                try {
                    CtTypeReference<?> t = ex.getType();
                    if (t != null && !isJdkType(t) && t.getDeclaration() == null) {
                        CtTypeReference<?> owner = chooseOwnerPackage(t, thr);
                        out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                        out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /* ======================================================================
     *                         DECLARED TYPES / IMPORTS
     * ====================================================================== */

    /**
     * Walk declared types in variables/fields/params/throws and plan any unresolved references.
     */
    private void collectUnresolvedDeclaredTypes(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        for (CtField<?> fd : getElementsFromSliceTypes(model, (CtField<?> f) -> true)) {
            collectTypeRefDeep(fd, fd.getType(), out);
        }
        for (CtLocalVariable<?> lv : getElementsFromSliceTypes(model, (CtLocalVariable<?> v) -> true)) {
            collectTypeRefDeep(lv, lv.getType(), out);
        }
        for (CtParameter<?> p : getElementsFromSliceTypes(model, (CtParameter<?> pp) -> true)) {
                        collectTypeRefDeep(p, p.getType(), out);
            }
        for (CtMethod<?> m : getElementsFromSliceTypes(model, (CtMethod<?> mm) -> true)) {
            for (CtTypeReference<? extends Throwable> thr : m.getThrownTypes()) {
                collectTypeRefDeep(m, thr, out);
        }
        }
        for (CtConstructor<?> c : getElementsFromSliceTypes(model, (CtConstructor<?> cc) -> true)) {
            for (CtTypeReference<? extends Throwable> thr : c.getThrownTypes()) {
                collectTypeRefDeep(c, thr, out);
            }
        }
    }

    /**
     * Maybe plan a declared type (and its package decision) if it is unresolved and non-JDK.
     */
    @SuppressWarnings("unchecked")
    private void maybePlanDeclaredType(CtElement ctx, CtTypeReference<?> t, CollectResult out) {
        if (t == null) return;

        try {
            if (t.isArray() || t instanceof CtArrayTypeReference) {
                CtTypeReference<?> comp = componentOf(t);
                if (comp != null) maybePlanDeclaredType(ctx, comp, out);
                return;
            }
        } catch (Throwable ignored) { }

        try {
            if (t.isPrimitive()) return;
            if (t.equals(f.Type().VOID_PRIMITIVE)) return;
        } catch (Throwable ignored) { }

        // IMPROVEMENT 6: Use real resolution check before stubbing types
        // Try ref.getTypeDeclaration() or ref.getDeclaration() first
        try { 
            CtType<?> decl = null;
            try {
                decl = t.getTypeDeclaration();
            } catch (Throwable ignored) {
                // Fallback to getDeclaration()
                try {
                    decl = t.getDeclaration();
                } catch (Throwable ignored2) {}
            }
            if (decl != null) {
                // Type exists in model - check if it's from slice
                if (isFromSlice(decl)) {
                    return; // Type is in slice - will be pretty-printed, no stub needed
                }
                // Type exists but is from context - we still need a stub (fall through)
            }
        } catch (Throwable ignored) { }

        String qn = safeQN(t);
        String simple = t.getSimpleName();
        if (simple == null || simple.isEmpty()) return;
        
        if (isJdkType(t)) return;
        
        // Filter out constants/fields that are being mistaken for types
        // ALL_CAPS names without package are likely constants, not types
        // Note: Constants in expression context should have been normalized to literals already,
        // but we still filter them here as a safety measure for type-position constants
        if (!qn.contains(".") && isConstantLikeName(simple)) {
            // This is likely a constant field (e.g., PUSH_ANDROID_SERVER_ADDRESS), not a type
            // Check if it exists as a field in the context
            try {
                // Get model from factory
                CtModel model = f.getModel();
                if (model != null) {
                    // Look for this as a field in slice types
                    Collection<CtType<?>> sliceTypes = getSliceTypes(model);
                    for (CtType<?> sliceType : sliceTypes) {
                        if (sliceType.getField(simple) != null) {
                            // It's a field, not a type - skip
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            // Even if not found as a field, if it's in a type position and looks like a constant,
            // it's safer to skip it (constants shouldn't be types)
            // Only skip if it's truly a simple name (no package)
            if (!qn.contains(".")) {
                return; // Skip constant-like names in type positions
            }
        }

        if (!qn.contains(".")) {
            // For simple names, use chooseOwnerPackage which handles ambiguity correctly
            CtTypeReference<?> resolved = chooseOwnerPackage(t, ctx);
            String resolvedFqn = resolved.getQualifiedName();
            out.addTypePlanIfNew(new TypeStubPlan(resolvedFqn, TypeStubPlan.Kind.CLASS), f);
                return;
            }

       // out.typePlans.add(new TypeStubPlan(qn, TypeStubPlan.Kind.CLASS));
        String nestedFqn = nestedAwareFqnOf(t);
        out.addTypePlanIfNew(new TypeStubPlan(nestedFqn, TypeStubPlan.Kind.CLASS), f);

    }

    /* ======================================================================
     *                           OWNER / IMPORT SEEDING
     * ====================================================================== */

    /**
     * Get elements of a specific type only from slice types (for performance).
     * OPTIMIZATION: Iterate over slice types first, then get elements from each slice type.
     * This avoids calling model.getElements() on the entire model (594 types), which is very slow.
     */
    @SuppressWarnings("unchecked")
    private <T extends CtElement> List<T> getElementsFromSliceTypes(CtModel model, java.util.function.Predicate<T> predicate) {
        List<T> result = new ArrayList<>();
        try {
            // OPTIMIZATION: Iterate over slice types first, then get elements from each slice type
            // This avoids traversing all 594 types in the model
            Collection<CtType<?>> sliceTypes = getSliceTypes(model);
            // Reduced verbosity: only log summary, not per-type details
            // debugLog(getElementsFromSliceTypes - Found " + sliceTypes.size() + " slice types);
            
            for (CtType<?> sliceType : sliceTypes) {
                try {
                    String typeName = sliceType.getQualifiedName();
                    // Reduced verbosity: removed per-type logging to avoid spam
                    // System.out.println("[SpoonCollector] DEBUG: Processing slice type: " + typeName);
                    
                    // Get all elements from this slice type, then filter with predicate
                    // This avoids type erasure issues with Filter<T>
                    final java.util.concurrent.atomic.AtomicInteger allElementsCount = new java.util.concurrent.atomic.AtomicInteger(0);
                    final java.util.concurrent.atomic.AtomicInteger matchingElementsCount = new java.util.concurrent.atomic.AtomicInteger(0);
                    final String debugTypeName = typeName; // Final copy for inner class
                    
                    try {
                        // Check if this is likely a very large generated class (protobuf, etc.)
                        // These classes can have thousands of elements and cause performance issues
                        boolean isLikelyLargeGeneratedClass = typeName.contains(".proto.") || 
                                                             typeName.contains("WFCMessage");
                        
                        // SAFETY RULE: Never skip traversal if this type's file is mentioned in diagnostics
                        // or if the target method's file is this type's compilation unit.
                        // However, since diagnostics are not available at collection time, we rely on
                        // diagnostics-driven fallback to add missing members after compilation fails.
                        // This heuristic is safe because diagnostics fallback will catch any missing members.
                        
                        if (isLikelyLargeGeneratedClass) {
                            // For very large protobuf classes, skip detailed element traversal
                            // These classes are typically not part of the actual slice logic
                            // NOTE: Diagnostics-driven fallback will add missing members if compilation fails
                            System.out.println("[SpoonCollector] DEBUG: Skipping detailed traversal of large generated class: " + typeName + 
                                " (diagnostics fallback will handle missing members if needed)");
                            // Still try to get elements, but with a strict limit
                            int elementLimit = 1000; // Limit to prevent infinite loops
                            int processed = 0;
                            for (CtElement el : sliceType.getElements(new Filter<CtElement>() {
                                @Override
                                public boolean matches(CtElement element) {
                                    if (element instanceof spoon.reflect.declaration.CtModule) {
                                        return false;
                                    }
                                    return true;
                                }
                            })) {
                                if (processed++ >= elementLimit) {
                                    System.err.println("[SpoonCollector] WARNING: Type " + typeName + " exceeded element limit (" + elementLimit + "), stopping early");
                                    break;
                                }
                                try {
                                    @SuppressWarnings("unchecked")
                                    T typedElement = (T) el;
                                    if (predicate.test(typedElement)) {
                                        matchingElementsCount.incrementAndGet();
                                        result.add(typedElement);
                                    }
                                } catch (ClassCastException e) {
                                    // Element is not of type T, skip it
                                }
                            }
                            allElementsCount.set(processed);
                        } else {
                            // Get all elements first (for normal types)
                            for (CtElement el : sliceType.getElements(new Filter<CtElement>() {
                                @Override
                                public boolean matches(CtElement element) {
                                    // Skip module elements
                                    if (element instanceof spoon.reflect.declaration.CtModule) {
                                        return false;
                                    }
                                    allElementsCount.incrementAndGet();
                                    // Removed verbose per-invocation logging to avoid spam
                                    return true;
                                }
                            })) {
                                // Now filter with the predicate
                                try {
                                    @SuppressWarnings("unchecked")
                                    T typedElement = (T) el;
                                    if (predicate.test(typedElement)) {
                                        matchingElementsCount.incrementAndGet();
                                        result.add(typedElement);
                                    }
                                } catch (ClassCastException e) {
                                    // Element is not of type T, skip it
                                    // This is expected for elements that don't match the generic type
                                }
                            }
                        }
                    } catch (Throwable e) {
                        System.err.println("[SpoonCollector] DEBUG: Error processing elements in " + typeName + ": " + e.getMessage());
                        // Don't print full stack trace for large types to avoid spam
                        if (!typeName.contains("WFCMessage") && !typeName.contains(".proto.")) {
                            e.printStackTrace();
                        }
                    }
                    
                    // Reduced verbosity: removed per-type summary logging to avoid spam
                    // debugLog("Type " + typeName + " has " + allElementsCount.get() + " total elements, matched " + matchingElementsCount.get() + " with predicate);
                } catch (Throwable e) {
                    // Only log errors for non-large types to avoid spam
                    String typeName = "unknown";
                    try {
                        typeName = sliceType.getQualifiedName();
                    } catch (Throwable ignored) {}
                    if (!typeName.contains("WFCMessage") && !typeName.contains(".proto.")) {
                        System.err.println("[SpoonCollector] DEBUG: Error processing slice type " + typeName + ": " + e.getMessage());
                    }
                    // Skip types we can't process
                }
            }
            
            // Reduced verbosity: only log summary once per call
            // debugLog(getElementsFromSliceTypes returning " + result.size() + " elements);
        } catch (StackOverflowError e) {
            System.err.println("[SpoonCollector] StackOverflowError getting elements from slice types");
            // Return empty list on StackOverflowError
            return Collections.emptyList();
        } catch (Throwable e) {
            System.err.println("[SpoonCollector] Error getting elements: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * Get elements of a specific type only from slice types (for performance).
     * OPTIMIZATION: Iterate over slice types first, then get elements from each slice type.
     * This avoids calling model.getElements() on the entire model (594 types), which is very slow.
     */
    @SuppressWarnings("unchecked")
    private <T extends CtElement> List<T> getElementsFromSliceTypes(CtModel model, TypeFilter<T> typeFilter) {
        List<T> result = new ArrayList<>();
        try {
            // OPTIMIZATION: Iterate over slice types first, then get elements from each slice type
            // This avoids traversing all 594 types in the model
            Collection<CtType<?>> sliceTypes = getSliceTypes(model);
            for (CtType<?> sliceType : sliceTypes) {
                try {
                    // Get elements from this slice type only (recursive search within the type)
                    // TypeFilter already handles type checking, so we can use it directly
                    for (T el : sliceType.getElements(typeFilter)) {
                        result.add(el);
                }
            } catch (Throwable ignored) {
                    // Skip types we can't process
                }
            }
        } catch (StackOverflowError e) {
            System.err.println("[SpoonCollector] StackOverflowError getting elements from slice types");
            // Return empty list on StackOverflowError
            return Collections.emptyList();
        } catch (Throwable e) {
            System.err.println("[SpoonCollector] Error getting elements: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * Get only slice types from the model (for performance - avoid processing all types).
     * Uses path-based detection (primary) + descriptor FQN lookup (secondary).
     */
    private Collection<CtType<?>> getSliceTypes(CtModel model) {
        // If no slicedSrcDir and no descriptor info, return all types (backward compat)
        if (slicedSrcDir == null && (descriptor == null || descriptor.sliceTypeFqns == null || descriptor.sliceTypeFqns.isEmpty())) {
            try {
                return safeGetAllTypes(model);
            } catch (Throwable e) {
                return Collections.emptyList();
            }
        }
        
        // Collect slice types using path-based check (primary) + descriptor FQNs (secondary)
        Set<String> foundFqns = new HashSet<>();
        List<CtType<?>> sliceTypes = new ArrayList<>();
        
        try {
            Collection<CtType<?>> allTypes = safeGetAllTypes(model);
            for (CtType<?> type : allTypes) {
                if (isFromSlice(type)) {
                    String qn = type.getQualifiedName();
                    if (qn != null && !foundFqns.contains(qn)) {
                        sliceTypes.add(type);
                        foundFqns.add(qn);
                    }
                }
            }
        } catch (Throwable e) {
            System.err.println("[SpoonCollector] Error getting slice types: " + e.getMessage());
        }
        
        return sliceTypes;
    }
    
    /**
     * Safely get all types from model, handling StackOverflowError.
     */
    private Collection<CtType<?>> safeGetAllTypes(CtModel model) {
        try {
            return model.getAllTypes();
        } catch (StackOverflowError e) {
            System.err.println("[SpoonCollector] StackOverflowError getting all types - likely circular dependencies");
            System.err.println("[SpoonCollector] Returning empty collection - some slice types may be missing");
            return Collections.emptyList();
        } catch (Throwable e) {
            System.err.println("[SpoonCollector] Error getting all types: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Post-processing: Remove duplicate unknown package types that have known package equivalents.
     * BUG CLASS 1 FIX: Canonicalize missing types by simple name - prefer anchored FQNs over unknown/bare variants.
     * 
     * This handles cases like:
     * - unknown.Project vs org.apache.tools.ant.Project → keep only org.apache.tools.ant.Project
     * - Project vs org.apache.tools.ant.Project → keep only org.apache.tools.ant.Project
     * - unknown.PushMessage vs cn.wildfirechat.push.PushMessage → keep only cn.wildfirechat.push.PushMessage
     */
    private void removeDuplicateUnknownPackageTypes(CollectResult result) {
        String unknownPackage = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
        List<TypeStubPlan> toRemove = new ArrayList<>();
        
        // Group all type plans by simple name
        Map<String, List<TypeStubPlan>> bySimpleName = new HashMap<>();
        for (TypeStubPlan plan : result.typePlans) {
            String fqn = plan.qualifiedName;
            if (fqn == null) continue;
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            bySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(plan);
        }
        
        // For each simple name group, if there's an anchored FQN (non-unknown package), remove unknown/bare variants
        for (Map.Entry<String, List<TypeStubPlan>> entry : bySimpleName.entrySet()) {
            String simpleName = entry.getKey();
            List<TypeStubPlan> plans = entry.getValue();
            
            // Find anchored FQNs (non-unknown package, non-bare)
            List<String> anchoredFqns = new ArrayList<>();
            for (TypeStubPlan plan : plans) {
                String fqn = plan.qualifiedName;
                if (fqn != null && !fqn.startsWith(unknownPackage + ".") && fqn.contains(".")) {
                    anchoredFqns.add(fqn);
                }
            }
            
            // If we have anchored FQNs, remove unknown.* and bare simple name variants
            if (!anchoredFqns.isEmpty()) {
                // Keep the first anchored FQN (or all if multiple - they'll be deduplicated elsewhere)
                String preferredFqn = anchoredFqns.get(0);
                
                // Remove unknown.* variants and bare simple names
                for (TypeStubPlan plan : plans) {
                    String fqn = plan.qualifiedName;
                    if (fqn == null) continue;
                    
                    // Remove if it's unknown.* variant
                    if (fqn.startsWith(unknownPackage + "." + simpleName)) {
                        debugLog("Canonicalizing - removing unknown.* variant " + fqn + " (preferring " + preferredFqn + ")");
                        toRemove.add(plan);
                    }
                    // Remove if it's a bare simple name (no package, just the simple name)
                    else if (fqn.equals(simpleName) && !fqn.contains(".")) {
                        debugLog("Canonicalizing - removing bare simple name " + fqn + " (preferring " + preferredFqn + ")");
                        toRemove.add(plan);
                    }
                }
            }
        }
        
        // Remove duplicates
        result.typePlans.removeAll(toRemove);
        for (TypeStubPlan plan : toRemove) {
            result.typePlanFqns.remove(plan.qualifiedName);
        }
    }

    /**
     * Collect stubs needed purely because of static imports.
     *
     * Example:
     *   import static ext.Api.FLAG;
     *   class C { boolean b() { return FLAG; } }
     *
     * Even after we rewrite the FLAG expression to a literal, the static import
     * still requires that ext.Api and a static member FLAG exist. This pass
     * creates minimal stubs for those owners / fields.
     */
    private void collectStaticImports(CtModel model, CollectResult out) {
        // We only care about compilation units that correspond to slice types.
        Collection<CtType<?>> sliceTypes = getSliceTypes(model);
        // Use Set<Object> to avoid type issues with deprecated CompilationUnit
        Set<Object> visitedCus = new LinkedHashSet<>();

        for (CtType<?> t : sliceTypes) {
            try {
                SourcePosition pos = t.getPosition();
                if (pos != null) {
                    Object cu = pos.getCompilationUnit();
                    if (cu != null) {
                        visitedCus.add(cu);
                    }
                }
            } catch (Throwable e) {
                System.out.println("[SpoonCollector] DEBUG: Error getting CU for type " + t.getQualifiedName() + ": " + e.getMessage());
            }
        }

        if (visitedCus.isEmpty()) return;

        // Also check original source code for static imports (fallback for unresolved imports)
        // Pattern: import static pkg.Type.MEMBER; or import static pkg.Type.*;
        final Pattern STATIC_FIELD_IMPORT = Pattern.compile("\\bimport\\s+static\\s+([\\w\\.]+)\\.([A-Z_][\\w_]*)\\s*;");
        final Pattern STATIC_ONDEMAND_IMPORT = Pattern.compile("\\bimport\\s+static\\s+([\\w\\.]+)\\.\\*\\s*;");

        // For de-duplication of owner + field name combinations
        Set<String> existingFieldKeys = new HashSet<>();
        for (FieldStubPlan p : out.fieldPlans) {
            String ownerFqn = null;
            try {
                if (p.ownerType != null) ownerFqn = p.ownerType.getQualifiedName();
            } catch (Throwable ignored) {}
            if (ownerFqn != null) {
                existingFieldKeys.add(ownerFqn + "#" + p.fieldName);
            }
        }

        // Track type plans we have already added, so we do not add duplicates.
        Set<String> existingTypePlanFqns = out.typePlans.stream()
                .map(tp -> tp.qualifiedName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (Object cuObj : visitedCus) {
            // Get imports using reflection to handle both CtCompilationUnit and deprecated CompilationUnit
            java.util.Collection<CtImport> imports = null;
            try {
                if (cuObj instanceof CtCompilationUnit) {
                    imports = ((CtCompilationUnit) cuObj).getImports();
                } else {
                    // Try to call getImports() via reflection for deprecated CompilationUnit
                    java.lang.reflect.Method getImports = cuObj.getClass().getMethod("getImports");
                    Object importsObj = getImports.invoke(cuObj);
                    if (importsObj instanceof java.util.Collection) {
                        @SuppressWarnings("unchecked")
                        java.util.Collection<CtImport> importsCast = (java.util.Collection<CtImport>) importsObj;
                        imports = importsCast;
                    }
                }
            } catch (Throwable e) {
                System.out.println("[SpoonCollector] DEBUG: Error getting imports from CU: " + e.getMessage());
                continue;
            }
            
            if (imports == null || imports.isEmpty()) {
                debugLog("CU has no imports");
                continue;
            }
            
            for (CtImport imp : imports) {
                CtReference ref = imp.getReference();
                if (ref == null) continue;

                // Get import kind
                CtImportKind kind = null;
                try {
                    kind = imp.getImportKind();
                } catch (Throwable ignored) {}

                // Check if this is a static import by examining both the import kind and reference type
                // Pattern matches resolveOwnerFromStaticImports: check kind name for METHOD/ALL/FIELD
                boolean isStatic = false;
                if (kind != null) {
                    String kindName = kind.name().toUpperCase();
                    // Static imports: FIELD, METHOD, or ALL_STATIC_TYPES (on-demand)
                    // Regular imports: TYPE, ALL_TYPES (non-static)
                    isStatic = kindName.contains("FIELD") 
                            || kindName.contains("METHOD")
                            || kindName.contains("ALL_STATIC");
                }

                // Also check reference type as primary indicator (more reliable)
                // Static field imports have CtFieldReference, static method imports have CtExecutableReference
                if (!isStatic) {
                    isStatic = (ref instanceof CtFieldReference) || (ref instanceof CtExecutableReference);
                }

                if (!isStatic) continue;

                // Case 1: import static pkg.Type.MEMBER;  (field)
                if (ref instanceof CtFieldReference) {
                    CtFieldReference<?> fr = (CtFieldReference<?>) ref;
                    CtTypeReference<?> declaringType = fr.getDeclaringType();
                    if (declaringType == null) continue;

                    String ownerFqn = null;
                    try {
                        ownerFqn = declaringType.getQualifiedName();
                    } catch (Throwable ignored) {}

                    if (ownerFqn == null) continue;

                    // Never stub JDK owners
                    if (isJdkType(declaringType)) continue;

                    String simpleName = fr.getSimpleName();
                    if (simpleName == null || simpleName.isEmpty()) continue;

                    String key = ownerFqn + "#" + simpleName;
                    if (!existingFieldKeys.add(key)) {
                        // We already have a field plan for this owner + name
                        continue;
                    }

                    // Plan the static field with a minimal unknown type.
                    CtTypeReference<?> ownerRef  = f.Type().createReference(ownerFqn);
                    CtTypeReference<?> fieldType = f.Type().createReference(UNKNOWN_TYPE_FQN);
                    out.fieldPlans.add(new FieldStubPlan(ownerRef, simpleName, fieldType, true));

                    // ownersNeedingTypes(res) will later ensure a TypeStubPlan for ownerRef.
                }

                // Case 2: import static pkg.Type.*;  (on-demand)
                else if (ref instanceof CtTypeReference) {
                    // Verify this is actually an on-demand static import (not just a regular type import)
                    boolean isOnDemandStatic = false;
                    if (kind != null && kind.name().contains("ALL")) {
                        isOnDemandStatic = true;
                    }
                    
                    // Fallback: if we already know it's static and ref is a type reference, 
                    // it's likely an on-demand static import
                    if (!isOnDemandStatic && isStatic) {
                        isOnDemandStatic = true;
                    }
                    
                    if (!isOnDemandStatic) continue;
                    
                    CtTypeReference<?> tr = (CtTypeReference<?>) ref;
                    String ownerFqn = null;
                    try {
                        ownerFqn = tr.getQualifiedName();
                    } catch (Throwable ignored) {}

                    if (ownerFqn == null) continue;
                    if (isJdkType(tr)) continue;
                    if (existingTypePlanFqns.contains(ownerFqn)) continue;

                    out.addTypePlanIfNew(new TypeStubPlan(ownerFqn, TypeStubPlan.Kind.CLASS), f);
                    existingTypePlanFqns.add(ownerFqn);
                }

                // NOTE: import static pkg.Type.someMethod; does not need special handling here:
                // static methods are already discovered via unresolved method calls +
                // resolveOwnerFromStaticImports(...).
            }

            // Fallback: parse original source code for static imports that Spoon might not have parsed
            try {
                String src = null;
                if (cuObj instanceof CtCompilationUnit) {
                    src = ((CtCompilationUnit) cuObj).getOriginalSourceCode();
                } else {
                    // Try to get source via reflection for deprecated CompilationUnit
                    try {
                        java.lang.reflect.Method getSource = cuObj.getClass().getMethod("getOriginalSourceCode");
                        Object srcObj = getSource.invoke(cuObj);
                        if (srcObj instanceof String) {
                            src = (String) srcObj;
                        }
                    } catch (Throwable ignored) {}
                }

                if (src != null) {
                    // Check for static field imports: import static pkg.Type.MEMBER;
                    java.util.regex.Matcher m = STATIC_FIELD_IMPORT.matcher(src);
                    while (m.find()) {
                        String ownerFqn = m.group(1);
                        String memberName = m.group(2);
                        
                        if (isJdkPkg(ownerFqn)) continue;
                        
                        String key = ownerFqn + "#" + memberName;
                        if (!existingFieldKeys.contains(key)) {
                            existingFieldKeys.add(key);
                            CtTypeReference<?> ownerRef = f.Type().createReference(ownerFqn);
                            CtTypeReference<?> fieldType = f.Type().createReference(UNKNOWN_TYPE_FQN);
                            out.fieldPlans.add(new FieldStubPlan(ownerRef, memberName, fieldType, true));
                        }
                    }

                    // Check for on-demand static imports: import static pkg.Type.*;
                    m = STATIC_ONDEMAND_IMPORT.matcher(src);
                    while (m.find()) {
                        String ownerFqn = m.group(1);
                        if (isJdkPkg(ownerFqn)) continue;
                        if (!existingTypePlanFqns.contains(ownerFqn)) {
                            out.addTypePlanIfNew(new TypeStubPlan(ownerFqn, TypeStubPlan.Kind.CLASS), f);
                            existingTypePlanFqns.add(ownerFqn);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // If source parsing fails, we've already tried Spoon-parsed imports above
            }
        }
    }
    
    /**
     * Seed synthetic anchors for on-demand (star) imports, preserving source order.
     */
    private void seedOnDemandImportAnchors(CtModel model, CollectResult out) {
        final Pattern STAR_IMPORT = Pattern.compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\.\\*\\s*;");

        getSliceTypes(model).forEach(t -> {
            var pos = t.getPosition();
            var cu = (pos != null) ? pos.getCompilationUnit() : null;
            if (cu == null) return;

            Set<String> starPkgs = new LinkedHashSet<>();

            // 1) Spoon-understood star imports
            for (CtImport imp : cu.getImports()) {
                if (imp.getImportKind() == CtImportKind.ALL_TYPES) {
                    String raw = String.valueOf(imp.getReference());
                    if (raw != null && !raw.isEmpty() && !isJdkPkg(raw)) starPkgs.add(raw);
                }
            }

            // 2) Fallback to raw source parsing
            try {
                String src = cu.getOriginalSourceCode();
                if (src != null) {
                    Matcher m = STAR_IMPORT.matcher(src);
                    while (m.find()) {
                        String pkg = m.group(1);
                        if (!isJdkPkg(pkg)) starPkgs.add(pkg);
                    }
                }
            } catch (Throwable ignored) { }

            for (String pkg : starPkgs) {
                out.addTypePlanIfNew(new TypeStubPlan(pkg + ".PackageAnchor", TypeStubPlan.Kind.CLASS), f);
            }
        });
    }

    /**
     * Returns true if a package or FQN belongs to the JDK space.
     */
    private boolean isJdkPkg(String pkg) {
        return pkg.startsWith("java.") || pkg.startsWith("javax.") ||
                pkg.startsWith("jakarta.") || pkg.startsWith("sun.") || pkg.startsWith("jdk.");
    }

    /**
     * Returns true if a reference is "locally assumed" (current package) or a simple name.
     */
    private boolean isLocallyAssumedOrSimple(CtTypeReference<?> t, CtElement ctx) {
        if (t == null) return true;
        String qn;
        try { qn = t.getQualifiedName(); } catch (Throwable ignored) { qn = null; }
        if (qn == null || qn.isEmpty() || !qn.contains(".")) return true;

        CtPackage pkg = Optional.ofNullable(ctx.getParent(CtType.class))
                .map(CtType::getPackage).orElse(null);
        String currentPkg = (pkg == null ? "" : pkg.getQualifiedName());
        return (qn.startsWith(currentPkg + ".") && t.getDeclaration() == null);
    }

    /**
     * Get star-import packages in the original source order for the given context.
     */
    private List<String> starImportsInOrder(CtElement ctx) {
        var type = ctx.getParent(CtType.class);
        var pos = (type != null ? type.getPosition() : null);
        var cu = (pos != null ? pos.getCompilationUnit() : null);
        if (cu == null) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        try {
            String src = cu.getOriginalSourceCode();
            if (src != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\.\\*\\s*;")
                        .matcher(src);
                while (m.find()) {
                    String pkg = m.group(1);
                    if (!isJdkPkg(pkg) && !out.contains(pkg)) out.add(pkg);
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * Choose a package for a possibly simple owner reference, honoring star imports and strict mode.
     * Never guesses between multiple possible packages - always routes to unknown package for ambiguity.
     */
    private CtTypeReference<?> chooseOwnerPackage(CtTypeReference<?> ownerRef, CtElement ctx) {

        if (ownerRef == null) return f.Type().createReference(UNKNOWN_PACKAGE + ".Missing");

        String qn = safeQN(ownerRef);

        // Treat assumed-local qualified refs as simple to allow re-qualification from star imports.
        if (qn.contains(".") && isLocallyAssumedOrSimple(ownerRef, ctx)) {
            ownerRef = f.Type().createReference(ownerRef.getSimpleName());
            qn = ownerRef.getQualifiedName();
        }

        // If already qualified (has package), trust it
        if (qn.contains(".")) return ownerRef;

        // For simple names, collect candidate packages from explicit imports and star imports
        String simple = Optional.ofNullable(ownerRef.getSimpleName()).orElse("Missing");
        Set<String> candidatePackages = new LinkedHashSet<>();

        // 1) Collect from explicit single-type imports
        List<String> explicitPackages = resolveExplicitImportPackages(ctx, simple);
        candidatePackages.addAll(explicitPackages);

        // 2) If no explicit candidates, check star imports
        if (candidatePackages.isEmpty()) {
        List<String> starPkgs = starImportsInOrder(ctx);
            // Filter out "unknown" from star imports - we'll handle it separately
            for (String starPkg : starPkgs) {
                if (!UNKNOWN_PACKAGE.equals(starPkg)) {
                    candidatePackages.add(starPkg);
                }
            }
        }

        // 3) Decide: no candidates -> unknown, one candidate -> use it, multiple -> unknown (never guess)
        if (candidatePackages.isEmpty()) {
            // No clue where it comes from -> unknown
            return f.Type().createReference(UNKNOWN_PACKAGE + "." + simple);
        } else if (candidatePackages.size() == 1) {
            // One unique candidate -> use it
            return f.Type().createReference(candidatePackages.iterator().next() + "." + simple);
            } else {
            // Ambiguous: multiple possible packages -> always route to unknown (never guess)
            return f.Type().createReference(UNKNOWN_PACKAGE + "." + simple);
            }
    }

    /**
     * Resolve an explicit single-type import to a concrete type reference, if present.
     * Returns the first matching import, or null if none.
     */
    private CtTypeReference<?> resolveFromExplicitTypeImports(CtElement ctx, String simple) {
        List<String> packages = resolveExplicitImportPackages(ctx, simple);
        if (packages.isEmpty()) return null;
        // Return first match (for backward compatibility)
        return f.Type().createReference(packages.get(0) + "." + simple);
    }

    /**
     * Resolve all explicit single-type imports matching a simple name.
     * Returns list of package names (in source order).
     */
    private List<String> resolveExplicitImportPackages(CtElement ctx, String simple) {
        List<String> packages = new ArrayList<>();
        var type = ctx.getParent(CtType.class);
        var pos = (type != null ? type.getPosition() : null);
        var cu = (pos != null ? pos.getCompilationUnit() : null);
        if (cu == null) return packages;

        for (CtImport imp : cu.getImports()) {
            if (imp.getImportKind() == CtImportKind.TYPE) {
                try {
                    var ref = imp.getReference();
                    if (ref instanceof CtTypeReference) {
                        String qn = ((CtTypeReference<?>) ref).getQualifiedName();
                        if (qn != null && qn.endsWith("." + simple)) {
                            String pkg = qn.substring(0, qn.lastIndexOf('.'));
                            if (!packages.contains(pkg)) {
                                packages.add(pkg);
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }
        }
        return packages;
    }

    /**
     * Seed types that appear only as explicit (single-type) imports.
     */
    @SuppressWarnings("deprecation")
    private void seedExplicitTypeImports(CtModel model, CollectResult out) {
        final java.util.regex.Pattern SINGLE_IMPORT =
                java.util.regex.Pattern.compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\s*;");

        getSliceTypes(model).forEach(t -> {
            SourcePosition pos = t.getPosition();
            var cu = (pos != null ? pos.getCompilationUnit() : null);
            if (cu == null) return;

            Set<String> fqns = new LinkedHashSet<>();
            for (CtImport imp : cu.getImports()) {
                if (imp.getImportKind() == CtImportKind.TYPE) {
                    try {
                        var r = imp.getReference();
                        if (r instanceof CtTypeReference) {
                            String qn = ((CtTypeReference<?>) r).getQualifiedName();
                            if (qn != null && !qn.isEmpty()) fqns.add(qn);
                        }
                    } catch (Throwable ignored) { }
                }
            }

            try {
                String src = cu.getOriginalSourceCode();
                if (src != null) {
                    var m = SINGLE_IMPORT.matcher(src);
                    while (m.find()) {
                        String fqn = m.group(1);
                        if (fqn.endsWith(".*")) continue;
                        fqns.add(fqn);
                    }
                }
            } catch (Throwable ignored) { }

            for (String fqn : fqns) {
                if (isJdkPkg(fqn)) continue;
                out.addTypePlanIfNew(new TypeStubPlan(fqn, TypeStubPlan.Kind.CLASS), f);
            }
        });
    }

    /* ======================================================================
     *                           OVERLOAD GAP PASS
     * ====================================================================== */

    /**
     * Detect invocations where an owner type and same-name methods exist, but no overload matches
     * the given argument types. Emit a plan to create a matching overload.
     */
    private void collectOverloadGaps(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        List<CtInvocation<?>> invocations = getElementsFromSliceTypes(model, (CtInvocation<?> inv) -> {
            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : null);
            if (name == null || "<init>".equals(name)) return false;

            CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
            if (rawOwner == null) return false;
            CtTypeReference<?> owner = chooseOwnerPackage(rawOwner, inv);
            if (owner == null || isJdkType(owner)) return false;

            CtType<?> ownerDecl = null;
            try { ownerDecl = owner.getTypeDeclaration(); } catch (Throwable ignored) {}
            if (!(ownerDecl instanceof CtClass)) return false;

            List<CtMethod<?>> sameName = ((CtClass<?>) ownerDecl).getMethods().stream()
                    .filter(m -> name.equals(m.getSimpleName()))
                    .collect(java.util.stream.Collectors.toList());
            if (sameName.isEmpty()) return false;

            return !hasApplicableOverload(sameName, inv.getArguments());
        });

        for (CtInvocation<?> inv : invocations) {
            CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
            CtTypeReference<?> owner = chooseOwnerPackage(rawOwner, inv);
            if (owner == null || isJdkType(owner)) continue;

            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : "m");

            boolean isStatic = inv.getTarget() instanceof CtTypeAccess<?>;
            boolean isSuperCall = inv.getTarget() instanceof CtSuperAccess<?>;

            CtTypeReference<?> returnType = inferReturnTypeFromContext(inv);
            if (returnType == null) returnType = f.Type().VOID_PRIMITIVE;
            
            List<CtTypeReference<?>> paramTypes = inv.getArguments().stream()
                    .map(this::paramTypeOrObject)
                    .collect(Collectors.toList());

            MethodStubPlan.Visibility vis = MethodStubPlan.Visibility.PACKAGE;
            try {
                CtType<?> ownerDecl = owner.getTypeDeclaration();
                if (ownerDecl instanceof CtClass) {
                    List<CtMethod<?>> sameName = ((CtClass<?>) ownerDecl).getMethods().stream()
                            .filter(m -> name.equals(m.getSimpleName()))
                            .collect(Collectors.toList());
                    if (!sameName.isEmpty()) {
                        Set<ModifierKind> mods = sameName.get(0).getModifiers();
                        if (mods.contains(ModifierKind.PUBLIC)) vis = MethodStubPlan.Visibility.PUBLIC;
                        else if (mods.contains(ModifierKind.PROTECTED)) vis = MethodStubPlan.Visibility.PROTECTED;
                        else if (mods.contains(ModifierKind.PRIVATE)) vis = MethodStubPlan.Visibility.PRIVATE;
                        else vis = MethodStubPlan.Visibility.PACKAGE;
                    }
                }
            } catch (Throwable ignored) { }

            List<CtTypeReference<?>> thrown = Collections.emptyList();
            if (isSuperCall) {
                thrown = new ArrayList<>(
                        Optional.ofNullable(inv.getParent(CtMethod.class))
                                .map(CtMethod::getThrownTypes)
                                .orElse(Collections.emptySet())
                );
            }

            // Check if a method with the same name and same parameter count already exists
            // This prevents generating duplicate method stubs that would cause ambiguity errors
            boolean methodAlreadyExists = false;
            try {
                CtType<?> ownerDecl = owner.getTypeDeclaration();
                int paramCount = paramTypes != null ? paramTypes.size() : 0;
                if (ownerDecl instanceof CtClass) {
                    CtClass<?> clazz = (CtClass<?>) ownerDecl;
                    for (CtMethod<?> m : clazz.getMethods()) {
                        if (name.equals(m.getSimpleName()) && m.getParameters().size() == paramCount) {
                            methodAlreadyExists = true;
                            System.out.println("[SpoonCollector] DEBUG: methodAlreadyExists - skipping " + 
                                owner.getQualifiedName() + "#" + name + " with " + paramCount + " parameters");
                            break;
                        }
                    }
                } else if (ownerDecl instanceof CtInterface) {
                    CtInterface<?> iface = (CtInterface<?>) ownerDecl;
                    for (CtMethod<?> m : iface.getMethods()) {
                        if (name.equals(m.getSimpleName()) && m.getParameters().size() == paramCount) {
                            methodAlreadyExists = true;
                            System.out.println("[SpoonCollector] DEBUG: methodAlreadyExists - skipping " + 
                                owner.getQualifiedName() + "#" + name + " with " + paramCount + " parameters");
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) { }

            if (!methodAlreadyExists) {
                out.methodPlans.add(new MethodStubPlan(owner, name, returnType, paramTypes, isStatic, vis, thrown));
            }
        }
    }

    /**
     * Lightweight applicability test: require arity match and relaxed type compatibility.
     */
    private boolean hasApplicableOverload(List<CtMethod<?>> methods, List<CtExpression<?>> args) {
        for (CtMethod<?> m : methods) {
            List<CtParameter<?>> ps = m.getParameters();
            if (ps.size() != args.size()) continue;

            boolean allOk = true;
            for (int i = 0; i < ps.size(); i++) {
                CtTypeReference<?> pt = null;
                try { pt = ps.get(i).getType(); } catch (Throwable ignored) {}
                CtTypeReference<?> at = paramTypeOrObject(args.get(i));
                if (!isSaneType(pt) || !isSaneType(at)) { allOk = false; break; }

                String pqn = safeQN(pt), aqn = safeQN(at);
                if (pqn.equals(aqn)) continue;
                if (isPrimitiveBoxPair(pqn, aqn)) continue;

                allOk = false; break;
            }
            if (allOk) return true;
        }
        return false;
    }

    /**
     * Primitive vs boxed type pairing check (coarse).
     */
    private boolean isPrimitiveBoxPair(String a, String b) {
        return (a.equals("int") && b.equals("java.lang.Integer")) || (b.equals("int") && a.equals("java.lang.Integer")) ||
                (a.equals("long") && b.equals("java.lang.Long"))     || (b.equals("long") && a.equals("java.lang.Long")) ||
                (a.equals("double") && b.equals("java.lang.Double")) || (b.equals("double") && a.equals("java.lang.Double")) ||
                (a.equals("float") && b.equals("java.lang.Float"))   || (b.equals("float") && a.equals("java.lang.Float")) ||
                (a.equals("short") && b.equals("java.lang.Short"))   || (b.equals("short") && a.equals("java.lang.Short")) ||
                (a.equals("byte") && b.equals("java.lang.Byte"))     || (b.equals("byte") && a.equals("java.lang.Byte")) ||
                (a.equals("char") && b.equals("java.lang.Character"))|| (b.equals("char") && a.equals("java.lang.Character")) ||
                (a.equals("boolean") && b.equals("java.lang.Boolean"))|| (b.equals("boolean") && a.equals("java.lang.Boolean"));
    }

    /* ======================================================================
     *                      TYPE WALKING / GENERICS PASS
     * ====================================================================== */

    /**
     * Walk a type reference and all its actual type arguments, planning unresolved ones.
     */
    private void collectTypeRefDeep(CtElement ctx, CtTypeReference<?> t, CollectResult out) {
        if (t == null) return;

        maybePlanDeclaredType(ctx, t, out);

        try {
            for (CtTypeReference<?> arg : t.getActualTypeArguments()) {
                if (arg == null) continue;
                if (arg instanceof spoon.reflect.reference.CtWildcardReference) {
                    var w = (spoon.reflect.reference.CtWildcardReference) arg;
                    CtTypeReference<?> bound = w.getBoundingType();
                    if (bound != null) collectTypeRefDeep(ctx, bound, out);
                } else {
                    collectTypeRefDeep(ctx, arg, out);
                }
            }
        } catch (Throwable ignored) { }
    }

    /* ======================================================================
     *                        SUPERTYPES / INHERITANCE PASS
     * ====================================================================== */

    /**
     * Collect supertypes (superclass and superinterfaces) and their generic arguments.
     */
    private void collectSupertypes(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        // classes: superclass + superinterfaces
        for (CtClass<?> c : getElementsFromSliceTypes(model, (CtClass<?> cc) -> true)) {
            CtTypeReference<?> sup = null;
            try { sup = c.getSuperclass(); } catch (Throwable ignored) {}
            if (sup != null) {
                CtTypeReference<?> owner = chooseOwnerPackage(sup, c);
                if (owner != null && !isJdkType(owner)) {
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                }
            }
            for (CtTypeReference<?> si : safe(c.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, c);
                if (owner != null && !isJdkType(owner)) {
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.INTERFACE), f);
                }
            }
        }

        // interfaces: superinterfaces
        for (CtInterface<?> i : getElementsFromSliceTypes(model, (CtInterface<?> ii) -> true)) {
            for (CtTypeReference<?> si : safe(i.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, i);
                if (owner != null && !isJdkType(owner)) {
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.INTERFACE), f);
                }
            }
        }

        // Generic type arguments inside extends/implements
        for (CtType<?> t : getSliceTypes(model)) {
            CtTypeReference<?> sup = null;
            try { sup = (t instanceof CtClass) ? ((CtClass<?>) t).getSuperclass() : null; } catch (Throwable ignored) {}
            if (sup != null) {
                for (CtTypeReference<?> ta : safe(sup.getActualTypeArguments())) {
                    if (ta == null) continue;
                    CtTypeReference<?> owner = chooseOwnerPackage(ta, t);
                    if (owner != null && !isJdkType(owner) && owner.getDeclaration() == null) {
                        out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                    }
                }
            }

            Collection<CtTypeReference<?>> sis = (t instanceof CtClass)
                    ? ((CtClass<?>) t).getSuperInterfaces()
                    : (t instanceof CtInterface) ? ((CtInterface<?>) t).getSuperInterfaces()
                    : Collections.emptyList();

            for (CtTypeReference<?> si : safe(sis)) {
                for (CtTypeReference<?> ta : safe(si.getActualTypeArguments())) {
                    if (ta == null) continue;
                    CtTypeReference<?> owner = chooseOwnerPackage(ta, t);
                    if (owner != null && !isJdkType(owner) && owner.getDeclaration() == null) {
                        out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS), f);
                    }
                }
            }
        }
    }

    /**
     * Null-safe wrapper for collections (returns empty list if null).
     */
    @SuppressWarnings("unchecked")
    private <T> Collection<T> safe(Collection<T> c) {
        return (c == null ? Collections.emptyList() : c);
    }

    /* ======================================================================
     *         INSTANCEOF / CASTS / CLASS LITERALS / FOREACH CONTRACTS
     * ====================================================================== */

    /**
     * Collect types referenced via instanceof, casts, class literals, and for-each loops.
     * Also plan iterator() and Iterable&lt;E&gt; contracts for foreach.
     */
    private void collectFromInstanceofCastsClassLiteralsAndForEach(CtModel model, CollectResult out) {
        // OPTIMIZATION: Get elements only from slice types, not entire model
        // instanceof (right-hand side type)
        for (CtBinaryOperator<?> bo : getElementsFromSliceTypes(model, new TypeFilter<>(CtBinaryOperator.class))) {
            if (bo.getKind() == BinaryOperatorKind.INSTANCEOF) {
                if (bo.getRightHandOperand() instanceof CtTypeAccess) {
                    CtTypeReference<?> t = ((CtTypeAccess<?>) bo.getRightHandOperand()).getAccessedType();
                    if (t != null) maybePlanDeclaredType(bo, t, out);
                }
            }
        }

        // class literals: Foo.class
        for (CtTypeAccess<?> ta : getElementsFromSliceTypes(model, new TypeFilter<>(CtTypeAccess.class))) {
            CtTypeReference<?> t = ta.getAccessedType();
            if (t != null) maybePlanDeclaredType(ta, t, out);
        }

        // foreach contracts
        for (CtForEach fe : getElementsFromSliceTypes(model, new TypeFilter<>(CtForEach.class))) {
            CtExpression<?> expr = fe.getExpression();
            CtTypeReference<?> iterType = (expr != null ? expr.getType() : null);

            CtTypeReference<?> elem = null;
            if (fe.getVariable() != null) elem = fe.getVariable().getType();
            if (elem == null) elem = inferIterableElementType(iterType);

            if (elem != null) maybePlanDeclaredType(fe, elem, out);

            if (iterType != null) {
                CtTypeReference<?> owner = chooseOwnerPackage(iterType, fe);
                if (owner != null && !isJdkType(owner)) {
                    CtTypeReference<?> iterRef = f.Type().createReference("java.util.Iterator");
                    if (elem != null) iterRef.addActualTypeArgument(elem);

                    out.methodPlans.add(new MethodStubPlan(
                            owner, "iterator", iterRef, Collections.emptyList(),
                            false, MethodStubPlan.Visibility.PUBLIC, Collections.emptyList()
                    ));
                }
            }
        }

        // casts (reflected to avoid hard dependency)
        try {
            Class<?> CT_TYPE_CAST = Class.forName("spoon.reflect.code.CtTypeCast");
            // OPTIMIZATION: Get elements only from slice types, not entire model
            for (CtElement el : getElementsFromSliceTypes(model, new TypeFilter<>(CtElement.class))) {
                if (CT_TYPE_CAST.isInstance(el)) {
                    CtTypeReference<?> t = null;
                    try {
                        Object typeRef = CT_TYPE_CAST.getMethod("getTypeCasted").invoke(el);
                        if (typeRef instanceof CtTypeReference) t = (CtTypeReference<?>) typeRef;
                    } catch (NoSuchMethodException nf) {
                        Object typeRef = CT_TYPE_CAST.getMethod("getType").invoke(el);
                        if (typeRef instanceof CtTypeReference) t = (CtTypeReference<?>) typeRef;
                    }
                    if (t != null) maybePlanDeclaredType(el, t, out);
                }
            }
        } catch (ClassNotFoundException ignore) {
            // Older/variant Spoon; other passes still cover most cases.
        } catch (Throwable ignore) { /* best-effort */ }
    }

    /**
     * Infer the element type from an iterable/array type reference if possible.
     */
    private CtTypeReference<?> inferIterableElementType(CtTypeReference<?> itT) {
        if (itT == null) return null;
        try {
            if (itT instanceof CtArrayTypeReference) return ((CtArrayTypeReference<?>) itT).getComponentType();
            var args = itT.getActualTypeArguments();
            if (args != null && !args.isEmpty()) return args.get(0);
        } catch (Throwable ignored) { }
        return null;
    }

    /* ======================================================================
     *                             OWNER TYPING
     * ====================================================================== */

    /**
     * Aggregate owner types that must exist to support planned members/methods/throws.
     */
    private List<TypeStubPlan> ownersNeedingTypes(CollectResult res) {
        Set<String> fqns = new LinkedHashSet<>();

        for (FieldStubPlan p : res.fieldPlans) addIfNonJdk(fqns, p.ownerType);

        for (ConstructorStubPlan p : res.ctorPlans) {
            addIfNonJdk(fqns, p.ownerType);
            for (CtTypeReference<?> t : p.parameterTypes) addIfNonJdk(fqns, t);
        }

        for (MethodStubPlan p : res.methodPlans) {
            addIfNonJdk(fqns, p.ownerType);
            addIfNonJdk(fqns, p.returnType);
            for (CtTypeReference<?> t : p.paramTypes) addIfNonJdk(fqns, t);
            for (CtTypeReference<?> t : p.thrownTypes) addIfNonJdk(fqns, t);
        }

        return fqns.stream()
                .map(fqn -> new TypeStubPlan(fqn, TypeStubPlan.Kind.CLASS))
                .collect(Collectors.toList());
    }

    /**
     * Add a type's qualified name if it is a non-JDK, non-primitive/void/array type.
     */
    private void addIfNonJdk(Set<String> out, CtTypeReference<?> t) {
        if (t == null) return;

        try {
            if (t.isPrimitive()) return;
            if (t.equals(f.Type().VOID_PRIMITIVE)) return;
            if (t.isArray()) return;
        } catch (Throwable ignored) { }

        String qn = t.getQualifiedName();
        if (qn == null || qn.isEmpty()) return;

        if (qn.startsWith("java.") || qn.startsWith("javax.")
                || qn.startsWith("jakarta.") || qn.startsWith("sun.")
                || qn.startsWith("jdk.")) return;

        out.add(qn);
    }

    /**
     * Derive a parameter type from an argument expression; returns Unknown for null/unknown-ish.
     */
    private CtTypeReference<?> paramTypeOrObject(CtExpression<?> arg) {
        if (arg == null) {
            debugLog("paramTypeOrObject - arg is null");
            return f.Type().createReference(UNKNOWN_TYPE_FQN);
        }
        
        debugLog("paramTypeOrObject - arg type: " + arg.getClass().getSimpleName());

        // If argument is a field access that can't be resolved, don't treat it as a type
        // This prevents constants like PUSH_ANDROID_SERVER_ADDRESS from being stubbed as types
        if (arg instanceof CtFieldAccess) {
            CtFieldAccess<?> fa = (CtFieldAccess<?>) arg;
            String fieldName = fa.getVariable() != null ? fa.getVariable().getSimpleName() : "unknown";
            debugLog("paramTypeOrObject - Field access: " + fieldName);
            boolean isUnresolved = false;
            try {
                isUnresolved = (fa.getVariable() == null || fa.getVariable().getDeclaration() == null);
            } catch (StackOverflowError | OutOfMemoryError e) {
                isUnresolved = true;
                } catch (Throwable ignored) {}
            
            if (isUnresolved) {
                debugLog("paramTypeOrObject - Field is unresolved");
                
                // CRITICAL FIX: For static field accesses like EventType.PUSH_SERVER_Exception,
                // check if it's a static field access FIRST before calling getType()
                // getType() on static field accesses can return wrong types (like the enclosing class)
                if (fa.getTarget() instanceof CtTypeAccess) {
                    // Static field access - use the owner type, not getType()
                    debugLog("paramTypeOrObject - Detected static field access, using owner type");
                    // Static field access - try to infer type from the owner type
                    CtTypeAccess<?> target = (CtTypeAccess<?>) fa.getTarget();
                    CtTypeReference<?> ownerType = target.getAccessedType();
                    String ownerQn = safeQN(ownerType);
                    debugLog("paramTypeOrObject - Static field access: " + ownerQn + "." + fieldName);
                    
                    if (ownerType != null) {
                        // Resolve the owner type to its fully qualified name using chooseOwnerPackage
                        // This handles imports like "import IMExceptionEvent.EventType;" -> EventType resolves to IMExceptionEvent.EventType
                        CtTypeReference<?> resolvedOwner = chooseOwnerPackage(ownerType, fa);
                        String resolvedOwnerQn = safeQN(resolvedOwner);
                        debugLog("paramTypeOrObject - Resolved owner: " + resolvedOwnerQn);
                        
                        // For static field accesses, return the owner type (the type of the field is the owner type)
                        // This handles both enum constants (EventType.PUSH_SERVER_Exception -> EventType)
                        // and static fields (SomeClass.SOME_FIELD -> SomeClass)
                        if (resolvedOwnerQn != null && !resolvedOwnerQn.equals(UNKNOWN_TYPE_FQN)) {
                            debugLog("paramTypeOrObject - Static field access, returning owner type: " + resolvedOwnerQn);
                            return resolvedOwner;
                        }
                        // If resolved to unknown, still return it (better than Unknown)
                        debugLog("paramTypeOrObject - Owner resolved to unknown, but returning it anyway: " + resolvedOwnerQn);
                        return resolvedOwner;
                    }
                }
                
                // If we can't infer, return String for constants (common case)
                if (fieldName != null && fieldName.equals(fieldName.toUpperCase()) && fieldName.length() > 1) {
                    // Likely a constant - return String type (most constants are String)
                    debugLog("paramTypeOrObject - Returning String for constant-like field");
                    return f.Type().createReference("java.lang.String");
                }
                debugLog("paramTypeOrObject - Returning Unknown");
                return f.Type().createReference(UNKNOWN_TYPE_FQN);
            } else {
                debugLog("paramTypeOrObject - Field is resolved, using getType()");
                try {
                    CtTypeReference<?> t = fa.getType();
                    debugLog("paramTypeOrObject - Resolved field type: " + safeQN(t));
                    return t != null ? t : f.Type().createReference(UNKNOWN_TYPE_FQN);
                } catch (Throwable e) {
                    System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Exception getting resolved field type: " + e.getMessage());
                return f.Type().createReference(UNKNOWN_TYPE_FQN);
                }
            }
        }

        if (arg instanceof CtBinaryOperator) {
            CtBinaryOperator<?> bin = (CtBinaryOperator<?>) arg;
            if (bin.getKind() == BinaryOperatorKind.PLUS &&
                    (isStringy(bin) || isStringy(bin.getLeftHandOperand()) || isStringy(bin.getRightHandOperand()))) {
                return f.Type().createReference("java.lang.String");
            }
        }

        if (arg instanceof CtLiteral && ((CtLiteral<?>) arg).getValue() == null) {
            return f.Type().createReference(UNKNOWN_TYPE_FQN);
        }

        CtTypeReference<?> t = null;
        try { t = arg.getType(); } catch (Throwable ignored) {}
        if (t == null) return f.Type().createReference(UNKNOWN_TYPE_FQN);

        String qn = t.getQualifiedName();
        if (qn == null || "null".equals(qn) || qn.contains("NullType")) {
            return f.Type().createReference(UNKNOWN_TYPE_FQN);
        }
        return t;
    }

    /**
     * Returns true when an invocation is used as a standalone statement.
     */
    private boolean isStandaloneInvocation(CtInvocation<?> inv) {
        return (inv.getParent() instanceof CtBlock) && (inv.getRoleInParent() == CtRole.STATEMENT);
    }

    /**
     * Returns true if any of the given arguments is a null literal.
     */
    private boolean argsContainNullLiteral(List<CtExpression<?>> args) {
        for (CtExpression<?> a : args) {
            if (a instanceof CtLiteral && ((CtLiteral<?>) a).getValue() == null) return true;
        }
        return false;
    }

    /**
     * Safely obtain qualified name; never returns null (empty string on failure).
     */
    private static String safeQN(CtTypeReference<?> t) {
        try {
            String s = (t == null ? null : t.getQualifiedName());
            return (s == null ? "" : s);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Returns true if a type reference belongs to the JDK space (type form).
     */
    private boolean isJdkType(CtTypeReference<?> t) {
        if (t == null) return false;
        String qn = t.getQualifiedName();
        return qn != null && (qn.startsWith("java.") || qn.startsWith("javax.") ||
                qn.startsWith("jakarta.") || qn.startsWith("sun.") || qn.startsWith("jdk."));
    }


    /** FQN that uses '$' for member classes (Outer$Inner) instead of dotted form. */
    /** FQN that uses '$' for member classes (Outer$Inner). */
    private static String nestedAwareFqnOf(CtTypeReference<?> ref) {
        if (ref == null) return null;
        CtTypeReference<?> decl = ref.getDeclaringType();
        if (decl != null) return nestedAwareFqnOf(decl) + "$" + ref.getSimpleName();
        return ref.getQualifiedName();
    }
    // In SpoonCollector
    private CtTypeReference<?> resolveOwnerFromStaticImports(CtInvocation<?> inv, String methodSimple) {
        var type = inv.getParent(CtType.class);
        var pos  = (type != null ? type.getPosition() : null);
        var cu   = (pos != null ? pos.getCompilationUnit() : null);
        if (cu == null) return null;

        // 1) Spoon-parsed static method imports
        for (CtImport imp : cu.getImports()) {
            try {
                if (imp.getImportKind().name().contains("METHOD")) { // CtImportKind.METHOD
                    var ref = imp.getReference();
                    if (ref instanceof CtExecutableReference) {
                        var er = (CtExecutableReference<?>) ref;
                        if (methodSimple.equals(er.getSimpleName()) && er.getDeclaringType() != null) {
                            return f.Type().createReference(er.getDeclaringType().getQualifiedName());
                        }
                    }
                }
                // On-demand static: import static pkg.Api.*;
                if (imp.getImportKind().name().contains("ALL") && imp.getReference() instanceof CtTypeReference) {
                    // This means: any static member of that type; owner is that type.
                    CtTypeReference<?> tr = (CtTypeReference<?>) imp.getReference();
                    return f.Type().createReference(tr.getQualifiedName());
                }
            } catch (Throwable ignored) {}
        }

        // 2) Raw-source fallback (like your star-import parser)
        try {
            String src = cu.getOriginalSourceCode();
            if (src != null) {
                var m = java.util.regex.Pattern
                        .compile("\\bimport\\s+static\\s+([\\w\\.]+)\\.(\\*|[A-Za-z_][\\w_]*)\\s*;")
                        .matcher(src);
                while (m.find()) {
                    String clsFqn = m.group(1);
                    String member = m.group(2);
                    if ("*".equals(member) || methodSimple.equals(member)) {
                        return f.Type().createReference(clsFqn);
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
    
    /* ======================================================================
     *                    CONSTANT / ENUM NORMALIZATION
     * ====================================================================== */

    /**
     * Check if a name looks like a constant (ALL_CAPS with underscores/numbers).
     */
    private boolean isConstantLikeName(String name) {
        return name != null
                && name.equals(name.toUpperCase())
                && name.matches("[A-Z0-9_]+");
    }

    /**
     * Normalize unresolved ALL_CAPS constants in expression contexts to literals.
     * This runs BEFORE type collection to prevent constants from being treated as types.
     */
    private void normalizeUnresolvedConstants(CtModel model) {
        // Collect elements to modify first, then modify after traversal to avoid modifying during traversal
        List<CtExpression<?>> toRewrite = new ArrayList<>();
        
        getSliceTypes(model).forEach(type -> {
            type.getElements((CtElement el) -> {
                // Look for unresolved simple-name expressions that look like constants
                if (el instanceof CtTypeAccess) {
                    CtTypeAccess<?> ta = (CtTypeAccess<?>) el;
                    String simple = ta.getAccessedType() != null ? ta.getAccessedType().getSimpleName() : null;
                    if (simple != null && isConstantLikeName(simple)) {
                        // Check if it's unresolved and in expression context
                        // Avoid calling getDeclaration() which might trigger type resolution
                        try {
                            CtTypeReference<?> accessedType = ta.getAccessedType();
                            if (accessedType != null) {
                                // Check if unresolved by trying to get declaration (but catch StackOverflow)
                                boolean isUnresolved = false;
                                try {
                                    isUnresolved = (accessedType.getDeclaration() == null);
                                } catch (StackOverflowError | OutOfMemoryError e) {
                                    // If we get stack overflow, assume unresolved to avoid infinite loops
                                    isUnresolved = true;
                                } catch (Throwable ignored) {
                                    // Other errors - assume resolved to be safe
                                }
                                
                                if (isUnresolved && isInExpressionContext(ta)) {
                                    toRewrite.add(ta);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } else if (el instanceof CtFieldRead) {
                    CtFieldRead<?> fr = (CtFieldRead<?>) el;
                    String simple = fr.getVariable() != null ? fr.getVariable().getSimpleName() : null;
                    if (simple != null && isConstantLikeName(simple)) {
                        try {
                            var varRef = fr.getVariable();
                            if (varRef != null) {
                                // Check if unresolved by trying to get declaration (but catch StackOverflow)
                                boolean isUnresolved = false;
                                try {
                                    isUnresolved = (varRef.getDeclaration() == null);
                                } catch (StackOverflowError | OutOfMemoryError e) {
                                    // If we get stack overflow, assume unresolved to avoid infinite loops
                                    isUnresolved = true;
                                } catch (Throwable ignored) {
                                    // Other errors - assume resolved to be safe
                                }
                                
                                if (isUnresolved && isInExpressionContext(fr)) {
                                    toRewrite.add(fr);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } else if (el instanceof CtVariableRead) {
                    CtVariableRead<?> vr = (CtVariableRead<?>) el;
                    String simple = vr.getVariable() != null ? vr.getVariable().getSimpleName() : null;
                    if (simple != null && isConstantLikeName(simple)) {
                        try {
                            var varRef = vr.getVariable();
                            if (varRef != null) {
                                // Check if unresolved by trying to get declaration (but catch StackOverflow)
                                boolean isUnresolved = false;
                                try {
                                    isUnresolved = (varRef.getDeclaration() == null);
                                } catch (StackOverflowError | OutOfMemoryError e) {
                                    // If we get stack overflow, assume unresolved to avoid infinite loops
                                    isUnresolved = true;
                                } catch (Throwable ignored) {
                                    // Other errors - assume resolved to be safe
                                }
                                
                                if (isUnresolved && isInExpressionContext(vr)) {
                                    toRewrite.add(vr);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                return false; // Continue traversal
            });
        });
        
        // Now modify collected elements after traversal
        for (CtExpression<?> expr : toRewrite) {
            try {
                rewriteConstantToLiteral(expr);
            } catch (StackOverflowError | OutOfMemoryError e) {
                // Skip if we get stack overflow during rewrite
                continue;
            } catch (Throwable ignored) {
                // Skip on other errors
            }
        }
    }

    /**
     * Check if an element is in an expression context (not a type position).
     */
    private boolean isInExpressionContext(CtElement el) {
        CtElement parent = el.getParent();
        if (parent == null) return false;

        // Type positions: skip these
        if (parent instanceof CtTypeReference) return false;
        if (parent instanceof CtField && el.getRoleInParent() == CtRole.TYPE) return false;
        if (parent instanceof CtVariable && el.getRoleInParent() == CtRole.TYPE) return false;
        if (parent instanceof CtParameter && el.getRoleInParent() == CtRole.TYPE) return false;
        if (parent instanceof CtMethod && el.getRoleInParent() == CtRole.TYPE) return false;
        // For CtClass and CtInterface, if the element is a type reference, it's likely in extends/implements
        // We can check this by seeing if it's a CtTypeAccess or if parent has getSuperclass/getSuperInterfaces
        if (parent instanceof CtClass && el instanceof CtTypeAccess) {
            // Likely in extends clause - skip
            return false;
        }
        if (parent instanceof CtInterface && el instanceof CtTypeAccess) {
            // Likely in extends clause - skip
            return false;
        }

        // Expression positions: rewrite these
        if (parent instanceof CtInvocation) return true;
        if (parent instanceof CtAssignment) return true;
        if (parent instanceof CtReturn) return true;
        if (parent instanceof CtBinaryOperator) return true;
        if (parent instanceof CtUnaryOperator) return true;
        if (parent instanceof CtVariable && el.getRoleInParent() == CtRole.DEFAULT_EXPRESSION) return true;
        if (parent instanceof CtField && el.getRoleInParent() == CtRole.DEFAULT_EXPRESSION) return true;

        return false;
    }

    /**
     * Rewrite a constant-like expression to a literal based on expected type.
     */
    private void rewriteConstantToLiteral(CtExpression<?> expr) {
        CtTypeReference<?> expectedType = inferExpectedType(expr);
        if (expectedType == null) return; // Can't safely infer, leave it

        try {
            String qn = expectedType.getQualifiedName();
            if ("java.lang.String".equals(qn)) {
                // Rewrite to string literal
                String constantName = extractConstantName(expr);
                CtLiteral<String> lit = f.Core().createLiteral();
                lit.setValue(constantName); // Use the constant name as the string value
                lit.setType(f.Type().STRING);
                replaceExpression(expr, lit);
            } else if (expectedType.isPrimitive()) {
                // Rewrite to default primitive literal
                CtLiteral<?> lit = createDefaultPrimitiveLiteral(expectedType);
                if (lit != null) {
                    replaceExpression(expr, lit);
                }
            }
        } catch (StackOverflowError | OutOfMemoryError e) {
            // Avoid infinite loops - skip rewriting if we hit stack overflow
            return;
        } catch (Throwable ignored) {}
    }

    /**
     * Extract the constant name from an expression.
     */
    private String extractConstantName(CtExpression<?> expr) {
        if (expr instanceof CtTypeAccess) {
            CtTypeAccess<?> ta = (CtTypeAccess<?>) expr;
            return ta.getAccessedType() != null ? ta.getAccessedType().getSimpleName() : "CONSTANT";
        } else if (expr instanceof CtFieldRead) {
            CtFieldRead<?> fr = (CtFieldRead<?>) expr;
            return fr.getVariable() != null ? fr.getVariable().getSimpleName() : "CONSTANT";
        } else if (expr instanceof CtVariableRead) {
            CtVariableRead<?> vr = (CtVariableRead<?>) expr;
            return vr.getVariable() != null ? vr.getVariable().getSimpleName() : "CONSTANT";
        }
        return "CONSTANT";
    }

    /**
     * Infer the expected type of an expression from its context.
     */
    private CtTypeReference<?> inferExpectedType(CtExpression<?> expr) {
        CtElement parent = expr.getParent();
        if (parent == null) return null;

        // Method argument
        if (parent instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) parent;
            List<CtExpression<?>> args = inv.getArguments();
            int idx = args.indexOf(expr);
            if (idx >= 0) {
                try {
                    CtExecutableReference<?> ex = inv.getExecutable();
                    if (ex != null && ex.getParameters().size() > idx) {
                        return ex.getParameters().get(idx);
                    }
                } catch (StackOverflowError | OutOfMemoryError e) {
                    return null; // Avoid infinite loops
                } catch (Throwable ignored) {}
            }
        }

        // Assignment LHS
        if (parent instanceof CtAssignment) {
            CtAssignment<?, ?> as = (CtAssignment<?, ?>) parent;
            if (Objects.equals(as.getAssignment(), expr)) {
                try {
                    CtExpression<?> assigned = (CtExpression<?>) as.getAssigned();
                    if (assigned != null) {
                        return assigned.getType();
                    }
                } catch (StackOverflowError | OutOfMemoryError e) {
                    return null; // Avoid infinite loops
                } catch (Throwable ignored) {}
            }
        }

        // Variable initialization
        if (parent instanceof CtVariable) {
            CtVariable<?> v = (CtVariable<?>) parent;
            if (Objects.equals(v.getDefaultExpression(), expr)) {
                try {
                    return v.getType();
                } catch (StackOverflowError | OutOfMemoryError e) {
                    return null; // Avoid infinite loops
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }

        // Return statement
        if (parent instanceof CtReturn) {
            try {
                CtMethod<?> m = parent.getParent(CtMethod.class);
                if (m != null) {
                    return m.getType();
                }
            } catch (StackOverflowError | OutOfMemoryError e) {
                return null; // Avoid infinite loops
            } catch (Throwable ignored) {
                return null;
            }
        }

        // Binary operators
        if (parent instanceof CtBinaryOperator) {
            CtBinaryOperator<?> bo = (CtBinaryOperator<?>) parent;
            try {
                // First, check if the binary operator is in a variable/assignment context
                CtElement boParent = bo.getParent();
                if (boParent instanceof CtVariable) {
                    CtVariable<?> var = (CtVariable<?>) boParent;
                    if (Objects.equals(var.getDefaultExpression(), bo)) {
                        CtTypeReference<?> varType = var.getType();
                        if (varType != null) {
                            return varType;
                        }
                    }
                }
                if (boParent instanceof CtAssignment) {
                    CtAssignment<?, ?> as = (CtAssignment<?, ?>) boParent;
                    if (Objects.equals(as.getAssignment(), bo)) {
                        try {
                            CtTypeReference<?> assignedType = ((CtExpression<?>) as.getAssigned()).getType();
                            if (assignedType != null) {
                                return assignedType;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                
                // If not in variable/assignment context, infer from binary operator itself
                BinaryOperatorKind kind = bo.getKind();
                
                // String concatenation
                if (kind == BinaryOperatorKind.PLUS) {
                    if (isStringy(bo.getLeftHandOperand()) || isStringy(bo.getRightHandOperand())) {
                        return f.Type().STRING;
                    }
                }
                
                // Arithmetic operators (+, -, *, /, %)
                if (kind == BinaryOperatorKind.PLUS || kind == BinaryOperatorKind.MINUS ||
                    kind == BinaryOperatorKind.MUL || kind == BinaryOperatorKind.DIV ||
                    kind == BinaryOperatorKind.MOD) {
                    // Check the other operand's type
                    CtExpression<?> other = (Objects.equals(bo.getLeftHandOperand(), expr)) 
                        ? bo.getRightHandOperand() : bo.getLeftHandOperand();
                    if (other != null) {
                        try {
                            CtTypeReference<?> otherType = other.getType();
                            if (otherType != null && otherType.isPrimitive()) {
                                String qn = otherType.getQualifiedName();
                                if (qn != null && (qn.equals("int") || qn.equals("long") || 
                                    qn.equals("double") || qn.equals("float") || qn.equals("short") || 
                                    qn.equals("byte"))) {
                                    return otherType;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    // Default to int for arithmetic
                    return f.Type().INTEGER_PRIMITIVE;
                }
                
                // Logical operators (&&, ||)
                if (kind == BinaryOperatorKind.AND || kind == BinaryOperatorKind.OR) {
                    return f.Type().BOOLEAN_PRIMITIVE;
                }
            } catch (StackOverflowError | OutOfMemoryError e) {
                return null; // Avoid infinite loops
            } catch (Throwable ignored) {}
        }

        // Unary operators
        if (parent instanceof CtUnaryOperator) {
            CtUnaryOperator<?> uo = (CtUnaryOperator<?>) parent;
            try {
                UnaryOperatorKind kind = uo.getKind();
                // Logical negation
                if (kind == UnaryOperatorKind.NOT) {
                    return f.Type().BOOLEAN_PRIMITIVE;
                }
                // Numeric unary (+, -)
                if (kind == UnaryOperatorKind.POS || kind == UnaryOperatorKind.NEG) {
                    return f.Type().INTEGER_PRIMITIVE;
                }
            } catch (StackOverflowError | OutOfMemoryError e) {
                return null;
            } catch (Throwable ignored) {}
        }

        // Conditional / loop conditions (if, while, for, do-while)
        if (parent instanceof CtIf || parent instanceof CtWhile || parent instanceof CtFor ||
            parent instanceof CtDo) {
            return f.Type().BOOLEAN_PRIMITIVE;
        }

        return null;
    }

    /**
     * Create a default primitive literal for a type.
     */
    private CtLiteral<?> createDefaultPrimitiveLiteral(CtTypeReference<?> type) {
        try {
            if (type.equals(f.Type().BOOLEAN_PRIMITIVE)) {
                CtLiteral<Boolean> lit = f.Core().createLiteral();
                lit.setValue(false);
                lit.setType(f.Type().BOOLEAN_PRIMITIVE);
                return lit;
            } else if (type.equals(f.Type().CHARACTER_PRIMITIVE)) {
                CtLiteral<Character> lit = f.Core().createLiteral();
                lit.setValue('\0');
                lit.setType(f.Type().CHARACTER_PRIMITIVE);
                return lit;
            } else if (type.equals(f.Type().BYTE_PRIMITIVE)) {
                CtLiteral<Byte> lit = f.Core().createLiteral();
                lit.setValue((byte) 0);
                lit.setType(f.Type().BYTE_PRIMITIVE);
                return lit;
            } else if (type.equals(f.Type().SHORT_PRIMITIVE)) {
                CtLiteral<Short> lit = f.Core().createLiteral();
                lit.setValue((short) 0);
                lit.setType(f.Type().SHORT_PRIMITIVE);
                return lit;
            } else if (type.equals(f.Type().INTEGER_PRIMITIVE)) {
                CtLiteral<Integer> lit = f.Core().createLiteral();
                lit.setValue(0);
                lit.setType(f.Type().INTEGER_PRIMITIVE);
                return lit;
            } else if (type.equals(f.Type().LONG_PRIMITIVE)) {
                CtLiteral<Long> lit = f.Core().createLiteral();
                lit.setValue(0L);
                lit.setType(f.Type().LONG_PRIMITIVE);
                return lit;
            } else if (type.equals(f.Type().FLOAT_PRIMITIVE)) {
                CtLiteral<Float> lit = f.Core().createLiteral();
                lit.setValue(0f);
                lit.setType(f.Type().FLOAT_PRIMITIVE);
                return lit;
            } else if (type.equals(f.Type().DOUBLE_PRIMITIVE)) {
                CtLiteral<Double> lit = f.Core().createLiteral();
                lit.setValue(0d);
                lit.setType(f.Type().DOUBLE_PRIMITIVE);
                return lit;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Replace an expression with another in the AST.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void replaceExpression(CtExpression<?> oldExpr, CtExpression<?> newExpr) {
        try {
            CtElement parent = oldExpr.getParent();
            if (parent == null) return;

            if (parent instanceof CtInvocation) {
                CtInvocation<?> inv = (CtInvocation<?>) parent;
                List<CtExpression<?>> args = inv.getArguments();
                int idx = args.indexOf(oldExpr);
                if (idx >= 0) {
                    args.set(idx, newExpr);
                }
            } else if (parent instanceof CtAssignment) {
                CtAssignment<?, ?> as = (CtAssignment<?, ?>) parent;
                if (Objects.equals(as.getAssignment(), oldExpr)) {
                    // Use unchecked cast to handle generic type mismatch
                    as.setAssignment((CtExpression) newExpr);
                }
            } else if (parent instanceof CtVariable) {
                CtVariable<?> v = (CtVariable<?>) parent;
                if (Objects.equals(v.getDefaultExpression(), oldExpr)) {
                    // Use unchecked cast to handle generic type mismatch
                    v.setDefaultExpression((CtExpression) newExpr);
                }
            } else if (parent instanceof CtReturn) {
                CtReturn<?> ret = (CtReturn<?>) parent;
                if (Objects.equals(ret.getReturnedExpression(), oldExpr)) {
                    // Use unchecked cast to handle generic type mismatch
                    ret.setReturnedExpression((CtExpression) newExpr);
                }
            } else if (parent instanceof CtBinaryOperator) {
                CtBinaryOperator<?> bo = (CtBinaryOperator<?>) parent;
                if (Objects.equals(bo.getLeftHandOperand(), oldExpr)) {
                    bo.setLeftHandOperand((CtExpression) newExpr);
                } else if (Objects.equals(bo.getRightHandOperand(), oldExpr)) {
                    bo.setRightHandOperand((CtExpression) newExpr);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Determine the kind (enum/interface/class) for an owner type based on usage heuristics.
     * Used for Owner.CONSTANT patterns.
     */
    private TypeStubPlan.Kind determineOwnerTypeKind(CtTypeReference<?> ownerRef, String constantName,
                                                      CtFieldAccess<?> fieldAccess, CtModel model) {
        String ownerFqn = ownerRef.getQualifiedName();
        if (ownerFqn == null || ownerFqn.isEmpty()) {
            return TypeStubPlan.Kind.CLASS; // Default
        }

        // Check if owner already exists in model
        try {
            CtType<?> ownerType = ownerRef.getTypeDeclaration();
            if (ownerType != null) {
                // Owner exists - return its kind
                if (ownerType instanceof CtEnum) return TypeStubPlan.Kind.ENUM;
                if (ownerType instanceof CtInterface) return TypeStubPlan.Kind.INTERFACE;
                return TypeStubPlan.Kind.CLASS;
            }
        } catch (Throwable ignored) {}

        // Owner doesn't exist - use heuristics
        boolean usedInSwitch = false;
        boolean usedInEnumComparison = false;
        boolean usedInPrimitiveContext = false;

        // Check current usage context
        CtTypeReference<?> fieldType = inferFieldTypeFromUsage(fieldAccess);
        if (fieldType != null) {
            String fieldTypeQn = fieldType.getQualifiedName();
            if (fieldTypeQn != null && (fieldTypeQn.equals("int") || fieldTypeQn.equals("short") || fieldTypeQn.equals("byte"))) {
                usedInPrimitiveContext = true;
            }
        }

        // Check if used in switch or enum comparison
        CtElement parent = fieldAccess.getParent();
        if (parent != null) {
            // Check for switch case (CtSwitch exists in Spoon)
            try {
                CtElement switchParent = parent.getParent();
                while (switchParent != null) {
                    if (switchParent.getClass().getSimpleName().contains("Switch")) {
                        usedInSwitch = true;
                        break;
                    }
                    switchParent = switchParent.getParent();
                }
            } catch (Throwable ignored) {}
            
            // Check for == comparison with owner type
            if (parent instanceof CtBinaryOperator) {
                CtBinaryOperator<?> bo = (CtBinaryOperator<?>) parent;
                if (bo.getKind() == BinaryOperatorKind.EQ || bo.getKind() == BinaryOperatorKind.NE) {
                    CtExpression<?> other = Objects.equals(bo.getLeftHandOperand(), fieldAccess) 
                            ? bo.getRightHandOperand() : bo.getLeftHandOperand();
                    try {
                        CtTypeReference<?> otherType = other.getType();
                        if (otherType != null && ownerFqn.equals(otherType.getQualifiedName())) {
                            usedInEnumComparison = true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Apply heuristics
        if (usedInSwitch || usedInEnumComparison) {
            return TypeStubPlan.Kind.ENUM;
        } else if (usedInPrimitiveContext) {
            return TypeStubPlan.Kind.INTERFACE; // Interface with int constants (pre-enum pattern)
        } else {
            return TypeStubPlan.Kind.CLASS; // Default to class with static fields
        }
    }


}
