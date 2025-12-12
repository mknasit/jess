package de.upb.sse.jess.stubbing.spoon.collector;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.exceptions.AmbiguityException;
import de.upb.sse.jess.stubbing.SliceDescriptor;
import de.upb.sse.jess.stubbing.spoon.plan.*;
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
    public static final class CollectResult {
        public final List<TypeStubPlan> typePlans = new ArrayList<>();
        public final List<FieldStubPlan> fieldPlans = new ArrayList<>();
        public final List<ConstructorStubPlan> ctorPlans = new ArrayList<>();
        public final List<MethodStubPlan> methodPlans = new ArrayList<>();
        
        // Track FQNs to avoid duplicate type plans
        private final Set<String> typePlanFqns = new LinkedHashSet<>();

        // RULE 4: Track annotation attributes (annotation FQN -> attribute name -> attribute type)
        public final Map<String, Map<String, String>> annotationAttributes = new HashMap<>();

        /**
         * Add a type plan only if it hasn't been added before (by FQN).
         * Also checks if a type with the same simple name already exists in a known package,
         * and if so, skips adding the unknown package version.
         */
        public void addTypePlanIfNew(TypeStubPlan plan) {
            String fqn = plan.qualifiedName;
            if (fqn == null || typePlanFqns.contains(fqn)) {
                return; // Already added or invalid
            }

            // CRITICAL FIX: If this is an unknown package type, check if a type with the same
            // simple name already exists in a known package. If so, skip adding the unknown version.
            String unknownPackage = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
            if (fqn.startsWith(unknownPackage + ".")) {
                String simpleName = fqn.substring(unknownPackage.length() + 1);
                // Check if any existing type plan has the same simple name but in a known package
                for (String existingFqn : typePlanFqns) {
                    if (existingFqn != null && !existingFqn.startsWith(unknownPackage + ".")) {
                        String existingSimple = existingFqn.substring(existingFqn.lastIndexOf('.') + 1);
                        if (simpleName.equals(existingSimple)) {
                            // A type with the same simple name already exists in a known package
                            // Skip adding the unknown package version
                            System.out.println("[SpoonCollector] DEBUG: Skipping " + fqn + " - " + existingFqn + " already exists");
                            return;
                        }
                    }
                }
            }

                typePlanFqns.add(fqn);
                typePlans.add(plan);
        }
    }

    /* ======================================================================
     *                               FIELDS
     * ====================================================================== */

    private final Factory f;
    private final JessConfiguration cfg;
    private final SliceDescriptor descriptor;  // Path-agnostic: uses descriptor instead of paths
    
    // Legacy fields for backward compatibility (deprecated, should not be used)
    @Deprecated
    private final java.nio.file.Path slicedSrcDir;
    @Deprecated
    private final Set<String> sliceTypeFqns;

    // Centralized unknown type FQN constant. (Do not rename or remove.)
    private static final String UNKNOWN_TYPE_FQN = de.upb.sse.jess.generation.unknown.UnknownType.CLASS;
    // Centralized unknown package constant
    private static final String UNKNOWN_PACKAGE = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;

    /* ======================================================================
     *                             CONSTRUCTION
     * ====================================================================== */

    /**
     * Primary constructor: path-agnostic, uses SliceDescriptor.
     * @param f Factory
     * @param cfg Configuration
     * @param descriptor SliceDescriptor describing the slice (null means process everything)
     */
    public SpoonCollector(Factory f, JessConfiguration cfg, SliceDescriptor descriptor) {
        this.f = f;
        this.cfg = cfg;
        this.descriptor = descriptor;
        // Legacy fields for backward compatibility
        this.slicedSrcDir = null;
        this.sliceTypeFqns = descriptor != null ? descriptor.sliceTypeFqns : new HashSet<>();
    }
    
    /**
     * Legacy constructor for backward compatibility (deprecated).
     * @deprecated Use {@link #SpoonCollector(Factory, JessConfiguration, SliceDescriptor)} instead
     */
    @Deprecated
    public SpoonCollector(Factory f, JessConfiguration cfg, java.nio.file.Path slicedSrcDir, Set<String> sliceTypeFqns) {
        this.f = f;
        this.cfg = cfg;
        this.slicedSrcDir = slicedSrcDir;
        this.sliceTypeFqns = sliceTypeFqns != null ? sliceTypeFqns : new HashSet<>();
        this.descriptor = null;  // No descriptor in legacy mode
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
     * Check if an element is from the slice (should be processed).
     * PATH-AGNOSTIC: Uses SliceDescriptor instead of file paths.
     * An element is considered from the slice if:
     * 1. Its type's FQN is in the slice descriptor, OR
     * 2. No descriptor is available (process everything for backward compat)
     */
    private boolean isFromSlice(CtElement element) {
        // If no descriptor, process everything (backward compatibility or no filtering)
        if (descriptor == null) {
            // Fallback to legacy path-based check if legacy fields are set
            if (slicedSrcDir != null && sliceTypeFqns != null && !sliceTypeFqns.isEmpty()) {
                return isFromSliceLegacy(element);
            }
            return true; // No filtering info, process everything
        }

        // Path-agnostic check: use FQN from descriptor
        CtType<?> type = element.getParent(CtType.class);
        if (type != null) {
            String typeFqn = type.getQualifiedName();
            return descriptor.isSliceType(typeFqn);
        }
        
        // If element has no parent type, check if it's a type itself
        if (element instanceof CtType) {
            String typeFqn = ((CtType<?>) element).getQualifiedName();
            return descriptor.isSliceType(typeFqn);
        }
        
        // Default: if we can't determine, process it (conservative)
        return true;
    }
    
    /**
     * Legacy path-based check (deprecated, only used for backward compatibility).
     */
    @Deprecated
    private boolean isFromSliceLegacy(CtElement element) {
        if (slicedSrcDir == null || sliceTypeFqns == null || sliceTypeFqns.isEmpty()) {
            return true;
        }

        // First check: is the element's file in the slice directory?
        try {
            spoon.reflect.cu.SourcePosition pos = element.getPosition();
            if (pos != null && pos.getFile() != null) {
                java.nio.file.Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                java.nio.file.Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
                if (filePath.startsWith(sliceRoot)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // Second check: is the element's parent type (or any ancestor) a slice type?
        // This catches elements that are referenced from slice types but defined in source roots
        CtElement current = element;
        int depth = 0;
        while (current != null && depth < 10) { // Limit depth to avoid infinite loops
            CtType<?> parentType = current.getParent(CtType.class);
            if (parentType != null) {
                String qn = parentType.getQualifiedName();
                if (qn != null && sliceTypeFqns.contains(qn)) {
                    return true;
                }
                // Also check if the parent type's file is in slice
                try {
                    spoon.reflect.cu.SourcePosition typePos = parentType.getPosition();
                    if (typePos != null && typePos.getFile() != null) {
                        java.nio.file.Path typeFilePath = typePos.getFile().toPath().toAbsolutePath().normalize();
                        java.nio.file.Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
                        if (typeFilePath.startsWith(sliceRoot)) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            current = current.getParent();
            depth++;
        }

        return false; // Conservative: don't process if we can't determine
    }

    /* ======================================================================
     *                                DRIVER
     * ====================================================================== */

    /**
     * Main entry: scan the model and produce an aggregated set of stub plans.
     */
    public CollectResult collect(CtModel model) {
        CollectResult result = new CollectResult();

        // --- order matters: normalize constants BEFORE collecting types ---
        normalizeUnresolvedConstants(model);

        // --- order matters only for readability; each pass is independent ---
        collectUnresolvedFields(model, result);
        // NEW: collect bare unknown simple names (like 'g' in 'int a = util() + g;')
        collectBareUnknownSimpleNames(model, result);
        collectUnresolvedCtorCalls(model, result);
        collectUnresolvedMethodCalls(model, result);
        collectUnresolvedAnnotations(model, result);

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
        result.typePlans.addAll(ownersNeedingTypes(result));

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

                // RULE 5: Don't throw on standalone x.f; even in strict mode - just skip
                if (isStandaloneFieldStatement(fa)) {
                    continue; // Always skip standalone field statements
                }

            boolean isStatic = fa.getTarget() instanceof CtTypeAccess<?>;

            // DEBUG: Log field access details
            String fieldName = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "f");
            System.out.println("[SpoonCollector] DEBUG: Collecting field access: " + fieldName);
            if (fa.getTarget() != null) {
                System.out.println("[SpoonCollector] DEBUG: Field target type: " + fa.getTarget().getClass().getSimpleName());
                if (fa.getTarget() instanceof CtVariableRead) {
                    CtVariableRead<?> vr = (CtVariableRead<?>) fa.getTarget();
                    if (vr.getVariable() != null) {
                        try {
                            CtTypeReference<?> varType = vr.getVariable().getType();
                            System.out.println("[SpoonCollector] DEBUG: Variable type (from declaration): " + safeQN(varType));
                        } catch (Throwable e) {
                            System.out.println("[SpoonCollector] DEBUG: Could not get variable type: " + e.getMessage());
                        }
                    }
                }
                try {
                    CtTypeReference<?> targetType = fa.getTarget().getType();
                    System.out.println("[SpoonCollector] DEBUG: Target expression type: " + safeQN(targetType));
                } catch (Throwable e) {
                    System.out.println("[SpoonCollector] DEBUG: Could not get target type: " + e.getMessage());
                }
            }

            CtTypeReference<?> rawOwner = resolveOwnerTypeFromFieldAccess(fa);
            System.out.println("[SpoonCollector] DEBUG: Raw owner from resolveOwnerTypeFromFieldAccess: " + safeQN(rawOwner));

            // CRITICAL FIX: If the type is already fully qualified, check multiple sources before routing
            // Don't route it through chooseOwnerPackage which might incorrectly route it to unknown package
            String rawOwnerQn = safeQN(rawOwner);
            CtTypeReference<?> ownerRef = null;
            if (rawOwnerQn != null && rawOwnerQn.contains(".") && !rawOwnerQn.startsWith(UNKNOWN_PACKAGE + ".")) {
                // Priority 1: Check if this type is in the slice - if so, use it directly
                if (sliceTypeFqns.contains(rawOwnerQn)) {
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

                                    System.out.println("[SpoonCollector] DEBUG: Checking parameter type: " + paramTypeQn + " vs " + rawOwnerQn);

                                    // Check exact match first
                                    if (rawOwnerQn != null && paramTypeQn != null && rawOwnerQn.equals(paramTypeQn)) {
                                        System.out.println("[SpoonCollector] DEBUG: Type is a parameter type in current method (exact match), using directly: " + rawOwnerQn);
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
                                            System.out.println("[SpoonCollector] DEBUG: Type is a parameter type in current method (simple name match), using directly: " + rawOwnerQn);
                                            ownerRef = rawOwner;
                                            isParameterType = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {
                        System.out.println("[SpoonCollector] DEBUG: Exception checking parameter types: " + e.getMessage());
                        e.printStackTrace();
                    }

                    if (!isParameterType) {
                        // Priority 3: Check if it exists in model
                        try {
                            CtType<?> typeInModel = f.Type().get(rawOwnerQn);
                            if (typeInModel != null) {
                                System.out.println("[SpoonCollector] DEBUG: Type exists in model, using directly: " + rawOwnerQn);
                                ownerRef = rawOwner;
                            } else {
                                // Type doesn't exist - route through chooseOwnerPackage
                                System.out.println("[SpoonCollector] DEBUG: Type not in model, routing through chooseOwnerPackage");
                                ownerRef = chooseOwnerPackage(rawOwner, fa);
                            }
                        } catch (Throwable e) {
                            // If we can't check, route through chooseOwnerPackage
                            System.out.println("[SpoonCollector] DEBUG: Exception checking model, routing through chooseOwnerPackage: " + e.getMessage());
                            ownerRef = chooseOwnerPackage(rawOwner, fa);
                        }
                    }
                }
            } else {
                // Not fully qualified or in unknown package - route through chooseOwnerPackage
                ownerRef = chooseOwnerPackage(rawOwner, fa);
            }

            // Ensure ownerRef is initialized (fallback)
            if (ownerRef == null) {
                ownerRef = chooseOwnerPackage(rawOwner, fa);
            }
            System.out.println("[SpoonCollector] DEBUG: Final owner: " + safeQN(ownerRef));
            if (isJdkType(ownerRef)) continue;

            // Check if this is a constant-like field (Owner.CONSTANT pattern)
            boolean isConstantLike = isConstantLikeName(fieldName);
            System.out.println("[SpoonCollector] DEBUG: Field " + fieldName + " - isStatic: " + isStatic + ", isConstantLike: " + isConstantLike);
            if (isConstantLike && isStatic) {
                // This is likely Owner.CONSTANT pattern - determine owner type kind
                TypeStubPlan.Kind ownerKind = determineOwnerTypeKind(ownerRef, fieldName, fa, model);
                // Ensure owner type exists with the determined kind
                out.addTypePlanIfNew(new TypeStubPlan(ownerRef.getQualifiedName(), ownerKind));
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
                System.out.println("[SpoonCollector] DEBUG: Checking if field " + fieldName + " exists in type " + ownerFqn);

                CtType<?> ownerTypeDecl = null;
                try {
                    // Method 1: Try getTypeDeclaration() first
                    ownerTypeDecl = ownerRef.getTypeDeclaration();
                    System.out.println("[SpoonCollector] DEBUG: getTypeDeclaration() returned: " + (ownerTypeDecl != null ? ownerTypeDecl.getQualifiedName() : "null"));
                } catch (StackOverflowError | OutOfMemoryError e) {
                    System.out.println("[SpoonCollector] DEBUG: StackOverflowError in getTypeDeclaration()");
                } catch (Throwable e) {
                    System.out.println("[SpoonCollector] DEBUG: Exception in getTypeDeclaration(): " + e.getMessage());
                }

                // Method 2: If getTypeDeclaration() failed, try direct lookup by FQN
                if (ownerTypeDecl == null && ownerFqn != null && !ownerFqn.isEmpty() && !ownerFqn.startsWith(UNKNOWN_PACKAGE + ".")) {
                    try {
                        ownerTypeDecl = f.Type().get(ownerFqn);
                        System.out.println("[SpoonCollector] DEBUG: f.Type().get(" + ownerFqn + ") returned: " + (ownerTypeDecl != null ? ownerTypeDecl.getQualifiedName() : "null"));
                    } catch (StackOverflowError | OutOfMemoryError e) {
                        System.out.println("[SpoonCollector] DEBUG: StackOverflowError in f.Type().get()");
                    } catch (Throwable e) {
                        System.out.println("[SpoonCollector] DEBUG: Exception in f.Type().get(): " + e.getMessage());
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
                                    System.out.println("[SpoonCollector] DEBUG: Found type in model by searching: " + tQn);
                                    break;
                                }
                            }
                        }
                    } catch (StackOverflowError | OutOfMemoryError e) {
                        System.out.println("[SpoonCollector] DEBUG: StackOverflowError searching all types");
                    } catch (Throwable e) {
                        System.out.println("[SpoonCollector] DEBUG: Exception searching all types: " + e.getMessage());
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
                                System.out.println("[SpoonCollector] DEBUG: Field " + fieldName + " exists in model, actual type: " + actualTypeQn);
                                fieldType = actualFieldType;
                            } else {
                                System.out.println("[SpoonCollector] DEBUG: Field " + fieldName + " found but type is null");
                            }
                        } else {
                            System.out.println("[SpoonCollector] DEBUG: Field " + fieldName + " not found in type " + ownerTypeDecl.getQualifiedName());
                        }
                    } catch (StackOverflowError | OutOfMemoryError e) {
                        System.out.println("[SpoonCollector] DEBUG: StackOverflowError getting field from type");
                    } catch (Throwable e) {
                        System.out.println("[SpoonCollector] DEBUG: Exception getting field from type: " + e.getMessage());
                    }
                } else {
                    System.out.println("[SpoonCollector] DEBUG: Could not find type " + ownerFqn + " in model");
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
                    System.out.println("[SpoonCollector] DEBUG: Static constant-like field, using owner type as field type: " + safeQN(ownerRef));
                    fieldType = ownerRef;
                }
            }

            // Fallback: infer from usage context
            if (fieldType == null) {
                System.out.println("[SpoonCollector] DEBUG: Field " + fieldName + " - NOT using owner type (isStatic: " + isStatic + ", isConstantLike: " + isConstantLike + "), inferring from usage");
                fieldType = inferFieldTypeFromUsage(fa);
            }

            if (fieldType == null) {
                    if (cfg.isFailOnAmbiguity()) {
                        String ownerQN = ownerRef != null ? ownerRef.getQualifiedName() : "<unknown>";
                    String simple = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "<missing>");
                    throw new AmbiguityException("Ambiguous field (no usable type context): " + ownerQN + "#" + simple);
                    }
                    fieldType = f.Type().createReference(UNKNOWN_TYPE_FQN);
            }

            out.fieldPlans.add(new FieldStubPlan(ownerRef, fieldName, fieldType, isStatic));
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
        if (p == null) return false;

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
            return false;
        }

        try {
            if (p instanceof CtBlock && fa.getRoleInParent() == CtRole.STATEMENT) return true;
        } catch (Throwable ignored) { /* best-effort */ }

        return !(p instanceof CtExpression);
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
                        System.out.println("[SpoonCollector] DEBUG: resolveOwnerTypeFromFieldAccess - VariableRead found");
                        System.out.println("[SpoonCollector] DEBUG:   Variable name: " + (vr.getVariable().getSimpleName()));
                        System.out.println("[SpoonCollector] DEBUG:   Declared type QN (from safeQN): " + declaredQn);
                        System.out.println("[SpoonCollector] DEBUG:   Declared type simple name: " + declaredType.getSimpleName());
                        System.out.println("[SpoonCollector] DEBUG:   Declared type package: " + (declaredType.getPackage() != null ? declaredType.getPackage().getQualifiedName() : "null"));

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
                                System.out.println("[SpoonCollector] DEBUG:   Type declaration is null");
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
                        System.out.println("[SpoonCollector] DEBUG: Declared type not fully qualified, routing through chooseOwnerPackage");
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
     */
    private CtTypeReference<?> inferFieldTypeFromUsage(CtFieldAccess<?> fa) {
        CtElement parent = fa.getParent();

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

                    out.addTypePlanIfNew(new TypeStubPlan(outerFqn, TypeStubPlan.Kind.CLASS));
                    out.addTypePlanIfNew(new TypeStubPlan(outerFqn + "$" + innerSimple, TypeStubPlan.Kind.CLASS));
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
        System.out.println("[SpoonCollector] DEBUG: Found " + allInvocations.size() + " total method invocations in slice types");

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
                    System.out.println("[SpoonCollector] DEBUG: Resolved method call: " + ownerQn + "." + methodName + " (declaration exists)");
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
                    System.out.println("[SpoonCollector] DEBUG: Unresolved method call: " + ownerQn + "." + methodName + " (no declaration)");
                } catch (Throwable ignored) {}
            }
        }

        System.out.println("[SpoonCollector] DEBUG: Found " + unresolved.size() + " unresolved method invocations");

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
            if (implicitThis) {
                CtClass<?> enclosingClass = inv.getParent(CtClass.class);
                if (enclosingClass != null) {
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
            System.out.println("[SpoonCollector] DEBUG: Method has " + (inv.getArguments() != null ? inv.getArguments().size() : 0) + " arguments");
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
            System.out.println("[SpoonCollector] DEBUG: Inferred " + paramTypes.size() + " parameter types:");
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
                    System.out.println("[SpoonCollector] DEBUG: Detected side-effect-only method: " + methodSig + " → return void");
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
                            System.out.println("[SpoonCollector] DEBUG: Executable param count (" + fromRef.size() + ") != arg count (" + argCount + "), inferring from arguments");
                        }
                    }
                } else {
                    System.out.println("[SpoonCollector] DEBUG: Executable is unresolved, inferring from arguments");
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
        System.out.println("[SpoonCollector] DEBUG: Inferred " + inferred.size() + " parameter types from arguments");
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
            out.addTypePlanIfNew(new TypeStubPlan(annFqn, TypeStubPlan.Kind.ANNOTATION));

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
                    out.addTypePlanIfNew(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION));
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
                    out.addTypePlanIfNew(new TypeStubPlan(resolved.getQualifiedName(), TypeStubPlan.Kind.ANNOTATION));
                    // Also plan container (Tag -> Tags) in same pkg, as annotation
                    String pkg = resolved.getPackage() == null ? "" : resolved.getPackage().getQualifiedName();
                    String simple = resolved.getSimpleName();
                    String containerSimple = simple.endsWith("s") ? simple + "es" : simple + "s";
                    String containerFqn = (pkg.isEmpty() ? containerSimple : pkg + "." + containerSimple);
                    out.addTypePlanIfNew(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION));
                    continue;
                }
            }

            // Otherwise, keep whatever we have if it’s non-JDK
            if (!isJdkType(at)) {
                out.addTypePlanIfNew(new TypeStubPlan((qn.isEmpty() ? UNKNOWN_PACKAGE + "." + at.getSimpleName() : qn),
                        TypeStubPlan.Kind.ANNOTATION));
                // Plan container in same package
                String pkg = at.getPackage() == null ? "" : at.getPackage().getQualifiedName();
                String simple = at.getSimpleName();
                String containerSimple = simple.endsWith("s") ? simple + "es" : simple + "s";
                String containerFqn = (pkg.isEmpty() ? containerSimple : pkg + "." + containerSimple);
                out.typePlans.add(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION));
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
                out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                    List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(owner, ps));
                }
            } else if (ex != null) {
                try {
                    CtTypeReference<?> t = ex.getType();
                    if (t != null && !isJdkType(t) && t.getDeclaration() == null) {
                        CtTypeReference<?> owner = chooseOwnerPackage(t, thr);
                        out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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

        // Check if type declaration exists in model (from any source - slice or context)
        // If it exists in slice, it will be pretty-printed, so we don't need a stub
        // If it exists only in context, we still need a stub (context types won't be pretty-printed)
        try { 
            CtType<?> decl = t.getDeclaration();
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
            out.addTypePlanIfNew(new TypeStubPlan(resolvedFqn, TypeStubPlan.Kind.CLASS));
                return;
            }

       // out.typePlans.add(new TypeStubPlan(qn, TypeStubPlan.Kind.CLASS));
        String nestedFqn = nestedAwareFqnOf(t);
        out.addTypePlanIfNew(new TypeStubPlan(nestedFqn, TypeStubPlan.Kind.CLASS));

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
            // System.out.println("[SpoonCollector] DEBUG: getElementsFromSliceTypes - Found " + sliceTypes.size() + " slice types");
            
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
                        
                        if (isLikelyLargeGeneratedClass) {
                            // For very large protobuf classes, skip detailed element traversal
                            // These classes are typically not part of the actual slice logic
                            System.out.println("[SpoonCollector] DEBUG: Skipping detailed traversal of large generated class: " + typeName);
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
                    // System.out.println("[SpoonCollector] DEBUG: Type " + typeName + " has " + allElementsCount.get() + " total elements, matched " + matchingElementsCount.get() + " with predicate");
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
            // System.out.println("[SpoonCollector] DEBUG: getElementsFromSliceTypes returning " + result.size() + " elements");
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
     * Uses direct FQN lookup when possible to avoid getAllTypes().
     */
    private Collection<CtType<?>> getSliceTypes(CtModel model) {
        List<CtType<?>> sliceTypes = new ArrayList<>();
        if (slicedSrcDir == null || sliceTypeFqns == null || sliceTypeFqns.isEmpty()) {
            // No filtering - return all types (backward compat)
            try {
                return safeGetAllTypes(model);
            } catch (Throwable e) {
                return Collections.emptyList();
            }
        }
        
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
        
        // Fallback: check file paths (only if FQN lookup didn't find all types)
        Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
        try {
            Collection<CtType<?>> allTypes = safeGetAllTypes(model);
            for (CtType<?> type : allTypes) {
                // Skip if already found via FQN lookup
                String qn = type.getQualifiedName();
                if (qn != null && sliceTypeFqns.contains(qn)) {
                    continue; // Already added
                }
                
                if (isFromSlice(type)) {
                    sliceTypes.add(type);
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
     * This handles cases where unknown.PushMessage is added before cn.wildfirechat.push.PushMessage.
     */
    private void removeDuplicateUnknownPackageTypes(CollectResult result) {
        String unknownPackage = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
        List<TypeStubPlan> toRemove = new ArrayList<>();
        
        // Build a map of simple names to known package FQNs
        Map<String, String> knownPackageTypes = new HashMap<>();
        for (TypeStubPlan plan : result.typePlans) {
            String fqn = plan.qualifiedName;
            if (fqn != null && !fqn.startsWith(unknownPackage + ".")) {
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                knownPackageTypes.put(simple, fqn);
            }
        }
        
        // Find unknown package types that have known equivalents
        for (TypeStubPlan plan : result.typePlans) {
            String fqn = plan.qualifiedName;
            if (fqn != null && fqn.startsWith(unknownPackage + ".")) {
                String simple = fqn.substring(unknownPackage.length() + 1);
                if (knownPackageTypes.containsKey(simple)) {
                    String knownFqn = knownPackageTypes.get(simple);
                    System.out.println("[SpoonCollector] DEBUG: Removing duplicate " + fqn + " - " + knownFqn + " already exists");
                    toRemove.add(plan);
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
                System.out.println("[SpoonCollector] DEBUG: CU has no imports");
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

                    out.addTypePlanIfNew(new TypeStubPlan(ownerFqn, TypeStubPlan.Kind.CLASS));
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
                            out.addTypePlanIfNew(new TypeStubPlan(ownerFqn, TypeStubPlan.Kind.CLASS));
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
                out.addTypePlanIfNew(new TypeStubPlan(pkg + ".PackageAnchor", TypeStubPlan.Kind.CLASS));
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
                out.addTypePlanIfNew(new TypeStubPlan(fqn, TypeStubPlan.Kind.CLASS));
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
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                }
            }
            for (CtTypeReference<?> si : safe(c.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, c);
                if (owner != null && !isJdkType(owner)) {
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.INTERFACE));
                }
            }
        }

        // interfaces: superinterfaces
        for (CtInterface<?> i : getElementsFromSliceTypes(model, (CtInterface<?> ii) -> true)) {
            for (CtTypeReference<?> si : safe(i.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, i);
                if (owner != null && !isJdkType(owner)) {
                    out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.INTERFACE));
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
                        out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                        out.addTypePlanIfNew(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
            System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - arg is null");
            return f.Type().createReference(UNKNOWN_TYPE_FQN);
        }
        
        System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - arg type: " + arg.getClass().getSimpleName());

        // If argument is a field access that can't be resolved, don't treat it as a type
        // This prevents constants like PUSH_ANDROID_SERVER_ADDRESS from being stubbed as types
        if (arg instanceof CtFieldAccess) {
            CtFieldAccess<?> fa = (CtFieldAccess<?>) arg;
            String fieldName = fa.getVariable() != null ? fa.getVariable().getSimpleName() : "unknown";
            System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Field access: " + fieldName);
            boolean isUnresolved = false;
            try {
                isUnresolved = (fa.getVariable() == null || fa.getVariable().getDeclaration() == null);
            } catch (StackOverflowError | OutOfMemoryError e) {
                isUnresolved = true;
                } catch (Throwable ignored) {}
            
            if (isUnresolved) {
                System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Field is unresolved");
                
                // CRITICAL FIX: For static field accesses like EventType.PUSH_SERVER_Exception,
                // check if it's a static field access FIRST before calling getType()
                // getType() on static field accesses can return wrong types (like the enclosing class)
                if (fa.getTarget() instanceof CtTypeAccess) {
                    // Static field access - use the owner type, not getType()
                    System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Detected static field access, using owner type");
                    // Static field access - try to infer type from the owner type
                    CtTypeAccess<?> target = (CtTypeAccess<?>) fa.getTarget();
                    CtTypeReference<?> ownerType = target.getAccessedType();
                    String ownerQn = safeQN(ownerType);
                    System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Static field access: " + ownerQn + "." + fieldName);
                    
                    if (ownerType != null) {
                        // Resolve the owner type to its fully qualified name using chooseOwnerPackage
                        // This handles imports like "import IMExceptionEvent.EventType;" -> EventType resolves to IMExceptionEvent.EventType
                        CtTypeReference<?> resolvedOwner = chooseOwnerPackage(ownerType, fa);
                        String resolvedOwnerQn = safeQN(resolvedOwner);
                        System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Resolved owner: " + resolvedOwnerQn);
                        
                        // For static field accesses, return the owner type (the type of the field is the owner type)
                        // This handles both enum constants (EventType.PUSH_SERVER_Exception -> EventType)
                        // and static fields (SomeClass.SOME_FIELD -> SomeClass)
                        if (resolvedOwnerQn != null && !resolvedOwnerQn.equals(UNKNOWN_TYPE_FQN)) {
                            System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Static field access, returning owner type: " + resolvedOwnerQn);
                            return resolvedOwner;
                        }
                        // If resolved to unknown, still return it (better than Unknown)
                        System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Owner resolved to unknown, but returning it anyway: " + resolvedOwnerQn);
                        return resolvedOwner;
                    }
                }
                
                // If we can't infer, return String for constants (common case)
                if (fieldName != null && fieldName.equals(fieldName.toUpperCase()) && fieldName.length() > 1) {
                    // Likely a constant - return String type (most constants are String)
                    System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Returning String for constant-like field");
                    return f.Type().createReference("java.lang.String");
                }
                System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Returning Unknown");
                return f.Type().createReference(UNKNOWN_TYPE_FQN);
            } else {
                System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Field is resolved, using getType()");
                try {
                    CtTypeReference<?> t = fa.getType();
                    System.out.println("[SpoonCollector] DEBUG: paramTypeOrObject - Resolved field type: " + safeQN(t));
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
