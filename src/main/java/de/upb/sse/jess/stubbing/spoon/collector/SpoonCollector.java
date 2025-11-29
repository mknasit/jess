package de.upb.sse.jess.stubbing.spoon.collector;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.exceptions.AmbiguityException;
import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.reflect.visitor.Filter;

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
    }

    /* ======================================================================
     *                               FIELDS
     * ====================================================================== */

    private final Factory f;
    private final JessConfiguration cfg;
    private final java.nio.file.Path slicedSrcDir;
    private final Set<String> sliceTypeFqns;

    // Centralized unknown type FQN constant. (Do not rename or remove.)
    private static final String UNKNOWN_TYPE_FQN = de.upb.sse.jess.generation.unknown.UnknownType.CLASS;

    /* ======================================================================
     *                             CONSTRUCTION
     * ====================================================================== */

    /**
     * Constructs a SpoonCollector bound to a Spoon Factory and the Jess configuration.
     * @param f Factory
     * @param cfg Configuration
     * @param slicedSrcDir The sliced source directory (only process types from here)
     * @param sliceTypeFqns Set of FQNs for types in the slice (for filtering)
     */
    public SpoonCollector(Factory f, JessConfiguration cfg, java.nio.file.Path slicedSrcDir, Set<String> sliceTypeFqns) {
        this.f = f;
        this.cfg = cfg;
        this.slicedSrcDir = slicedSrcDir;
        this.sliceTypeFqns = sliceTypeFqns != null ? sliceTypeFqns : new HashSet<>();
    }
    
    /**
     * Legacy constructor for backward compatibility.
     */
    public SpoonCollector(Factory f, JessConfiguration cfg) {
        this(f, cfg, null, new HashSet<>());
    }
    
    /**
     * Check if an element is from the slice directory (should be processed).
     * An element is considered from the slice if:
     * 1. Its source file is in the slice directory, OR
     * 2. Its parent type (or any ancestor type) is a slice type
     */
    private boolean isFromSlice(CtElement element) {
        if (slicedSrcDir == null || sliceTypeFqns == null || sliceTypeFqns.isEmpty()) {
            return true; // If no filtering info, process everything (backward compat)
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

        // --- order matters only for readability; each pass is independent ---
        collectUnresolvedFields(model, result);
        collectUnresolvedCtorCalls(model, result);
        collectUnresolvedMethodCalls(model, result);
        collectUnresolvedAnnotations(model, result);

        collectExceptionTypes(model, result);
        collectSupertypes(model, result);

        collectFromInstanceofCastsClassLiteralsAndForEach(model, result);
        collectUnresolvedDeclaredTypes(model, result);
        collectAnnotationTypeUsages(model, result);
        collectOverloadGaps(model, result);

        seedOnDemandImportAnchors(model, result);
        seedExplicitTypeImports(model, result);

        // Ensure owners exist for any planned members / references discovered above.
        result.typePlans.addAll(ownersNeedingTypes(result));
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
            return ref == null || ref.getDeclaration() == null;
        });

        for (CtFieldAccess<?> fa : unresolved) {
            // Ignore class-literals like Foo.class (modeled as a field 'class').
            String vname = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : null);
            if ("class".equals(vname) && fa.getTarget() instanceof CtTypeAccess) continue;

            // Ignore enum constants inside annotations.
            if (fa.getParent(CtAnnotation.class) != null) continue;

            // Standalone 'x.f;' is ambiguous in strict mode.
            if (isStandaloneFieldStatement(fa)) {
                if (cfg.isFailOnAmbiguity()) {
                    String owner = Optional.ofNullable(resolveOwnerTypeFromFieldAccess(fa))
                            .map(CtTypeReference::getQualifiedName).orElse("<unknown>");
                    String name = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "<missing>");
                    throw new AmbiguityException("Ambiguous field access with no type context: " + owner + "#" + name);
                } else {
                    continue; // lenient: skip
                }
            }

            boolean isStatic = fa.getTarget() instanceof CtTypeAccess<?>;
            CtTypeReference<?> rawOwner = resolveOwnerTypeFromFieldAccess(fa);
            CtTypeReference<?> ownerRef = chooseOwnerPackage(rawOwner, fa);
            if (isJdkType(ownerRef)) continue;

            String fieldName = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "f");
            CtTypeReference<?> fieldType = inferFieldTypeFromUsage(fa);

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
            try {
                CtTypeReference<?> t = fa.getTarget().getType();
                CtTypeReference<?> base = componentOf(t);
                return (base != null ? chooseOwnerPackage(base, fa) : chooseOwnerPackage(t, fa));
            } catch (Throwable ignored) {}
        }

        String simple = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "Owner");
        return f.Type().createReference("unknown." + simple);
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
            return ex == null || ex.getDeclaration() == null;
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

                    out.typePlans.add(new TypeStubPlan(outerFqn, TypeStubPlan.Kind.CLASS));
                    out.typePlans.add(new TypeStubPlan(outerFqn + "$" + innerSimple, TypeStubPlan.Kind.CLASS));
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
        List<CtInvocation<?>> unresolved = getElementsFromSliceTypes(model, (CtInvocation<?> inv) -> {
            var ex = inv.getExecutable();
            return ex == null || ex.getDeclaration() == null;
        });

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

            if (owner == null || "unknown.Missing".equals(safeQN(owner)) || inv.getTarget() == null) {
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

            List<CtTypeReference<?>> paramTypes = inferParamTypesFromCall(ex, inv.getArguments());

     

            List<CtTypeReference<?>> fromRef = (ex != null ? ex.getParameters() : Collections.emptyList());
            boolean refSane = fromRef != null && !fromRef.isEmpty() && fromRef.stream().allMatch(this::isSaneType);
            if (!refSane && argsContainNullLiteral(inv.getArguments()) && cfg.isFailOnAmbiguity()) {
                String ownerQN = owner != null ? owner.getQualifiedName() : "<unknown>";
                throw new AmbiguityException("Ambiguous method parameters (null argument): " + ownerQN + "#" + name + "(...)");
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
        return f.Type().createReference("unknown.Missing");
    }

    /**
     * Infer a method invocation's return type from surrounding context (assignment, arg, concat, etc.).
     */
    private CtTypeReference<?> inferReturnTypeFromContext(CtInvocation<?> inv) {
        CtElement p = inv.getParent();

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

        if (p instanceof CtBinaryOperator) {
            CtBinaryOperator<?> bo = (CtBinaryOperator<?>) p;
            if (bo.getKind() == BinaryOperatorKind.PLUS) {
                CtExpression<?> other =
                        Objects.equals(bo.getLeftHandOperand(), inv)
                                ? bo.getRightHandOperand()
                                : bo.getLeftHandOperand();
                if (isStringy(other)) return f.Type().STRING;
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
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * Infer parameter types for a call: prefer executable signature if sane; otherwise derive from args.
     */
    private List<CtTypeReference<?>> inferParamTypesFromCall(CtExecutableReference<?> ex,
                                                             List<CtExpression<?>> args) {
        List<CtTypeReference<?>> fromRef = (ex != null ? ex.getParameters() : Collections.emptyList());
        if (fromRef != null && !fromRef.isEmpty() && fromRef.stream().allMatch(this::isSaneType)) {
            return fromRef;
        }
        return args.stream().map(this::paramTypeOrObject).collect(Collectors.toList());
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
            out.typePlans.add(new TypeStubPlan(annFqn, TypeStubPlan.Kind.ANNOTATION));

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
                    out.typePlans.add(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION));
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
                    out.typePlans.add(new TypeStubPlan(resolved.getQualifiedName(), TypeStubPlan.Kind.ANNOTATION));
                    // Also plan container (Tag -> Tags) in same pkg, as annotation
                    String pkg = resolved.getPackage() == null ? "" : resolved.getPackage().getQualifiedName();
                    String simple = resolved.getSimpleName();
                    String containerSimple = simple.endsWith("s") ? simple + "es" : simple + "s";
                    String containerFqn = (pkg.isEmpty() ? containerSimple : pkg + "." + containerSimple);
                    out.typePlans.add(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION));
                    continue;
                }
            }

            // Otherwise, keep whatever we have if it’s non-JDK
            if (!isJdkType(at)) {
                out.typePlans.add(new TypeStubPlan((qn.isEmpty() ? "unknown."+at.getSimpleName() : qn),
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
                out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                    out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                    List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(owner, ps));
                }
            } else if (ex != null) {
                try {
                    CtTypeReference<?> t = ex.getType();
                    if (t != null && !isJdkType(t) && t.getDeclaration() == null) {
                        CtTypeReference<?> owner = chooseOwnerPackage(t, thr);
                        out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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

        if (!qn.contains(".")) {
            CtTypeReference<?> explicit = resolveFromExplicitTypeImports(ctx, simple);
            if (explicit != null) {
                out.typePlans.add(new TypeStubPlan(explicit.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                return;
            }

            List<String> starPkgs = starImportsInOrder(ctx);
            List<String> nonUnknown = starPkgs.stream()
                    .filter(p -> !"unknown".equals(p))
                    .collect(java.util.stream.Collectors.toList());

            if (nonUnknown.size() > 1 && cfg.isFailOnAmbiguity()) {
                throw new AmbiguityException("Ambiguous simple type '" + simple + "' from on-demand imports: " + nonUnknown);
            }
            if (!nonUnknown.isEmpty()) {
                out.typePlans.add(new TypeStubPlan(nonUnknown.get(0) + "." + simple, TypeStubPlan.Kind.CLASS));
            } else {
                out.typePlans.add(new TypeStubPlan("unknown." + simple, TypeStubPlan.Kind.CLASS));
            }
            return;
        }

       // out.typePlans.add(new TypeStubPlan(qn, TypeStubPlan.Kind.CLASS));
        String nestedFqn = nestedAwareFqnOf(t);
        out.typePlans.add(new TypeStubPlan(nestedFqn, TypeStubPlan.Kind.CLASS));

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
            for (CtType<?> sliceType : sliceTypes) {
                try {
                    // Get elements from this slice type only (recursive search within the type)
                    Filter<T> typeFilter = new Filter<T>() {
                        @Override
                        public boolean matches(T element) {
                            // Skip module elements
                            if (element instanceof spoon.reflect.declaration.CtModule) {
                                return false;
                            }
                            // Test the predicate
                            return predicate.test(element);
                        }
                    };
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
                out.typePlans.add(new TypeStubPlan(pkg + ".PackageAnchor", TypeStubPlan.Kind.CLASS));
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
     */

    private CtTypeReference<?> chooseOwnerPackage(CtTypeReference<?> ownerRef, CtElement ctx) {

        if (ownerRef == null) return f.Type().createReference("unknown.Missing");

        String qn = safeQN(ownerRef);

        // Treat assumed-local qualified refs as simple to allow re-qualification from star imports.
        if (qn.contains(".") && isLocallyAssumedOrSimple(ownerRef, ctx)) {
            ownerRef = f.Type().createReference(ownerRef.getSimpleName());
            qn = ownerRef.getQualifiedName();
        }

        if (qn.contains(".")) return ownerRef;

        // ---- NEW: prefer explicit single-type import if present ----
        String simple = Optional.ofNullable(ownerRef.getSimpleName()).orElse("Missing");
        CtTypeReference<?> explicit = resolveFromExplicitTypeImports(ctx, simple);
        if (explicit != null) {
            return explicit; // e.g., ext.outer.Outer
        }
        // ------------------------------------------------------------

        List<String> starPkgs = starImportsInOrder(ctx);

        if (starPkgs.contains("unknown")) return f.Type().createReference("unknown." + simple);

        if (starPkgs.size() == 1) return f.Type().createReference(starPkgs.get(0) + "." + simple);

        if (starPkgs.size() > 1) {
            if (cfg.isFailOnAmbiguity()) {
                throw new AmbiguityException("Ambiguous simple type '" + simple + "' from on-demand imports: " + starPkgs);
            } else {
                return f.Type().createReference(starPkgs.get(0) + "." + simple);
            }
        }

        return f.Type().createReference("unknown." + simple);
    }

    /**
     * Resolve an explicit single-type import to a concrete type reference, if present.
     */
    private CtTypeReference<?> resolveFromExplicitTypeImports(CtElement ctx, String simple) {
        var type = ctx.getParent(CtType.class);
        var pos = (type != null ? type.getPosition() : null);
        var cu = (pos != null ? pos.getCompilationUnit() : null);
        if (cu == null) return null;

        for (CtImport imp : cu.getImports()) {
            if (imp.getImportKind() == CtImportKind.TYPE) {
                try {
                    var ref = imp.getReference();
                    if (ref instanceof CtTypeReference) {
                        String qn = ((CtTypeReference<?>) ref).getQualifiedName();
                        if (qn != null && qn.endsWith("." + simple)) {
                            return f.Type().createReference(qn);
                        }
                    }
                } catch (Throwable ignored) { }
            }
        }
        return null;
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
            CompilationUnit cu = (pos != null ? pos.getCompilationUnit() : null);
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
                out.typePlans.add(new TypeStubPlan(fqn, TypeStubPlan.Kind.CLASS));
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

            out.methodPlans.add(new MethodStubPlan(owner, name, returnType, paramTypes, isStatic, vis, thrown));
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
                    out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                }
            }
            for (CtTypeReference<?> si : safe(c.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, c);
                if (owner != null && !isJdkType(owner)) {
                    out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.INTERFACE));
                }
            }
        }

        // interfaces: superinterfaces
        for (CtInterface<?> i : getElementsFromSliceTypes(model, (CtInterface<?> ii) -> true)) {
            for (CtTypeReference<?> si : safe(i.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, i);
                if (owner != null && !isJdkType(owner)) {
                    out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.INTERFACE));
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
                        out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
                        out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
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
        if (arg == null) return f.Type().createReference(UNKNOWN_TYPE_FQN);

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




}
