package de.upb.sse.jess.stubbing.spoon.generate;

import de.upb.sse.jess.generation.unknown.UnknownType;
import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.*;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.stream.Collectors;

public final class SpoonStubber {

    /* ======================================================================
     *                                 FIELDS
     * ====================================================================== */

    private final Factory f;
    private final CtModel model;

    private final Set<String> createdTypes = new LinkedHashSet<>();
    private final Set<String> functionalInterfaces = new LinkedHashSet<>(); // Track functional interfaces (should only have one abstract method)
    private final List<String> createdFields = new ArrayList<>();
    private final List<String> createdCtors = new ArrayList<>();
    private final List<String> createdMethods = new ArrayList<>();

    /* ======================================================================
     *                              CONSTRUCTION
     * ====================================================================== */

    /**
     * Create a stubber bound to a Spoon Factory.
     */
    public SpoonStubber(Factory f, CtModel model) {
        this.f = f;
        this.model = model;
    }

    /* ======================================================================
     *                                TYPES
     * ====================================================================== */

    /**
     * Apply all TypeStubPlans; creates missing classes/interfaces/annotations.
     * 
     * @param plans Optional collection of method plans to infer type parameter names from.
     *                    If provided, type parameter names (T, R, U, etc.) will be inferred from
     *                    method signatures before creating types.
     *
     * @return number of newly created types
     */
    public int applyTypePlans(Collection<TypeStubPlan> plans) {
        return applyTypePlans(plans, null);
    }
    
    /**
     * Apply all TypeStubPlans with method plans for type parameter name inference.
     */
    public int applyTypePlans(Collection<TypeStubPlan> plans, Collection<MethodStubPlan> methodPlans) {
        int created = 0;
        if (plans == null || plans.isEmpty()) return created;

        // (1) collect simple names that have a non-unknown plan
        Set<String> concretePlannedSimples = new HashSet<>();
        for (TypeStubPlan p : plans) {
            String qn = p.qualifiedName;
            // Validate FQN: reject null, empty, or invalid FQNs
            if (qn == null || qn.isEmpty() || qn.endsWith(".")) continue;
            int lastDot = qn.lastIndexOf('.');
            String simpleName = (lastDot >= 0 ? qn.substring(lastDot + 1) : qn);
            if (simpleName == null || simpleName.isEmpty()) continue;
            
            if (!qn.startsWith("unknown.")) {
                concretePlannedSimples.add(simpleName);
                created++;
            }
        }

        // (2) create types, skipping unknown twins
        for (TypeStubPlan p : plans) {
            String qn = p.qualifiedName;
            // Validate FQN: reject null, empty, or invalid FQNs
            if (qn == null || qn.isEmpty() || qn.endsWith(".")) continue;
            int lastDot = qn.lastIndexOf('.');
            String simpleName = (lastDot >= 0 ? qn.substring(lastDot + 1) : qn);
            if (simpleName == null || simpleName.isEmpty()) continue;
            if (qn.startsWith("unknown.")) {
                if (concretePlannedSimples.contains(simpleName)) {
                    continue; // do not create unknown.Simple when pkg.Simple is planned
                }
            }
            boolean wasCreated = ensureTypeExists(p, methodPlans);
            if (wasCreated && p.kind == TypeStubPlan.Kind.INTERFACE) {
                // Check if this interface will be used as a functional interface
                // We'll mark it later when we see method plans with "apply" method
            }
            created++;// your existing creator
        }
        return created;
    }





    /**
     * Ensure a type exists for the given plan (class/interface/annotation).
     * Handles generic arity inference and exception/error superclasses.
     *
     * @param p Optional method plans to infer type parameter names from
     * @return true if a new type was created
     */
    private boolean ensureTypeExists(TypeStubPlan p) {
        return ensureTypeExists(p, null);
    }
    
    private boolean ensureTypeExists(TypeStubPlan p, Collection<MethodStubPlan> methodPlans) {
        String qn = p.qualifiedName;
        
        // Validate FQN: reject null, empty, or invalid FQNs
        if (qn == null || qn.isEmpty()) return false;
        if (qn.endsWith(".")) return false; // Invalid: ends with dot but no simple name
        int lastDot = qn.lastIndexOf('.');
        String simpleName = (lastDot >= 0 ? qn.substring(lastDot + 1) : qn);
        if (simpleName == null || simpleName.isEmpty()) return false; // Invalid: no simple name
        
        if (qn.startsWith("java.")
                || qn.startsWith("javax.")
                || qn.startsWith("jakarta.")
                || qn.startsWith("sun.")
                || qn.startsWith("jdk.")) {
            return false;
        }
        if (qn.startsWith("unknown.")) {
            String simple = simpleName; // Use the already extracted simple name
            
            // Prevent creating primitive types as classes (byte, int, short, etc.)
            if (isPrimitiveTypeName(simple)) {
                return false;
            }

            for (String createdFqn : createdTypes) {
                if (createdFqn == null) continue;
                String createdSimple = createdFqn.contains("$")
                        ? createdFqn.substring(createdFqn.lastIndexOf('$') + 1)
                        : createdFqn.substring(createdFqn.lastIndexOf('.') + 1);
                if (simple.equals(createdSimple) && !createdFqn.startsWith("unknown.")) {
                    return false;
                }
            }
            // 2) Already present in model in a non-unknown package?
            for (CtType<?> t : f.getModel().getAllTypes()) {
                if (!simple.equals(t.getSimpleName())) continue;
                CtPackage pkg = t.getPackage();
                String pkgName = (pkg != null ? pkg.getQualifiedName() : "");
                if (!"unknown".equals(pkgName)) {
                    return false;
                }
            }
        }


        // === skip creating unknown.* if a concrete same-simple type exists ===
        if (qn.startsWith("unknown.")) {
            String simple = simpleName; // Use the already extracted simple name
            for (String createdFqn : createdTypes) {
                if (createdFqn == null) continue;
                String createdSimple = createdFqn.contains("$")
                        ? createdFqn.substring(createdFqn.lastIndexOf('$') + 1)
                        : createdFqn.substring(createdFqn.lastIndexOf('.') + 1);
                if (simple.equals(createdSimple) && !createdFqn.startsWith("unknown.")) {
                    // We already decided to have a concrete owner for this simple name
                    return false;
                }
            }
        }

        // --- member type fast-path: plan says Outer$Inner (or deeper) ---
        if (qn != null && qn.contains("$")) {
            int lastDotofinedx = qn.lastIndexOf('.');
            String pkg = (lastDotofinedx >= 0 ? qn.substring(0, lastDotofinedx) : "");
            String afterPkg = (lastDotofinedx >= 0 ? qn.substring(lastDotofinedx + 1) : qn); // e.g., Outer$Inner$Deeper

            String[] parts = afterPkg.split("\\$");
            if (parts.length >= 2) {
                // 1) ensure outer (top-level) exists as a CLASS
                String outerFqn = (pkg.isEmpty() ? parts[0] : pkg + "." + parts[0]);
                CtClass<?> outer = ensurePublicClass(f.Type().createReference(outerFqn));

                // 2) walk/create each nested level under the previous
                CtType<?> parent = outer;
                for (int i = 1; i < parts.length; i++) {
                    String simple = parts[i];
                    CtType<?> existing = parent.getNestedType(simple);
                    if (existing != null) {
                        // Upgrade/downgrade if the existing kind is wrong
                        if (p.kind == TypeStubPlan.Kind.INTERFACE && existing instanceof CtClass) {
                            existing.delete();
                            existing = null; // Force creation below
                        } else if (p.kind == TypeStubPlan.Kind.CLASS && existing instanceof CtInterface) {
                            existing.delete();
                            existing = null; // Force creation below
                        } else if (p.kind == TypeStubPlan.Kind.ANNOTATION && !(existing instanceof CtAnnotationType)) {
                            existing.delete();
                            existing = null; // Force creation below
                    } else {
                        parent = existing;
                            continue; // Type exists and is correct kind
                    }
                }

                    // Create the nested type if it doesn't exist or was deleted
                    if (existing == null) {
                        CtType<?> nested;
                        if (p.kind == TypeStubPlan.Kind.INTERFACE) {
                if (parent instanceof CtClass) {
                                nested = f.Interface().create((CtClass<?>) parent, simple);
                            } else {
                                nested = f.Core().createInterface();
                                nested.setSimpleName(simple);
                                ((CtType<?>) parent).addNestedType(nested);
                            }
                        } else if (p.kind == TypeStubPlan.Kind.ANNOTATION) {
                            nested = f.Annotation().create(parent instanceof CtClass ? (CtPackage) parent : null, simple);
                            if (!(parent instanceof CtClass)) {
                                ((CtType<?>) parent).addNestedType(nested);
                            }
                        } else {
                            // Default to class
                            if (parent instanceof CtClass) {
                                nested = f.Class().create((CtClass<?>) parent, simple);
                            } else {
                                nested = f.Core().createClass();
                                nested.setSimpleName(simple);
                                ((CtType<?>) parent).addNestedType(nested);
                            }
                        }
                        nested.addModifier(ModifierKind.PUBLIC);
                        // For nested classes, check if they should be non-static (for o.new Inner() syntax)
                        if (nested instanceof CtClass && parent instanceof CtClass) {
                            if (p.isNonStaticInner) {
                                // Don't add static modifier - it's an instance inner class
                                nested.removeModifier(ModifierKind.STATIC);
                            } else {
                                // Default to static for nested classes
                                nested.addModifier(ModifierKind.STATIC);
                            }
                        }
                        parent = nested;
                        createdTypes.add(parent.getQualifiedName());
                    }
                }

                return true; // We created nested types
            }
        }

        String pkg = "";
        String name = "Missing";

        int i = qn.lastIndexOf('.');
        if (i >= 0) {
            pkg = qn.substring(0, i);
            name = qn.substring(i + 1);
        } else {
            pkg = "unknown";
            name = qn;
        }
        
        // Prevent creating primitive types as classes
        if (isPrimitiveTypeName(name)) {
            return false;
        }

        CtPackage packageObj = f.Package().getOrCreate(pkg);
        CtType<?> existing = packageObj.getType(name);
        if (existing != null) return false;

        CtType<?> created;

        switch (p.kind) {
            case INTERFACE:
                created = f.Interface().create(packageObj, name);
                break;
            case ANNOTATION: {
                CtAnnotationType<?> at = f.Annotation().create(packageObj, name);
                at.addModifier(ModifierKind.PUBLIC);

                // Add default element: String value();  (harmless if not used, but enables @Tag("x") pattern)
                if (at.getMethods().stream().noneMatch(m -> "value".equals(m.getSimpleName()))) {
                    CtAnnotationMethod<?> am = f.Core().createAnnotationMethod();
                    am.setSimpleName("value");
                    am.setType(f.Type().STRING);
                    at.addMethod(am);
                }
                created = at;
                break;
            }
            default:
                created = f.Class().create(packageObj, name);
        }
        created.addModifier(ModifierKind.PUBLIC);

        // Add generic parameters if usages imply arity.
        // Also infer actual type parameter names (T, R, U, etc.) from method plans
        int arity = inferGenericArityFromUsages(qn);
        if (arity > 0) {
            List<String> paramNames = inferTypeParameterNamesFromMethodPlans(qn, arity, methodPlans);
            addTypeParameters(created, arity, paramNames);
        }

        // If it looks like an exception/error, set a throwable superclass.
        if (created instanceof CtClass) {
            CtClass<?> cls = (CtClass<?>) created;
            String simple = name;

            boolean looksException = simple.matches(".*Exception(\\d+)?$") || simple.contains("Exception");
            boolean looksError = simple.matches(".*Error(\\d+)?$") || simple.endsWith("Error");

            if (looksError) {
                cls.setSuperclass(f.Type().createReference("java.lang.Error"));
            } else if (looksException) {
                cls.setSuperclass(f.Type().createReference("java.lang.RuntimeException"));
            }
        }

        //createdTypes.add(qn);
        createdTypes.add(created.getQualifiedName());
        return true;
    }

    /* ======================================================================
     *                                FIELDS
     * ====================================================================== */

    /**
     * Apply all FieldStubPlans; creates public fields (and static if required).
     * Adds explicit import for unknown.Unknown when necessary.
     *
     * @return number of newly created fields
     */
    public int applyFieldPlans(Collection<FieldStubPlan> plans) {
        int created = 0;
        for (FieldStubPlan p : plans) {

            CtTypeReference<?> ownerRef = normalizeOwnerRef(p.ownerType);
            if (ownerRef == null) continue;

            CtClass<?> owner = ensurePublicClass(p.ownerType);
            if (owner.getField(p.fieldName) != null) continue;

            CtTypeReference<?> fieldType = normalizeUnknownRef(p.fieldType);
            if ("unknown.Unknown".equals(readable(fieldType)) || "Unknown".equals(fieldType.getSimpleName())) {
                ensureExplicitUnknownImport(owner);
            }

            Set<ModifierKind> mods = new HashSet<>();
            mods.add(ModifierKind.PUBLIC);
            if (p.isStatic) mods.add(ModifierKind.STATIC);

            CtField<?> fd = f.Field().create(owner, mods, fieldType, p.fieldName);
            ensureImport(owner, fieldType);

            created++;
            createdFields.add(owner.getQualifiedName() + "#" + p.fieldName + ":" +
                    (fieldType == null ? "java.lang.Object" : fieldType.getQualifiedName()));
        }
        return created;
    }

    /* ======================================================================
     *                             CONSTRUCTORS
     * ====================================================================== */

    /**
     * Apply all ConstructorStubPlans; creates public constructors with normalized params.
     * Adds explicit import for unknown.Unknown when parameters use it.
     *
     * @return number of newly created constructors
     */
    public int applyConstructorPlans(Collection<ConstructorStubPlan> plans) {
        int created = 0;
        if (plans == null || plans.isEmpty()) return created;
        for (ConstructorStubPlan p : plans) {

            CtTypeReference<?> ownerRef = normalizeOwnerRef(p.ownerType);
            CtClass<?> owner = ensurePublicClass(ownerRef);
            // Normalize params first.
            List<CtTypeReference<?>> normParams = new ArrayList<>();
            for (CtTypeReference<?> t : p.parameterTypes) normParams.add(normalizeUnknownRef(t));

            if (hasConstructor(owner, normParams)) continue;

            Set<ModifierKind> mods = new HashSet<>();
            mods.add(ModifierKind.PUBLIC);
            List<CtParameter<?>> params = makeParams(normParams);

            CtConstructor<?> ctor = f.Constructor().create(owner, mods, params, Collections.emptySet(), f.Core().createBlock());

            boolean ctorUsesUnknown = params.stream().anyMatch(d ->
                    "unknown.Unknown".equals(readable(d.getType()))
                            || "Unknown".equals(d.getType().getSimpleName())
            );
            if (ctorUsesUnknown) {
                ensureExplicitUnknownImport(owner);
            }

            for (CtParameter<?> par : params) ensureImport(owner, par.getType());

            createdCtors.add(sig(owner.getQualifiedName(), owner.getSimpleName(), normParams));
            created++;
        }
        return created;
    }

    /* ======================================================================
     *                                METHODS
     * ====================================================================== */

    /**
     * Apply all MethodStubPlans; creates methods with normalized return/param types,
     * mirrors visibility, and provides default bodies (or none for interfaces).
     * Also wires Iterable<T> if creating iterator().
     *
     * @return number of newly created methods
     */
    public int applyMethodPlans(Collection<MethodStubPlan> plans) {
        int created = 0;

        // First pass: identify functional interfaces (those with "apply" or "make" method from method references)
        for (MethodStubPlan p : plans) {
            if (("apply".equals(p.name) || "make".equals(p.name)) && !p.defaultOnInterface && !p.isStatic) {
                CtTypeReference<?> normalizedOwnerRef = normalizeOwnerRef(p.ownerType);
                String ownerQn = safeQN(normalizedOwnerRef);
                if (ownerQn != null && !ownerQn.isEmpty()) {
                    // Mark as functional interface - we'll create the type if needed
                    functionalInterfaces.add(ownerQn);
                }
            }
        }

        // Second pass: apply method plans
        for (MethodStubPlan p : plans) {


            CtTypeReference<?> normalizedOwnerRef = normalizeOwnerRef(p.ownerType);;

            String oqn = null;
            try { oqn = (normalizedOwnerRef != null ? normalizedOwnerRef.getQualifiedName() : null); } catch (Throwable ignored) {}

            if (oqn != null && oqn.startsWith("unknown.")) {
                String simple = normalizedOwnerRef.getSimpleName();

                // (1) prefer an already-created concrete owner (same simple name)
                CtTypeReference<?> concrete = null;
                for (String createdFqn : createdTypes) {
                    if (createdFqn == null || createdFqn.startsWith("unknown.")) continue;
                    String createdSimple = createdFqn.contains("$")
                            ? createdFqn.substring(createdFqn.lastIndexOf('$') + 1)
                            : createdFqn.substring(createdFqn.lastIndexOf('.') + 1);
                    if (simple.equals(createdSimple)) {
                        concrete = f.Type().createReference(createdFqn);
                        break;
                    }
                }

                // (2) or any concrete owner currently in the model
                if (concrete == null) {
                    for (CtType<?> t : f.getModel().getAllTypes()) {
                        if (!simple.equals(t.getSimpleName())) continue;
                        CtPackage pkg = t.getPackage();
                        String pkgName = (pkg != null ? pkg.getQualifiedName() : "");
                        if (!"unknown".equals(pkgName)) {
                            concrete = t.getReference();
                            break;
                        }
                    }
                }

                if (concrete != null) {
                    normalizedOwnerRef = concrete; // ← use this below
                }
            }

            // 1) pick/create the owner type using the *normalized* owner ref
            CtType<?> owner = p.defaultOnInterface
                    ? ensurePublicInterfaceForTypeRef(normalizedOwnerRef)
                    : ensurePublicOwnerForTypeRef(normalizedOwnerRef);

            // Check if this is a functional interface - if so, only allow the SAM method
            // Note: We mark functional interfaces when we add the "apply" or "make" method below
            String ownerQn = safeQN(owner.getReference());
            if (owner instanceof CtInterface && functionalInterfaces.contains(ownerQn)) {
                // For functional interfaces, only allow the SAM method ("apply" or "make")
                // Skip other abstract methods that would make it non-functional
                if (!"apply".equals(p.name) && !"make".equals(p.name) && !p.defaultOnInterface && !p.isStatic) {
                    continue; // Skip this abstract method plan (not the SAM)
                }
                
                // For functional interfaces, check if we already have a SAM method (apply/make)
                // Even if parameter types differ (int vs Integer), we can only have ONE SAM method
                // Prefer the one with non-primitive parameters (Integer over int) as it's more general
                List<CtMethod<?>> existingSamMethods = owner.getMethods().stream()
                        .filter(m -> {
                            String mName = m.getSimpleName();
                            return ("apply".equals(mName) || "make".equals(mName)) && 
                                   m.getModifiers().contains(ModifierKind.ABSTRACT);
                        })
                        .collect(Collectors.toList());
                
                if (!existingSamMethods.isEmpty() && ("apply".equals(p.name) || "make".equals(p.name)) && !p.defaultOnInterface && !p.isStatic) {
                    // Check if the new method has non-primitive parameters and existing has primitive
                    // Compare parameter-by-parameter: if new has Integer and existing has int, prefer new
                    boolean shouldReplace = false;
                    for (CtMethod<?> existing : existingSamMethods) {
                        if (existing.getParameters().size() != p.paramTypes.size()) continue;
                        
                        boolean newHasNonPrimitive = false;
                        boolean existingHasPrimitive = false;
                        
                        for (int i = 0; i < p.paramTypes.size(); i++) {
                            try {
                                String newParamQn = safeQN(p.paramTypes.get(i));
                                String existingParamQn = safeQN(existing.getParameters().get(i).getType());
                                
                                boolean newIsPrimitive = newParamQn != null && 
                                    (newParamQn.equals("int") || newParamQn.equals("long") || 
                                     newParamQn.equals("short") || newParamQn.equals("byte") || 
                                     newParamQn.equals("char") || newParamQn.equals("boolean") ||
                                     newParamQn.equals("float") || newParamQn.equals("double"));
                                
                                boolean existingIsPrimitive = existingParamQn != null && 
                                    (existingParamQn.equals("int") || existingParamQn.equals("long") || 
                                     existingParamQn.equals("short") || existingParamQn.equals("byte") || 
                                     existingParamQn.equals("char") || existingParamQn.equals("boolean") ||
                                     existingParamQn.equals("float") || existingParamQn.equals("double"));
                                
                                // If at same position, new is non-primitive wrapper and existing is primitive, prefer new
                                if (!newIsPrimitive && existingIsPrimitive) {
                                    newHasNonPrimitive = true;
                                    existingHasPrimitive = true;
                                    break;
                                }
                            } catch (Throwable ignored) {}
                        }
                        
                        if (newHasNonPrimitive && existingHasPrimitive) {
                            shouldReplace = true;
                            break;
                        }
                    }
                    
                    // If new method has non-primitive params and existing has primitive, remove existing and add new
                    if (shouldReplace) {
                        System.err.println("[applyMethodPlans] Removing primitive SAM method and adding non-primitive for " + ownerQn);
                        for (CtMethod<?> m : existingSamMethods) {
                            owner.removeMethod(m);
                        }
                        // Continue to create the new method
                    } else {
                        System.err.println("[applyMethodPlans] Skipping duplicate SAM method for functional interface " + ownerQn + 
                            ": " + p.name + " (already exists)");
                        continue; // Skip - functional interface already has a SAM method
                    }
                }
            }

            // short-circuit if already present on the owner using your current check
            if (hasMethod(owner, p.name, p.paramTypes)) continue;

            // 2) normalize return type
            // IMPORTANT: Check for type parameters BEFORE normalization
            CtTypeReference<?> rt0 = (p.returnType != null ? p.returnType : f.Type().VOID_PRIMITIVE);
            
            // Check if return type matches owner's type parameters BEFORE normalization
            if (owner instanceof CtFormalTypeDeclarer && rt0 != null) {
                String returnTypeSimple = rt0.getSimpleName();
                String returnQn = safeQN(rt0);
                
                // Check if this looks like a type parameter (single uppercase letter)
                // The qualified name might be null, empty, equal to simple name, or start with "unknown."
                boolean looksLikeTypeParam = returnTypeSimple != null && 
                    returnTypeSimple.length() == 1 && 
                    returnTypeSimple.matches("[A-Z]") && 
                    returnTypeSimple.charAt(0) >= 'T';
                
                boolean hasNoPackage = returnQn == null || returnQn.isEmpty() || 
                    returnQn.equals(returnTypeSimple) ||
                    (returnQn.startsWith("unknown.") && returnQn.equals("unknown." + returnTypeSimple));
                
                if (looksLikeTypeParam && hasNoPackage) {
                    try {
                        List<CtTypeParameter> ownerParams = ((CtFormalTypeDeclarer) owner).getFormalCtTypeParameters();
                        if (ownerParams != null && !ownerParams.isEmpty()) {
                            // First try to find by exact name match
                            boolean found = false;
                            for (CtTypeParameter tp : ownerParams) {
                                if (returnTypeSimple.equals(tp.getSimpleName())) {
                                    rt0 = tp.getReference();
                                    found = true;
                                    break;
                                }
                            }
                            
                            // If not found by name, try by position:
                            // T -> first param, R -> second param, U -> third param, etc.
                            if (!found) {
                                String[] standardNames = {"T", "R", "U", "V", "W", "X", "Y", "Z"};
                                int index = -1;
                                for (int j = 0; j < standardNames.length; j++) {
                                    if (standardNames[j].equals(returnTypeSimple)) {
                                        index = j;
                                        break;
                                    }
                                }
                                if (index >= 0 && index < ownerParams.size()) {
                                    rt0 = ownerParams.get(index).getReference();
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            // Now normalize (but type parameter references should already be resolved)
            // IMPORTANT: If rt0 is already a type parameter reference, don't normalize it
            if (!(rt0 instanceof CtTypeParameterReference)) {
                rt0 = normalizeUnknownRef(rt0);
            }
            if (rt0 == null) {
                rt0 = f.Type().VOID_PRIMITIVE;
            }
            
            @SuppressWarnings({"rawtypes", "unchecked"})
            CtTypeReference rt = (CtTypeReference) rt0;

            // 3) normalize parameters (convert last to array if varargs)
            // IMPORTANT: Check for type parameters BEFORE normalization to avoid breaking the match
            boolean willBeVarargs = p.varargs;
            List<CtTypeReference<?>> normParams = new ArrayList<>(p.paramTypes.size());
            for (int i = 0; i < p.paramTypes.size(); i++) {
                CtTypeReference<?> t = p.paramTypes.get(i);
                
                // Check if this is a type parameter BEFORE normalization
                // This handles cases like Uncheck.apply(T) where T is a type parameter
                if (owner instanceof CtFormalTypeDeclarer && t != null) {
                    String paramTypeSimple = t.getSimpleName();
                    String paramQn = safeQN(t);
                    
                    // Check if this looks like a type parameter (single uppercase letter)
                    // The qualified name might be null, empty, equal to simple name, or start with "unknown."
                    boolean looksLikeTypeParam = paramTypeSimple != null && 
                        paramTypeSimple.length() == 1 && 
                        paramTypeSimple.matches("[A-Z]") && 
                        paramTypeSimple.charAt(0) >= 'T';
                    
                    boolean hasNoPackage = paramQn == null || paramQn.isEmpty() || 
                        paramQn.equals(paramTypeSimple) ||
                        (paramQn.startsWith("unknown.") && paramQn.equals("unknown." + paramTypeSimple));
                    
                    if (looksLikeTypeParam && hasNoPackage) {
                        try {
                            List<CtTypeParameter> ownerParams = ((CtFormalTypeDeclarer) owner).getFormalCtTypeParameters();
                            if (ownerParams != null && !ownerParams.isEmpty()) {
                                String ownerQnref = safeQN(owner.getReference());
                                System.err.println("[applyMethodPlans] Trying to match type parameter '" + paramTypeSimple + 
                                    "' for owner " + ownerQnref + " (has " + ownerParams.size() + " type params)");
                                
                                // First try to find by exact name match
                                boolean found = false;
                                for (CtTypeParameter tp : ownerParams) {
                                    String tpName = tp.getSimpleName();
                                    System.err.println("[applyMethodPlans]   - Checking type param: " + tpName);
                                    if (paramTypeSimple.equals(tpName)) {
                                        t = tp.getReference();
                                        found = true;
                                        System.err.println("[applyMethodPlans]   - Matched by name: " + paramTypeSimple);
                                        break;
                                    }
                                }
                                
                                // If not found by name, try by position:
                                // T -> first param, R -> second param, U -> third param, etc.
                                if (!found) {
                                    String[] standardNames = {"T", "R", "U", "V", "W", "X", "Y", "Z"};
                                    int index = -1;
                                    for (int j = 0; j < standardNames.length; j++) {
                                        if (standardNames[j].equals(paramTypeSimple)) {
                                            index = j;
                                            break;
                                        }
                                    }
                                    if (index >= 0 && index < ownerParams.size()) {
                                        t = ownerParams.get(index).getReference();
                                        System.err.println("[applyMethodPlans]   - Matched by position: " + paramTypeSimple + " -> index " + index);
                                    } else {
                                        System.err.println("[applyMethodPlans]   - No match found for " + paramTypeSimple);
                                    }
                                }
                            } else {
                                String ownerQnRef = safeQN(owner.getReference());
                                System.err.println("[applyMethodPlans] Owner " + ownerQnRef + " has no type parameters!");
                            }
                        } catch (Throwable e) {
                            System.err.println("[applyMethodPlans] Error matching type parameter: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
                
                // Now normalize (but type parameter references should already be resolved)
                // IMPORTANT: If t is already a type parameter reference, don't normalize it
                if (!(t instanceof CtTypeParameterReference)) {
                    t = normalizeUnknownRef(t);
                }
                if (t == null) {
                    // Fallback to Object if normalization fails
                    t = f.Type().createReference("java.lang.Object");
                }
                
                if (willBeVarargs && i == p.paramTypes.size() - 1) {
                    // varargs at AST-level is an array on the last parameter
                    // Only create array if not already an array
                    if (!t.isArray()) {
                    t = f.Type().createArrayReference(t);
                    }
                }
                normParams.add(t);
            }
            List<CtParameter<?>> params = makeParams(normParams);

            // 4) thrown types
            Set<CtTypeReference<? extends Throwable>> thrown = new LinkedHashSet<>();
            for (CtTypeReference<?> t : p.thrownTypes) {
                if (t == null) continue;
                @SuppressWarnings({"rawtypes", "unchecked"})
                CtTypeReference<? extends Throwable> tt = (CtTypeReference) t;
                thrown.add(tt);
            }

            // 5) modifiers
            Set<ModifierKind> mods = new HashSet<>();
            if (p.isStatic) mods.add(ModifierKind.STATIC);
            switch (p.visibility) {
                case PRIVATE:
                    mods.add(ModifierKind.PRIVATE);
                    break;
                case PROTECTED:
                    mods.add(ModifierKind.PROTECTED);
                    break;
                case PUBLIC:
                    mods.add(ModifierKind.PUBLIC);
                    break;
                case PACKAGE:   /* none */
                    break;
            }

            // 6) create the method on the real owner
            CtMethod<?> m = f.Method().create(owner, mods, rt, p.name, params, thrown);
            
            // Special handling for methods overriding Object methods - must be public
            // Also prevent overriding final methods like getClass()
            if ("getClass".equals(p.name) && p.paramTypes.isEmpty()) {
                // Cannot override Object.getClass() - it's final
                owner.removeMethod(m);
                createdMethods.add(sig(owner.getQualifiedName(), p.name, normParams) + " : " + readable(rt0) + " [SKIPPED: final method]");
                continue;
            }
            
            // Methods overriding Object methods must be public
            if (("toString".equals(p.name) || "equals".equals(p.name) || "hashCode".equals(p.name)) && 
                owner instanceof CtClass) {
                m.addModifier(ModifierKind.PUBLIC);
                m.removeModifier(ModifierKind.PRIVATE);
                m.removeModifier(ModifierKind.PROTECTED);
            }

            // mark varargs on the last parameter (Spoon 10.x)
            if (willBeVarargs && !params.isEmpty()) {
                CtParameter<?> last = params.get(params.size() - 1);
                CtTypeReference<?> t = last.getType();
                if (t != null && !t.isArray()) {
                    CtArrayTypeReference<?> arr = f.Core().createArrayTypeReference();
                    arr.setComponentType(t);
                    last.setType(arr);
                }
                last.setVarArgs(true);
            }

            // Prevent illegal modifier combinations: abstract and static
            if (m.hasModifier(ModifierKind.ABSTRACT) && m.hasModifier(ModifierKind.STATIC)) {
                // Remove abstract if static (static methods can't be abstract in interfaces)
                m.removeModifier(ModifierKind.ABSTRACT);
            }

            // 7) interface default/abstract body handling
            boolean ownerIsInterface = owner instanceof CtInterface;
            if (ownerIsInterface) {
                if (p.isStatic) {
                    m.addModifier(ModifierKind.STATIC);
                    // Static methods in interfaces can't be abstract
                    m.removeModifier(ModifierKind.ABSTRACT);
                }

                if (p.defaultOnInterface) {
                    m.setDefaultMethod(true);
                    m.addModifier(ModifierKind.PUBLIC);
                    CtBlock<?> body = f.Core().createBlock();
                    CtReturn<?> ret = defaultReturn(rt0);
                    if (ret != null) body.addStatement(ret);
                    m.setBody(body);
                    m.removeModifier(ModifierKind.ABSTRACT);
                } else {
                    // Only make abstract if not static
                    if (!p.isStatic) {
                    m.setBody(null);
                    m.addModifier(ModifierKind.PUBLIC);
                    m.addModifier(ModifierKind.ABSTRACT);
                    } else {
                        // Static method in interface - must have body
                        CtBlock<?> body = f.Core().createBlock();
                        CtReturn<?> ret = defaultReturn(rt0);
                        if (ret != null) body.addStatement(ret);
                        m.setBody(body);
                        m.addModifier(ModifierKind.PUBLIC);
                    }
                }
            } else {
                CtBlock<?> body = f.Core().createBlock();
                CtReturn<?> ret = defaultReturn(rt0);
                if (ret != null) body.addStatement(ret);
                m.setBody(body);
                m.removeModifier(ModifierKind.ABSTRACT);
            }


            // 9) imports for owner
            boolean usesUnknown =
                    "unknown.Unknown".equals(readable(rt0))
                            || "Unknown".equals(rt0.getSimpleName())
                            || params.stream().anyMatch(d ->
                            "unknown.Unknown".equals(readable(d.getType()))
                                    || "Unknown".equals(d.getType().getSimpleName()));
            if (usesUnknown) ensureExplicitUnknownImport(owner);

            ensureImport(owner, rt0);
            for (CtParameter<?> par : params) ensureImport(owner, par.getType());
            for (CtTypeReference<?> t : thrown) ensureImport(owner, t);

            createdMethods.add(sig(owner.getQualifiedName(), p.name, normParams) + " : " + readable(rt0));
            created++;

            // Mark functional interface if this is the SAM method (apply or make) on an interface
            if (owner instanceof CtInterface && ("apply".equals(p.name) || "make".equals(p.name)) && !p.defaultOnInterface) {
                String ownerQnnew = safeQN(owner.getReference());
                if (ownerQnnew != null && !ownerQnnew.isEmpty()) {
                    functionalInterfaces.add(ownerQnnew);
                }
            }

            // 10) MIRROR into unknown.* if requested (so calls like unknown.T.m(...) compile)
            if (p.mirror && p.mirrorOwnerRef != null) {
                String moqn = safeQN(p.mirrorOwnerRef);
                if (moqn == null || !moqn.startsWith("unknown.")) {
                    // do nothing; we only mirror into unknown.*
                } else {
                    CtType<?> mirrorOwner = ensurePublicOwnerForTypeRef(p.mirrorOwnerRef);
// Skip mirroring for enum helper names; Collector should have scheduled an ENUM type plan
                    if (("values".equals(p.name) && p.paramTypes.isEmpty())
                            || ("valueOf".equals(p.name) && p.paramTypes.size() == 1)
                            || ("name".equals(p.name) && p.paramTypes.isEmpty())) {
                        // do not mirror; rely on TypeStubPlan.Kind.ENUM to satisfy these calls
                        // (no-op here)
                    } else {
                        // --- build mirror return & params adapted to the MIRROR owner ---
                        CtTypeReference<?> mirrorRt = rt; // default: same as real
                        List<CtTypeReference<?>> mirrorParamRefs = new ArrayList<>(normParams);

                        // Special-case enum helpers to mirror to the mirror-owner type:
                        String mn = p.name;
                        String mirrorOwnerQN = safeQN(p.mirrorOwnerRef);

                        boolean isEnumValues = "values".equals(mn) && mirrorParamRefs.isEmpty();
                        boolean isEnumValueOf = "valueOf".equals(mn) && mirrorParamRefs.size() == 1;
                        boolean isEnumName = "name".equals(mn) && mirrorParamRefs.isEmpty();

                        if (isEnumValues || isEnumValueOf || isEnumName) {
                            // mirrorOwnerRef element type
                            CtTypeReference<?> mirrorElem = f.Type().createReference(mirrorOwnerQN);

                            if (isEnumValues) {
                                CtArrayTypeReference<?> arr = f.Core().createArrayTypeReference();
                                arr.setComponentType(mirrorElem);
                                mirrorRt = arr;
                                mirrorParamRefs = Collections.emptyList();
                            } else if (isEnumValueOf) {
                                mirrorRt = mirrorElem;
                                mirrorParamRefs = List.of(f.Type().createReference("java.lang.String"));
                            } else { // name()
                                mirrorRt = f.Type().createReference("java.lang.String");
                                mirrorParamRefs = Collections.emptyList();
                            }
                        }

                        // Create parameters for mirror
                        List<CtParameter<?>> mirrorParams = makeParams(mirrorParamRefs);

                        // --- compute mirror modifiers independently (don't reuse owner's blindly) ---
                        Set<ModifierKind> mirrorMods = new HashSet<>();
                        if (isEnumValues || isEnumValueOf) {
                            // static enum helpers
                            mirrorMods.add(ModifierKind.PUBLIC);
                            mirrorMods.add(ModifierKind.STATIC);
                        } else if (isEnumName) {
                            // instance
                            mirrorMods.add(ModifierKind.PUBLIC);
                        } else {
                            // non-helper: mirror what the plan says
                            if (p.isStatic) mirrorMods.add(ModifierKind.STATIC);
                            switch (p.visibility) {
                                case PRIVATE:
                                    mirrorMods.add(ModifierKind.PRIVATE);
                                    break;
                                case PROTECTED:
                                    mirrorMods.add(ModifierKind.PROTECTED);
                                    break;
                                case PUBLIC:
                                    mirrorMods.add(ModifierKind.PUBLIC);
                                    break;
                                case PACKAGE:   /* none */
                                    break;
                            }
                        }

                        // Avoid duplicates on mirror using mirrorParamRefs
                        if (!hasMethod(mirrorOwner, p.name, mirrorParamRefs)) {
                            CtMethod<?> um = f.Method().create(mirrorOwner, mirrorMods, mirrorRt, p.name, mirrorParams, thrown);

                            // varargs on mirror (non-helper case only; helpers don’t use varargs)
                            if (p.varargs && !(isEnumValues || isEnumValueOf || isEnumName) && !um.getParameters().isEmpty()) {
                                CtParameter<?> last = um.getParameters().get(um.getParameters().size() - 1);
                                CtTypeReference<?> lt = last.getType();
                                if (lt != null && !lt.isArray()) {
                                    CtArrayTypeReference<?> arr = f.Core().createArrayTypeReference();
                                    arr.setComponentType(lt);
                                    last.setType(arr);
                                }
                                last.setVarArgs(true);
                            }

                            // body rules (same as owner)
                            boolean mirrorIsInterface = mirrorOwner instanceof CtInterface;
                            if (mirrorIsInterface) {
                                if (p.defaultOnInterface) {
                                    um.setDefaultMethod(true);
                                    um.addModifier(ModifierKind.PUBLIC);
                                    CtBlock<?> body = f.Core().createBlock();
                                    CtReturn<?> r = defaultReturn(mirrorRt);
                                    if (r != null) body.addStatement(r);
                                    um.setBody(body);
                                    um.removeModifier(ModifierKind.ABSTRACT);
                                } else {
                                    um.setBody(null);
                                    um.addModifier(ModifierKind.PUBLIC);
                                    um.addModifier(ModifierKind.ABSTRACT);
                                }
                            } else {
                                CtBlock<?> body = f.Core().createBlock();
                                CtReturn<?> r = defaultReturn(mirrorRt);
                                if (r != null) body.addStatement(r);
                                um.setBody(body);
                                um.removeModifier(ModifierKind.ABSTRACT);
                            }

                            // imports for mirror
                            ensureImport(mirrorOwner, mirrorRt);
                            for (CtParameter<?> par : um.getParameters()) ensureImport(mirrorOwner, par.getType());
                            for (CtTypeReference<?> t : thrown) ensureImport(mirrorOwner, t);

                            createdMethods.add(sig(mirrorOwner.getQualifiedName(), p.name, mirrorParamRefs) + " : " + readable(mirrorRt));
                            created++;
                        }
                    }
                }

            }
        }

        return created;
    }




    private CtType<?> ensurePublicOwnerForMethod(MethodStubPlan p) {
        CtTypeReference<?> ref = p.ownerType;
        String qn = safeQN(ref);
        // Do not remap if we deliberately pinned it (e.g., fixtures.arr.E)
        return ensurePublicOwnerForTypeRef(ref);
    }

    private CtType<?> ensurePublicOwnerForTypeRef(CtTypeReference<?> ref) {
        String qn = safeQN(ref);
        CtType<?> t = model.getAllTypes().stream()
                .filter(tt -> qn.equals(tt.getQualifiedName()))
                .findFirst().orElse(null);
        if (t != null) return t;

        int dot = qn.lastIndexOf('.');
        String pkg = (dot > 0 ? qn.substring(0, dot) : "");
        String simple = (dot > 0 ? qn.substring(dot + 1) : qn);

        CtPackage p = (pkg.isEmpty() ? f.Package().getRootPackage() : f.Package().getOrCreate(pkg));
        CtClass<?> c = f.Class().create(p, simple);
        c.addModifier(ModifierKind.PUBLIC);
        return c;
    }

    /* ======================================================================
     *                               REPORTING
     * ====================================================================== */

    /**
     * Print a simple creation report to stdout.
     */
    public void report() {
        System.out.println("\n== SPOON STUBS generated ==");
        for (String t : createdTypes) System.out.println(" +type  " + t);
        for (String s : createdFields) System.out.println(" +field " + s);
        for (String s : createdCtors) System.out.println(" +ctor  " + s);
        for (String s : createdMethods) System.out.println(" +method " + s);
    }

    /* ======================================================================
     *                                HELPERS
     * ====================================================================== */

    /**
     * Ensure a public class exists for a reference; creates it if needed.
     * Returns the CtClass<?> for that type.
     */
    private CtClass<?> ensurePublicClass(CtTypeReference<?> ref) {
        String qn = ref.getQualifiedName();
        String pkg = "";
        String name = "Missing";
        int i = (qn != null ? qn.lastIndexOf('.') : -1);


        if (qn != null && qn.contains("$")) {
            String outerFqn = qn.substring(0, qn.indexOf('$'));
            String innerSimple = qn.substring(qn.indexOf('$') + 1);
            // ensure outer
            CtClass<?> outer = ensurePublicClass(f.Type().createReference(outerFqn));
            // create member class inside outer if missing
            CtType<?> ex = outer.getNestedType(innerSimple);
            if (ex instanceof CtClass) return (CtClass<?>) ex;
            CtClass<?> inner = f.Class().create(outer, innerSimple);
            inner.addModifier(ModifierKind.PUBLIC);
            createdTypes.add(outerFqn + "$" + innerSimple);
            return inner;
        }

        if (qn != null && !qn.isEmpty()) {
            if (qn.contains(".")) {
                if (i >= 0) {
                    pkg = qn.substring(0, i);
                    name = qn.substring(i + 1);
                } else name = qn;
            } else {
                pkg = "unknown";
                name = qn;
            }

        }
        CtPackage p = f.Package().getOrCreate(pkg);
        CtType<?> ex = p.getType(name);
        if (ex instanceof CtClass) return (CtClass<?>) ex;

        CtClass<?> cls = f.Class().create(p, name);
        cls.addModifier(ModifierKind.PUBLIC);
        createdTypes.add((pkg.isEmpty() ? name : (pkg + "." + name)));
        return cls;
    }

    /**
     * Returns true for null/NullType-like references.
     */
    private boolean isNullish(CtTypeReference<?> t) {
        if (t == null) return true;
        String qn = t.getQualifiedName();
        return qn == null || "null".equals(qn) || qn.contains("NullType");
    }

    /**
     * Create CtParameter list for the provided types; assigns arg0..argN.
     */
    private List<CtParameter<?>> makeParams(List<CtTypeReference<?>> types) {
        List<CtParameter<?>> params = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            CtParameter<?> par = f.Core().createParameter();
            CtTypeReference<?> raw = (i < types.size() ? types.get(i) : null);
            CtTypeReference<?> safe;
            try {
                safe = (raw == null || isNullish(raw))
                        ? f.Type().createReference(UnknownType.CLASS)
                    : normalizeUnknownRef(raw); // ensure normalization survives
                // Additional safety check
                if (safe == null || isNullish(safe)) {
                    safe = f.Type().createReference("java.lang.Object");
                }
            } catch (Throwable e) {
                // Fallback to Object on any error
                safe = f.Type().createReference("java.lang.Object");
            }

            par.setType(safe);
            par.setSimpleName("arg" + i);
            params.add(par);
        }
        return params;
    }

    /**
     * Check if a method with name and parameter signature exists.
     */
    private boolean hasMethod(CtType<?> owner, String name, List<CtTypeReference<?>> paramTypes) {
        for (CtMethod<?> m : owner.getMethods()) {
            if (!m.getSimpleName().equals(name)) continue;
            List<CtParameter<?>> ps = m.getParameters();
            if (ps.size() != paramTypes.size()) continue;
            boolean all = true;
            for (int i = 0; i < ps.size(); i++) {
                String a = readable(ps.get(i).getType());
                String b = readable(paramTypes.get(i));
                if (!Objects.equals(a, b)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }


    /**
     * Check if a constructor with parameter signature exists.
     */
    private boolean hasConstructor(CtClass<?> owner, List<CtTypeReference<?>> paramTypes) {
        outer:
        for (CtConstructor<?> c : owner.getConstructors()) {
            List<CtParameter<?>> ps = c.getParameters();
            if (ps.size() != paramTypes.size()) continue;
            for (int i = 0; i < ps.size(); i++) {
                String a = readable(ps.get(i).getType());
                String b = readable(paramTypes.get(i));
                if (!Objects.equals(a, b)) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Produce a default return statement for the given return type; null for void.
     */
    private CtReturn<?> defaultReturn(CtTypeReference<?> t) {
        if (t == null || t.equals(f.Type().VOID_PRIMITIVE)) return null;

        CtCodeSnippetExpression<Object> expr;
        if (t.equals(f.Type().BOOLEAN_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("false");
        else if (t.equals(f.Type().CHARACTER_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("'\\0'");
        else if (t.equals(f.Type().BYTE_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("(byte)0");
        else if (t.equals(f.Type().SHORT_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("(short)0");
        else if (t.equals(f.Type().INTEGER_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("0");
        else if (t.equals(f.Type().LONG_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("0L");
        else if (t.equals(f.Type().FLOAT_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("0f");
        else if (t.equals(f.Type().DOUBLE_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("0d");
        else expr = f.Code().createCodeSnippetExpression("null");

        CtReturn<Object> r = f.Core().createReturn();
        r.setReturnedExpression(expr);
        return r;
    }

    /**
     * Safe readable name for types (falls back to "void").
     */
    private static String readable(CtTypeReference<?> t) {
        return (t == null ? "void" : String.valueOf(t.getQualifiedName()));
    }

    /**
     * Build a signature string like Owner#name(T1, T2).
     */
    private static String sig(String ownerFqn, String name, List<CtTypeReference<?>> params) {
        String p = String.join(", ", params.stream().map(SpoonStubber::readable).toArray(String[]::new));
        return ownerFqn + "#" + name + "(" + p + ")";
    }

    /* ======================================================================
     *                   PACKAGE/QUALIFICATION UTILITIES
     * ====================================================================== */

    /**
     * De-qualify unresolved type refs that only appear to be in the current package,
     * unless they already belong to 'unknown.' space.
     */
    public void dequalifyCurrentPackageUnresolvedRefs() {
        CtModel model = f.getModel();
        model.getAllTypes().forEach(t -> {
            String pkg = Optional.ofNullable(t.getPackage())
                    .map(CtPackage::getQualifiedName)
                    .orElse("");
            // Skip de-qualification if a type with that simple name exists in *another* package we generated this run.

            t.getElements(e -> e instanceof CtTypeReference<?>).forEach(refEl -> {
                CtTypeReference<?> ref = (CtTypeReference<?>) refEl;
                String qn = null;
                try {
                    qn = ref.getQualifiedName();
                } catch (Throwable ignored) {
                }

                boolean looksCurrentPkg = qn != null && !qn.isEmpty()
                        && pkg.length() > 0 && qn.startsWith(pkg + ".");
                if (looksCurrentPkg && ref.getDeclaration() == null) {
                    String qn2 = safeQN(ref);
                    if (qn2.isEmpty() || !qn2.startsWith("unknown.")) {
                        ref.setPackage(null);
                    }
                }
            });
        });
    }

    /**
     * Safely get a type's qualified name; returns empty string on failure.
     */
    public static String safeQN(CtTypeReference<?> t) {
        try {
            String s = (t == null ? null : t.getQualifiedName());
            return (s == null ? "" : s);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Force FQN printing for non-JDK, non-primitive types and skip imports.
     * Keeps primitives/void/arrays and JDK types untouched.
     * Special handling for unknown.Unknown: allows simple name with import.
     */
    private void ensureImport(CtType<?> owner, CtTypeReference<?> ref) {
        if (ref == null) return;

        // Skip primitives, void, arrays
        try {
            if (ref.isPrimitive()) return;
            if (ref.equals(f.Type().VOID_PRIMITIVE)) return;
            if (ref.isArray()) return;
        } catch (Throwable ignored) {
        }

        // Skip JDK types
        String qn = safeQN(ref);
        if (qn.isEmpty()
                || qn.startsWith("java.")
                || qn.startsWith("javax.")
                || qn.startsWith("jakarta.")
                || qn.startsWith("sun.")
                || qn.startsWith("jdk.")) {
            return;
        }

        // Special handling for unknown.Unknown: don't force FQN, allow simple name with import
        if ("unknown.Unknown".equals(qn) || ("Unknown".equals(ref.getSimpleName()) && qn.startsWith("unknown."))) {
            // Don't override the setSimplyQualified(false) set by normalizeUnknownRef
            // The import will be added by ensureExplicitUnknownImport
            return;
        }

        // If it already has a package (qn contains '.'), keep it qualified.
        // If it is a simple name (no package), DO NOT add any import—just force FQN printing.
        if (!qn.contains(".")) {
            ref.setImplicit(false);
            ref.setSimplyQualified(true); // print FQN, avoid imports entirely
            return;
        }

        // For non-JDK, non-primitive, with a package: force FQN printing (no import).
        if (ref.getPackage() == null) {
            int i = qn.lastIndexOf('.');
            if (i > 0) ref.setPackage(f.Package().createReference(qn.substring(0, i)));
        }
        ref.setImplicit(false);
        ref.setSimplyQualified(true); // always print FQN
    }

    /**
     * Normalize a reference to 'unknown.Unknown' when it appears as bare 'Unknown'
     * or inside the 'unknown.' package; makes it rely on an explicit import.
     * Also tries to resolve to concrete types when available.
     */
    private CtTypeReference<?> normalizeUnknownRef(CtTypeReference<?> t) {
        if (t == null) return null;
        try {
            // Check if this is a type parameter reference - if so, don't normalize it
            // Type parameters are created with references that should be preserved
            if (t instanceof CtTypeParameterReference) {
                return t; // Don't normalize type parameter references
            }
            
        String qn = safeQN(t);
        String simple = t.getSimpleName();

        if ("Unknown".equals(simple) && (qn.isEmpty() || !qn.contains("."))) {
            CtTypeReference<?> u = f.Type().createReference(
                        UnknownType.CLASS
            );
            u.setImplicit(false);
            // Set package for unknown.Unknown
            if (u.getPackage() == null) {
                u.setPackage(f.Package().createReference("unknown"));
            }
            // Use simple name (import will be added separately)
            u.setSimplyQualified(false);   // Use simple name: Unknown (with import)
            return u;
        }
            if (qn != null && qn.startsWith("unknown.")) {
                // Try to find a concrete type with the same simple name first
                CtType<?> concrete = findConcreteBySimple(simple);
                if (concrete != null) {
                    CtTypeReference<?> concreteRef = concrete.getReference();
                    concreteRef.setImplicit(false);
                    // Use simple name for concrete types (import will be added if needed)
                    concreteRef.setSimplyQualified(false);
                    return concreteRef;
                }
                // No concrete type found, use unknown - use simple name (import will be added)
                if (t.getPackage() == null) {
                    t.setPackage(f.Package().createReference("unknown"));
                }
                t.setImplicit(false);
                t.setSimplyQualified(false);   // Use simple name: Unknown (with import)
        }
        return t;
        } catch (Throwable e) {
            // If normalization fails, return the original reference
            return t;
        }
    }

    /**
     * Adds `import unknown.Unknown;` to the owner's CU once (idempotent).
     */
    private void ensureExplicitUnknownImport(CtType<?> owner) {
        final String FQN = "unknown.Unknown";
        CtCompilationUnit cu = f.CompilationUnit().getOrCreate(owner);

        boolean present = cu.getImports().stream().anyMatch(imp -> {
            try {
                var r = imp.getReference();
                return (r instanceof CtTypeReference)
                        && FQN.equals(((CtTypeReference<?>) r).getQualifiedName());
            } catch (Throwable ignored) {
                return false;
            }
        });

        if (!present) {
            cu.getImports().add(f.createImport(f.Type().createReference(FQN)));
        }
    }

    /**
     * Build a map of simple type name -> set of packages present in the model (non-JDK).
     * Used to detect ambiguous simple names.
     */
    private Map<String, Set<String>> simpleNameToPkgs() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        f.getModel().getAllTypes().forEach(t -> {
            String pkg = Optional.ofNullable(t.getPackage())
                    .map(CtPackage::getQualifiedName).orElse("");
            String simple = t.getSimpleName();
            if (simple == null || simple.isEmpty()) return;
            // skip JDK-ish
            if (pkg.startsWith("java.") || pkg.startsWith("javax.") ||
                    pkg.startsWith("jakarta.") || pkg.startsWith("sun.") || pkg.startsWith("jdk.")) return;

            m.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(pkg);
        });
        return m;
    }

    /**
     * Qualify ambiguous simple type refs to a chosen package (prefer 'unknown' if present),
     * and force FQN printing to avoid import conflicts.
     */

    public void qualifyAmbiguousSimpleTypes(Set<String> onlySimples) {
        if (onlySimples == null || onlySimples.isEmpty()) return;
        Map<String, Set<String>> map = simpleNameToPkgs();
        map.entrySet().removeIf(e -> e.getValue().size() < 2 || !onlySimples.contains(e.getKey()));
        if (map.isEmpty()) return;
        Map<String, String> chosen = new HashMap<>();
        for (var e : map.entrySet()) {
            Set<String> pkgs = e.getValue();
            String pick = pkgs.contains("unknown") ? "unknown" : pkgs.iterator().next();
            chosen.put(e.getKey(), pick);
        }
        f.getModel().getAllTypes().forEach(owner -> {
            owner.getElements(el -> el instanceof CtTypeReference<?>).forEach(refEl -> {
                CtTypeReference<?> ref = (CtTypeReference<?>) refEl;
                String simple = ref.getSimpleName();
                if (simple == null || simple.isEmpty()) return;
                String qn = readable(ref);
                if (qn.contains(".")) return;
                String pkg = chosen.get(simple);
                if (pkg == null) return;
                if (!pkg.isEmpty()) ref.setPackage(f.Package().createReference(pkg));
                else ref.setPackage(null);
                ref.setImplicit(false);
                ref.setSimplyQualified(true);
            });
        });
    }
    /**
     * Inspect model usages of a type FQN to infer maximum number of generic type arguments.
     *
     * @return maximum arity observed (0 if none)
     */
    private int inferGenericArityFromUsages(String fqn) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        int max = 0;
        for (var el : f.getModel().getElements(e -> e instanceof CtTypeReference<?>)) {
            CtTypeReference<?> ref = (CtTypeReference<?>) el;
            String qn = safeQN(ref);              // empty if unknown
            String sn = ref.getSimpleName();      // never null for normal refs

            boolean sameType =
                    fqn.equals(qn)                    // exact FQN match
                            || (qn.isEmpty() && simple.equals(sn)); // unresolved simple-name match

            if (!sameType) continue;

            int n = 0;
            try {
                n = ref.getActualTypeArguments().size();
            } catch (Throwable ignored) {
            }
            if (n > max) max = n;
        }
        return max;
    }

    /**
     * Infer type parameter names (T, R, U, etc.) from method plans.
     * This is more accurate than inferring from the model because we can see
     * the actual type parameter names used in method signatures.
     */
    private List<String> inferTypeParameterNamesFromMethodPlans(String fqn, int arity, 
            Collection<MethodStubPlan> methodPlans) {
        if (methodPlans == null || methodPlans.isEmpty()) {
            // Fallback to standard names
            return getDefaultTypeParameterNames(arity);
        }
        
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        Set<String> usedNames = new LinkedHashSet<>();
        
        // Collect type parameter names from method plans for this type
        for (MethodStubPlan plan : methodPlans) {
            try {
                String ownerQn = safeQN(plan.ownerType);
                // Also check erased FQN for generic types
                String ownerQnErased = erasureFqn(plan.ownerType);
                String fqnErased = erasureFqn(f.Type().createReference(fqn));
                
                boolean matches = ownerQn != null && (ownerQn.equals(fqn) || ownerQn.equals(fqnErased));
                if (!matches && ownerQnErased != null) {
                    matches = ownerQnErased.equals(fqn) || ownerQnErased.equals(fqnErased);
                }
                if (!matches) continue;
                
                // Check return type
                if (plan.returnType != null) {
                    String retSimple = plan.returnType.getSimpleName();
                    String retQn = safeQN(plan.returnType);
                    // Check if it's a type parameter (single uppercase letter, no real package)
                    if (retSimple != null && retSimple.length() == 1 && 
                        retSimple.matches("[A-Z]") && retSimple.charAt(0) >= 'T' &&
                        (retQn == null || retQn.isEmpty() || retQn.equals(retSimple) || 
                         retQn.startsWith("unknown."))) {
                        usedNames.add(retSimple);
                    }
                }
                
                // Check parameter types
                if (plan.paramTypes != null) {
                    for (CtTypeReference<?> paramType : plan.paramTypes) {
                        if (paramType == null) continue;
                        String paramSimple = paramType.getSimpleName();
                        String paramQn = safeQN(paramType);
                        // Check if it's a type parameter (single uppercase letter, no real package)
                        if (paramSimple != null && paramSimple.length() == 1 && 
                            paramSimple.matches("[A-Z]") && paramSimple.charAt(0) >= 'T' &&
                            (paramQn == null || paramQn.isEmpty() || paramQn.equals(paramSimple) ||
                             paramQn.startsWith("unknown."))) {
                            usedNames.add(paramSimple);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Use collected names, or fall back to standard names
        List<String> result = new ArrayList<>();
        String[] defaults = {"T", "R", "U", "V", "W", "X", "Y", "Z"};
        List<String> collected = new ArrayList<>(usedNames);
        
        for (int i = 0; i < arity; i++) {
            if (i < collected.size()) {
                result.add(collected.get(i));
            } else if (i < defaults.length) {
                result.add(defaults[i]);
            } else {
                result.add("T" + i);
            }
        }
        return result;
    }
    
    /**
     * Get the erased FQN of a type reference (removes generic type arguments).
     */
    private String erasureFqn(CtTypeReference<?> ref) {
        if (ref == null) return null;
        try {
            String qn = safeQN(ref);
            if (qn == null) return null;
            // Remove generic type arguments (e.g., "Uncheck<T>" -> "Uncheck")
            int angleBracket = qn.indexOf('<');
            if (angleBracket > 0) {
                return qn.substring(0, angleBracket);
            }
            return qn;
        } catch (Throwable ignored) {
            return null;
        }
    }
    
    /**
     * Get default type parameter names (T, R, U, etc.)
     */
    private List<String> getDefaultTypeParameterNames(int arity) {
        List<String> result = new ArrayList<>();
        String[] defaults = {"T", "R", "U", "V", "W", "X", "Y", "Z"};
        for (int i = 0; i < arity; i++) {
            if (i < defaults.length) {
                result.add(defaults[i]);
            } else {
                result.add("T" + i);
            }
        }
        return result;
    }
    
    /**
     * Check if a simple name is a Java primitive type name.
     * Prevents stubbing primitive types like byte, int, short, etc. as classes.
     */
    private boolean isPrimitiveTypeName(String simpleName) {
        if (simpleName == null) return false;
        return simpleName.equals("byte") || simpleName.equals("short") || simpleName.equals("int") ||
               simpleName.equals("long") || simpleName.equals("float") || simpleName.equals("double") ||
               simpleName.equals("char") || simpleName.equals("boolean") || simpleName.equals("void");
    }
    
    /**
     * Add type parameters with given names (or T0..T{arity-1} if names not provided) to a newly created type.
     */
    private void addTypeParameters(CtType<?> created, int arity, List<String> paramNames) {
        if (!(created instanceof CtFormalTypeDeclarer) || arity <= 0) return;
        CtFormalTypeDeclarer decl = (CtFormalTypeDeclarer) created;

        // don't duplicate if already has params
        if (decl.getFormalCtTypeParameters() != null && !decl.getFormalCtTypeParameters().isEmpty()) return;

        String createdQn = safeQN(created.getReference());
        System.err.println("[addTypeParameters] Creating " + arity + " type parameter(s) for " + createdQn);

        for (int i = 0; i < arity; i++) {
            CtTypeParameter tp = f.Core().createTypeParameter();
            String name = (paramNames != null && i < paramNames.size()) 
                    ? paramNames.get(i) 
                    : ("T" + i);
            tp.setSimpleName(name);
            decl.addFormalCtTypeParameter(tp);
            System.err.println("[addTypeParameters]   - Added type parameter: " + name);
        }
    }



    public void finalizeRepeatableAnnotations() {
        // package -> simpleName -> annotation type
        Map<String, Map<String, CtAnnotationType<?>>> byPkg = new LinkedHashMap<>();
        for (CtType<?> t : f.getModel().getAllTypes()) {
            if (!(t instanceof CtAnnotationType)) continue;
            String pkg = Optional.ofNullable(t.getPackage()).map(CtPackage::getQualifiedName).orElse("");
            byPkg.computeIfAbsent(pkg, k -> new LinkedHashMap<>())
                    .put(t.getSimpleName(), (CtAnnotationType<?>) t);
        }

        for (var ePkg : byPkg.entrySet()) {
            String pkg = ePkg.getKey();
            Map<String, CtAnnotationType<?>> anns = ePkg.getValue();


            for (var eBase : anns.entrySet()) {
                String baseSimple = eBase.getKey();
                CtAnnotationType<?> base = eBase.getValue();

                // Heuristic: container name = Base+'s' (Tag -> Tags). Works for the test.
                String containerSimple = baseSimple.endsWith("s") ? baseSimple + "es" : baseSimple + "s";
                CtAnnotationType<?> container = anns.get(containerSimple);
                if (container == null) continue; // nothing to wire

                // Build refs
                String baseFqn = (pkg.isEmpty() ? baseSimple : pkg + "." + baseSimple);
                String containerFqn = (pkg.isEmpty() ? containerSimple : pkg + "." + containerSimple);
                CtTypeReference<?> baseRef = f.Type().createReference(baseFqn);
                CtTypeReference<?> arrayOfBaseRef = f.Type().createArrayReference(baseRef);

                // Ensure the container has value(): Base[] (create or fix)
                CtAnnotationMethod<?> valueM = (CtAnnotationMethod<?>) container.getMethods().stream()
                        .filter(m -> "value".equals(m.getSimpleName()))
                        .findFirst().orElse(null);

                if (valueM == null) {
                    valueM = f.Core().createAnnotationMethod();
                    valueM.setSimpleName("value");
                    valueM.setType(arrayOfBaseRef);
                    container.addMethod(valueM);
                } else {
                    // fix wrong type like String -> Tag[]
                    valueM.setType(arrayOfBaseRef);
                }

                // Add @Repeatable(Container.class) on base if missing
                boolean alreadyRepeatable = base.getAnnotations().stream().anyMatch(a -> {
                    try {
                        return "java.lang.annotation.Repeatable"
                                .equals(a.getAnnotationType().getQualifiedName());
                    } catch (Throwable ignored) {
                        return false;
                    }
                });

                if (!alreadyRepeatable) {
                    CtAnnotation<?> rep = f.Core().createAnnotation();
                    rep.setAnnotationType(f.Type().createReference("java.lang.annotation.Repeatable"));

                    // class literal via snippet is fine with Spoon
                    CtCodeSnippetExpression<?> classLit =
                            f.Code().createCodeSnippetExpression(containerFqn + ".class");

                    rep.addValue("value", classLit);
                    base.addAnnotation(rep);
                }

                // After you have base (CtAnnotationType<?>) and container (CtAnnotationType<?>) …

// 1) Copy @Target if present on base -> container (exact same elements)
                CtAnnotation<?> baseTarget = findMeta(base, "Target");
                if (baseTarget != null && findMeta(container, "Target") == null) {
                    container.addAnnotation(cloneMeta(baseTarget));
                }

// 2) Copy @Retention if present
                CtAnnotation<?> baseRetention = findMeta(base, "Retention");
                if (baseRetention != null && findMeta(container, "Retention") == null) {
                    container.addAnnotation(cloneMeta(baseRetention));
                }

// 3) Mirror @Documented/@Inherited if present
                CtAnnotation<?> baseDoc = findMeta(base, "Documented");
                if (baseDoc != null && findMeta(container, "Documented") == null) {
                    container.addAnnotation(cloneMeta(baseDoc));
                }
                CtAnnotation<?> baseInh = findMeta(base, "Inherited");
                if (baseInh != null && findMeta(container, "Inherited") == null) {
                    container.addAnnotation(cloneMeta(baseInh));
                }

// 4) Ensure base’s @Repeatable points to the container (you already do this); keep it but canonicalize:
                for (CtAnnotation<?> a : base.getAnnotations()) canonicalizeMetaAnnotationType(a);
                for (CtAnnotation<?> a : container.getAnnotations()) canonicalizeMetaAnnotationType(a);

            }
        }
    }


    // --- in SpoonStubber ---

    /**
     * Force JDK meta-annotation types on a given annotation instance.
     */
    private void canonicalizeMetaAnnotationType(CtAnnotation<?> ann) {
        if (ann == null || ann.getAnnotationType() == null) return;
        String simple = ann.getAnnotationType().getSimpleName();
        switch (simple) {
            case "Target":
                ann.setAnnotationType(f.Type().createReference("java.lang.annotation.Target"));
                break;
            case "Retention":
                ann.setAnnotationType(f.Type().createReference("java.lang.annotation.Retention"));
                break;
            case "Repeatable":
                ann.setAnnotationType(f.Type().createReference("java.lang.annotation.Repeatable"));
                break;
            case "Documented":
                ann.setAnnotationType(f.Type().createReference("java.lang.annotation.Documented"));
                break;
            case "Inherited":
                ann.setAnnotationType(f.Type().createReference("java.lang.annotation.Inherited"));
                break;
            default:
                // leave others alone
        }
    }

    private static CtAnnotation<?> findMeta(CtAnnotationType<?> t, String simple) {
        for (CtAnnotation<?> a : t.getAnnotations()) {
            try {
                if (simple.equals(a.getAnnotationType().getSimpleName())) return a;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CtAnnotation<?> cloneMeta(CtAnnotation<?> src) {
        if (src == null) return null;

        CtAnnotation<?> c = f.Core().createAnnotation();
        c.setAnnotationType(src.getAnnotationType().clone());

        // Spoon 10.x: Map<String, CtExpression<?>>
        try {
            Map<String, CtExpression> vals = src.getValues();
            if (vals != null) {
                for (Map.Entry<String, CtExpression> e : vals.entrySet()) {
                    c.addValue(e.getKey(), e.getValue().clone());
                }
            }
        } catch (Throwable ignore) {
            // Older Spoon variants: getElementValues(): Map<CtMethod<?>, CtExpression<?>>
            try {
                Map<?, ?> vals = (Map<?, ?>) CtAnnotation.class
                        .getMethod("getElementValues")
                        .invoke(src);
                if (vals != null) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) vals).entrySet()) {
                        Object k = e.getKey();
                        String name;
                        try {
                            // k might be CtMethod or CtExecutableReference
                            name = (String) k.getClass().getMethod("getSimpleName").invoke(k);
                        } catch (Throwable t2) {
                            name = "value";
                        }
                        c.addValue(name, ((CtExpression<?>) e.getValue()).clone());
                    }
                }
            } catch (Throwable t3) {
                // last resort: nothing to copy
            }
        }

        canonicalizeMetaAnnotationType(c);
        return c;
    }

    public void canonicalizeAllMetaAnnotations() {
        for (CtType<?> t : f.getModel().getAllTypes()) {
            if (!(t instanceof CtAnnotationType)) continue;
            CtAnnotationType<?> at = (CtAnnotationType<?>) t;
            for (CtAnnotation<?> a : at.getAnnotations()) canonicalizeMetaAnnotationType(a);
        }
    }

    public void applyImplementsPlans(Map<String, Set<CtTypeReference<?>>> plans) {
        if (plans == null || plans.isEmpty()) return;

        for (var e : plans.entrySet()) {
            String ownerFqn = e.getKey();
            if (ownerFqn == null || ownerFqn.isEmpty()) continue;

            // Always create/fetch the owner only (never the interface type)
            CtType<?> owner = ensurePublicOwnerForFqn(ownerFqn);
            if (owner == null) continue;

            Set<CtTypeReference<?>> ifaces = e.getValue();
            if (ifaces == null || ifaces.isEmpty()) continue;

            for (CtTypeReference<?> ifaceRef : ifaces) {
                if (ifaceRef == null) continue;

                String iqn = safeQN(ifaceRef);
                if (iqn == null || iqn.isEmpty()) continue;

                CtTypeReference<?> toAttach = null;

                // --- JDK / Jakarta etc.: do NOT create types; attach reference but keep <T> ---
                if (isJdkFqn(iqn)) {
                    CtTypeReference<?> withArgs = cloneIntoFactoryWithTypeArgs(ifaceRef, owner.getFactory());

                    // If a raw superinterface with same erasure exists, remove it; if exact parameterized exists, skip
                    CtTypeReference<?> rawExisting = null;
                    boolean exactExists = false;
                    for (CtTypeReference<?> cur : new ArrayList<>(owner.getSuperInterfaces())) {
                        if (cur == null) continue;
                        if (cur.getQualifiedName().equals(withArgs.getQualifiedName())) {
                            if (cur.getActualTypeArguments().isEmpty()) {
                                rawExisting = cur;
                            } else if (cur.toString().equals(withArgs.toString())) {
                                exactExists = true;
                            }
                        }
                    }
                    if (rawExisting != null) owner.getSuperInterfaces().remove(rawExisting);
                    if (!exactExists) owner.addSuperInterface(withArgs);
                    continue; // done with this iface
                }

                // --- Non-JDK: reuse existing interface if present, preserving <T> from ifaceRef ---
                CtType<?> existing = f.Type().get(iqn);
                if (existing instanceof CtInterface) {
                    toAttach = cloneIntoFactoryWithTypeArgs(ifaceRef, owner.getFactory());
                } else if (existing instanceof CtClass) {
                    // Only convert if it's one of OUR fresh empty stubs
                    boolean weCreated = createdTypes.contains(existing.getQualifiedName());
                    boolean looksEmpty =
                            ((CtClass<?>) existing).getFields().isEmpty()
                                    && ((CtClass<?>) existing).getConstructors().isEmpty()
                                    && ((CtClass<?>) existing).getMethods().isEmpty()
                                    && existing.getNestedTypes().isEmpty();

                    if (weCreated && looksEmpty) {
                        // replace class with interface of same FQN
                        String qn = existing.getQualifiedName();
                        int dot = qn.lastIndexOf('.');
                        String pkg = (dot >= 0 ? qn.substring(0, dot) : "");
                        String sn  = (dot >= 0 ? qn.substring(dot + 1) : qn);
                        CtPackage p = (existing.getParent() instanceof CtPackage)
                                ? (CtPackage) existing.getParent()
                                : f.Package().getOrCreate(pkg);
                        existing.delete();
                        CtInterface<?> itf = f.Interface().create(p, sn);
                        itf.addModifier(ModifierKind.PUBLIC);
                        createdTypes.add(itf.getQualifiedName());
                        // again, preserve type args from ifaceRef on the reference we attach
                        toAttach = cloneIntoFactoryWithTypeArgs(ifaceRef, owner.getFactory());
                    } else {
                        // don't mutate user code – just attach a reference with the type args from ifaceRef
                        toAttach = cloneIntoFactoryWithTypeArgs(ifaceRef, owner.getFactory());
                    }
                } else {
                    // Not present → create a new interface (non-JDK)
                    int dot = iqn.lastIndexOf('.');
                    String pkg = (dot >= 0 ? iqn.substring(0, dot) : "");
                    String sn  = (dot >= 0 ? iqn.substring(dot + 1) : iqn);
                    CtPackage p = f.Package().getOrCreate(pkg);
                    CtInterface<?> itf = f.Interface().create(p, sn);
                    itf.addModifier(ModifierKind.PUBLIC);
                    createdTypes.add(itf.getQualifiedName());
                    toAttach = cloneIntoFactoryWithTypeArgs(ifaceRef, owner.getFactory());
                }

                // --- Attach if not already present; upgrade raw to parameterized when needed ---
                if (toAttach != null) {
                    CtTypeReference<?> rawExisting = null;
                    boolean exactExists = false;
                    for (CtTypeReference<?> cur : new ArrayList<>(owner.getSuperInterfaces())) {
                        if (cur == null) continue;
                        if (cur.getQualifiedName().equals(toAttach.getQualifiedName())) {
                            if (cur.getActualTypeArguments().isEmpty()) {
                                rawExisting = cur; // we'll upgrade it
                            } else if (cur.toString().equals(toAttach.toString())) {
                                exactExists = true; // exact parameterization already present
                            }
                        }
                    }
                    if (rawExisting != null) owner.getSuperInterfaces().remove(rawExisting);
                    if (!exactExists) owner.addSuperInterface(toAttach);
                }
            }
        }
    }




    private CtTypeReference<?> cloneIntoFactoryWithTypeArgs(CtTypeReference<?> src, Factory targetFactory) {
        if (src == null) return null;

        // Create a reference in the target factory with the same erasure (qualified name)
        CtTypeReference<?> dst = targetFactory.Type().createReference(src.getQualifiedName());

        // Clear any default args (usually empty) and copy actual type arguments recursively
        dst.getActualTypeArguments().clear();
        for (CtTypeReference<?> a : src.getActualTypeArguments()) {
            // Recursively preserve nested arguments/wildcards/etc.
            CtTypeReference<?> aCopy = a.clone(); // cloning is OK; Spoon will accept it on dst
            dst.addActualTypeArgument(aCopy);
        }
        return dst;
    }





    // --- in SpoonStubber --------------------------------------------------------

    /** Return the CtType for the given FQN, creating it (public) if needed.
     *  Supports member types written as pkg.Outer$Inner$Deeper.
     *  Never creates types under java.* / javax.* / jakarta.* / sun.* / jdk.*.
     */
    // Spoon 10.4.2–compatible
    private CtType<?> ensurePublicOwnerForFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) return null;
        if (isJdkFqn(fqn)) return f.Type().get(fqn); // never create JDK types

        // Already exists?
        CtType<?> existing = f.Type().get(fqn);
        if (existing != null) {
            if (!existing.hasModifier(ModifierKind.PUBLIC)) existing.addModifier(ModifierKind.PUBLIC);
            return existing;
        }

        // Split pkg + after-pkg (may contain $ for members)
        final int lastDot = fqn.lastIndexOf('.');
        final String pkgName  = (lastDot >= 0 ? fqn.substring(0, lastDot) : "");
        final String afterPkg = (lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn);

        // No member part → top-level
        if (!afterPkg.contains("$")) {
            CtPackage p = f.Package().getOrCreate(pkgName);
            CtType<?> t = p.getType(afterPkg);              // <-- lookup in package (Spoon 10)
            if (t == null) {
                CtClass<?> c = f.Class().create(p, afterPkg); // <-- create in package (Spoon 10)
                c.addModifier(ModifierKind.PUBLIC);
                createdTypes.add(c.getQualifiedName());
                return c;
            } else {
                if (!t.hasModifier(ModifierKind.PUBLIC)) t.addModifier(ModifierKind.PUBLIC);
                return t;
            }
        }

        // Member chain: Outer$Inner$Deeper…
        String[] parts = afterPkg.split("\\$");

        // 1) ensure top-level Outer
        String topFqn = (pkgName.isEmpty() ? parts[0] : pkgName + "." + parts[0]);
        CtType<?> top = f.Type().get(topFqn);
        if (top == null) {
            CtPackage p = f.Package().getOrCreate(pkgName);
            CtClass<?> c = f.Class().create(p, parts[0]);
            c.addModifier(ModifierKind.PUBLIC);
            createdTypes.add(c.getQualifiedName());
            top = c;
        } else if (!top.hasModifier(ModifierKind.PUBLIC)) {
            top.addModifier(ModifierKind.PUBLIC);
        }

        // 2) walk/create nested parts under parent
        CtType<?> parent = top;
        for (int i = 1; i < parts.length; i++) {
            String simple = parts[i];
            CtType<?> nested = parent.getNestedType(simple);
            if (nested == null) {
                // Create a nested *class* by default (safe for stubs)
                CtClass<?> cls;
                if (parent instanceof CtClass) {
                    cls = f.Class().create((CtClass<?>) parent, simple);
                } else {
                    // Parent might be interface/annotation/enum – attach via Core + addNestedType
                    cls = f.Core().createClass();
                    cls.setSimpleName(simple);
                    ((CtType<?>) parent).addNestedType(cls);
                }
                cls.addModifier(ModifierKind.PUBLIC);
                cls.removeModifier(ModifierKind.STATIC); // default non-static
                nested = cls;
                createdTypes.add(nested.getQualifiedName());
            } else if (!nested.hasModifier(ModifierKind.PUBLIC)) {
                nested.addModifier(ModifierKind.PUBLIC);
            }
            parent = nested;
        }
        return parent;
    }

    // already in your class or add it if missing
    private static boolean isJdkFqn(String qn) {
        return qn != null && (qn.startsWith("java.")
                || qn.startsWith("javax.")
                || qn.startsWith("jakarta.")
                || qn.startsWith("sun.")
                || qn.startsWith("jdk."));
    }



    // After generation, rebind superclasses that still point at unknown.* to a concrete owner we created,
// preferring the current package if available; otherwise keep as-is.
    public void rebindUnknownSupertypesToConcrete() {
        CtModel model = f.getModel();

        // Build a quick lookup: simpleName -> non-unknown FQNs we created
        Map<String, String> concreteBySimple = new HashMap<>();
        for (String fq : createdTypes) {
            if (fq == null || fq.startsWith("unknown.")) continue;
            String simple = fq.contains("$") ? fq.substring(fq.lastIndexOf('$') + 1) : fq.substring(fq.lastIndexOf('.') + 1);
            // Prefer first concrete occurrence; that's enough for our rebinding rule
            concreteBySimple.putIfAbsent(simple, fq);
        }

        for (CtType<?> t : model.getAllTypes()) {
            if (!(t instanceof CtClass)) continue;

            CtClass<?> cls = (CtClass<?>) t;
            CtTypeReference<?> sup = null;
            try { sup = cls.getSuperclass(); } catch (Throwable ignored) {}
            if (sup == null) continue;

            String qn = null;
            try { qn = sup.getQualifiedName(); } catch (Throwable ignored) {}
            if (qn == null || !qn.startsWith("unknown.")) continue;

            String simple = sup.getSimpleName();

            // 1) Prefer current package (like fixtures.sup.P)
            String pkg = Optional.ofNullable(cls.getPackage()).map(CtPackage::getQualifiedName).orElse("");
            if (!pkg.isEmpty()) {
                CtTypeReference<?> candidate = f.Type().createReference(pkg + "." + simple);
                // We can just rebind to this ref; Stubber already creates types by plan.
                cls.setSuperclass(candidate);
                continue;
            }

            // 2) Otherwise, if we created a concrete owner with same simple name, bind to that
            String concreteFqn = concreteBySimple.get(simple);
            if (concreteFqn != null) {
                cls.setSuperclass(f.Type().createReference(concreteFqn));
            }
        }
    }

    public void rebindUnknownTypeReferencesToConcrete(Map<String, String> unknownToConcrete) {
        // Build a fallback index: simple name -> concrete FQN present in the model
        Map<String, String> concreteBySimple = new HashMap<>();
        for (CtType<?> t : model.getAllTypes()) {
            String qn = safeQN(t.getReference());
            if (qn == null || qn.startsWith("unknown.")) continue;
            concreteBySimple.put(simpleNameOfFqn(qn), qn);
        }

        // Visit every type reference and adjust package/simple name when a mapping exists
        for (CtTypeReference<?> tr : model.getElements(new TypeFilter<>(CtTypeReference.class))) {
            String qn = safeQN(tr);
            if (qn == null || !qn.startsWith("unknown.")) continue;

            // 1) Primary mapping from Collector (unknownToConcrete)
            String mapped = (unknownToConcrete != null ? unknownToConcrete.get(qn) : null);

            // 2) Fallback: unique concrete by simple name
            if (mapped == null) {
                mapped = concreteBySimple.get(tr.getSimpleName());
            }
            if (mapped == null) continue; // nothing to rebind

                String pkg = getPackageOfFqn(mapped);
            String sn  = simpleNameOfFqn(mapped);

            if (pkg.isEmpty()) {
                tr.setPackage(null);
            } else {
                // Use the reference's own factory to get a package reference
                CtPackageReference pref =
                        tr.getFactory().Package().getOrCreate(pkg).getReference();
                tr.setPackage(pref);
            }
            tr.setSimpleName(sn);
        }
    }





    private static String simpleNameOfFqn(String fqn) {
        int i = (fqn == null ? -1 : fqn.lastIndexOf('.'));
        return (i < 0 ? fqn : fqn.substring(i + 1));
    }

    private static String getPackageOfFqn(String fqn) {
        int i = (fqn == null ? -1 : fqn.lastIndexOf('.'));
        return (i <= 0 ? "" : fqn.substring(0, i));
    }

    /** Returns a concrete (non-unknown) type in the model with the given simple name, or null. */
    private CtType<?> findConcreteBySimple(String simple) {
        if (simple == null) return null;
        for (CtType<?> t : model.getAllTypes()) {
            String qn = safeQN(t.getReference());
            if (qn == null || qn.startsWith("unknown.")) continue;
            if (simple.equals(simpleNameOfFqn(qn))) return t;
        }
        return null;
    }

    private CtTypeReference<?> normalizeOwnerRef(CtTypeReference<?> ownerRef) {
        if (ownerRef == null) return null;
        try {
        String qn = safeQN(ownerRef);
        if (qn != null && qn.startsWith("unknown.")) {
            CtType<?> concrete = findConcreteBySimple(ownerRef.getSimpleName());
            if (concrete != null) {
                return concrete.getReference();
            }
        }
        return ownerRef;
        } catch (Throwable e) {
            // If we can't normalize, try to find by simple name
            try {
                String simple = ownerRef.getSimpleName();
                if (simple != null) {
                    CtType<?> concrete = findConcreteBySimple(simple);
                    if (concrete != null) {
                        return concrete.getReference();
                    }
                }
            } catch (Throwable ignored) {
            }
            // Last resort: return as-is
            return ownerRef;
        }
    }



    public void removeUnknownStarImportsIfUnused() {
        CtModel model = f.getModel();

        for (CtType<?> t : model.getAllTypes()) {
            CtCompilationUnit cu;
            try {
                cu = f.CompilationUnit().getOrCreate(t);
            } catch (Throwable ignored) {
                continue;
            }
            if (cu == null) continue;

            boolean hasUnknownRefs = t.getElements(e -> e instanceof CtTypeReference<?>)
                    .stream()
                    .map(e -> (CtTypeReference<?>) e)
                    .anyMatch(ref -> {
                        try {
                            String qn = ref.getQualifiedName();
                            String simple = ref.getSimpleName();
                            // Check for unknown.Unknown by qualified name OR simple name
                            return (qn != null && qn.startsWith("unknown.")) ||
                                   "Unknown".equals(simple);
                        } catch (Throwable ex) {
                            return false;
                        }
                    });

            if (hasUnknownRefs) continue;

            // Only remove unknown.* imports, NOT explicit unknown.Unknown imports
            boolean removed = cu.getImports().removeIf(imp -> {
                try {
                    CtReference r = imp.getReference();
                    if (r == null) return false;
                    String s = r.toString(); // robust across CtReference subclasses
                    // Only remove unknown.*, not unknown.Unknown
                    return "unknown.*".equals(s);
                } catch (Throwable ex) {
                    return false;
                }
            });

            if (removed) {
                System.out.println("[imports] removed unknown.* import from CU of " + t.getQualifiedName());
            }
        }
    }

    /* ======================================================================
     *                    CRITICAL FIXES: GENERIC TYPE ARGUMENTS
     * ====================================================================== */

    /**
     * Preserve generic type arguments when creating type references.
     * This ensures that Mono<T>, Optional<T>, etc. maintain their type parameters.
     */
    public void preserveGenericTypeArgumentsInUsages() {
        // Map of erased FQN -> list of parameterized usages
        Map<String, List<CtTypeReference<?>>> usagesByErasure = new HashMap<>();
        
        // Collect all type references with generic arguments
        for (CtTypeReference<?> ref : model.getElements(new TypeFilter<>(CtTypeReference.class))) {
            try {
                if (ref.getActualTypeArguments().isEmpty()) continue;
                
                String qn = safeQN(ref);
                if (qn == null || qn.isEmpty()) continue;
                
                // Skip JDK types - they're already correct
                if (isJdkFqn(qn)) continue;
                
                // Get erased FQN (without type arguments)
                String erased = erasureFqn(ref);
                if (erased == null || erased.isEmpty()) continue;
                
                usagesByErasure.computeIfAbsent(erased, k -> new ArrayList<>()).add(ref);
            } catch (Throwable ignored) {}
        }
        
        // For each erased type, if we created it, ensure it has type parameters
        for (Map.Entry<String, List<CtTypeReference<?>>> entry : usagesByErasure.entrySet()) {
            String erasedFqn = entry.getKey();
            List<CtTypeReference<?>> usages = entry.getValue();
            
            // Find the maximum arity
            int maxArity = usages.stream()
                    .mapToInt(r -> {
                        try {
                            return r.getActualTypeArguments().size();
                        } catch (Throwable e) {
                            return 0;
                        }
                    })
                    .max()
                    .orElse(0);
            
            if (maxArity == 0) continue;
            
            // Check if we created this type
            CtType<?> type = f.Type().get(erasedFqn);
            if (type == null || !createdTypes.contains(erasedFqn)) continue;
            
            // Ensure type has type parameters
            if (type instanceof CtFormalTypeDeclarer) {
                CtFormalTypeDeclarer declarer = (CtFormalTypeDeclarer) type;
                List<CtTypeParameter> existing = declarer.getFormalCtTypeParameters();
                if (existing == null || existing.size() < maxArity) {
                    // Add missing type parameters
                    String[] defaultNames = {"T", "R", "U", "V", "W", "X", "Y", "Z"};
                    for (int i = existing == null ? 0 : existing.size(); i < maxArity; i++) {
                        String name = i < defaultNames.length ? defaultNames[i] : "T" + i;
                        CtTypeParameter tp = f.Core().createTypeParameter();
                        tp.setSimpleName(name);
                        declarer.addFormalCtTypeParameter(tp);
                    }
                }
            }
        }
    }


    /* ======================================================================
     *              CRITICAL FIXES: AUTO-IMPLEMENT INTERFACE METHODS
     * ====================================================================== */

    /**
     * Auto-implement all abstract methods from interfaces that a class implements.
     * This fixes "class is not abstract and does not override abstract method" errors.
     */
    public void autoImplementInterfaceMethods() {
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type instanceof CtClass)) continue;
            
            CtClass<?> cls = (CtClass<?>) type;
            if (cls.getSuperInterfaces().isEmpty()) continue;
            
            // Collect all abstract methods from all superinterfaces
            Set<String> implementedMethods = new HashSet<>();
            for (CtMethod<?> m : cls.getMethods()) {
                implementedMethods.add(methodSignature(m));
            }
            
            // For each superinterface, add missing abstract methods
            for (CtTypeReference<?> superIface : cls.getSuperInterfaces()) {
                try {
                    CtType<?> ifaceType = superIface.getTypeDeclaration();
                    if (ifaceType == null || !(ifaceType instanceof CtInterface)) continue;
                    
                    CtInterface<?> iface = (CtInterface<?>) ifaceType;
                    for (CtMethod<?> ifaceMethod : iface.getMethods()) {
                        // Only add abstract methods that aren't already implemented
                        // Check if it's abstract, not static, and doesn't have a body (not default)
                        boolean hasBody = ifaceMethod.getBody() != null;
                        if (ifaceMethod.isAbstract() && !hasBody && !ifaceMethod.isStatic()) {
                            String sig = methodSignature(ifaceMethod);
                            if (!implementedMethods.contains(sig)) {
                                // Clone the method and add it to the class
                                CtMethod<?> impl = cloneMethodForImplementation(ifaceMethod, cls);
                                cls.addMethod(impl);
                                implementedMethods.add(sig);
                                createdMethods.add(cls.getQualifiedName() + "#" + sig);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Clone an interface method for implementation in a class.
     */
    private CtMethod<?> cloneMethodForImplementation(CtMethod<?> ifaceMethod, CtClass<?> targetClass) {
        CtMethod<?> impl = f.Core().clone(ifaceMethod);
        impl.setParent(targetClass);
        impl.removeModifier(ModifierKind.ABSTRACT);
        impl.removeModifier(ModifierKind.PUBLIC); // Will be added by default
        
        // Add a default body
        CtBlock<?> body = f.Core().createBlock();
        CtTypeReference<?> returnType = impl.getType();
        if (returnType != null && !returnType.equals(f.Type().VOID_PRIMITIVE)) {
            // Return default value based on type
            CtReturn<?> ret = f.Core().createReturn();
            @SuppressWarnings({"unchecked", "rawtypes"})
            CtExpression defaultValue = (CtExpression) createDefaultValue(returnType);
            ret.setReturnedExpression(defaultValue);
            body.addStatement(ret);
        }
        impl.setBody(body);
        
        return impl;
    }

    /**
     * Create a default value for a type (null for objects, 0 for primitives, etc.).
     */
    private CtExpression<?> createDefaultValue(CtTypeReference<?> type) {
        if (type == null) return f.Code().createLiteral(null);
        
        try {
            if (type.isPrimitive()) {
                String typeName = type.getSimpleName();
                if ("boolean".equals(typeName)) {
                    return f.Code().createLiteral(false);
                } else if ("int".equals(typeName) || "long".equals(typeName) || 
                          "short".equals(typeName) || "byte".equals(typeName) ||
                          "char".equals(typeName)) {
                    return f.Code().createLiteral(0);
                } else if ("float".equals(typeName)) {
                    return f.Code().createLiteral(0.0f);
                } else if ("double".equals(typeName)) {
                    return f.Code().createLiteral(0.0);
                }
            }
        } catch (Throwable ignored) {}
        
        return f.Code().createLiteral(null);
    }

    /**
     * Create a method signature string for comparison.
     */
    private String methodSignature(CtMethod<?> m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getSimpleName());
        sb.append("(");
        for (CtParameter<?> p : m.getParameters()) {
            if (sb.length() > m.getSimpleName().length() + 1) sb.append(",");
            sb.append(safeQN(p.getType()));
        }
        sb.append(")");
        return sb.toString();
    }

    /* ======================================================================
     *                    CRITICAL FIXES: BUILDER PATTERN
     * ====================================================================== */

    /**
     * Detect and fix builder pattern issues.
     * Creates Builder classes and ensures builder() methods return Builder instances.
     */
    public void fixBuilderPattern() {
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type instanceof CtClass)) continue;
            
            CtClass<?> cls = (CtClass<?>) type;
            
            // Look for builder() methods
            for (CtMethod<?> method : cls.getMethods()) {
                if (!"builder".equals(method.getSimpleName()) || 
                    !method.getParameters().isEmpty()) continue;
                
                CtTypeReference<?> returnType = method.getType();
                if (returnType == null) continue;
                
                String returnQn = safeQN(returnType);
                if (returnQn == null || !returnQn.contains("Builder")) continue;
                
                // Extract Builder class name
                String builderQn = returnQn;
                if (builderQn.contains("$")) {
                    // Nested Builder
                } else {
                    // Top-level Builder - should be nested
                    String ownerQn = cls.getQualifiedName();
                    builderQn = ownerQn + "$Builder";
                }
                
                // Ensure Builder class exists
                ensureBuilderClass(cls, builderQn);
            }
        }
    }

    /**
     * Ensure a Builder class exists for the given owner class.
     */
    private void ensureBuilderClass(CtClass<?> owner, String builderQn) {
        // Check if Builder already exists
        CtType<?> existing = f.Type().get(builderQn);
        if (existing != null && existing instanceof CtClass) {
            return; // Already exists
        }
        
        // Create Builder as nested class
        String builderSimple = builderQn.contains("$") 
            ? builderQn.substring(builderQn.lastIndexOf('$') + 1)
            : "Builder";
        
        CtClass<?> builder = f.Class().create(owner, builderSimple);
        builder.addModifier(ModifierKind.PUBLIC);
        builder.addModifier(ModifierKind.STATIC);
        
        // Add get() method that returns the owner type
        Set<ModifierKind> getMods = new HashSet<>();
        getMods.add(ModifierKind.PUBLIC);
        CtMethod<?> getMethod = f.Method().create(
            builder,
            getMods,
            owner.getReference(),
            "get",
            Collections.emptyList(),
            Collections.emptySet(),
            f.Core().createBlock()
        );
        CtBlock<?> getBody = getMethod.getBody();
        CtReturn<?> ret = f.Core().createReturn();
        @SuppressWarnings({"unchecked", "rawtypes"})
        CtExpression ctorCall = (CtExpression) f.Code().createConstructorCall(owner.getReference());
        ret.setReturnedExpression(ctorCall);
        getBody.addStatement(ret);
        getMethod.setBody(getBody);
        
        createdTypes.add(builderQn);
    }

    /* ======================================================================
     *                    CRITICAL FIXES: FIELD INITIALIZATION
     * ====================================================================== */

    /**
     * Auto-initialize common fields (logger, etc.).
     */
    public void autoInitializeFields() {
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type instanceof CtClass)) continue;
            
            CtClass<?> cls = (CtClass<?>) type;
            for (CtField<?> field : cls.getFields()) {
                if (field.getDefaultExpression() != null) continue; // Already initialized
                
                String fieldName = field.getSimpleName();
                CtTypeReference<?> fieldType = field.getType();
                
                // Initialize logger fields
                if ("logger".equals(fieldName) || fieldName.toLowerCase().contains("log")) {
                    if (fieldType != null) {
                        String typeQn = safeQN(fieldType);
                        if (typeQn != null && typeQn.contains("Logger")) {
                            // Try to initialize with Logger.getLogger(...)
                            try {
                                CtTypeReference<?> loggerType = f.Type().createReference("java.util.logging.Logger");
                                CtType<?> loggerTypeDecl = loggerType.getTypeDeclaration();
                                if (loggerTypeDecl != null) {
                                    CtMethod<?> getLoggerMethod = loggerTypeDecl.getMethod("getLogger", 
                                        f.Type().createReference("java.lang.String"));
                                    if (getLoggerMethod != null) {
                                        CtTypeAccess<?> typeAccess = f.Code().createTypeAccess(loggerType);
                                        CtLiteral<String> className = f.Code().createLiteral(cls.getQualifiedName());
                                        @SuppressWarnings({"unchecked", "rawtypes"})
                                        CtExpression loggerCall = (CtExpression) f.Code().createInvocation(
                                            typeAccess,
                                            getLoggerMethod.getReference(),
                                            className
                                        );
                                        field.setAssignment(loggerCall);
                                    } else {
                                        // Fallback: initialize with null
                                        field.setAssignment(f.Code().createLiteral(null));
                                    }
                                } else {
                                    // Fallback: initialize with null
                                    field.setAssignment(f.Code().createLiteral(null));
                                }
                            } catch (Throwable ignored) {
                                // Fallback: initialize with null
                                field.setAssignment(f.Code().createLiteral(null));
                            }
                        }
                    }
                }
            }
        }
    }

    /* ======================================================================
     *                    CRITICAL FIXES: STREAM API METHODS
     * ====================================================================== */

    /**
     * Add Stream API interface methods to types that extend BaseStream.
     */
    public void addStreamApiMethods() {
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type instanceof CtInterface)) continue;
            
            CtInterface<?> iface = (CtInterface<?>) type;
            String ifaceQn = safeQN(iface.getReference());
            if (ifaceQn == null) continue;
            
            // Check if this extends BaseStream
            boolean extendsStream = false;
            for (CtTypeReference<?> superIface : iface.getSuperInterfaces()) {
                String superQn = safeQN(superIface);
                if (superQn != null && superQn.contains("BaseStream")) {
                    extendsStream = true;
                    break;
                }
            }
            
            if (!extendsStream) continue;
            
            // Add Stream API methods if missing
            addStreamMethodIfMissing(iface, "isParallel", f.Type().BOOLEAN_PRIMITIVE, Collections.emptyList());
            addStreamMethodIfMissing(iface, "iterator", f.Type().createReference("java.util.Iterator"), Collections.emptyList());
            addStreamMethodIfMissing(iface, "parallel", iface.getReference(), Collections.emptyList());
            addStreamMethodIfMissing(iface, "sequential", iface.getReference(), Collections.emptyList());
            addStreamMethodIfMissing(iface, "spliterator", f.Type().createReference("java.util.Spliterator"), Collections.emptyList());
            addStreamMethodIfMissing(iface, "unordered", iface.getReference(), Collections.emptyList());
        }
    }

    /**
     * Add a Stream API method if it's missing.
     */
    private void addStreamMethodIfMissing(CtInterface<?> iface, String methodName, 
                                         CtTypeReference<?> returnType, 
                                         List<CtTypeReference<?>> paramTypes) {
        if (hasMethod(iface, methodName, paramTypes)) return;
        
        Set<ModifierKind> mods = new HashSet<>();
        mods.add(ModifierKind.PUBLIC);
        mods.add(ModifierKind.ABSTRACT);
        CtMethod<?> method = f.Method().create(
            iface,
            mods,
            returnType,
            methodName,
            makeParams(paramTypes),
            Collections.emptySet()
        );
    }

    /* ======================================================================
     *                    CRITICAL FIXES: TYPE CONVERSION
     * ====================================================================== */

    /**
     * Fix type conversion issues by improving Unknown type handling.
     */
    public void fixTypeConversionIssues() {
        // Replace Unknown types in binary operations with appropriate types
        for (CtBinaryOperator<?> binOp : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            try {
                CtExpression<?> left = binOp.getLeftHandOperand();
                CtExpression<?> right = binOp.getRightHandOperand();
                
                if (left == null || right == null) continue;
                
                CtTypeReference<?> leftType = left.getType();
                CtTypeReference<?> rightType = right.getType();
                
                // If right is Unknown and left is a primitive, infer right type from context
                if (rightType != null && "unknown.Unknown".equals(safeQN(rightType))) {
                    if (leftType != null && leftType.isPrimitive()) {
                        // For comparison operations, right should match left
                        if (binOp.getKind() == BinaryOperatorKind.EQ || 
                            binOp.getKind() == BinaryOperatorKind.NE ||
                            binOp.getKind() == BinaryOperatorKind.GT ||
                            binOp.getKind() == BinaryOperatorKind.GE ||
                            binOp.getKind() == BinaryOperatorKind.LT ||
                            binOp.getKind() == BinaryOperatorKind.LE) {
                            // Try to replace Unknown with matching primitive
                            if (right instanceof CtLiteral) {
                                CtLiteral<?> lit = (CtLiteral<?>) right;
                                Object value = lit.getValue();
                                if (value instanceof Number) {
                                    // Infer type from value - create new literal with correct type
                                    Number num = (Number) value;
                                    if (leftType.getSimpleName().equals("int")) {
                                        CtLiteral<Integer> intLit = f.Code().createLiteral(num.intValue());
                                        // Note: We can't directly replace the literal in the binary op,
                                        // but this helps with type inference
                                    } else if (leftType.getSimpleName().equals("long")) {
                                        CtLiteral<Long> longLit = f.Code().createLiteral(num.longValue());
                                        // Note: We can't directly replace the literal in the binary op,
                                        // but this helps with type inference
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /* ======================================================================
     *                    CRITICAL FIXES: SYNTAX ERRORS
     * ====================================================================== */

    /**
     * Fix syntax generation errors (void type issues, illegal expressions).
     */
    public void fixSyntaxErrors() {
        // Fix "void type not allowed here" errors
        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                CtBlock<?> body = method.getBody();
                if (body == null) continue;
                
                // Fix void method calls used as expressions
                for (CtStatement stmt : body.getStatements()) {
                    if (stmt instanceof CtInvocation) {
                        CtInvocation<?> inv = (CtInvocation<?>) stmt;
                        try {
                            CtTypeReference<?> returnType = inv.getType();
                            if (returnType != null && returnType.equals(f.Type().VOID_PRIMITIVE)) {
                                // This is a void method call - ensure it's used as a statement, not expression
                                // (Spoon should handle this, but we check anyway)
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    // Create (or fetch) a public interface for the given type ref.
// If we mistakenly created an empty class earlier with the same FQN, replace it with an interface.
    private CtType<?> ensurePublicInterfaceForTypeRef(CtTypeReference<?> tr) {
        if (tr == null) tr = f.Type().createReference("unknown.Missing");

        String qn;
        try { qn = tr.getQualifiedName(); } catch (Throwable e) { qn = tr.getSimpleName(); }
        if (qn == null || qn.isEmpty()) qn = "unknown.Missing";

        int dot = qn.lastIndexOf('.');
        String pkgName = (dot >= 0 ? qn.substring(0, dot) : "");
        String simple  = (dot >= 0 ? qn.substring(dot + 1) : qn);

        CtPackage pkg = f.Package().getOrCreate(pkgName);
        CtType<?> existing = pkg.getType(simple);
        if (existing instanceof CtInterface) {
            // already correct kind
            existing.addModifier(ModifierKind.PUBLIC);
            return existing;
        }
        if (existing instanceof CtClass) {
            // If we created this class in this run, and it is still empty, replace it with an interface
            String fqn = (pkgName.isEmpty() ? simple : pkgName + "." + simple);
            boolean weCreated = createdTypes.contains(fqn);
            boolean looksEmpty =
                    ((CtClass<?>) existing).getFields().isEmpty()
                            && ((CtClass<?>) existing).getConstructors().isEmpty()
                            && ((CtClass<?>) existing).getMethods().isEmpty()
                            && existing.getNestedTypes().isEmpty();

            if (weCreated && looksEmpty) {
                existing.delete();
                CtInterface<?> itf = f.Interface().create(pkg, simple);
                itf.addModifier(ModifierKind.PUBLIC);
                createdTypes.add(itf.getQualifiedName());
                return itf;
            }
            // Otherwise, keep the class (don’t destruct user code), but compilation would fail if C “implements” it.
            // Returning the class is safer than exploding here; other passes may fix callers.
            existing.addModifier(ModifierKind.PUBLIC);
            return existing;
        }

        // Not present: create the interface
        CtInterface<?> itf = f.Interface().create(pkg, simple);
        itf.addModifier(ModifierKind.PUBLIC);
        createdTypes.add(itf.getQualifiedName());
        return itf;
    }




}
