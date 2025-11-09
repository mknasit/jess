package de.upb.sse.jess.stubbing.spoon.collector;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.exceptions.AmbiguityException;
import de.upb.sse.jess.generation.unknown.UnknownType;
import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;

import java.lang.annotation.Repeatable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import spoon.reflect.declaration.CtAnonymousExecutable;

/**
 * Collects all stub plans (types, fields, constructors, methods) needed to make a sliced set of
 * Java sources compile, under Spoon --no-classpath scenarios.
 * <p>
 * NOTE: Names, signatures, and logic are intentionally preserved; this version only restructures
 * and documents the code for clarity and maintainability.
 */
public final class SpoonCollector {

    /* ======================================================================
     *                               NESTED TYPES
     * ====================================================================== */

    /**
     * Aggregates all plans found during collection.
     */
    public static final class CollectResult {
        public final List<TypeStubPlan> typePlans = new ArrayList<>();
        public final List<FieldStubPlan> fieldPlans = new ArrayList<>();
        public final List<ConstructorStubPlan> ctorPlans = new ArrayList<>();
        public final List<MethodStubPlan> methodPlans = new ArrayList<>();
        public final Set<String> ambiguousSimples = new LinkedHashSet<>();
        public final Map<String, Set<CtTypeReference<?>>> implementsPlans = new LinkedHashMap<>();
        // de.upb.sse.jess.stubbing.spoon.collector.CollectResult
        public Map<String, String> unknownToConcrete = new HashMap<>();



    }

    // --- Minimal JDK simple-name → package map (extend as needed) ---
    private static final Map<String,String> JDK_SIMPLE = new HashMap<>();
    static {
        // lang
        JDK_SIMPLE.put("String", "java.lang");
        JDK_SIMPLE.put("Object", "java.lang");
        JDK_SIMPLE.put("Throwable", "java.lang");
        JDK_SIMPLE.put("Exception", "java.lang");
        JDK_SIMPLE.put("RuntimeException", "java.lang");
        JDK_SIMPLE.put("Iterable", "java.lang"); // special-cased below to java.lang.Iterable
        // util
        JDK_SIMPLE.put("List", "java.util");
        JDK_SIMPLE.put("ArrayList", "java.util");
        JDK_SIMPLE.put("Map", "java.util");
        JDK_SIMPLE.put("HashMap", "java.util");
        JDK_SIMPLE.put("Set", "java.util");
        JDK_SIMPLE.put("HashSet", "java.util");
        JDK_SIMPLE.put("Optional", "java.util");
        JDK_SIMPLE.put("Iterator", "java.util");
        JDK_SIMPLE.put("Collection", "java.util");
        // nio
        JDK_SIMPLE.put("Path", "java.nio.file");
        JDK_SIMPLE.put("Files", "java.nio.file");
        // time
        JDK_SIMPLE.put("Instant", "java.time");
        JDK_SIMPLE.put("Duration", "java.time");
        // regex
        JDK_SIMPLE.put("Pattern", "java.util.regex");
        JDK_SIMPLE.put("Matcher", "java.util.regex");
    }

    private static boolean isKnownJdkSimple(String simple) {
        return JDK_SIMPLE.containsKey(simple);
    }

    /* ======================================================================
     *                               FIELDS
     * ====================================================================== */

    private final Factory f;
    private final JessConfiguration cfg;

    // Centralized unknown type FQN constant. (Do not rename or remove.)
    private static final String UNKNOWN_TYPE_FQN = UnknownType.CLASS;

    /* ======================================================================
     *                             CONSTRUCTION
     * ====================================================================== */

    /**
     * Constructs a SpoonCollector bound to a Spoon Factory and the Jess configuration.
     */
    public SpoonCollector(Factory f, JessConfiguration cfg) {
        this.f = f;
        this.cfg = cfg;
    }

    /* ======================================================================
     *                                DRIVER
     * ====================================================================== */

    /**
     * Main entry: scan the model and produce an aggregated set of stub plans.
     */
    public CollectResult collect(CtModel model) {
        CollectResult result = new CollectResult();

        ensureRepeatablesForDuplicateUses(model);
        rebindUnknownHomonyms(model,result);
        // --- order matters only for readability; each pass is independent ---
        collectTryWithResources(model, result);
        collectUnresolvedFields(model, result);
        collectUnresolvedCtorCalls(model, result);
        collectForEachLoops(model, result);
        collectStreamApiMethods(model, result);  // Collect Stream API methods (stream(), forEach, map, filter, etc.)
        collectMethodReferences(model, result);  // Collect method references FIRST (creates functional interface SAM methods)
        collectUnresolvedMethodCalls(model, result);  // Collect method calls (may create SAM methods from calls like f.apply(1))
        collectLambdas(model, result);  // Collect lambdas LAST and remove/replace any existing SAM methods with correct signature from lambda
        collectUnresolvedAnnotations(model, result);

        collectExceptionTypes(model, result);
        collectSupertypes(model, result);

        collectFromInstanceofCastsClassLiteralsAndForEach(model, result);
        collectUnresolvedDeclaredTypes(model, result);
        collectAnnotationTypeUsages(model, result);
        collectOverloadGaps(model, result);  // This runs AFTER collectLambdas, so it might re-add methods - need to prevent duplicates

        seedOnDemandImportAnchors(model, result);
        seedExplicitTypeImports(model, result);

        // Final cleanup: Remove duplicate SAM methods from functional interfaces
        // This ensures that functional interfaces have only ONE abstract method
        // Even if different collection phases added methods with different parameter types (int vs Integer)
        removeDuplicateSamMethods(result);

        // Ensure owners exist for any planned members / references discovered above.
        result.typePlans.addAll(ownersNeedingTypes(result));

        // --- compute scoped ambiguous simple names ---
        Map<String, Set<String>> simpleToPkgs = new HashMap<>();


// (1) add existing model types
        model.getAllTypes().forEach(t -> {
            String qn = safeQN(t.getReference());
            if (qn == null) return;
            int lastDot = qn.lastIndexOf('.');
            String pkg = (lastDot >= 0 ? qn.substring(0, lastDot) : "");
            String simple = t.getSimpleName();
            if (simple == null) return;
            if (isJdkPackage(pkg)) return;
            simpleToPkgs.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(pkg);
        });

// (2) add planned owner types (where we will create/mirror)
        Stream.concat(
                result.typePlans.stream().map(tp -> tp.qualifiedName),
                Stream.concat(
                        result.fieldPlans.stream().map(fp -> fp.ownerType != null ? fp.ownerType.getQualifiedName() : null),
                        Stream.concat(
                                result.ctorPlans.stream().map(cp -> cp.ownerType != null ? cp.ownerType.getQualifiedName() : null),
                                result.methodPlans.stream().map(mp -> mp.ownerType != null ? mp.ownerType.getQualifiedName() : null)
                        )
                )
        ).filter(Objects::nonNull).forEach(qn -> {
            int lastDot = qn.lastIndexOf('.');
            String pkg = (lastDot >= 0 ? qn.substring(0, lastDot) : "");
            String simple = (lastDot >= 0 ? qn.substring(lastDot + 1) : qn);
            if (isJdkPackage(pkg)) return;
            simpleToPkgs.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(pkg);
        });

// (3) keep only those simples that are (a) part of our plans and (b) map to >1 pkgs
        Set<String> plannedSimples = new HashSet<>();
        result.typePlans.forEach(tp -> { String qn = tp.qualifiedName; if (qn != null) plannedSimples.add(qn.substring(qn.lastIndexOf('.')+1)); });
        result.fieldPlans.forEach(fp -> { if (fp.ownerType != null) plannedSimples.add(fp.ownerType.getSimpleName()); });
        result.ctorPlans.forEach(cp -> { if (cp.ownerType != null) plannedSimples.add(cp.ownerType.getSimpleName()); });
        result.methodPlans.forEach(mp -> { if (mp.ownerType != null) plannedSimples.add(mp.ownerType.getSimpleName()); });

        simpleToPkgs.forEach((simple, pkgs) -> {
            if (pkgs.size() > 1 && plannedSimples.contains(simple)) {
                result.ambiguousSimples.add(simple);
            }
        });

        preferConcreteOverUnknown(result);
        return result;
    }

    /* ======================================================================
     *                              FIELDS PASS
     * ====================================================================== */

    /**
     * Collect field stubs from unresolved field accesses (including static and instance cases).
     */
    private void collectUnresolvedFields(CtModel model, CollectResult out) {
        List<CtFieldAccess<?>> unresolved = model.getElements((CtFieldAccess<?> fa) -> {
            var ref = fa.getVariable();
            return ref == null || ref.getDeclaration() == null;
        });

        for (CtFieldAccess<?> fa : unresolved) {
            if (fa.getParent(CtExecutableReferenceExpression.class) != null) {
                continue; // In A::inc the 'A' token isn't a field; it's a type name
            }
            String simple = null;
            try { simple = fa.getVariable().getSimpleName(); } catch (Throwable ignored) {}
            if (simple != null) {
                boolean looksTypeish = Character.isUpperCase(simple.charAt(0));
                if (looksTypeish && isTypeVisibleInScope(fa, simple)) {
                    // It's almost certainly a type token that got misclassified as a field
                    continue;
                }
            }
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
                    String simplename = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "<missing>");
                   throw new AmbiguityException("Ambiguous field (no usable type context): " + ownerQN + "#" + simplename);
                }
                fieldType = f.Type().createReference(UNKNOWN_TYPE_FQN);
            }

            out.fieldPlans.add(new FieldStubPlan(ownerRef, fieldName, fieldType, isStatic));
        }
    }

    private boolean isTypeVisibleInScope(CtElement ctx, String simple) {
        if (simple == null || simple.isEmpty()) return false;
        final CtModel model = f.getModel();

        // 1) type exists in the model (non-unknown)
        boolean existsInModel = model.getAllTypes().stream().anyMatch(t ->
                simple.equals(t.getSimpleName())
                        && t.getPackage() != null
                        && !"unknown".equals(t.getPackage().getQualifiedName())
        );
        if (existsInModel) return true;

        // 2) type with same simple name exists in the current CU's package
        try {
            CtType<?> host = ctx.getParent(CtType.class);
            if (host != null && host.getPackage() != null) {
                String curPkg = host.getPackage().getQualifiedName();
                boolean inPkg = model.getAllTypes().stream().anyMatch(t ->
                        simple.equals(t.getSimpleName())
                                && t.getPackage() != null
                                && curPkg.equals(t.getPackage().getQualifiedName())
                );
                if (inPkg) return true;
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
            try {
                arrType = ((CtExpression<?>) aa.getTarget()).getType();
            } catch (Throwable ignored) {
            }
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
            } catch (Throwable ignored) {
            }
        }

        if (fa.getTarget() != null) {
            try {
                CtTypeReference<?> t = fa.getTarget().getType();
                CtTypeReference<?> base = componentOf(t);
                return (base != null ? chooseOwnerPackage(base, fa) : chooseOwnerPackage(t, fa));
            } catch (Throwable ignored) {
            }
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
                try {
                    return ((CtExpression<?>) as.getAssigned()).getType();
                } catch (Throwable ignored) {
                }
            }
            if (Objects.equals(as.getAssigned(), fa)) {
                try {
                    return ((CtExpression<?>) as.getAssignment()).getType();
                } catch (Throwable ignored) {
                }
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
                    } catch (Throwable ignored) {
                    }
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
            try {
                return ((CtExpression<?>) ((CtAssignment<?, ?>) gp).getAssigned()).getType();
            } catch (Throwable ignored) {
            }
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
                    } catch (Throwable ignored) {
                    }
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
        // unresolved constructor calls
        var unresolved = model.getElements((CtConstructorCall<?> cc) -> {
            var ex = cc.getExecutable();
            return ex == null || ex.getDeclaration() == null;
        });

        for (CtConstructorCall<?> cc : unresolved) {
            CtTypeReference<?> rawOwner = cc.getType();

            // ---------- MEMBER-INNER new Outer().new Inner(...) ----------
            CtExpression<?> targetExpr = null;
            try {
                // Spoon 10.x has getTarget()
                targetExpr = (CtExpression<?>) cc.getClass().getMethod("getTarget").invoke(cc);
            } catch (Throwable ignore) {
                try {
                    // fallback on older variants
                    targetExpr = (CtExpression<?>) cc.getClass()
                            .getMethod("getEnclosingExpression")
                            .invoke(cc);
                } catch (Throwable ignored) {}
            }

            if (targetExpr != null) {
                CtTypeReference<?> outerT = null;
                try { outerT = targetExpr.getType(); } catch (Throwable ignored) {}

                if (outerT != null) {
                    CtTypeReference<?> resolvedOuter = chooseOwnerPackage(outerT, cc);
                    String outerFqn = safeQN(resolvedOuter);
                    if (outerFqn == null || outerFqn.isEmpty()) {
                        outerFqn = resolvedOuter.getQualifiedName();
                    }
                    String innerSimple = rawOwner.getSimpleName();

                    CtTypeReference<?> memberOwner = f.Type().createReference(outerFqn + "$" + innerSimple);

                    // params + functional coercion
                    List<CtTypeReference<?>> ps =
                            inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
                    List<CtExpression<?>> cargs = cc.getArguments();
                    for (int i = 0; i < cargs.size() && i < ps.size(); i++) {
                        CtExpression<?> a = cargs.get(i);
                        CtTypeReference<?> expected = ps.get(i);
                        CtTypeReference<?> coerced = coerceFunctionalToTarget(a, expected);
                        if (coerced != expected) ps.set(i, coerced);
                    }

                    // plan types + ctor
                    // Mark inner class as non-static since it's used with o.new Inner() syntax
                    addTypePlanIfNonJdk(out, outerFqn, TypeStubPlan.Kind.CLASS);
                    String innerFqn = outerFqn + "$" + innerSimple;
                    if (!isJdkFqn(innerFqn)) {
                        out.typePlans.add(new TypeStubPlan(innerFqn, TypeStubPlan.Kind.CLASS, true)); // non-static inner
                    }

                    out.ctorPlans.add(new ConstructorStubPlan(memberOwner, ps));
                    // do NOT fall through to generic path
                    continue;
                }
            }
            // -------------------------------------------------------------

            // --- infer superinterface from assignment/var-init target ---
            CtTypeReference<?> targetType = null;
            CtElement p = cc.getParent();
            if (p instanceof CtVariable && Objects.equals(((CtVariable<?>) p).getDefaultExpression(), cc)) {
                targetType = ((CtVariable<?>) p).getType();
            } else if (p instanceof CtAssignment && Objects.equals(((CtAssignment<?, ?>) p).getAssignment(), cc)) {
                try {
                    targetType = ((CtExpression<?>) ((CtAssignment<?, ?>) p).getAssigned()).getType();
                } catch (Throwable ignored) {}
            }

            CtTypeReference<?> owner = chooseOwnerPackage(rawOwner, cc);

            // Only enqueue "implements" when: owner is concrete/non-unknown AND target looks like interface/unknown
            if (owner != null && !safeQN(owner).startsWith("unknown.")
                    && targetType != null && isSaneType(targetType) && !isJdkType(targetType)) {

                boolean looksUnknown = safeQN(targetType).startsWith("unknown.");
                boolean isInterface = false;
                try { isInterface = targetType.getTypeDeclaration() instanceof CtInterface; } catch (Throwable ignored) {}

                // skip self (by erasure)
                boolean isSelf = erasureFqn(targetType).equals(erasureFqn(owner));

                if ((isInterface || looksUnknown) && !isSelf) {
                    var set = out.implementsPlans
                            .computeIfAbsent(owner.getQualifiedName(), k -> new LinkedHashSet<>());

                    String newErasure = erasureFqn(targetType);
                    CtTypeReference<?> existingRaw = set.stream()
                            .filter(r -> erasureFqn(r).equals(newErasure))
                            .findFirst().orElse(null);

                    boolean newIsParam = !targetType.getActualTypeArguments().isEmpty();
                    boolean oldIsParam = existingRaw != null && !existingRaw.getActualTypeArguments().isEmpty();

                    if (existingRaw == null) {
                        set.add(targetType.clone());
                    } else if (newIsParam && !oldIsParam) {
                        set.remove(existingRaw);
                        set.add(targetType.clone()); // upgrade raw → parameterized
                    }
                }
            }

            // --- normal ctor stubbing ---
            if (owner != null && !isJdkType(owner)) {
                collectTypeRefDeep(cc, rawOwner, out);
                List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());

                // functional coercion for ctor params
                List<CtExpression<?>> cargs = cc.getArguments();
                for (int i = 0; i < cargs.size() && i < ps.size(); i++) {
                    CtExpression<?> a = cargs.get(i);
                    CtTypeReference<?> expected = ps.get(i);
                    CtTypeReference<?> coerced = coerceFunctionalToTarget(a, expected);
                    if (coerced != expected) ps.set(i, coerced);
                }

                // preserve nested owners ($)
                String ownerFqn = nestedAwareFqnOf(owner);
                out.ctorPlans.add(new ConstructorStubPlan(f.Type().createReference(ownerFqn), ps));
            }
        }
    }



    /* ======================================================================
     *                             METHODS PASS
     * ====================================================================== */

    @SuppressWarnings("unchecked")
    private void collectUnresolvedMethodCalls(CtModel model, CollectResult out) {

        // --- small local helpers -------------------------------------------------
        Function<CtInvocation<?>, Boolean> targetExplicitlyUnknown = (inv) -> {
            CtExpression<?> tgt = inv.getTarget();
            try {
                if (tgt instanceof CtTypeAccess<?>) {
                    CtTypeReference<?> tr = ((CtTypeAccess<?>) tgt).getAccessedType();
                    String qn = safeQN(tr);
                    if (qn != null && qn.startsWith("unknown.")) return true;
                    try {
                        var pkg = (tr != null && tr.getPackage() != null) ? tr.getPackage().getQualifiedName() : null;
                        if ("unknown".equals(pkg)) return true;
                    } catch (Throwable ignored) {
                    }
                    String sn = (tr != null ? tr.getSimpleName() : null);
                    if (sn != null) {
                        // if a type unknown.<SimpleName> exists in the model, prefer unknown.*
                        CtType<?> unk = f.Type().get("unknown." + sn);
                        if (unk != null) return true;
                    }
                }
                // textual fallback
                String ts = (tgt != null ? tgt.toString() : "");
                if (ts.startsWith("unknown.")) return true;
            } catch (Throwable ignored) {
            }
            return false;
        };

        Function<CtInvocation<?>, String> typeAccessSimpleName = (inv) -> {
            CtExpression<?> tgt = inv.getTarget();
            if (tgt instanceof CtTypeAccess<?>) {
                CtTypeReference<?> tr = ((CtTypeAccess<?>) tgt).getAccessedType();
                if (tr != null && tr.getSimpleName() != null) return tr.getSimpleName();
            }
            return null;
        };
        // ------------------------------------------------------------------------

        List<CtInvocation<?>> unresolved = model.getElements((CtInvocation<?> inv) -> {
            CtExecutableReference<?> ex = inv.getExecutable();
            return ex == null || ex.getDeclaration() == null;
        });

        for (CtInvocation<?> inv : unresolved) {
            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : null);
            // decide staticness & varargs early

            boolean makeStatic =
                    // 1) static context (static field init, static block, static method)
                    isInStaticContext(inv)
                            // 2) explicit Type::call (receiver is a type)
                            || (inv.getTarget() instanceof CtTypeAccess<?>)
                            // 3) static import call: method() with no target but resolved from a static import owner
                            || (inv.getTarget() == null && resolveOwnerFromStaticImports(inv, name) != null);
            boolean makeVarargs = looksLikeVarargs(inv);

            // constructors
            if ("<init>".equals(name)) {
                CtTypeReference<?> ownerForCtor = chooseOwnerPackage(resolveOwnerTypeFromInvocation(inv), inv);
                if (!isJdkType(ownerForCtor)) {
                    List<CtTypeReference<?>> ps = inferParamTypesFromCall(ex, inv.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(ownerForCtor, ps));
                }
                continue;
            }


            // --- owner from call-site -------------------------------------------
            CtExpression<?> tgt = inv.getTarget();
            String callOwnerFqn = null;
            if (tgt instanceof CtTypeAccess<?>) {
                try {
                    callOwnerFqn = safeQN(((CtTypeAccess<?>) tgt).getAccessedType());
                } catch (Throwable ignored) {
                }
            }
            if (callOwnerFqn == null && tgt == null) {
                CtTypeReference<?> fromStatic = resolveOwnerFromStaticImports(inv, name);
                if (fromStatic != null) callOwnerFqn = safeQN(fromStatic);
            }
// --- NEW: raw-target override to pin unknown.* ---
            String rawTarget = (tgt != null ? tgt.toString() : null);
// examples rawTarget: "unknown.FS", "pkg.Cls"
            if (rawTarget != null && rawTarget.startsWith("unknown.")) {
                callOwnerFqn = rawTarget; // hard override
            }

            boolean explicitUnknown = targetExplicitlyUnknown.apply(inv);
            if (explicitUnknown && (callOwnerFqn == null || !callOwnerFqn.startsWith("unknown."))) {
                // synthesize unknown.<SimpleName> when Spoon didn’t give us a QN
                String sn = typeAccessSimpleName.apply(inv);
                if (sn != null) callOwnerFqn = "unknown." + sn;
            }
            boolean ownerIsUnknown = (callOwnerFqn != null && callOwnerFqn.startsWith("unknown."));

            // --- force owner when slicer used unknown.* --------------------------
            CtTypeReference<?> owner = null;
            boolean defaultOnIface = false;   // never for unknown.*
            if (ownerIsUnknown) {
                owner = f.Type().createReference(callOwnerFqn);
            } else {
                CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
                
                // Special handling for field access targets: preserve the field's declared type
                // This prevents cases where logger (org.example.Log) gets changed to inheritance2.Log
                if (tgt instanceof CtFieldAccess<?>) {
                    CtFieldAccess<?> fa = (CtFieldAccess<?>) tgt;
                    try {
                        // Try multiple ways to get the field's actual declared type
                        CtTypeReference<?> fieldType = null;
                        String fieldName = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : null);
                        System.err.println("[collectUnresolvedMethodCalls] Processing field access: " + fieldName);
                        
                        // Priority 1: Try from the field's declaration (most reliable for inherited fields)
                        if (fa.getVariable() != null) {
                            try {
                                CtField<?> fieldDecl = fa.getVariable().getDeclaration();
                                if (fieldDecl != null && fieldDecl.getType() != null) {
                                    fieldType = fieldDecl.getType();
                                    String fieldTypeQn = safeQN(fieldType);
                                    System.err.println("[collectUnresolvedMethodCalls] Field declaration type (raw): " + fieldTypeQn + 
                                        " (from field " + fieldDecl.getSimpleName() + " in " + 
                                        (fieldDecl.getDeclaringType() != null ? safeQN(fieldDecl.getDeclaringType().getReference()) : "?") + ")");
                                    
                                    // If the type is not fully qualified, try to resolve it using imports from the declaring class
                                    if (fieldTypeQn == null || !fieldTypeQn.contains(".") || fieldTypeQn.startsWith("unknown.")) {
                                        String simpleName = fieldType.getSimpleName();
                                        if (simpleName != null && fieldDecl.getDeclaringType() != null) {
                                            // Try to resolve using imports from the field's declaring class
                                            CtTypeReference<?> resolved = resolveFromExplicitTypeImports(fieldDecl, simpleName);
                                            if (resolved != null) {
                                                fieldType = resolved;
                                                fieldTypeQn = safeQN(resolved);
                                                System.err.println("[collectUnresolvedMethodCalls] Resolved field type from imports: " + fieldTypeQn);
                                            }
                                        }
                                    }
                                    
                                    if (fieldTypeQn != null && fieldTypeQn.contains(".") && !fieldTypeQn.startsWith("unknown.")) {
                                        owner = fieldType.clone();
                                        System.err.println("[collectUnresolvedMethodCalls] Using field declaration type: " + fieldTypeQn);
                                    } else {
                                        System.err.println("[collectUnresolvedMethodCalls] Field declaration type not fully qualified: " + fieldTypeQn);
                                        fieldType = null; // Not fully qualified, try other methods
                                    }
                                } else {
                                    System.err.println("[collectUnresolvedMethodCalls] Field declaration is null or has no type");
                                }
                            } catch (Throwable t) {
                                System.err.println("[collectUnresolvedMethodCalls] Error getting field declaration: " + t.getMessage());
                                t.printStackTrace();
                            }
                        }
                        
                        // Priority 2: Try from the field variable's type (if declaration didn't work)
                        if (owner == null && fa.getVariable() != null) {
                            try {
                                fieldType = fa.getVariable().getType();
                                String fieldTypeQn = safeQN(fieldType);
                                System.err.println("[collectUnresolvedMethodCalls] Field variable type: " + fieldTypeQn);
                                if (fieldTypeQn != null && fieldTypeQn.contains(".") && !fieldTypeQn.startsWith("unknown.")) {
                                    owner = fieldType.clone();
                                    System.err.println("[collectUnresolvedMethodCalls] Using field variable type: " + fieldTypeQn);
                                } else {
                                    fieldType = null; // Not fully qualified, try expression type
                                }
                            } catch (Throwable ignored) {}
                        }
                        
                        // Priority 3: Try from the field access expression type (least reliable - may be inferred from context)
                        if (owner == null) {
                            try {
                                fieldType = fa.getType();
                                String fieldTypeQn = safeQN(fieldType);
                                System.err.println("[collectUnresolvedMethodCalls] Field access expression type: " + fieldTypeQn);
                                // Only use if fully qualified and not unknown
                                if (fieldTypeQn != null && fieldTypeQn.contains(".") && !fieldTypeQn.startsWith("unknown.")) {
                                    owner = fieldType.clone();
                                    System.err.println("[collectUnresolvedMethodCalls] Using field access type: " + fieldTypeQn);
                                } else {
                                    System.err.println("[collectUnresolvedMethodCalls] Field access type not fully qualified, will fall back");
                                }
                            } catch (Throwable ignored) {}
                        }
                        
                        // If we still don't have a fully qualified type, fall back to chooseOwnerPackage
                        if (owner == null) {
                owner = chooseOwnerPackage(rawOwner, inv);
                            System.err.println("[collectUnresolvedMethodCalls] Falling back to chooseOwnerPackage: " + safeQN(owner));
                        }
                    } catch (Throwable t) {
                        System.err.println("[collectUnresolvedMethodCalls] Error getting field type: " + t.getMessage());
                        t.printStackTrace();
                        owner = chooseOwnerPackage(rawOwner, inv);
                    }
                } else {
                    owner = chooseOwnerPackage(rawOwner, inv);
                }

                // implicit this + single non-JDK interface => default method on that iface
                boolean implicitThis = (tgt == null) || (tgt instanceof CtThisAccess<?>);
                if (implicitThis && !makeStatic) {
                    CtClass<?> encl = inv.getParent(CtClass.class);
                    if (encl != null) {
                        List<CtTypeReference<?>> nonJdkIfaces = encl.getSuperInterfaces().stream()
                                .filter(Objects::nonNull)
                                .filter(ifr -> {
                                    String qn = safeQN(ifr);
                                    return !(qn.startsWith("java.") || qn.startsWith("javax.")
                                            || qn.startsWith("jakarta.") || qn.startsWith("sun.")
                                            || qn.startsWith("jdk."));
                                })
                                .collect(Collectors.toList());
                        if (nonJdkIfaces.size() == 1) {
                            owner = chooseOwnerPackage(nonJdkIfaces.get(0), inv);
                            defaultOnIface = true;
                        }
                    }
                }
            }
            // --- NEW: if the target is (or contains) an array element, use the element type as the owner

            // ==== HARD OVERRIDE for array-element calls: a[i][j].m(...) ====
            if (inv.getTarget() instanceof CtArrayAccess<?, ?>) {
                CtTypeReference<?> elemT = elementTypeFromArrayAccess((CtArrayAccess<?, ?>) inv.getTarget());
                if (elemT != null) {
                    // Prefer the current package’s E over unknown.* even if unknown.* is star-imported
                    String curPkg = pkgOf(inv);
                    CtTypeReference<?> chosenOwner;
                    if (curPkg != null && !curPkg.isEmpty()) {
                        chosenOwner = f.Type().createReference(curPkg + "." + elemT.getSimpleName());
                    } else {
                        chosenOwner = chooseOwnerPackage(elemT, inv);
                        if (chosenOwner == null) chosenOwner = elemT;
                    }
                    owner = chosenOwner;        // e.g., fixtures.arr.E
                    defaultOnIface = false;     // element calls are instance, not default interface
                }
            }

// Mirror to unknown.<SimpleName> whenever the slice forces unknown.* at this site
            boolean mirror = false;
            CtTypeReference<?> mirrorOwnerRef = null;

            if (owner != null && !safeQN(owner).startsWith("unknown.")) {
                boolean arrayElemUnknown = false;

                if (inv.getTarget() instanceof CtArrayAccess<?, ?>) {
                    // If Spoon typed the array element as unknown.*, mirror too
                    try {
                        CtTypeReference<?> elemOrAccessT = ((CtArrayAccess<?, ?>) inv.getTarget()).getType();
                        arrayElemUnknown = (elemOrAccessT != null && safeQN(elemOrAccessT).startsWith("unknown."));
                    } catch (Throwable ignored) {
                    }
                }

                if (hasUnknownStarImport(inv) || callSiteTargetsUnknown(inv) || arrayElemUnknown) {
                    mirror = true;
                    mirrorOwnerRef = f.Type().createReference("unknown." + owner.getSimpleName());
                }
            }

            if (mirror && mirrorOwnerRef != null) {
                String simple = owner != null ? owner.getSimpleName() : null;
                boolean realExists =
                        (simple != null) && (
                                inv.getFactory().getModel().getAllTypes().stream().anyMatch(t ->
                                        simple.equals(t.getSimpleName())
                                                && t.getPackage() != null
                                                && !"unknown".equals(t.getPackage().getQualifiedName()))
                                        ||
                                        out.typePlans.stream().anyMatch(tp -> {
                                            int i = tp.qualifiedName.lastIndexOf('.');
                                            String pkg = i >= 0 ? tp.qualifiedName.substring(0, i) : "";
                                            String s   = i >= 0 ? tp.qualifiedName.substring(i+1) : tp.qualifiedName;
                                            return simple.equals(s) && !"unknown".equals(pkg);
                                        })
                        );
                if (realExists) {
                    mirror = false;
                    mirrorOwnerRef = null;
                }
            }




            if (isJdkType(owner)) continue;

            // EXTRA fallback: enum field initializer => treat as static
            if (!makeStatic) {
                if (inv.getParent(CtEnum.class) != null && inv.getParent(CtField.class) != null) {
                    makeStatic = true;
                }
            }

            // return type from context
            CtTypeReference<?> returnType = inferReturnTypeFromContext(inv);
            if (returnType == null && isStandaloneInvocation(inv)) {
                returnType = f.Type().VOID_PRIMITIVE;
            }

            // parameter types (collapse anonymous classes)
            List<CtTypeReference<?>> paramTypes = inferParamTypesFromCall(ex, inv.getArguments());
            List<CtExpression<?>> args = inv.getArguments();
            // collapse anonymous classes to nominal super (first interface, else superclass),
// then coerce functional args (lambda/method-ref), then fix varargs element type
            for (int i = 0; i < args.size() && i < paramTypes.size(); i++) {
                CtExpression<?> a = args.get(i);

                // --- anonymous class -> interface/superclass ---
                if (a instanceof CtNewClass) {
                    var nc = (CtNewClass<?>) a;
                    CtClass<?> anon = nc.getAnonymousClass();
                    if (anon != null) {
                        CtTypeReference<?> pick = null;
                        try {
                            var ifaces = anon.getSuperInterfaces();
                            if (ifaces != null && !ifaces.isEmpty()) pick = ifaces.iterator().next();
                        } catch (Throwable ignored) { }
                        if (pick == null) {
                            try { pick = anon.getSuperclass(); } catch (Throwable ignored) { }
                        }
                        if (pick != null) {
                            CtTypeReference<?> chosen = chooseOwnerPackage(pick, inv);
                            if (chosen != null) paramTypes.set(i, chosen);
                        }
                    }
                }

                // --- functional arg coercion (lambda / method-ref) ---
                CtTypeReference<?> expected = paramTypes.get(i);
                CtTypeReference<?> coerced = coerceFunctionalToTarget(a, expected);
                if (coerced != expected) {
                    paramTypes.set(i, coerced);
                }
            }

// --- varargs element type coercion (when call looks varargs) ---
            if (makeVarargs && !paramTypes.isEmpty()) {
                int varargIndex = paramTypes.size() - 1;
                CtTypeReference<?> currentElem = paramTypes.get(varargIndex);
                List<CtTypeReference<?>> coerced =
                        coerceVarargs(args, varargIndex, currentElem);
                // last param represents the vararg element type (SpoonStubber will turn it into array)
                if (!coerced.isEmpty() && coerced.get(0) != null) {
                    paramTypes.set(varargIndex, coerced.get(0));
                }
            }


            // ambiguity guard
            List<CtTypeReference<?>> fromRef = (ex != null ? ex.getParameters() : Collections.emptyList());
            boolean refSane = fromRef != null && !fromRef.isEmpty() && fromRef.stream().allMatch(this::isSaneType);
            if (!refSane && argsContainNullLiteral(inv.getArguments()) && cfg.isFailOnAmbiguity()) {
                String ownerQN = (owner != null ? owner.getQualifiedName() : "<unknown>");
                throw new AmbiguityException("Ambiguous method parameters (null argument): " + ownerQN + "#" + name + "(...)");
            }

            // super-call visibility / throws
            boolean isSuperCall = (tgt instanceof CtSuperAccess<?>);
            MethodStubPlan.Visibility vis = isSuperCall ? MethodStubPlan.Visibility.PROTECTED
                    : MethodStubPlan.Visibility.PUBLIC;
            List<CtTypeReference<?>> thrown =
                    isSuperCall
                            ? new ArrayList<>(Optional.ofNullable(inv.getParent(CtMethod.class))
                            .map(CtMethod::getThrownTypes)
                            .orElse(Collections.emptySet()))
                            : Collections.emptyList();

            // enum helpers normalized on the chosen owner (unknown.* or concrete)
            if ("values".equals(name) && args.isEmpty()) {
                makeStatic = true;
                CtArrayTypeReference<?> arr = f.Core().createArrayTypeReference();
                arr.setComponentType(owner);
                returnType = arr;
                paramTypes = Collections.emptyList();
            } else if ("valueOf".equals(name) && args.size() == 1) {
                makeStatic = true;
                returnType = owner;
                paramTypes = List.of(f.Type().createReference("java.lang.String"));
            } else if ("name".equals(name) && args.isEmpty()) {
                if (returnType == null) returnType = f.Type().createReference("java.lang.String");
            }

            if (returnType == null) returnType = f.Type().VOID_PRIMITIVE;

            // --- Enum utilities: values() & valueOf(String) ---
            boolean looksEnumValues  = "values".equals(name) && (args == null || args.isEmpty());
            boolean looksEnumValueOf = "valueOf".equals(name)
                    && paramTypes.size() == 1
                    && "java.lang.String".equals(safeQN(paramTypes.get(0)));

            if (owner != null && (looksEnumValues || looksEnumValueOf)) {
                CtTypeReference<?> arrOfOwner = f.Core().createArrayTypeReference();
                ((CtArrayTypeReference<?>) arrOfOwner).setComponentType(owner.clone());

                if (looksEnumValues) {
                    out.methodPlans.add(new MethodStubPlan(
                            owner, "values", arrOfOwner,
                            Collections.emptyList(),
                            /*isStatic*/ true, MethodStubPlan.Visibility.PUBLIC,
                            Collections.emptyList(),
                            /*defaultOnIface*/ false, /*isAbstract*/ false, /*isFinal*/ true, null));
                } else {
                    out.methodPlans.add(new MethodStubPlan(
                            owner, "valueOf", owner.clone(),
                            List.of(f.Type().createReference("java.lang.String")),
                            /*isStatic*/ true, MethodStubPlan.Visibility.PUBLIC,
                            Collections.emptyList(),
                            /*defaultOnIface*/ false, /*isAbstract*/ false, /*isFinal*/ true, null));
                }
                // NOTE: don't "continue" — let normal flow still plan 'name()' if seen elsewhere.
            }


            boolean staticCtx = isInStaticContext(inv);
            
            // Check if this method was already added by collectStreamApiMethods
            // (which has better type inference for modern API methods like map, thenApply, etc.)
            CtTypeReference<?> finalOwner = owner;
            boolean alreadyExists = out.methodPlans.stream().anyMatch(p -> {
                try {
                    String pOwnerQn = safeQN(p.ownerType);
                    String ownerQn = safeQN(finalOwner);
                    return ownerQn != null && ownerQn.equals(pOwnerQn) && name.equals(p.name);
                } catch (Throwable ignored) {
                    return false;
                }
            });
            
            if (alreadyExists) {
                // Skip - method was already added with better type information
                continue;
            }
            
            // enqueue plan — NOTE: no mirroring when owner is already unknown.*
            out.methodPlans.add(new MethodStubPlan(
                    owner, name,
                    (returnType != null ? returnType : f.Type().VOID_PRIMITIVE),
                    paramTypes,
                    makeStatic, vis, thrown, defaultOnIface,
                    /* varargs */ makeVarargs,
                    /* mirror   */ false,
                    /* mirrorOwnerRef */ null
            ));

            if (mirror && mirrorOwnerRef != null) {
                out.methodPlans.add(new MethodStubPlan(
                        mirrorOwnerRef, name,
                        (returnType != null ? returnType : f.Type().VOID_PRIMITIVE),
                        paramTypes,
                        makeStatic, vis, thrown, /*defaultOnIface*/ false,
                        makeVarargs,
                        /* mirror */ false,
                        /* mirrorOwnerRef */ null
                ));
            }

        }
    }


    /**
     * Resolve the *owner* type for a method invocation (handles static, field receiver, generic).
     */
    private CtTypeReference<?> resolveOwnerTypeFromInvocation(CtInvocation<?> inv) {
        // 1) Static call: TypeName.m(...)
        if (inv.getTarget() instanceof CtTypeAccess) {
            CtTypeReference<?> at = ((CtTypeAccess<?>) inv.getTarget()).getAccessedType();
            if (at != null) return at;
            // return ((CtTypeAccess<?>) inv.getTarget()).getAccessedType();
        }

        // 2) Prefer declared type of a field access receiver, if present.
        if (inv.getTarget() instanceof CtFieldAccess) {
            CtFieldAccess<?> fa = (CtFieldAccess<?>) inv.getTarget();

            // Best: the type of the field access expression itself
            try {
                CtTypeReference<?> t = fa.getType();
                if (t != null) {
                    String tQn = safeQN(t);
                    // If it's fully qualified and not unknown, use it
                    if (tQn != null && tQn.contains(".") && !tQn.startsWith("unknown.")) {
                        return t;
                    }
                }
            } catch (Throwable ignored) {
            }

            // Fallback: field reference's declared type
            try {
                if (fa.getVariable() != null) {
                    // Try variable's type
                    CtTypeReference<?> varType = fa.getVariable().getType();
                    if (varType != null) {
                        String varTypeQn = safeQN(varType);
                        // If it's fully qualified and not unknown, use it
                        if (varTypeQn != null && varTypeQn.contains(".") && !varTypeQn.startsWith("unknown.")) {
                            return varType;
                        }
                    }
                    
                    // Try field's declaration (might be in superclass)
                    CtField<?> fieldDecl = fa.getVariable().getDeclaration();
                    if (fieldDecl != null && fieldDecl.getType() != null) {
                        CtTypeReference<?> declType = fieldDecl.getType();
                        String declTypeQn = safeQN(declType);
                        // If it's fully qualified and not unknown, use it
                        if (declTypeQn != null && declTypeQn.contains(".") && !declTypeQn.startsWith("unknown.")) {
                            return declType;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // Last: the target expression type (e.g., this)
            try {
                if (fa.getTarget() != null && fa.getTarget().getType() != null)
                    return fa.getTarget().getType();
            } catch (Throwable ignored) {
            }
        }


        // 3) Generic expression type.
        if (inv.getTarget() != null) {
            try {
                return inv.getTarget().getType();
            } catch (Throwable ignored) {
            }
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
            try {
                return ((CtExpression<?>) ((CtAssignment<?, ?>) p).getAssigned()).getType();
            } catch (Throwable ignored) {
            }
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
                if (Objects.equals(args.get(i), inv)) {
                    idx = i;
                    break;
                }
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
                return f.Type().createReference(UnknownType.CLASS);
            }
        }

        // --- FOREACH: for (E x : call()) { ... } ---
        if (p instanceof CtForEach) {
            var fe = (CtForEach) p;
            try {
                CtTypeReference<?> varT = fe.getVariable().getType();
                if (isSaneType(varT)) {
                    // prefer Iterable<E> (do not stub JDK types)
                    CtTypeReference<?> it = f.Type().createReference("java.lang.Iterable");
                    it.addActualTypeArgument(varT.clone());
                    return it;
                }
            } catch (Throwable ignored) {}
            // fallback: Iterable<unknown.Unknown>
            CtTypeReference<?> it = f.Type().createReference("java.lang.Iterable");
            it.addActualTypeArgument(f.Type().createReference("unknown.Unknown"));
            return it;
        }

// --- ARRAY CONTEXT: call()[i] or assigned to E[] ---
        if (p instanceof CtArrayAccess) {
            CtTypeReference<?> component = null;
            try {
                var aa = (CtArrayAccess<?, ?>) p;
                CtTypeReference<?> at = aa.getType();
                if (at instanceof CtArrayTypeReference) {
                    component = ((CtArrayTypeReference<?>) at).getComponentType();
                }
            } catch (Throwable ignored) {}

            if (isSaneType(component)) {
                // Build E[] properly
                CtTypeReference<?> arr = f.Type().createArrayReference(component.clone());
                return arr;
            }
            // fallback: unknown.Unknown[]
            CtTypeReference<?> arr = f.Type().createArrayReference(f.Type().createReference("unknown.Unknown"));
            return arr;
        }


// --- STRING CONCAT: "x" + call() or call() + "x" ---
        if (p instanceof CtBinaryOperator) {
            var bo = (CtBinaryOperator<?>) p;
            if (bo.getKind() == BinaryOperatorKind.PLUS) {
                try {
                    CtTypeReference<?> lt = bo.getLeftHandOperand() != null ? bo.getLeftHandOperand().getType() : null;
                    CtTypeReference<?> rt = bo.getRightHandOperand() != null ? bo.getRightHandOperand().getType() : null;
                    if ((lt != null && "java.lang.String".equals(safeQN(lt))) ||
                            (rt != null && "java.lang.String".equals(safeQN(rt)))) {
                        return f.Type().createReference("java.lang.String");
                    }
                } catch (Throwable ignored) {}
            }
        }

// --- TERNARY: cond ? call() : other (or vice versa) ---
        // --- TERNARY: cond ? call() : other (or vice versa) ---
        if (p instanceof CtConditional) {
            var ce = (CtConditional<?>) p;
            var thenExp = ce.getThenExpression();
            var elseExp = ce.getElseExpression();

            boolean inThen = thenExp == inv;
            var other = inThen ? elseExp : (elseExp == inv ? thenExp : null);
            if (other != null) {
                try {
                    CtTypeReference<?> ot = other.getType();
                    if (isSaneType(ot)) return ot;
                } catch (Throwable ignored) {}
            }
            try { // shallow LUB fallback
                CtTypeReference<?> t = ce.getType();
                if (isSaneType(t)) return t;
            } catch (Throwable ignored) {}
            return f.Type().createReference("java.lang.Object");
        }



        try {
            return inv.getType();
        } catch (Throwable ignored) {
        }
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
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Infer parameter types for a call: prefer executable signature if sane; otherwise derive from args.
     */

    private boolean isAnonymousOrLocal(CtTypeReference<?> t) {
        String qn = safeQN(t);
        // anonymous like ...$1 or sometimes printed as .1
        return qn.contains("$") || qn.matches(".*\\.[0-9]+$");
    }

    private List<CtTypeReference<?>> inferParamTypesFromCall(CtExecutableReference<?> ex,
                                                             List<CtExpression<?>> args) {
        List<CtTypeReference<?>> fromRef = (ex != null ? ex.getParameters() : Collections.emptyList());

        boolean allOk = fromRef != null
                && !fromRef.isEmpty()
                && fromRef.stream().allMatch(t -> isSaneType(t) && !isAnonymousOrLocal(t));

        if (allOk) {
            return new ArrayList<>(fromRef);
        }

        // fall back: derive from arguments (your anon→interface collapse lives here)
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
        // Walk all annotation occurrences in the slice
        for (CtAnnotation<?> ann : model.getElements((CtAnnotation<?> a) -> true)) {
            CtTypeReference<?> t = ann.getAnnotationType();
            if (t == null) continue;
            // already resolved? skip
            try {
                if (t.getDeclaration() != null) continue;
            } catch (Throwable ignored) {
            }

            // JDK annotations? skip
            if (isJdkType(t)) continue;

            // Decide the package for this unresolved annotation via your existing heuristic
            CtTypeReference<?> resolved = chooseOwnerPackage(t, ann);
            if (resolved == null) continue;
            String annFqn = safeQN(resolved);
            if (annFqn != null && !annFqn.isEmpty()) {
                addTypePlanFromRef(out, resolved, TypeStubPlan.Kind.ANNOTATION);
            }

            // If the same unresolved annotation appears more than once on the SAME element => repeatable
            if (annFqn != null && !annFqn.isEmpty()) {
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
                        addTypePlanIfNonJdk(out, containerFqn, TypeStubPlan.Kind.ANNOTATION);
                    }
                }
            }
        }
    }

    private void collectAnnotationTypeUsages(CtModel model, CollectResult out) {
        for (CtAnnotation<?> a : model.getElements((CtAnnotation<?> x) -> true)) {
            CtTypeReference<?> at = a.getAnnotationType();
            if (at == null) continue;

            // If simple name, try to resolve via explicit import in this CU
            String qn = safeQN(at);
            if (!qn.contains(".")) {
                CtTypeReference<?> resolved = resolveFromExplicitTypeImports(a, at.getSimpleName());
                if (resolved != null) {
                    addTypePlanFromRef(out, resolved, TypeStubPlan.Kind.ANNOTATION);
                    // Also plan container (Tag -> Tags) in same pkg, as annotation
                    String pkg = resolved.getPackage() == null ? "" : resolved.getPackage().getQualifiedName();
                    String simple = resolved.getSimpleName();
                    String containerSimple = simple.endsWith("s") ? simple + "es" : simple + "s";
                    String containerFqn = (pkg.isEmpty() ? containerSimple : pkg + "." + containerSimple);
                    out.typePlans.add(new TypeStubPlan(containerFqn, TypeStubPlan.Kind.ANNOTATION));
                    continue;
                }
            }

            // Otherwise, keep whatever we have if it's non-JDK
            if (!isJdkType(at)) {
                String simpleName = at.getSimpleName();
                if (simpleName != null && !simpleName.isEmpty()) {
                    String finalQn = (qn.isEmpty() ? "unknown." + simpleName : qn);
                    addTypePlanIfNonJdk(out, finalQn, TypeStubPlan.Kind.ANNOTATION);
                }
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
        // methods: throws
        List<CtMethod<?>> methods = model.getElements((CtMethod<?> mm) -> true);
        for (CtMethod<?> m : methods) {
            for (CtTypeReference<?> t : m.getThrownTypes()) {
                if (t == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(t, m);
                if (isJdkType(owner)) continue;
                addTypePlanFromRef(out, owner, TypeStubPlan.Kind.CLASS);
                out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
            }
        }

        // ctors: throws
        List<CtConstructor<?>> ctors = model.getElements((CtConstructor<?> cc) -> true);
        for (CtConstructor<?> c : ctors) {
            for (CtTypeReference<?> t : c.getThrownTypes()) {
                if (t == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(t, c);
                if (isJdkType(owner)) continue;
                addTypePlanFromRef(out, owner, TypeStubPlan.Kind.CLASS);
                out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
            }
        }

        // catch (single & multi)
        List<CtCatch> catches = model.getElements((CtCatch k) -> true);
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
                addTypePlanFromRef(out, owner, TypeStubPlan.Kind.CLASS);
                out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
            }
        }

        // throw statements
        List<CtThrow> throwsList = model.getElements((CtThrow th) -> true);
        for (CtThrow thr : throwsList) {
            CtExpression<?> ex = thr.getThrownExpression();
            if (ex instanceof CtConstructorCall) {
                CtConstructorCall<?> cc = (CtConstructorCall<?>) ex;
                CtTypeReference<?> owner = chooseOwnerPackage(cc.getType(), thr);
                if (!isJdkType(owner)) {
                    addTypePlanFromRef(out, owner, TypeStubPlan.Kind.CLASS);
                    List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(owner, ps));
                }
            } else if (ex != null) {
                try {
                    CtTypeReference<?> t = ex.getType();
                    if (t != null && !isJdkType(t) && t.getDeclaration() == null) {
                        CtTypeReference<?> owner = chooseOwnerPackage(t, thr);
                        addTypePlanIfNonJdk(out, owner.getQualifiedName(), TypeStubPlan.Kind.CLASS);
                        out.ctorPlans.add(new ConstructorStubPlan(owner, Collections.emptyList()));
                    }
                } catch (Throwable ignored) {
                }
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
        for (CtField<?> fd : model.getElements((CtField<?> f) -> true)) {
            collectTypeRefDeep(fd, fd.getType(), out);
        }
        for (CtLocalVariable<?> lv : model.getElements((CtLocalVariable<?> v) -> true)) {
            collectTypeRefDeep(lv, lv.getType(), out);
        }
        for (CtParameter<?> p : model.getElements((CtParameter<?> pp) -> true)) {
            collectTypeRefDeep(p, p.getType(), out);
        }
        for (CtMethod<?> m : model.getElements((CtMethod<?> mm) -> true)) {
            for (CtTypeReference<? extends Throwable> thr : m.getThrownTypes()) {
                collectTypeRefDeep(m, thr, out);
            }
        }
        for (CtConstructor<?> c : model.getElements((CtConstructor<?> cc) -> true)) {
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

        // arrays → recurse on component
        try {
            if (t.isArray() || t instanceof CtArrayTypeReference) {
                CtTypeReference<?> comp = componentOf(t);
                if (comp != null) maybePlanDeclaredType(ctx, comp, out);
                return;
            }
        } catch (Throwable ignored) {}

        // primitives / void
        try {
            if (t.isPrimitive()) return;
            if (t.equals(f.Type().VOID_PRIMITIVE)) return;
        } catch (Throwable ignored) {}

        // already resolved in model?
        try {
            if (t.getDeclaration() != null) return;
        } catch (Throwable ignored) {}

        // JDK types are never stubbed
        if (isJdkType(t)) return;

        String qn = safeQN(t);
        String simple = t.getSimpleName();
        if (simple == null || simple.isEmpty()) return;
        
        // Prevent stubbing primitive types by simple name (byte, int, short, etc.)
        // These should never be stubbed as classes
        if (isPrimitiveTypeName(simple)) {
            return;
        }

        // ---- SIMPLE NAME branch -------------------------------------------------
        if (!qn.contains(".")) {
            // 1) explicit single-type import wins
            CtTypeReference<?> explicit = resolveFromExplicitTypeImports(ctx, simple);
            if (explicit != null) {
                addTypePlanIfNonJdk(out, explicit.getQualifiedName(), TypeStubPlan.Kind.CLASS);
                return;
            }

            // 2) on-demand (star) imports, preserving order (excluding unknown)
            // Pseudocode close to your current block that decides owner for a simple type
            List<String> starPkgs = starImportsInOrder(ctx);         // in source order
            List<String> candidates = new ArrayList<>();
            for (String p : starPkgs) {
                if ("unknown".equals(p)) continue;                  // never create in unknown here
                String fqn = p + "." + simple;
                CtType<?> cttype = f.Type().get(fqn);
                if (cttype != null) candidates.add(fqn);
            }

// --- Case 1: resolution ambiguity (existing types)
            if (candidates.size() > 1) {
                if (cfg.isFailOnAmbiguity()) {
                    throw new AmbiguityException("Ambiguous simple type '" + simple + "' from on-demand imports: " + candidates);
                } else {
                    out.ambiguousSimples.add(simple);
                    // do NOT create anything here; your later qualify pass will handle it
                    return;
                }
            }

// --- Case 2: single existing type → use it
            if (candidates.size() == 1) {
                addTypePlanIfNonJdk(out, candidates.get(0), TypeStubPlan.Kind.CLASS);
                return;
            }

// --- Case 3: creation (doesn't exist anywhere) → first star-import package
            for (String p : starPkgs) {
                if ("unknown".equals(p)) continue;
                if (isJdkPackage(p)) continue;                      // never create in JDK
                String chosen = p + "." + simple;
                addTypePlanIfNonJdk(out, chosen, TypeStubPlan.Kind.CLASS);
                return;
            }


            // 3) fallback to unknown.*
            addTypePlanIfNonJdk(out, "unknown." + simple, TypeStubPlan.Kind.CLASS);
            return;
        }

        // ---- FQN branch ---------------------------------------------------------
        // Non-JDK and unresolved → plan its qualified name as-is.
        addTypePlanIfNonJdk(out, qn, TypeStubPlan.Kind.CLASS);
    }


    /* ======================================================================
     *                           OWNER / IMPORT SEEDING
     * ====================================================================== */

    /**
     * Seed synthetic anchors for on-demand (star) imports, preserving source order.
     */
    private void seedOnDemandImportAnchors(CtModel model, CollectResult out) {
        final Pattern STAR_IMPORT = Pattern.compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\.\\*\\s*;");

        model.getAllTypes().forEach(t -> {
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
            } catch (Throwable ignored) {
            }

            for (String pkg : starPkgs) {
                addTypePlanIfNonJdk(out, pkg + ".PackageAnchor", TypeStubPlan.Kind.CLASS);
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
        try {
            qn = t.getQualifiedName();
        } catch (Throwable ignored) {
            qn = null;
        }
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
                Matcher m = Pattern
                        .compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\.\\*\\s*;")
                        .matcher(src);
                while (m.find()) {
                    String pkg = m.group(1);
                    if (!isJdkPkg(pkg) && !out.contains(pkg)) out.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Choose a package for a possibly simple owner reference, honoring star imports and strict mode.
     */


    private CtTypeReference<?> chooseOwnerPackage(CtTypeReference<?> ownerRef, CtElement ctx) {
        if (ownerRef == null) return f.Type().createReference("unknown.Missing");
        if (ownerRef.isPrimitive() || ownerRef.isArray()) return ownerRef;

        String qn = safeQN(ownerRef);

        // NEW: treat 'unknown.*' as a *simple* name so we can rebind it sensibly
        if (qn != null && qn.startsWith("unknown.")) {
            ownerRef = f.Type().createReference(ownerRef.getSimpleName());
            qn = ownerRef.getQualifiedName(); // now simple
        }

        if (qn.contains(".") && !isLocallyAssumedOrSimple(ownerRef, ctx)) return ownerRef;
        if (qn.contains(".") && isLocallyAssumedOrSimple(ownerRef, ctx)) {
            ownerRef = f.Type().createReference(ownerRef.getSimpleName());
            qn = ownerRef.getQualifiedName();
        }
        if (qn.contains(".")) return ownerRef;

        String simple = Optional.ofNullable(ownerRef.getSimpleName()).orElse("Missing");

        // (A) explicit single-type import wins
        CtTypeReference<?> explicit = resolveFromExplicitTypeImports(ctx, simple);
        if (explicit != null) return explicit;

        // (A2) if simple is a well-known JDK type, prefer its JDK package
        if (isKnownJdkSimple(simple)) {
            return f.Type().createReference(JDK_SIMPLE.get(simple) + "." + simple);
        }



        // (B) star-imports
        List<String> stars = starImportsInOrder(ctx);

        List<String> nonJdkNonUnknown = stars.stream()
                .map(String::trim)
                .filter(p -> !"unknown".equals(p))
                .filter(p -> !isJdkPackage(p))
                .collect(Collectors.toList());

        if (nonJdkNonUnknown.size() == 1) {
            return f.Type().createReference(nonJdkNonUnknown.get(0) + "." + simple);
        }
        if (nonJdkNonUnknown.size() > 1) {
            if (cfg.isFailOnAmbiguity()) {
                throw new AmbiguityException("Ambiguous simple type '" + simple + "' from on-demand imports: " + nonJdkNonUnknown);
            } else {
                return f.Type().createReference("unknown." + simple);
            }
        }

        // Prefer current package for simple names (e.g., class C extends P) if ctx is in a package
        // // (D) current package (skip if default)
        String currentPkg = null;
        try {
            CtType<?> hostType = ctx.getParent(CtType.class);
            if (hostType != null && hostType.getPackage() != null) {
                currentPkg = hostType.getPackage().getQualifiedName();
            }
        } catch (Throwable ignored) {}
        if (currentPkg != null && !currentPkg.isEmpty()) {
            // Choose current package owner
            return f.Type().createReference(currentPkg + "." + simple);
        }




        // (C) prefer unknown if unknown.* present
        boolean hasUnknownStar = stars.stream().anyMatch("unknown"::equals);
        if (hasUnknownStar) return f.Type().createReference("unknown." + simple);

        // (E) fallback
        return f.Type().createReference("unknown." + simple);
    }

    private boolean isJdkPackage(String pkg) {
        return pkg.startsWith("java.") || pkg.startsWith("javax.")
                || pkg.startsWith("jakarta.") || pkg.startsWith("sun.") || pkg.startsWith("jdk.")
                || pkg.startsWith("javafx.");
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
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Seed types that appear only as explicit (single-type) imports.
     */
    private void seedExplicitTypeImports(CtModel model, CollectResult out) {
        final Pattern SINGLE_IMPORT =
                Pattern.compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\s*;");

        model.getAllTypes().forEach(t -> {
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
                    } catch (Throwable ignored) {
                    }
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
            } catch (Throwable ignored) {
            }

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
        System.err.println("[collectOverloadGaps] Starting, current method plans: " + out.methodPlans.size());
        List<CtInvocation<?>> invocations = model.getElements((CtInvocation<?> inv) -> {
            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : null);
            if (name == null || "<init>".equals(name)) return false;

            CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
            if (rawOwner == null) return false;
            CtTypeReference<?> owner = chooseOwnerPackage(rawOwner, inv);
            if (owner == null || isJdkType(owner)) return false;

            CtType<?> ownerDecl = null;
            try {
                ownerDecl = owner.getTypeDeclaration();
            } catch (Throwable ignored) {
            }
            if (!(ownerDecl instanceof CtClass)) return false;

            List<CtMethod<?>> sameName = ((CtClass<?>) ownerDecl).getMethods().stream()
                    .filter(m -> name.equals(m.getSimpleName()))
                    .collect(Collectors.toList());
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
            
            // For method calls on functional interfaces, check if this is the SAM method
            // If the owner is a functional interface and this is "make" or "apply", 
            // ensure we use the correct return type from the functional interface
            String ownerQn = safeQN(owner);
            if (ownerQn != null && ("make".equals(name) || "apply".equals(name))) {
                // Check if this owner is a functional interface (has a method plan with make/apply)
                boolean isFunctionalInterface = out.methodPlans.stream()
                        .anyMatch(p -> {
                            try {
                                String pOwnerQn = safeQN(p.ownerType);
                                return ownerQn.equals(pOwnerQn) && 
                                       ("make".equals(p.name) || "apply".equals(p.name));
                            } catch (Throwable ignored) {
                                return false;
                            }
                        });
                if (isFunctionalInterface) {
                    // Use the return type from the functional interface SAM, not from context
                    // This prevents wrong return types like "unknown.A" instead of "fixtures.lambda2.A"
                    for (MethodStubPlan plan : out.methodPlans) {
                        try {
                            String pOwnerQn = safeQN(plan.ownerType);
                            if (ownerQn.equals(pOwnerQn) && name.equals(plan.name)) {
                                returnType = plan.returnType;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

//            List<CtTypeReference<?>> paramTypes = inv.getArguments().stream()
//                    .map(this::paramTypeOrObject)
//                    .collect(Collectors.toList());

            List<CtExpression<?>> args = inv.getArguments();
            List<CtTypeReference<?>> paramTypes = new ArrayList<>(args.size());
            for (int i = 0; i < args.size(); i++) {
                CtTypeReference<?> base = paramTypeOrObject(args.get(i));
                base = coerceFunctionalToTarget(args.get(i), base);
                paramTypes.add(base);
            }
            boolean v = looksLikeVarargs(inv);
            if (v && !paramTypes.isEmpty()) {
                int varargIndex = paramTypes.size() - 1;
                CtTypeReference<?> elem = paramTypes.get(varargIndex);
                List<CtTypeReference<?>> coerced = coerceVarargs(args, varargIndex, elem);
                if (!coerced.isEmpty() && coerced.get(0) != null) {
                    paramTypes.set(varargIndex, coerced.get(0));
                }
            }


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
            } catch (Throwable ignored) {
            }

            List<CtTypeReference<?>> thrown = Collections.emptyList();
            if (isSuperCall) {
                thrown = new ArrayList<>(
                        Optional.ofNullable(inv.getParent(CtMethod.class))
                                .map(CtMethod::getThrownTypes)
                                .orElse(Collections.emptySet())
                );
            }

            // Check if this is a method call on a functional interface
            // Functional interfaces should only have ONE abstract method (the SAM)
            // If the owner already has a SAM method plan (make/apply), skip adding any other SAM method
            // This prevents creating multiple abstract methods which would make it non-functional
            // Note: Even if parameter types differ (e.g., apply(int) vs apply(Integer)), we should only have one
            // because Java autoboxing allows apply(1) to call apply(Integer)
            boolean isFunctionalInterfaceMethod = ("make".equals(name) || "apply".equals(name));
            if (isFunctionalInterfaceMethod) {
                String finalOwnerQn = ownerQn;
                // Also check erased FQN to handle generics (F vs F<Integer>)
                String finalOwnerQnErased = erasureFqn(owner);
                boolean alreadyHasSam = out.methodPlans.stream()
                        .anyMatch(p -> {
                            try {
                                String pOwnerQn = safeQN(p.ownerType);
                                // Check if owner already has ANY make/apply method (functional interface can only have one)
                                // Must be abstract (not default, not static) to count as SAM
                                boolean ownerMatches = finalOwnerQn != null && finalOwnerQn.equals(pOwnerQn);
                                if (!ownerMatches && finalOwnerQnErased != null) {
                                    String pOwnerQnErased = erasureFqn(p.ownerType);
                                    ownerMatches = finalOwnerQnErased.equals(pOwnerQnErased);
                                }
                                return ownerMatches && 
                                       ("make".equals(p.name) || "apply".equals(p.name)) &&
                                       !p.defaultOnInterface && !p.isStatic;
                            } catch (Throwable ignored) {
                                return false;
                            }
                        });
                if (alreadyHasSam) {
                    // Functional interface already has a SAM method - skip adding another one
                    // This prevents errors like "multiple non-overriding abstract methods found"
                    // Even if the parameter types are different (int vs Integer), we can only have one SAM
                    System.err.println("[collectOverloadGaps] Skipping duplicate SAM method: " + finalOwnerQn + "#" + name + 
                        " (already exists)");
                    continue;
                }
            }

            out.methodPlans.add(new MethodStubPlan(owner, name, returnType, paramTypes, isStatic, vis, thrown));
            if (("apply".equals(name) || "make".equals(name))) {
                System.err.println("[collectOverloadGaps] Added SAM method: " + ownerQn + "#" + name + "(" + 
                    paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
        }
        }
        System.err.println("[collectOverloadGaps] Finished, total method plans: " + out.methodPlans.size());
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
                try {
                    pt = ps.get(i).getType();
                } catch (Throwable ignored) {
                }
                CtTypeReference<?> at = paramTypeOrObject(args.get(i));
                if (!isSaneType(pt) || !isSaneType(at)) {
                    allOk = false;
                    break;
                }

                String pqn = safeQN(pt), aqn = safeQN(at);
                if (pqn.equals(aqn)) continue;
                if (isPrimitiveBoxPair(pqn, aqn)) continue;

                allOk = false;
                break;
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
                (a.equals("long") && b.equals("java.lang.Long")) || (b.equals("long") && a.equals("java.lang.Long")) ||
                (a.equals("double") && b.equals("java.lang.Double")) || (b.equals("double") && a.equals("java.lang.Double")) ||
                (a.equals("float") && b.equals("java.lang.Float")) || (b.equals("float") && a.equals("java.lang.Float")) ||
                (a.equals("short") && b.equals("java.lang.Short")) || (b.equals("short") && a.equals("java.lang.Short")) ||
                (a.equals("byte") && b.equals("java.lang.Byte")) || (b.equals("byte") && a.equals("java.lang.Byte")) ||
                (a.equals("char") && b.equals("java.lang.Character")) || (b.equals("char") && a.equals("java.lang.Character")) ||
                (a.equals("boolean") && b.equals("java.lang.Boolean")) || (b.equals("boolean") && a.equals("java.lang.Boolean"));
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
                if (arg instanceof CtWildcardReference) {
                    var w = (CtWildcardReference) arg;
                    CtTypeReference<?> bound = w.getBoundingType();
                    if (bound != null) collectTypeRefDeep(ctx, bound, out);
                } else {
                    collectTypeRefDeep(ctx, arg, out);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /* ======================================================================
     *                        SUPERTYPES / INHERITANCE PASS
     * ====================================================================== */

    /**
     * Collect supertypes (superclass and superinterfaces) and their generic arguments.
     */
    private void collectSupertypes(CtModel model, CollectResult out) {
        // classes: superclass + superinterfaces
        for (CtClass<?> c : model.getElements((CtClass<?> cc) -> true)) {
            CtTypeReference<?> sup = null;
            try {

                System.out.println("[collect] " + c.getQualifiedName() + " extends raw=" + safeQN(sup));
                CtTypeReference<?> owner = chooseOwnerPackage(sup, c);
                sup = c.getSuperclass();
                System.out.println("[collect] " + c.getQualifiedName() + " extends chosen=" + safeQN(owner));
            } catch (Throwable ignored) {
            }
            if (sup != null) {
                String sqn = safeQN(sup);
                if (sqn != null && sqn.startsWith("unknown.")) {
                    sup = f.Type().createReference(sup.getSimpleName());
                }
                CtTypeReference<?> owner = chooseOwnerPackage(sup, c);
                if (owner != null && !isJdkType(owner)) {
                    addTypePlanFromRef(out, owner, TypeStubPlan.Kind.CLASS);
                }
            }
            for (CtTypeReference<?> si : safe(c.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, c);
                if (owner != null && !isJdkType(owner)) {
                    addTypePlanFromRef(out, owner, TypeStubPlan.Kind.INTERFACE);
                }
            }
        }

        // interfaces: superinterfaces
        for (CtInterface<?> i : model.getElements((CtInterface<?> ii) -> true)) {
            for (CtTypeReference<?> si : safe(i.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, i);
                if (owner != null && !isJdkType(owner)) {
                    addTypePlanFromRef(out, owner, TypeStubPlan.Kind.INTERFACE);
                }
            }
        }

        // Generic type arguments inside extends/implements
        for (CtType<?> t : model.getAllTypes()) {
            CtTypeReference<?> sup = null;
            try {
                sup = (t instanceof CtClass) ? ((CtClass<?>) t).getSuperclass() : null;
            } catch (Throwable ignored) {
            }
            if (sup != null) {
                for (CtTypeReference<?> ta : safe(sup.getActualTypeArguments())) {
                    if (ta == null) continue;
                    CtTypeReference<?> owner = chooseOwnerPackage(ta, t);
                    if (owner != null && !isJdkType(owner) && owner.getDeclaration() == null) {
                        addTypePlanFromRef(out, owner, TypeStubPlan.Kind.CLASS);
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
                        addTypePlanFromRef(out, owner, TypeStubPlan.Kind.CLASS);
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
        // instanceof (right-hand side type)
        for (CtBinaryOperator<?> bo : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            if (bo.getKind() == BinaryOperatorKind.INSTANCEOF) {
                if (bo.getRightHandOperand() instanceof CtTypeAccess) {
                    CtTypeReference<?> t = ((CtTypeAccess<?>) bo.getRightHandOperand()).getAccessedType();
                    if (t != null) maybePlanDeclaredType(bo, t, out);
                }
            }
        }

        // class literals: Foo.class
        for (CtTypeAccess<?> ta : model.getElements(new TypeFilter<>(CtTypeAccess.class))) {
            CtTypeReference<?> t = ta.getAccessedType();
            if (t != null) maybePlanDeclaredType(ta, t, out);
        }

        // foreach contracts
        for (CtForEach fe : model.getElements(new TypeFilter<>(CtForEach.class))) {
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
            for (CtElement el : model.getElements(new TypeFilter<>(CtElement.class))) {
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
        } catch (Throwable ignored) {
        }
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
        } catch (Throwable ignored) {
        }

        String qn = t.getQualifiedName();
        if (qn == null || qn.isEmpty()) {
            // Check if simple name is a primitive type
            String simple = t.getSimpleName();
            if (simple != null && isPrimitiveTypeName(simple)) {
                return;
            }
            return;
        }

        // Check if the simple name part is a primitive type (e.g., "unknown.byte")
        String simple = qn.substring(qn.lastIndexOf('.') + 1);
        if (isPrimitiveTypeName(simple)) {
            return;
        }

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

        // collapse anonymous classes to nominal supertype (first interface, else superclass)
        if (arg instanceof CtNewClass) {
            var nc = (CtNewClass<?>) arg;
            CtClass<?> anon = nc.getAnonymousClass();
            if (anon != null) {
                CtTypeReference<?> pick = null;
                try {
                    var ifaces = anon.getSuperInterfaces();
                    if (ifaces != null && !ifaces.isEmpty()) pick = ifaces.iterator().next();
                } catch (Throwable ignored) {
                }
                if (pick == null) {
                    try {
                        pick = anon.getSuperclass();
                    } catch (Throwable ignored) {
                    }
                }
                if (pick != null) return pick;   // -> fixtures.gen2.C in your test
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
        try {
            t = arg.getType();
        } catch (Throwable ignored) {
        }
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
    public static String safeQN(CtTypeReference<?> t) {
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
    /**
     * FQN that uses '$' for member classes (Outer$Inner).
     */
    private static String nestedAwareFqnOf(CtTypeReference<?> ref) {
        if (ref == null) return null;
        CtTypeReference<?> decl = ref.getDeclaringType();
        if (decl != null) return nestedAwareFqnOf(decl) + "$" + ref.getSimpleName();
        return ref.getQualifiedName();
    }

    // In SpoonCollector
    private CtTypeReference<?> resolveOwnerFromStaticImports(CtInvocation<?> inv, String methodSimple) {
        var type = inv.getParent(CtType.class);
        var pos = (type != null ? type.getPosition() : null);
        var cu = (pos != null ? pos.getCompilationUnit() : null);
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
            } catch (Throwable ignored) {
            }
        }

        // 2) Raw-source fallback (like your star-import parser)
        try {
            String src = cu.getOriginalSourceCode();
            if (src != null) {
                var m = Pattern
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
        } catch (Throwable ignored) {
        }
        return null;
    }


    private boolean looksLikeVarargs(CtInvocation<?> inv) {
        var args = inv.getArguments();
        if (args == null || args.size() < 2) return false;
        CtTypeReference<?> a = paramTypeOrObject(args.get(args.size() - 1));
        CtTypeReference<?> b = paramTypeOrObject(args.get(args.size() - 2));
        return erasedEq(a, b);
    }

    private boolean erasedEq(CtTypeReference<?> x, CtTypeReference<?> y) {
        if (x == null || y == null) return false;
        String qx = safeQN(x);
        String qy = safeQN(y);
        // strip trailing [] so "String" vs "String[]" still returns false
        return qx.equals(qy);
    }


    private CtTypeReference<?> deepComponentType(CtTypeReference<?> t) {
        while (t instanceof CtArrayTypeReference) {
            t = ((CtArrayTypeReference<?>) t).getComponentType();
        }
        return t;
    }

    private String pkgOf(CtElement e) {
        CtType<?> encl = e.getParent(CtType.class);
        if (encl == null || encl.getPackage() == null) return "";
        String qn = encl.getPackage().getQualifiedName();
        return ("<unnamed>".equals(qn) ? "" : qn);
    }


    // Works for a[i][j] no matter how many dimensions.
// It tries (in order): declared variable type, the access' own type, and the target's type.
    private CtTypeReference<?> elementTypeFromArrayAccess(CtArrayAccess<?, ?> access) {
        if (access == null) return null;

        // 1) Walk to the base array expression (e.g., variable 'a')
        CtExpression<?> base = access;
        while (base instanceof CtArrayAccess) {
            base = ((CtArrayAccess<?, ?>) base).getTarget();
        }

        // 2) Try to get declared var type (best quality)
        try {
            if (base instanceof CtVariableRead) {
                var vr = (CtVariableRead<?>) base;
                if (vr.getVariable() != null && vr.getVariable().getType() != null) {
                    return deepComponentType(vr.getVariable().getType()); // E[][] -> E
                }
            } else if (base instanceof CtVariableAccess) {
                var va = (CtVariableAccess<?>) base;
                if (va.getVariable() != null && va.getVariable().getType() != null) {
                    return deepComponentType(va.getVariable().getType());
                }
            }
        } catch (Throwable ignored) {
        }

        // 3) Fallback: the type of the full array-access (if Spoon inferred E here)
        try {
            CtTypeReference<?> t = access.getType();
            if (t != null) return deepComponentType(t);
        } catch (Throwable ignored) {
        }

        // 4) Fallback: the type of the base expression (E[][])
        try {
            CtTypeReference<?> t = base.getType();
            if (t != null) return deepComponentType(t);
        } catch (Throwable ignored) {
        }

        return null;
    }


    private boolean hasUnknownStarImport(CtElement ctx) {
        var type = ctx.getParent(CtType.class);
        var pos = (type != null ? type.getPosition() : null);
        var cu = (pos != null ? pos.getCompilationUnit() : null);
        if (cu == null) return false;

        try {
            for (CtImport imp : cu.getImports()) {
                if (imp.getImportKind() == CtImportKind.ALL_TYPES) {
                    String raw = String.valueOf(imp.getReference());
                    if ("unknown".equals(raw)) return true;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            String src = cu.getOriginalSourceCode();
            return src != null && src.contains("import unknown.*;");
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean callSiteTargetsUnknown(CtInvocation<?> inv) {
        CtExpression<?> tgt = inv.getTarget();
        if (tgt instanceof CtTypeAccess<?>) {
            return safeQN(((CtTypeAccess<?>) tgt).getAccessedType()).startsWith("unknown.");
        }
        try {
            CtTypeReference<?> tt = (tgt != null ? tgt.getType() : null);
            return tt != null && safeQN(tt).startsWith("unknown.");
        } catch (Throwable ignored) {
        }
        return false;
    }

    // If arg is a lambda/method-ref and expected param type is known FI, keep it;
// if target type is unknown, prefer raw Object (legal & robust) over <Unknown>.
    private CtTypeReference<?> coerceFunctionalToTarget(CtExpression<?> arg, CtTypeReference<?> expected) {
        if (arg == null) return expected;
        boolean isFunc = (arg instanceof CtLambda)
                || (arg instanceof CtExecutableReferenceExpression);
        if (!isFunc) return expected;
        if (isSaneType(expected)) return expected; // target FI known → mirror it
        // no target type → raw/erased target to avoid illegal generics
        return f.Type().createReference("java.lang.Object");
    }

    // If varargs: pick a concrete element type from non-null args; else keep current/Unknown.
    private List<CtTypeReference<?>> coerceVarargs(List<CtExpression<?>> args,
                                                             int varargIndex,
                                                             CtTypeReference<?> currentElem) {
        if (varargIndex < 0 || varargIndex >= args.size()) {
            return Collections.singletonList(currentElem);
        }
        CtTypeReference<?> chosen = null;
        for (int i = varargIndex; i < args.size(); i++) {
            CtExpression<?> a = args.get(i);
            if (a == null) continue;
            if (a instanceof CtLiteral && ((CtLiteral<?>) a).getValue() == null) continue;
            try {
                CtTypeReference<?> t = a.getType();
                if (isSaneType(t)) { chosen = t; break; }
            } catch (Throwable ignored) {}
        }
        if (chosen == null) chosen = (currentElem != null ? currentElem : f.Type().createReference("unknown.Unknown"));
        return Collections.singletonList(chosen);
    }

    private String erasureFqn(CtTypeReference<?> tr) {
        String qn = safeQN(tr);
        int lt = qn.indexOf('<');
        return (lt >= 0 ? qn.substring(0, lt) : qn);
    }



    private boolean isInStaticContext(CtInvocation<?> inv) {
        return isInStaticContext((CtElement) inv);
    }



    // Canonical helper: works for invocations, field init exprs, static blocks, static methods
    private boolean isInStaticContext(CtElement e) {
        CtElement cur = e;
        while (cur != null) {
            if (cur instanceof CtMethod<?>) {
                return ((CtMethod<?>) cur)
                        .hasModifier(ModifierKind.STATIC);
            }
            if (cur instanceof CtField<?>) {
                return ((CtField<?>) cur)
                        .hasModifier(ModifierKind.STATIC);
            }
            if (cur instanceof CtAnonymousExecutable) {
                return ((CtAnonymousExecutable) cur)
                        .hasModifier(ModifierKind.STATIC);
            }
            if (cur instanceof CtType<?>) break; // stop at type boundary
            cur = cur.getParent();
        }
        return false;
    }




    /** Rebinds any TypeReference equal to unknown.<Simple> to a real <pkg>.<Simple> type if present.
     * Prefers the current CU package, else any non-unknown package.
     */
    private void rebindUnknownHomonyms(CtModel model, CollectResult collectResult) {
        // index: which non-unknown types exist or are planned
        Set<String> existingOrPlanned = new HashSet<>();
        model.getAllTypes().forEach(t -> {
            String qn = safeQN(t.getReference());
            if (qn != null && !qn.startsWith("unknown.")) existingOrPlanned.add(qn);
        });
        // also add planned type owners
        for (TypeStubPlan tp : collectResult.typePlans) {
            if (tp.qualifiedName != null && !tp.qualifiedName.startsWith("unknown."))
                existingOrPlanned.add(tp.qualifiedName);
        }

        for (CtType<?> t : model.getAllTypes()) {
            CtCompilationUnit cu = t.getPosition() != null ? t.getPosition().getCompilationUnit() : null;
            String cuPkg = (cu != null && cu.getDeclaredPackage() != null ? cu.getDeclaredPackage().getQualifiedName() : "");
            if (cuPkg == null) cuPkg = "";

            for (CtTypeReference<?> ref : t.getElements(new TypeFilter<>(CtTypeReference.class))) {
                String qn = safeQN(ref);
                if (qn == null || !qn.startsWith("unknown.")) continue;

                String simple = ref.getSimpleName();
                if (simple == null || simple.isEmpty()) continue;

                String samePkgFqn = (cuPkg.isEmpty() ? simple : cuPkg + "." + simple);
                if (existingOrPlanned.contains(samePkgFqn)) {
                    // rewrite to same-package type
                    CtTypeReference<?> newRef = f.Type().createReference(samePkgFqn);
                    ref.setPackage(newRef.getPackage());
                    ref.setSimpleName(newRef.getSimpleName());
                }
            }
        }

        // optional: remove useless unknown.* type plans if a same-name non-unknown plan exists
        collectResult.typePlans.removeIf(tp ->
                tp.qualifiedName != null
                        && tp.qualifiedName.startsWith("unknown.")
                        && existingOrPlanned.contains(tp.qualifiedName.substring("unknown.".length())) // crude; keep if you don’t maintain a map
        );
    }




    private void collectTryWithResources(CtModel model, CollectResult out) {
        for (CtTry twr : model.getElements(new TypeFilter<>(CtTry.class))) {

            // 1) Try to call CtTry#getResources() reflectively (if present in this Spoon version)
            List<CtLocalVariable<?>> res = null;
            try {
                Method m = twr.getClass().getMethod("getResources");
                @SuppressWarnings("unchecked")
                List<CtLocalVariable<?>> tmp = (List<CtLocalVariable<?>>) m.invoke(twr);
                res = tmp;
            } catch (Throwable ignore) {
                // 2) Fallback: collect locals whose role in parent is a resource of this CtTry
                res = new ArrayList<>();
                for (CtLocalVariable<?> lv :
                        twr.getElements(new TypeFilter<>(CtLocalVariable.class))) {
                    if (lv.getParent(CtTry.class) == twr) {
                        CtRole role = lv.getRoleInParent();
                        // some Spoon versions use RESOURCE or RESOURCES; be defensive:
                        if (role != null && role.name().toLowerCase().contains("resource")) {
                            res.add(lv);
                        }
                    }
                }
            }

            if (res == null || res.isEmpty()) continue;

            for (CtLocalVariable<?> r : res) {
                CtTypeReference<?> rt = r.getType();
                if (rt == null) continue;

                CtTypeReference<?> owner = chooseOwnerPackage(rt, twr);
                // skip JDK types and unknowns
                if (owner == null || isJdkType(owner)) continue;

                // Attach AutoCloseable (do NOT create java.lang.AutoCloseable)
                out.implementsPlans
                        .computeIfAbsent(owner.getQualifiedName(), k -> new LinkedHashSet<>())
                        .add(f.Type().createReference("java.lang.AutoCloseable"));

                // Plan abstract close()
                out.methodPlans.add(new MethodStubPlan(
                        owner,
                        "close",
                        f.Type().VOID_PRIMITIVE,
                        Collections.emptyList(),
                        /*isStatic*/ false,
                        MethodStubPlan.Visibility.PUBLIC,
                        Collections.emptyList(),
                        /*defaultOnIface*/ false,
                        /*isAbstract*/ true,
                        /*isFinal*/ false,
                        /*varargsOnLast*/ null
                ));
            }
        }
    }



    private CtPackage ensurePackage(String pkgFqn) {
        if (pkgFqn == null || pkgFqn.isEmpty()) return f.getModel().getRootPackage();
        CtPackage p = f.getModel().getRootPackage().getFactory().Package().get(pkgFqn);
        if (p == null) p = f.Package().getOrCreate(pkgFqn);
        return p;
    }




    private CtAnnotationType<?> ensureAnnotation(String fqn) {
        int i = fqn.lastIndexOf('.');
        String pkg = (i >= 0 ? fqn.substring(0, i) : "");
        String sn  = (i >= 0 ? fqn.substring(i + 1) : fqn);

        CtPackage p = ensurePackage(pkg);
        CtType<?> existing = f.Type().get(fqn);
        if (existing instanceof CtAnnotationType) {
            return (CtAnnotationType<?>) existing;
        }
        if (existing != null) existing.delete();
        return f.Annotation().create(p, sn);
    }

    // Create (if missing) an element method on an annotation type
    private CtAnnotationMethod<?> ensureAnnotationElement(
            CtAnnotationType<?> ann,
            String name,
            CtTypeReference<?> returnType
    ) {
        // already there?
        for (CtMethod<?> m : ann.getMethods()) {
            if (name.equals(m.getSimpleName())) {
                return (CtAnnotationMethod<?>) m;
            }
        }

        // create a new annotation element
        CtAnnotationMethod<?> m = f.Core().createAnnotationMethod();
        m.setSimpleName(name);
        m.setType(returnType);
        // (public abstract are implicit on annotation elements; modifiers are optional)
        ann.addMethod(m);   // important: register it on the CtAnnotationType
        return m;
    }


    // Ensure @Repeatable(container) on 'tagAnn'
    private void addRepeatableMeta(
            CtAnnotationType<?> tagAnn,
            CtAnnotationType<?> containerAnn
    ) {
        // @Repeatable
        CtTypeReference<Repeatable> repeatableRef =
                f.Type().createReference(Repeatable.class);

        CtAnnotation<?> rep = f.Core().createAnnotation();
        rep.setAnnotationType(repeatableRef);

        // value = Container.class  (class literal expression)
        CtTypeReference<?> containerRef = containerAnn.getReference();
        CtExpression<?> classLiteral = f.Code().createClassAccess(containerRef);
        rep.addValue("value", classLiteral);

        tagAnn.addAnnotation(rep);
    }





    private void ensureRepeatableAnnotation(String annFqn) {
        CtAnnotationType<?> tag = ensureAnnotation(annFqn);

        String containerFqn = annFqn + "$Container";  // or "...Tags" if you prefer
        CtAnnotationType<?> container = ensureAnnotation(containerFqn);

        // Tag: String value();
        ensureAnnotationElement(tag, "value", f.Type().createReference("java.lang.String"));

        // Container: Tag[] value();
        CtArrayTypeReference<?> tagArray = f.Core().createArrayTypeReference();
        tagArray.setComponentType(tag.getReference());
        ensureAnnotationElement(container, "value", tagArray);

        // @Repeatable(Container.class)
        addRepeatableMeta(tag, container);
    }



    private void ensureRepeatablesForDuplicateUses(CtModel model) {
        for (CtType<?> t : model.getAllTypes()) {
            // scan type
            List<CtAnnotation<?>> anns = t.getAnnotations();
            ensureRepeatableIfDuplicate(anns);

            // scan members
            for (CtTypeMember m : t.getTypeMembers()) {
                if (m instanceof CtAnnotationMethod || m instanceof CtMethod || m instanceof CtConstructor
                        || m instanceof CtField || m instanceof CtAnonymousExecutable) {
                    ensureRepeatableIfDuplicate(((CtModifiable) m).getAnnotations());
                }
            }
        }
    }

    private void ensureRepeatableIfDuplicate(List<CtAnnotation<?>> anns) {
        if (anns == null || anns.isEmpty()) return;
        Map<String, List<CtAnnotation<?>>> byType =
                anns.stream().collect(Collectors.groupingBy(a -> safeQN(a.getAnnotationType())));
        byType.forEach((annFqn, list) -> {
            if (annFqn == null) return;
            if (list.size() > 1) {
                ensureRepeatableAnnotation(annFqn);
            }
        });
    }


    private void addTypePlanIfNonJdk(CollectResult out, String fqn, TypeStubPlan.Kind kind) {
        if (fqn == null || fqn.isEmpty()) return;
        if (isJdkFqn(fqn)) return;
        // Validate FQN: must have a simple name (not just a package)
        // Reject FQNs like "unknown." or "package." (ending with dot but no simple name)
        if (fqn.endsWith(".")) return; // Invalid: ends with dot but no simple name
        int lastDot = fqn.lastIndexOf('.');
        String simpleName = (lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn);
        if (simpleName == null || simpleName.isEmpty()) return; // Invalid: no simple name
        out.typePlans.add(new TypeStubPlan(fqn, kind));
    }
    
    /**
     * Safely add a type plan from a type reference, validating the qualified name.
     */
    private void addTypePlanFromRef(CollectResult out, CtTypeReference<?> typeRef, TypeStubPlan.Kind kind) {
        if (typeRef == null) return;
        try {
            String fqn = typeRef.getQualifiedName();
            if (fqn == null || fqn.isEmpty()) return;
            // Validate: check if simple name is empty
            String simpleName = typeRef.getSimpleName();
            if (simpleName == null || simpleName.isEmpty()) return; // Invalid: no simple name
            addTypePlanIfNonJdk(out, fqn, kind);
        } catch (Throwable ignored) {}
    }


    private static boolean isJdkFqn(String qn) {
        return qn != null && (qn.startsWith("java.")
                || qn.startsWith("javax.")
                || qn.startsWith("jakarta.")
                || qn.startsWith("sun.")
                || qn.startsWith("jdk."));
    }

    // Return (ret, params) shape from FI type if we have it; otherwise default to (int -> int)
    // Return (ret, params) from FI if declared; otherwise default to (int -> int)
    private Map.Entry<CtTypeReference<?>, List<CtTypeReference<?>>> samShapeOrDefault(CtTypeReference<?> fiRef) {
        try {
            CtType<?> decl = (fiRef != null ? fiRef.getTypeDeclaration() : null);
            if (decl instanceof CtInterface) {
                CtMethod<?> sam = decl.getMethods().stream()
                        .filter(m -> {
                            try {
                                boolean isStatic = m.hasModifier(ModifierKind.STATIC);
                                boolean isAbstract = m.isAbstract() || m.getBody() == null;
                                return !isStatic && isAbstract;
                            } catch (Throwable ignored) {
                                return m.getBody() == null; // fallback
                            }
                        })
                        .findFirst().orElse(null);

                if (sam != null) {
                    CtTypeReference<?> ret = (sam.getType() != null ? sam.getType() : f.Type().VOID_PRIMITIVE);
                    @SuppressWarnings("unchecked")
                    List<CtTypeReference<?>> ps = (List<CtTypeReference<?>>) (List<?>) sam.getParameters().stream()
                            .map(CtParameter::getType)
                            .collect(Collectors.toList());
                    return Map.entry(ret, ps);
                }
            }
        } catch (Throwable ignored) {
            // fall through to default
        }
        // default: int -> int
        return Map.entry(f.Type().INTEGER_PRIMITIVE, List.of(f.Type().INTEGER_PRIMITIVE));
    }


    private void collectMethodReferences(CtModel model, CollectResult out) {
        List<CtExecutableReferenceExpression<?, ?>> mrefs =
                model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class));

        for (CtExecutableReferenceExpression<?, ?> mref : mrefs) {
            CtExecutableReference<?> ex = mref.getExecutable();
            if (ex != null && ex.getDeclaration() != null) continue; // resolved already

            // ---- (0) Determine FI type (prefer LHS local var if Spoon gave unknown) ----
            CtTypeReference<?> fiType = mref.getType();
            String fiQn = safeQN(fiType);
            if (fiType == null || fiQn == null || fiQn.startsWith("unknown.")) {
                CtLocalVariable<?> lv = mref.getParent(CtLocalVariable.class);
                if (lv != null && lv.getType() != null) {
                    fiType = chooseOwnerPackage(lv.getType(), mref);
                    fiQn = safeQN(fiType);
                }
            }
            // If still unknown, keep going; we’ll at least fix the owner of the reference.

            // ---- (1) Determine the referenced method name early (needed for constructor detection) ----
            String name = (ex != null ? ex.getSimpleName() : null);
            String mrefText = String.valueOf(mref);

// --- NEW: detect constructor method refs robustly ---
            boolean isCtorRef =
                    (name == null && mrefText != null && mrefText.contains("::new"))   // textual fallback
                            || "new".equals(name) || "<init>".equals(name);

            // ---- (2) Ensure FI exists as an INTERFACE (never a class) + stub SAM ----
            if (fiQn != null
                    && !(fiQn.startsWith("java.") || fiQn.startsWith("javax.") ||
                    fiQn.startsWith("jakarta.") || fiQn.startsWith("sun.")  ||
                    fiQn.startsWith("jdk."))) {
                out.typePlans.add(new TypeStubPlan(fiQn, TypeStubPlan.Kind.INTERFACE));

                // Figure out SAM (return + params) with your helper
                var shape = samShapeOrDefault(fiType); // Pair<ret, List<params>>
                CtTypeReference<?> samRet = shape.getKey();
                List<CtTypeReference<?>> samParams = shape.getValue();

                // For constructor references, infer SAM from the constructor signature
                if (isCtorRef) {
                    CtExpression<?> target = mref.getTarget();
                    CtTypeReference<?> constructedType = null;
                    
                    if (target instanceof CtTypeAccess<?>) {
                        constructedType = ((CtTypeAccess<?>) target).getAccessedType();
                    }
                    
                    // Also try to infer from method reference text (e.g., "String[]::new")
                    if (constructedType == null) {
                        String mrText = String.valueOf(mref);
                        if (mrText != null && mrText.contains("::new")) {
                            int newIdx = mrText.indexOf("::new");
                            if (newIdx > 0) {
                                String typePart = mrText.substring(0, newIdx).trim();
                                // Check if it's an array type
                                if (typePart.endsWith("[]")) {
                                    // Array constructor - create array type reference
                                    String elementType = typePart.substring(0, typePart.length() - 2);
                                    try {
                                        CtTypeReference<?> elemRef = f.Type().createReference(elementType);
                                        constructedType = f.Type().createArrayReference(elemRef);
                                    } catch (Throwable ignored) {
                                        // Fallback: try to create from full string
                                        try {
                                            constructedType = f.Type().createReference(typePart);
                                        } catch (Throwable ignored2) {}
                                    }
                                } else {
                                    // Non-array type
                                    try {
                                        constructedType = f.Type().createReference(typePart);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                    
                    if (constructedType != null) {
                        // Constructor reference: Type::new takes params and returns Type
                        samRet = constructedType.clone();
                        // For array constructors like String[]::new, default to int parameter
                        boolean isArray = false;
                        try {
                            isArray = constructedType.isArray();
                        } catch (Throwable ignored) {}
                        String typeQn = safeQN(constructedType);
                        if (!isArray && typeQn != null && typeQn.endsWith("[]")) {
                            isArray = true;
                        }
                        if (isArray && samParams.isEmpty()) {
                            samParams = Collections.singletonList(f.Type().createReference("int"));
                        }
                        // Note: For non-array constructors, we'll infer params from constructor plans below
                    }
                }

                // Stub abstract SAM: use "make" for constructor references, "apply" for others
                String samMethodName = isCtorRef ? "make" : "apply";
                // For constructor references, we'll infer params from the constructor below
                // Don't set default params here - let the constructor reference handling do it
                if (fiType != null && !isJdkType(fiType)) {
                    out.methodPlans.add(new MethodStubPlan(
                            fiType,
                            samMethodName,
                            samRet,
                            samParams,
                            /*isStatic*/ false,
                            MethodStubPlan.Visibility.PUBLIC,
                            Collections.emptyList(),
                            /*defaultOnInterface*/ false,  // abstract
                            /*varargs*/ false,
                            /*mirror*/ false,
                            /*mirrorOwnerRef*/ null
                    ));
                }
            }

// ---- (3) Choose owner correctly: static Type::m vs instance obj::m ----
            CtExpression<?> target = mref.getTarget();
            CtTypeReference<?> ownerRef = null;
            boolean isStatic = false;

            if (target instanceof CtTypeAccess<?>) {
                // For constructor refs, owner is exactly the LHS type
                String forcedQN = null;
                if (isCtorRef) {
                    CtTypeReference<?> lhs = ((CtTypeAccess<?>) target).getAccessedType();
                    // For array types, ensure we keep the array type (not just element type)
                    if (lhs != null) {
                        boolean isArray = false;
                        try {
                            isArray = lhs.isArray();
                        } catch (Throwable ignored) {}
                        if (isArray) {
                            ownerRef = lhs.clone(); // Keep array type as-is
                        } else {
                    ownerRef = chooseOwnerPackage(lhs, mref);
                        }
                    } else {
                        ownerRef = chooseOwnerPackage(lhs, mref);
                    }
                    isStatic = false; // ctors are never static
                } else {
                    // existing logic for static Type::method ...
                    String mrText = mrefText;
                    int idx = (mrText != null ? mrText.indexOf("::") : -1);
                    if (idx > 0) {
                        String left = mrText.substring(0, idx).trim();
                        String curPkg = Optional.ofNullable(mref.getParent(CtType.class))
                                .map(CtType::getPackage).map(CtPackage::getQualifiedName).orElse(null);
                        // If left has no dots and we have a current package, use it
                        if (left.indexOf('.') < 0 && curPkg != null && !curPkg.isEmpty()) {
                            forcedQN = curPkg + "." + left;
                        } else {
                            forcedQN = left;
                        }
                        ownerRef = f.Type().createReference(forcedQN);
                        isStatic = true;
                        
                        // Ensure the owner type is stubbed (e.g., "A" in "A::inc")
                        if (forcedQN != null && !isJdkFqn(forcedQN)) {
                            out.typePlans.add(new TypeStubPlan(forcedQN, TypeStubPlan.Kind.CLASS));
                        }
                    } else if (target instanceof CtTypeAccess<?>) {
                        // Fallback: try to get the type from the target directly
                        try {
                            CtTypeReference<?> targetType = ((CtTypeAccess<?>) target).getAccessedType();
                            ownerRef = chooseOwnerPackage(targetType, mref);
                            String ownerQn = safeQN(ownerRef);
                            if (ownerQn != null && !ownerQn.isEmpty() && !isJdkFqn(ownerQn)) {
                                addTypePlanIfNonJdk(out, ownerQn, TypeStubPlan.Kind.CLASS);
                            }
                        } catch (Throwable ignored) {
                            // If that fails, try to create a reference from the text directly
                            if (forcedQN == null && mrText != null) {
                                int idx2 = mrText.indexOf("::");
                                if (idx2 > 0) {
                                    String left2 = mrText.substring(0, idx2).trim();
                                    String curPkg2 = Optional.ofNullable(mref.getParent(CtType.class))
                                            .map(CtType::getPackage).map(CtPackage::getQualifiedName).orElse(null);
                                    if (left2.indexOf('.') < 0 && curPkg2 != null && !curPkg2.isEmpty()) {
                                        forcedQN = curPkg2 + "." + left2;
                                    } else if (!left2.isEmpty()) {
                                        forcedQN = left2;
                                    }
                                    if (forcedQN != null && !isJdkFqn(forcedQN)) {
                                        ownerRef = f.Type().createReference(forcedQN);
                                        out.typePlans.add(new TypeStubPlan(forcedQN, TypeStubPlan.Kind.CLASS));
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // INSTANCE METHOD REF: obj::method OR unresolved Type::method
                // Check if this might be a static method reference with unresolved type
                String mrText = mrefText;
                int idx = (mrText != null ? mrText.indexOf("::") : -1);
                
                // Check if target is a variable read that might actually be an unresolved type
                boolean mightBeUnresolvedType = false;
                if (target != null) {
                    try {
                        // If target is a variable read but the variable doesn't exist, it's likely a type
                        if (target instanceof CtVariableRead<?>) {
                            CtVariableRead<?> vr = (CtVariableRead<?>) target;
                            try {
                                CtVariableReference<?> varRef = vr.getVariable();
                                if (varRef == null || varRef.getDeclaration() == null) {
                                    mightBeUnresolvedType = true; // Variable doesn't exist, likely a type
                                }
                            } catch (Throwable ignored) {
                                mightBeUnresolvedType = true; // Can't resolve variable, likely a type
                            }
                        } else if (target instanceof CtVariableAccess<?>) {
                            CtVariableAccess<?> va = (CtVariableAccess<?>) target;
                            try {
                                CtVariableReference<?> varRef = va.getVariable();
                                if (varRef == null || varRef.getDeclaration() == null) {
                                    mightBeUnresolvedType = true;
                                }
                            } catch (Throwable ignored) {
                                mightBeUnresolvedType = true;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                
                if (idx > 0 && (target == null || mightBeUnresolvedType)) {
                    // Target is null or unresolved variable (likely type) - treat as Type::method
                    String left = mrText.substring(0, idx).trim();
                    String curPkg = Optional.ofNullable(mref.getParent(CtType.class))
                            .map(CtType::getPackage).map(CtPackage::getQualifiedName).orElse(null);
                    // If left has no dots and we have a current package, treat as Type::method
                    if (left.indexOf('.') < 0 && curPkg != null && !curPkg.isEmpty()) {
                        String forcedQN = curPkg + "." + left;
                        ownerRef = f.Type().createReference(forcedQN);
                        isStatic = true;
                        // Ensure the owner type is stubbed
                        if (!isJdkFqn(forcedQN)) {
                            out.typePlans.add(new TypeStubPlan(forcedQN, TypeStubPlan.Kind.CLASS));
                        }
                    } else if (!left.isEmpty() && left.indexOf('.') < 0) {
                        // Simple name without package - try current package first
                        if (curPkg != null && !curPkg.isEmpty()) {
                            String forcedQN = curPkg + "." + left;
                            ownerRef = f.Type().createReference(forcedQN);
                            isStatic = true;
                            if (!isJdkFqn(forcedQN)) {
                                out.typePlans.add(new TypeStubPlan(forcedQN, TypeStubPlan.Kind.CLASS));
                            }
                        } else {
                            // No package info - create as-is (will be in default package or unknown)
                            ownerRef = f.Type().createReference(left);
                            isStatic = true;
                            if (!isJdkFqn(left)) {
                                out.typePlans.add(new TypeStubPlan(left, TypeStubPlan.Kind.CLASS));
                            }
                        }
                    } else {
                        // Has dots or empty - treat as instance method ref
                CtTypeReference<?> objT = (target != null ? target.getType() : null);
                ownerRef = chooseOwnerPackage(objT, mref);
                isStatic = false;
                    }
                } else {
                    // INSTANCE METHOD REF: obj::method (target is a valid expression)
                    CtTypeReference<?> objT = (target != null ? target.getType() : null);
                    ownerRef = chooseOwnerPackage(objT, mref);
                    isStatic = false;
                }
            }

// If owner unresolved or JDK, bail
            if (ownerRef == null || isJdkType(ownerRef)) continue;

// ---- (4) SAM shape (ret + params) ----
            var shape2 = samShapeOrDefault(fiType);
            CtTypeReference<?> samRet2 = shape2.getKey();
            List<CtTypeReference<?>> samParams2 = shape2.getValue();

// --- NEW: plan CONSTRUCTOR for ::new, otherwise plan METHOD ---
            if (isCtorRef) {
                // Ensure owner type exists (non-JDK)
                String ownerQn = safeQN(ownerRef);
                if (ownerQn != null && !isJdkFqn(ownerQn)) {
                    addTypePlanIfNonJdk(out, ownerQn, TypeStubPlan.Kind.CLASS);
                }
                // Before adding constructor plan, check if we already have one for this type
                // and use its parameters for the SAM method
                List<CtTypeReference<?>> ctorParams = samParams2;
                String ownerQnForCtor = safeQN(ownerRef);
                
                // Try to infer constructor parameters from call sites
                // If the functional interface variable is used (e.g., `Maker m = A::new; m.make()`),
                // use the arguments from `m.make()` to infer the constructor parameters
                if (fiQn != null) {
                    CtLocalVariable<?> lv = mref.getParent(CtLocalVariable.class);
                    System.err.println("[collectMethodReferences] Processing constructor reference for FI: " + fiQn + 
                        ", local variable: " + (lv != null ? lv.getSimpleName() : "null"));
                    if (lv != null) {
                        // Find all invocations on this variable (e.g., `m.make()`)
                        String varName = lv.getSimpleName();
                        System.err.println("[collectMethodReferences] Looking for calls on variable: " + varName);
                        
                        // Look for method calls on this variable anywhere in the model
                        List<CtInvocation<?>> callsOnVar = model.getElements((CtInvocation<?> inv) -> {
                            CtExpression<?> targetinv = inv.getTarget();
                            if (targetinv instanceof CtVariableRead<?>) {
                                CtVariableRead<?> vr = (CtVariableRead<?>) targetinv;
                                try {
                                    CtVariableReference<?> varRef = vr.getVariable();
                                    if (varRef != null) {
                                        String targetVarName = varRef.getSimpleName();
                                        if (varName.equals(targetVarName)) {
                                            // Check if this is a call to the SAM method (make/apply)
                                            CtExecutableReference<?> exexe = inv.getExecutable();
                                            String methodName = (exexe != null ? exexe.getSimpleName() : null);
                                            System.err.println("[collectMethodReferences] Found call on variable " + varName + 
                                                ": " + methodName + " with " + inv.getArguments().size() + " args");
                                            if ("make".equals(methodName) || "apply".equals(methodName)) {
                                                return true;
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    System.err.println("[collectMethodReferences] Error checking variable: " + t.getMessage());
                                }
                            }
                            return false;
                        });
                        
                        System.err.println("[collectMethodReferences] Found " + callsOnVar.size() + " calls on variable " + varName);
                        
                        // If we found calls, use the arguments from the first call to infer constructor params
                        if (!callsOnVar.isEmpty()) {
                            CtInvocation<?> firstCall = callsOnVar.get(0);
                            List<CtExpression<?>> callArgs = firstCall.getArguments();
                            if (!callArgs.isEmpty()) {
                                // Infer parameter types from the call arguments
                                CtExecutableReference<?> callEx = firstCall.getExecutable();
                                ctorParams = inferParamTypesFromCall(callEx, callArgs);
                                System.err.println("[collectMethodReferences] Inferred constructor params from call site: " + 
                                    ctorParams.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")));
                            } else {
                                // No arguments means no-arg constructor
                                ctorParams = new ArrayList<>();
                                System.err.println("[collectMethodReferences] Inferred no-arg constructor from call site (m.make() with no args)");
                            }
                        } else {
                            System.err.println("[collectMethodReferences] No calls found on variable " + varName + ", will use fallback");
                        }
                    }
                }
                
                // Track if we inferred from call site
                boolean inferredFromCallSite = (ctorParams != samParams2);
                
                if (ownerQnForCtor != null && !inferredFromCallSite) {
                    // Look for existing constructor plans for this type (fallback)
                    System.err.println("[collectMethodReferences] No call site found, checking existing constructor plans for " + ownerQnForCtor);
                    for (ConstructorStubPlan existingCtor : out.ctorPlans) {
                        try {
                            String existingOwnerQn = safeQN(existingCtor.ownerType);
                            if (ownerQnForCtor.equals(existingOwnerQn) && !existingCtor.parameterTypes.isEmpty()) {
                                // Use the existing constructor's parameters
                                ctorParams = new ArrayList<>(existingCtor.parameterTypes);
                                System.err.println("[collectMethodReferences] Using existing constructor plan: " + 
                                    ctorParams.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")));
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    // If still no params, default to empty (no-arg constructor)
                    if (ctorParams == samParams2 || ctorParams.isEmpty()) {
                        System.err.println("[collectMethodReferences] No constructor params found, defaulting to no-arg constructor");
                        ctorParams = new ArrayList<>();
                    }
                }
                
                System.err.println("[collectMethodReferences] Final constructor params for " + ownerQnForCtor + ": " + 
                    ctorParams.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + 
                    " (size: " + ctorParams.size() + ")");
                
                out.ctorPlans.add(new ConstructorStubPlan(ownerRef, ctorParams));
                
                // For constructor references, create/update the SAM method plan with correct signature
                if (fiQn != null && !isJdkFqn(fiQn)) {
                    // The SAM should return the constructed type (ownerRef) and take constructor params
                    CtTypeReference<?> ctorReturnType = ownerRef.clone();
                    
                    // Remove any existing "make" or "apply" method plan for this functional interface
                    String finalFiQn = fiQn;
                    out.methodPlans.removeIf(p -> {
                        try {
                            String pOwnerQn = safeQN(p.ownerType);
                            return finalFiQn.equals(pOwnerQn) &&
                                   ("make".equals(p.name) || "apply".equals(p.name));
                        } catch (Throwable ignored) {
                            return false;
                        }
                    });
                    
                    // Add the correct "make" method plan with constructor parameters
                    if (fiType != null && !isJdkType(fiType)) {
                        out.methodPlans.add(new MethodStubPlan(
                                fiType,
                                "make",
                                ctorReturnType,
                                ctorParams,  // Use constructor parameters (from existing constructor plan or inferred)
                                /*isStatic*/ false,
                                MethodStubPlan.Visibility.PUBLIC,
                                Collections.emptyList(),
                                /*defaultOnInterface*/ false,
                                /*varargs*/ false,
                                /*mirror*/ false,
                                /*mirrorOwnerRef*/ null
                        ));
                    }
                }
                continue; // do not add a method plan for ::new
            }

// Existing method plan path
            out.methodPlans.add(new MethodStubPlan(
                    ownerRef, name, samRet2, samParams2,
                    /*isStatic*/ isStatic,
                    MethodStubPlan.Visibility.PUBLIC,
                    Collections.emptyList(),
                    /*defaultOnInterface*/ false,
                    /*varargs*/ false,
                    /*mirror*/ false,
                    /*mirrorOwnerRef*/ null
            ));

        }
    }

    /**
     * Collect lambda expressions and ensure their target types are created as INTERFACES (functional interfaces).
     */
    private void collectLambdas(CtModel model, CollectResult out) {
        List<CtLambda<?>> lambdas =
                model.getElements(new TypeFilter<>(CtLambda.class));

        for (CtLambda<?> lambda : lambdas) {
            // Find the variable this lambda is assigned to
            CtLocalVariable<?> lv = lambda.getParent(CtLocalVariable.class);
            if (lv != null && lv.getType() != null) {
                CtTypeReference<?> fiType = chooseOwnerPackage(lv.getType(), lambda);
                String fiQn = safeQN(fiType);
                
                // Debug: log lambda collection
                System.err.println("[collectLambdas] Processing lambda for functional interface: " + fiQn);
                
                if (fiQn != null && !isJdkFqn(fiQn)) {
                    // Mark this type as an INTERFACE (functional interface)
                    // Check if it's already planned as a CLASS and update it
                    boolean found = false;
                    for (TypeStubPlan plan : new ArrayList<>(out.typePlans)) {
                        if (fiQn.equals(plan.qualifiedName)) {
                            if (plan.kind == TypeStubPlan.Kind.CLASS) {
                                // Replace CLASS plan with INTERFACE plan
                                out.typePlans.remove(plan);
                                out.typePlans.add(new TypeStubPlan(fiQn, TypeStubPlan.Kind.INTERFACE));
                            }
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // Add new INTERFACE plan
                        out.typePlans.add(new TypeStubPlan(fiQn, TypeStubPlan.Kind.INTERFACE));
                    }
                    
                    // Infer SAM signature from lambda
                    try {
                        List<CtParameter<?>> lambdaParams = lambda.getParameters();
                        CtTypeReference<?> lambdaReturnType = null;
                        try {
                            lambdaReturnType = lambda.getType();
                        } catch (Throwable ignored) {}
                        
                        if (lambdaReturnType == null) {
                            // Try to infer from lambda body
                            CtStatement body = lambda.getBody();
                            CtExpression<?> bodyExpr = null;
                            
                            // Extract expression from body (could be return statement or direct expression)
                            if (body instanceof CtReturn<?>) {
                                bodyExpr = ((CtReturn<?>) body).getReturnedExpression();
                            } else if (body instanceof CtExpression) {
                                bodyExpr = (CtExpression<?>) body;
                            }
                            
                            if (bodyExpr != null) {
                                // For binary expressions like i + 1, check FIRST before trying getType()
                                // because getType() might return void or null for unresolved expressions
                                if (bodyExpr instanceof CtBinaryOperator) {
                                    CtBinaryOperator<?> bin = (CtBinaryOperator<?>) bodyExpr;
                                    try {
                                        CtTypeReference<?> leftType = null;
                                        CtTypeReference<?> rightType = null;
                                        try {
                                            leftType = bin.getLeftHandOperand().getType();
                                        } catch (Throwable ignored) {}
                                        try {
                                            rightType = bin.getRightHandOperand().getType();
                                        } catch (Throwable ignored) {}
                                        
                                        // Also check the operands themselves if types are null
                                        if (leftType == null) {
                                            try {
                                                CtExpression<?> left = bin.getLeftHandOperand();
                                                if (left instanceof CtVariableRead) {
                                                    CtParameter<?> param = lambda.getParameters().isEmpty() ? null : lambda.getParameters().get(0);
                                                    if (param != null) {
                                                        leftType = param.getType();
                                                    }
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                        if (rightType == null) {
                                            try {
                                                CtExpression<?> right = bin.getRightHandOperand();
                                                if (right instanceof CtLiteral) {
                                                    Object val = ((CtLiteral<?>) right).getValue();
                                                    if (val instanceof Integer || val instanceof Long || val instanceof Short || val instanceof Byte) {
                                                        rightType = f.Type().INTEGER_PRIMITIVE;
                                                    }
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                        
                                        // Check if operands are numeric (int or Integer)
                                        boolean leftIsInt = false;
                                        boolean rightIsInt = false;
                                        if (leftType != null) {
                                            String leftQn = safeQN(leftType);
                                            leftIsInt = "int".equals(leftQn) || "java.lang.Integer".equals(leftQn) ||
                                                       f.Type().INTEGER_PRIMITIVE.equals(leftType);
                                        }
                                        if (rightType != null) {
                                            String rightQn = safeQN(rightType);
                                            rightIsInt = "int".equals(rightQn) || "java.lang.Integer".equals(rightQn) ||
                                                        f.Type().INTEGER_PRIMITIVE.equals(rightType);
                                        }
                                        
                                        // For numeric operations (+, -, *, /, etc.), result is int
                                        if (leftIsInt || rightIsInt) {
                                            lambdaReturnType = f.Type().INTEGER_PRIMITIVE;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                
                                // If still not set, try to get type directly
                                if (lambdaReturnType == null) {
                                    try {
                                        lambdaReturnType = bodyExpr.getType();
                                    } catch (Throwable ignored) {}
                                }
                                
                                // If still null or void, try to infer from expression structure
                                if (lambdaReturnType == null || 
                                    (lambdaReturnType != null && safeQN(lambdaReturnType) != null && 
                                     (safeQN(lambdaReturnType).equals("void") || safeQN(lambdaReturnType).equals("java.lang.Void")))) {
                                    
                                    // For literals, infer type from literal value
                                    if (bodyExpr instanceof CtLiteral) {
                                        CtLiteral<?> lit = (CtLiteral<?>) bodyExpr;
                                        Object value = lit.getValue();
                                        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                                            lambdaReturnType = f.Type().INTEGER_PRIMITIVE;
                                        } else if (value instanceof Double || value instanceof Float) {
                                            lambdaReturnType = f.Type().DOUBLE_PRIMITIVE;
                                        } else if (value instanceof Boolean) {
                                            lambdaReturnType = f.Type().BOOLEAN_PRIMITIVE;
                                        } else if (value instanceof Character) {
                                            lambdaReturnType = f.Type().CHARACTER_PRIMITIVE;
                                        } else if (value instanceof String) {
                                            lambdaReturnType = f.Type().STRING;
                                        }
                                    }
                                }
                            }
                        }
                        // Final fallback: if still null or void, try to infer from context
                        if (lambdaReturnType == null || 
                            (lambdaReturnType != null && safeQN(lambdaReturnType) != null && 
                             (safeQN(lambdaReturnType).equals("void") || safeQN(lambdaReturnType).equals("java.lang.Void")))) {
                            // Check if lambda is assigned to a variable with a generic type
                            CtLocalVariable<?> lvForType = lambda.getParent(CtLocalVariable.class);
                            if (lvForType != null && lvForType.getType() != null) {
                                try {
                                    // Try to infer from the functional interface's generic type
                                    // For F<Integer>, the return type might be Integer or int
                                    // Default to int for numeric operations
                                    lambdaReturnType = f.Type().INTEGER_PRIMITIVE;
                                } catch (Throwable ignored) {
                                    lambdaReturnType = f.Type().VOID_PRIMITIVE;
                                }
                            } else {
                                lambdaReturnType = f.Type().VOID_PRIMITIVE;
                            }
                        }
                        
                        List<CtTypeReference<?>> samParams = new ArrayList<>();
                        for (CtParameter<?> param : lambdaParams) {
                            CtTypeReference<?> paramType = param.getType();
                            if (paramType != null) {
                                // Use the exact parameter type from lambda (preserve generics like Integer, not int)
                                samParams.add(paramType.clone());
                            } else {
                                samParams.add(f.Type().createReference("java.lang.Object"));
                            }
                        }
                        
                        // Remove ALL existing "apply" and "make" method plans for this functional interface
                        // and replace with the correct one from the lambda
                        // This ensures functional interfaces have only ONE abstract method
                        // Note: We remove ALL SAM methods regardless of parameter types (int vs Integer)
                        // because functional interfaces can only have ONE abstract method
                        String finalFiQn = fiQn;
                        // Also get the erased FQN to handle generic types (F vs F<Integer>)
                        String finalFiQnErased = erasureFqn(fiType);
                        
                        // Debug: log existing methods before removal
                        System.err.println("[collectLambdas] Checking existing method plans for " + finalFiQn + " (erased: " + finalFiQnErased + ")");
                        System.err.println("[collectLambdas] Total method plans: " + out.methodPlans.size());
                        for (MethodStubPlan p : out.methodPlans) {
                            try {
                                String pOwnerQn = safeQN(p.ownerType);
                                String pOwnerQnErased = erasureFqn(p.ownerType);
                                System.err.println("  - Existing: " + pOwnerQn + " (erased: " + pOwnerQnErased + ") #" + p.name + 
                                    "(" + p.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
                            } catch (Throwable ignored) {}
                        }
                        
                        // Debug: log before removal
                        List<MethodStubPlan> toRemove = new ArrayList<>();
                        for (MethodStubPlan p : out.methodPlans) {
                            try {
                                String pOwnerQn = safeQN(p.ownerType);
                                // Compare owner qualified names - must match exactly
                                // Also check if ownerType references match (in case of generics)
                                boolean ownerMatches = finalFiQn != null && finalFiQn.equals(pOwnerQn);
                                if (!ownerMatches && p.ownerType != null) {
                                    // Try comparing the type references directly
                                    try {
                                        String pOwnerQn2 = p.ownerType.getQualifiedName();
                                        String fiQn2 = fiType.getQualifiedName();
                                        ownerMatches = (pOwnerQn2 != null && fiQn2 != null && pOwnerQn2.equals(fiQn2));
                                    } catch (Throwable ignored) {}
                                    // Also try erased FQN comparison (handles F vs F<Integer>)
                                    if (!ownerMatches && finalFiQnErased != null) {
                                        String pOwnerQnErased = erasureFqn(p.ownerType);
                                        ownerMatches = finalFiQnErased.equals(pOwnerQnErased);
                                    }
                                }
                                if (ownerMatches && 
                                    ("apply".equals(p.name) || "make".equals(p.name)) &&
                                    !p.defaultOnInterface && !p.isStatic) {
                                    toRemove.add(p);
                                }
                            } catch (Throwable ignored) {}
                        }
                        
                        // Remove all matching methods
                        out.methodPlans.removeAll(toRemove);
                        
                        // Debug log
                        if (!toRemove.isEmpty()) {
                            System.err.println("[collectLambdas] Removed " + toRemove.size() + " existing SAM method(s) for " + finalFiQn);
                            for (MethodStubPlan p : toRemove) {
                                System.err.println("  - Removed: " + safeQN(p.ownerType) + "#" + p.name + "(" + 
                                    p.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
                            }
                        }
                        
                        // Add SAM method plan with correct signature from lambda
                        if (fiType != null && !isJdkType(fiType)) {
                            MethodStubPlan lambdaMethodPlan = new MethodStubPlan(
                                    fiType,
                                    "apply",
                                    lambdaReturnType,
                                    samParams,
                                    /*isStatic*/ false,
                                    MethodStubPlan.Visibility.PUBLIC,
                                    Collections.emptyList(),
                                    /*defaultOnInterface*/ false,
                                    /*varargs*/ false,
                                    /*mirror*/ false,
                                    /*mirrorOwnerRef*/ null
                            );
                            out.methodPlans.add(lambdaMethodPlan);
                        }
                        
                        // Debug: log what we're adding
                        System.err.println("[collectLambdas] Added lambda SAM method: " + finalFiQn + "#apply(" + 
                            samParams.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + 
                            ") : " + safeQN(lambdaReturnType));
                    } catch (Throwable ignored) {
                        // If we can't infer, use defaults
                    }
                }
            }
        }
    }

    /**
     * Final cleanup: Remove duplicate SAM methods from functional interfaces.
     * For each functional interface, keep only ONE "apply" or "make" method (prefer the one from lambda if available).
     */
    private void removeDuplicateSamMethods(CollectResult out) {
        System.err.println("[removeDuplicateSamMethods] Starting cleanup, total method plans: " + out.methodPlans.size());
        
        // Debug: log all SAM methods before grouping
        System.err.println("[removeDuplicateSamMethods] All SAM methods before grouping:");
        for (MethodStubPlan p : out.methodPlans) {
            if (("apply".equals(p.name) || "make".equals(p.name)) && !p.defaultOnInterface && !p.isStatic) {
                try {
                    if (p.ownerType != null) {
                        String ownerQn = safeQN(p.ownerType);
                        String ownerQnErased = erasureFqn(p.ownerType);
                        System.err.println("  - " + ownerQn + " (erased: " + ownerQnErased + ") #" + p.name + "(" + 
                            p.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
                    } else {
                        System.err.println("  - [NULL OWNER] #" + p.name + "(" + 
                            p.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
                    }
                } catch (Throwable ignored) {}
            }
        }
        
        // Group method plans by owner (using erased FQN to handle generics)
        Map<String, List<MethodStubPlan>> ownerToMethods = new HashMap<>();
        
        for (MethodStubPlan p : out.methodPlans) {
            if (("apply".equals(p.name) || "make".equals(p.name)) && !p.defaultOnInterface && !p.isStatic) {
                try {
                    if (p.ownerType != null) {
                        String ownerQnErased = erasureFqn(p.ownerType);
                        if (ownerQnErased != null && !isJdkFqn(ownerQnErased)) {
                            ownerToMethods.computeIfAbsent(ownerQnErased, k -> new ArrayList<>()).add(p);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        
        System.err.println("[removeDuplicateSamMethods] Found " + ownerToMethods.size() + " functional interfaces with SAM methods");
        
        // For each functional interface with multiple SAM methods, keep only one
        for (Map.Entry<String, List<MethodStubPlan>> entry : ownerToMethods.entrySet()) {
            List<MethodStubPlan> methods = entry.getValue();
            System.err.println("[removeDuplicateSamMethods] " + entry.getKey() + " has " + methods.size() + " SAM method(s)");
            if (methods.size() > 1) {
                String ownerQn = entry.getKey();
                System.err.println("[removeDuplicateSamMethods] Found " + methods.size() + " SAM methods for " + ownerQn + ", keeping only one");
                
                // Prefer the method with non-primitive parameters (Integer over int) as it's more general
                // This handles the case where we have both apply(int) and apply(Integer)
                // Also log all methods for debugging
                System.err.println("[removeDuplicateSamMethods] All methods for " + ownerQn + ":");
                for (MethodStubPlan p : methods) {
                    System.err.println("  - " + safeQN(p.ownerType) + "#" + p.name + "(" + 
                        p.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + 
                        ") : " + safeQN(p.returnType));
                }
                
                // Prefer the method that matches actual call sites (fewer parameters = more likely to be called)
                // Also prefer non-primitive parameters (Integer over int) as it's more general
                MethodStubPlan toKeep = null;
                
                // First, try to find a method that matches actual call sites (prefer methods with fewer parameters)
                // This handles cases like make() vs make(int) where make() is actually called
                MethodStubPlan matchingCallSite = null;
                int minParams = Integer.MAX_VALUE;
                for (MethodStubPlan candidate : methods) {
                    if (candidate.paramTypes.size() < minParams) {
                        minParams = candidate.paramTypes.size();
                        matchingCallSite = candidate;
                    }
                }
                
                // If we found a method with fewer parameters, prefer it (likely matches actual call site)
                if (matchingCallSite != null && minParams < methods.stream().mapToInt(m -> m.paramTypes.size()).max().orElse(Integer.MAX_VALUE)) {
                    toKeep = matchingCallSite;
                    System.err.println("[removeDuplicateSamMethods] Preferring method with fewer parameters (matches call site): " + 
                        safeQN(toKeep.ownerType) + "#" + toKeep.name + "(" + 
                        toKeep.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
                } else {
                    // Otherwise, prefer the method with non-primitive parameters (Integer over int)
                    for (MethodStubPlan candidate : methods) {
                        // Check if this candidate has non-primitive parameters at any position where others have primitives
                        boolean candidateIsBetter = false;
                        for (MethodStubPlan other : methods) {
                            if (candidate == other) continue;
                            if (candidate.paramTypes.size() != other.paramTypes.size()) continue;
                            
                            // Check if candidate has non-primitive where other has primitive at same position
                            for (int i = 0; i < candidate.paramTypes.size(); i++) {
                                try {
                                    String candidateParamQn = safeQN(candidate.paramTypes.get(i));
                                    String otherParamQn = safeQN(other.paramTypes.get(i));
                                    
                                    boolean candidateIsPrimitive = candidateParamQn != null && 
                                        (candidateParamQn.equals("int") || candidateParamQn.equals("long") || 
                                         candidateParamQn.equals("short") || candidateParamQn.equals("byte") || 
                                         candidateParamQn.equals("char") || candidateParamQn.equals("boolean") ||
                                         candidateParamQn.equals("float") || candidateParamQn.equals("double"));
                                    
                                    boolean otherIsPrimitive = otherParamQn != null && 
                                        (otherParamQn.equals("int") || otherParamQn.equals("long") || 
                                         otherParamQn.equals("short") || otherParamQn.equals("byte") || 
                                         otherParamQn.equals("char") || otherParamQn.equals("boolean") ||
                                         otherParamQn.equals("float") || otherParamQn.equals("double"));
                                    
                                    // If at same position, candidate is non-primitive and other is primitive, candidate is better
                                    if (!candidateIsPrimitive && otherIsPrimitive) {
                                        candidateIsBetter = true;
                                        System.err.println("[removeDuplicateSamMethods] Preferring method with non-primitive param at position " + i + 
                                            ": " + candidateParamQn + " over " + otherParamQn);
                                        break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                            if (candidateIsBetter) break;
                        }
                        
                        if (candidateIsBetter) {
                            toKeep = candidate;
                            break;
                        }
                    }
                }
                
                // If no better candidate found, use first one
                if (toKeep == null) {
                    toKeep = methods.get(0);
                }
                
                // Remove all others
                for (MethodStubPlan p : methods) {
                    if (p != toKeep) {
                        out.methodPlans.remove(p);
                        System.err.println("  - Removed duplicate: " + safeQN(p.ownerType) + "#" + p.name + "(" + 
                            p.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
                    }
                }
                System.err.println("  - Kept: " + safeQN(toKeep.ownerType) + "#" + toKeep.name + "(" + 
                    toKeep.paramTypes.stream().map(t -> safeQN(t)).collect(Collectors.joining(", ")) + ")");
            }
        }
    }

    // In SpoonCollector
    private static String simpleNameOfFqn(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    /**
     * If both unknown.Simple and some.pkg.Simple are scheduled, prefer the concrete type:
     *  - drop the unknown.* plan
     *  - record a rebind hint unknown.Simple -> some.pkg.Simple
     */
    private void preferConcreteOverUnknown(CollectResult out) {
        if (out == null || out.typePlans == null || out.typePlans.isEmpty()) return;

        // Find concrete winners keyed by simple name
        Map<String, String> concreteBySimple = new HashMap<>();
        for (TypeStubPlan p : out.typePlans) {
            String qn = p.qualifiedName;
            if (qn == null) continue;
            if (!qn.startsWith("unknown.")) {
                concreteBySimple.put(simpleNameOfFqn(qn), qn);
            }
        }
        if (concreteBySimple.isEmpty()) return;

        // Remove unknown.* twins
        Iterator<TypeStubPlan> it = out.typePlans.iterator();
        while (it.hasNext()) {
            TypeStubPlan p = it.next();
            String qn = p.qualifiedName;
            if (qn == null || !qn.startsWith("unknown.")) continue;
            String simple = simpleNameOfFqn(qn);
            if (concreteBySimple.containsKey(simple)) {
                it.remove();
            }
        }

        // Publish rebind hints
        for (Map.Entry<String, String> e : concreteBySimple.entrySet()) {
            out.unknownToConcrete.put("unknown." + e.getKey(), e.getValue());
        }
    }



    // import spoon.reflect.code.CtForEach;
// import spoon.reflect.visitor.filter.TypeFilter;

    /**
     * Collect Stream API methods (stream(), forEach, map, filter, collect, etc.)
     * and ensure proper stubbing of Stream interface and related functional interfaces.
     * This handles modern Java features like Stream API, Optional, CompletableFuture, etc.
     */
    private void collectStreamApiMethods(CtModel model, CollectResult out) {
        // Common Stream API method names
        java.util.Set<String> streamMethods = java.util.Set.of("stream", "parallelStream", "forEach", "map", "filter", 
                "flatMap", "distinct", "sorted", "peek", "limit", "skip", "collect", "reduce",
                "findFirst", "findAny", "anyMatch", "allMatch", "noneMatch", "count", "max", "min");
        
        // Collection forEach method
        java.util.Set<String> collectionMethods = java.util.Set.of("forEach");
        
        // Optional API methods
        java.util.Set<String> optionalMethods = java.util.Set.of("map", "flatMap", "orElse", "orElseGet", "orElseThrow", 
                "ifPresent", "ifPresentOrElse", "filter", "or", "stream");
        
        // CompletableFuture methods
        java.util.Set<String> completableFutureMethods = java.util.Set.of("thenApply", "thenAccept", "thenRun", 
                "thenCompose", "thenCombine", "handle", "whenComplete");
        
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            CtExecutableReference<?> ex = inv.getExecutable();
            if (ex == null) continue;
            
            String methodName = ex.getSimpleName();
            if (methodName == null) continue;
            
            // Get target once for all checks
            CtExpression<?> target = inv.getTarget();
            
            // Check if this is a Stream API method call
            if (streamMethods.contains(methodName)) {
                CtTypeReference<?> ownerType = null;
                
                // Get the owner type from the target
                if (target != null) {
                    try {
                        ownerType = target.getType();
                    } catch (Throwable ignored) {}
                }
                
                // For stream() and parallelStream(), the owner is a Collection
                // The return type should be Stream<T>
                if ("stream".equals(methodName) || "parallelStream".equals(methodName)) {
                    if (ownerType != null && !isJdkType(ownerType)) {
                        // Infer element type from collection
                        CtTypeReference<?> elementType = inferCollectionElementType(ownerType);
                        if (elementType == null) elementType = f.Type().OBJECT;
                        
                        // Create Stream<T> return type
                        CtTypeReference<?> streamType = f.Type().createReference("java.util.stream.Stream");
                        streamType.addActualTypeArgument(elementType.clone());
                        
                        // Stub stream() method on the collection type
                        CtTypeReference<?> owner = chooseOwnerPackage(ownerType, inv);
                        if (owner != null && !isJdkType(owner)) {
                            // Check if method already exists to avoid duplicates
                            boolean exists = out.methodPlans.stream().anyMatch(p -> {
                                try {
                                    String pOwnerQn = safeQN(p.ownerType);
                                    String ownerQn = safeQN(owner);
                                    return ownerQn != null && ownerQn.equals(pOwnerQn) && methodName.equals(p.name);
                                } catch (Throwable ignored) {
                                    return false;
                                }
                            });
                            if (!exists) {
                                List<CtTypeReference<?>> params = Collections.emptyList();
                                out.methodPlans.add(new MethodStubPlan(
                                        owner, methodName, streamType, params,
                                        false, MethodStubPlan.Visibility.PUBLIC, Collections.emptyList()
                                ));
                            }
                        }
                    }
                }
                // For forEach, map, filter, etc. called on a Stream
                else if (target != null) {
                    try {
                        CtTypeReference<?> streamType = target.getType();
                        if (streamType != null) {
                            String streamQn = safeQN(streamType);
                            // Check if this is a Stream type (or unknown type that might be Stream)
                            if (streamQn != null && (streamQn.contains("Stream") || streamQn.startsWith("unknown."))) {
                                // Infer Stream element type from context
                                CtTypeReference<?> elementType = inferStreamElementType(streamType, inv);
                                if (elementType == null) elementType = f.Type().OBJECT;
                                
                                // Stream is a JDK type, so we don't stub it
                                // But we ensure functional interfaces (Consumer, Function, Predicate) are stubbed
                                // by the existing collectUnresolvedMethodCalls logic
                                // The key is that the method call will be handled by collectUnresolvedMethodCalls
                                // which will stub the functional interface parameter types
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            // Check if this is Collection.forEach()
            if (collectionMethods.contains(methodName) && "forEach".equals(methodName)) {
                if (target != null) {
                    try {
                        CtTypeReference<?> collectionType = target.getType();
                        if (collectionType != null && !isJdkType(collectionType)) {
                            // Infer element type
                            CtTypeReference<?> elementType = inferCollectionElementType(collectionType);
                            if (elementType == null) elementType = f.Type().OBJECT;
                            
                            // Create Consumer<T> parameter type
                            CtTypeReference<?> consumerType = f.Type().createReference("java.util.function.Consumer");
                            consumerType.addActualTypeArgument(elementType.clone());
                            
                            // Stub forEach method
                            CtTypeReference<?> owner = chooseOwnerPackage(collectionType, inv);
                            if (owner != null && !isJdkType(owner)) {
                                // Check if method already exists to avoid duplicates
                                boolean exists = out.methodPlans.stream().anyMatch(p -> {
                                    try {
                                        String pOwnerQn = safeQN(p.ownerType);
                                        String ownerQn = safeQN(owner);
                                        return ownerQn != null && ownerQn.equals(pOwnerQn) && "forEach".equals(p.name);
                                    } catch (Throwable ignored) {
                                        return false;
                                    }
                                });
                                if (!exists) {
                                    List<CtTypeReference<?>> params = Collections.singletonList(consumerType);
                                    out.methodPlans.add(new MethodStubPlan(
                                            owner, "forEach", f.Type().VOID_PRIMITIVE, params,
                                            false, MethodStubPlan.Visibility.PUBLIC, Collections.emptyList()
                                    ));
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            // Handle Optional API methods (like Maybe<T>)
            if (optionalMethods.contains(methodName) && target != null) {
                try {
                    CtTypeReference<?> optionalType = null;
                    CtTypeReference<?> owner = null;
                    CtTypeReference<?> elementType = null;
                    
                    // Special handling for chained calls: if target is a method invocation (e.g., map().orElse())
                    // Get the return type directly from the previous invocation's type
                    if (target instanceof CtInvocation && "orElse".equals(methodName)) {
                        CtInvocation<?> prevInv = (CtInvocation<?>) target;
                        CtExecutableReference<?> prevEx = prevInv.getExecutable();
                        System.err.println("[collectStreamApiMethods] Processing orElse(), target is CtInvocation, previous method: " + 
                            (prevEx != null ? prevEx.getSimpleName() : "null"));
                        
                        if (prevEx != null && "map".equals(prevEx.getSimpleName())) {
                            // The target of orElse is the result of map()
                            // Since map() hasn't been stubbed yet, we need to infer its return type
                            // map() on Maybe<T> returns Maybe<R> where R is the lambda return type
                            try {
                                // First, try to get the return type from the invocation (might be null if not resolved)
                                CtTypeReference<?> prevInvReturnType = prevInv.getType();
                                System.err.println("[collectStreamApiMethods] Previous map() invocation return type: " + safeQN(prevInvReturnType));
                                
                                if (prevInvReturnType == null) {
                                    // Infer the return type: map() on Maybe<T> returns Maybe<R>
                                    // where R is inferred from the lambda
                                    CtTypeReference<?> prevTargetType = prevInv.getTarget() != null ? prevInv.getTarget().getType() : null;
                                    System.err.println("[collectStreamApiMethods] Previous map() target type: " + safeQN(prevTargetType));
                                    
                                    if (prevTargetType != null) {
                                        String prevTargetQn = safeQN(prevTargetType);
                                        if (prevTargetQn != null && !prevTargetQn.contains("java.util.Optional") && !isJdkType(prevTargetType)) {
                                            // Check if we've already created a map() plan
                                            boolean foundInPlans = false;
                                            for (MethodStubPlan plan : out.methodPlans) {
                                                try {
                                                    String planOwnerQn = safeQN(plan.ownerType);
                                                    if (prevTargetQn.equals(planOwnerQn) && "map".equals(plan.name)) {
                                                        optionalType = plan.returnType;
                                                        owner = plan.returnType;
                                                        elementType = inferCollectionElementType(owner);
                                                        if (elementType == null) elementType = f.Type().OBJECT;
                                                        System.err.println("[collectStreamApiMethods] ✓ Found map() plan: " + safeQN(owner));
                                                        foundInPlans = true;
                                                        break;
                                                    }
                                                } catch (Throwable ignored) {}
                                            }
                                            
                                            if (!foundInPlans) {
                                                // Infer return type from lambda in map() call
                                                List<CtExpression<?>> mapArgs = prevInv.getArguments();
                                                CtTypeReference<?> lambdaReturnType = null;
                                                
                                                if (!mapArgs.isEmpty() && mapArgs.get(0) instanceof CtLambda) {
                                                    CtLambda<?> lambda = (CtLambda<?>) mapArgs.get(0);
                                                    try {
                                                        CtExpression<?> bodyExpr = null;
                                                        try {
                                                            bodyExpr = lambda.getExpression();
                                                        } catch (Throwable ignored) {
                                                            CtStatement body = lambda.getBody();
                                                            if (body instanceof CtReturn<?>) {
                                                                bodyExpr = ((CtReturn<?>) body).getReturnedExpression();
                                                            } else if (body instanceof CtExpression) {
                                                                bodyExpr = (CtExpression<?>) body;
                                                            }
                                                        }
                                                        
                                                        if (bodyExpr != null) {
                                                            try {
                                                                lambdaReturnType = bodyExpr.getType();
                                                                System.err.println("[collectStreamApiMethods] Lambda return type: " + safeQN(lambdaReturnType));
                                                            } catch (Throwable ignored) {}
                                                        }
                                                    } catch (Throwable ignored) {}
                                                }
                                                
                                                // If we can't infer from lambda, use the element type from Maybe<T>
                                                if (lambdaReturnType == null) {
                                                    lambdaReturnType = inferCollectionElementType(prevTargetType);
                                                    if (lambdaReturnType == null) lambdaReturnType = f.Type().OBJECT;
                                                }
                                                
                                                // Create Maybe<R> return type
                                                optionalType = prevTargetType.clone();
                                                if (optionalType.getActualTypeArguments().isEmpty()) {
                                                    optionalType.addActualTypeArgument(lambdaReturnType.clone());
                                                } else {
                                                    optionalType.getActualTypeArguments().set(0, lambdaReturnType.clone());
                                                }
                                                
                                                owner = optionalType;
                                                elementType = lambdaReturnType;
                                                System.err.println("[collectStreamApiMethods] ✓ Inferred map() return type for orElse(): " + safeQN(owner) + 
                                                    " (element type: " + safeQN(elementType) + ")");
                                            }
                                        }
                                    }
                                } else {
                                    // Use the resolved return type
                                    String prevReturnQn = safeQN(prevInvReturnType);
                                    if (prevReturnQn != null && !prevReturnQn.contains("java.util.Optional") && !isJdkType(prevInvReturnType)) {
                                        optionalType = prevInvReturnType;
                                        owner = prevInvReturnType;
                                        elementType = inferCollectionElementType(owner);
                                        if (elementType == null) elementType = f.Type().OBJECT;
                                        System.err.println("[collectStreamApiMethods] ✓ Using map() return type for orElse(): " + safeQN(owner) + 
                                            " (element type: " + safeQN(elementType) + ")");
                                    }
                                }
                            } catch (Throwable t) {
                                System.err.println("[collectStreamApiMethods] Error processing chained orElse(): " + t.getMessage());
                                t.printStackTrace();
                            }
                        } else {
                            System.err.println("[collectStreamApiMethods] Previous method is not 'map', it's: " + 
                                (prevEx != null ? prevEx.getSimpleName() : "null"));
                        }
                    }
                    
                    // If not a chained call or chained call detection failed, use normal type resolution
                    if (optionalType == null) {
                        optionalType = target.getType();
                    }
                    
                    if (optionalType != null) {
                        String optionalQn = safeQN(optionalType);
                        // Check if this is an unresolved Optional-like type (not JDK Optional)
                        if (optionalQn != null && !optionalQn.contains("java.util.Optional") && !isJdkType(optionalType)) {
                            // Infer element type from Optional<T> or Maybe<T>
                            if (elementType == null) {
                                elementType = inferCollectionElementType(optionalType);
                                if (elementType == null) elementType = f.Type().OBJECT;
                            }
                            
                            if (owner == null) {
                                owner = chooseOwnerPackage(optionalType, inv);
                            }
                            
                            if (owner != null && !isJdkType(owner)) {
                                // For map(), create Function<T, R> parameter
                                if ("map".equals(methodName)) {
                                    // Infer return type from lambda if available
                                    CtTypeReference<?> returnType = elementType; // Default to same type
                                    List<CtExpression<?>> args = inv.getArguments();
                                    if (!args.isEmpty()) {
                                        CtExpression<?> arg = args.get(0);
                                        if (arg instanceof CtLambda) {
                                            CtLambda<?> lambda = (CtLambda<?>) arg;
                                            try {
                                                // Try to infer return type from lambda body
                                                // For expression lambdas (x -> expr), use getExpression()
                                                // For block lambdas (x -> { return expr; }), use getBody()
                                                CtExpression<?> bodyExpr = null;
                                                try {
                                                    bodyExpr = lambda.getExpression();
                                                } catch (Throwable ignored) {
                                                    // If getExpression() doesn't exist or returns null, try getBody()
                                                    CtStatement body = lambda.getBody();
                                                    if (body instanceof CtReturn<?>) {
                                                        bodyExpr = ((CtReturn<?>) body).getReturnedExpression();
                                                    } else if (body instanceof CtExpression) {
                                                        bodyExpr = (CtExpression<?>) body;
                                                    }
                                                }
                                                
                                                if (bodyExpr != null) {
                                                    // Check for string concatenation: "x" + y or y + "x"
                                                    if (bodyExpr instanceof CtBinaryOperator) {
                                                        CtBinaryOperator<?> bin = (CtBinaryOperator<?>) bodyExpr;
                                                        if (bin.getKind() == BinaryOperatorKind.PLUS) {
                                                            try {
                                                                CtTypeReference<?> leftType = bin.getLeftHandOperand() != null ? bin.getLeftHandOperand().getType() : null;
                                                                CtTypeReference<?> rightType = bin.getRightHandOperand() != null ? bin.getRightHandOperand().getType() : null;
                                                                // Check if either operand is a String literal or String type
                                                                boolean leftIsString = (bin.getLeftHandOperand() instanceof CtLiteral && 
                                                                    ((CtLiteral<?>) bin.getLeftHandOperand()).getValue() instanceof String) ||
                                                                    (leftType != null && "java.lang.String".equals(safeQN(leftType)));
                                                                boolean rightIsString = (bin.getRightHandOperand() instanceof CtLiteral && 
                                                                    ((CtLiteral<?>) bin.getRightHandOperand()).getValue() instanceof String) ||
                                                                    (rightType != null && "java.lang.String".equals(safeQN(rightType)));
                                                                if (leftIsString || rightIsString) {
                                                                    returnType = f.Type().createReference("java.lang.String");
                                                                }
                                                            } catch (Throwable ignored) {}
                                                        }
                                                    }
                                                    
                                                    // If not string concatenation, try to get type directly
                                                    String returnTypeQn = safeQN(returnType);
                                                    if (returnType == null || returnTypeQn == null || safeQN(elementType) != null && returnTypeQn.equals(safeQN(elementType))) {
                                                        try {
                                                            CtTypeReference<?> bodyType = bodyExpr.getType();
                                                            if (bodyType != null && !safeQN(bodyType).equals("void")) {
                                                                returnType = bodyType;
                                                            }
                                                        } catch (Throwable ignored) {}
                                                    }
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                    
                                    // Create Function<T, R> parameter type
                                    CtTypeReference<?> functionType = f.Type().createReference("java.util.function.Function");
                                    functionType.addActualTypeArgument(elementType.clone());
                                    functionType.addActualTypeArgument(returnType.clone());
                                    
                                    // Check if method already exists
                                    CtTypeReference<?> finalOwner1 = owner;
                                    boolean exists = out.methodPlans.stream().anyMatch(p -> {
                                        try {
                                            String pOwnerQn = safeQN(p.ownerType);
                                            String ownerQn = safeQN(finalOwner1);
                                            return ownerQn != null && ownerQn.equals(pOwnerQn) && "map".equals(p.name);
                                        } catch (Throwable ignored) {
                                            return false;
                                        }
                                    });
                                    if (!exists) {
                                        // Create Maybe<R> return type
                                        CtTypeReference<?> returnMaybeType = owner.clone();
                                        if (returnMaybeType.getActualTypeArguments().isEmpty()) {
                                            returnMaybeType.addActualTypeArgument(returnType.clone());
                                        } else {
                                            returnMaybeType.getActualTypeArguments().set(0, returnType.clone());
                                        }
                                        
                                        List<CtTypeReference<?>> params = Collections.singletonList(functionType);
                                        out.methodPlans.add(new MethodStubPlan(
                                                owner, "map", returnMaybeType, params,
                                                false, MethodStubPlan.Visibility.PUBLIC, Collections.emptyList()
                                        ));
                                    }
                                }
                                // For orElse(), just return the element type
                                else if ("orElse".equals(methodName)) {
                                    // owner and elementType are already set correctly above (handles chained calls)
                                    CtTypeReference<?> finalOwner = owner;
                                    boolean exists = out.methodPlans.stream().anyMatch(p -> {
                                        try {
                                            String pOwnerQn = safeQN(p.ownerType);
                                            String ownerQn = safeQN(finalOwner);
                                            return ownerQn != null && ownerQn.equals(pOwnerQn) && "orElse".equals(p.name);
                                        } catch (Throwable ignored) {
                                            return false;
                                        }
                                    });
                                    if (!exists) {
                                        List<CtTypeReference<?>> params = Collections.singletonList(elementType.clone());
                                        out.methodPlans.add(new MethodStubPlan(
                                                owner, "orElse", elementType.clone(), params,
                                                false, MethodStubPlan.Visibility.PUBLIC, Collections.emptyList()
                                        ));
                                        System.err.println("[collectStreamApiMethods] Added orElse() to " + safeQN(owner) + " with element type " + safeQN(elementType));
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            
            // Handle CompletableFuture methods (like AsyncResult<T>)
            if (completableFutureMethods.contains(methodName) && target != null) {
                try {
                    CtTypeReference<?> futureType = target.getType();
                    if (futureType != null) {
                        String futureQn = safeQN(futureType);
                        // Check if this is an unresolved CompletableFuture-like type (not JDK CompletableFuture)
                        if (futureQn != null && !futureQn.contains("java.util.concurrent.CompletableFuture") && !isJdkType(futureType)) {
                            // Infer element type from AsyncResult<T>
                            CtTypeReference<?> elementType = inferCollectionElementType(futureType);
                            if (elementType == null) elementType = f.Type().OBJECT;
                            
                            CtTypeReference<?> owner = chooseOwnerPackage(futureType, inv);
                            if (owner != null && !isJdkType(owner)) {
                                // For thenApply(), create Function<T, R> parameter
                                if ("thenApply".equals(methodName)) {
                                    // Infer return type from lambda if available
                                    CtTypeReference<?> returnType = f.Type().OBJECT; // Default
                                    List<CtExpression<?>> args = inv.getArguments();
                                    if (!args.isEmpty()) {
                                        CtExpression<?> arg = args.get(0);
                                        if (arg instanceof CtLambda) {
                                            CtLambda<?> lambda = (CtLambda<?>) arg;
                                            try {
                                                // Try to infer return type from lambda body
                                                // For expression lambdas (x -> expr), use getExpression()
                                                // For block lambdas (x -> { return expr; }), use getBody()
                                                CtExpression<?> bodyExpr = null;
                                                try {
                                                    bodyExpr = lambda.getExpression();
                                                    System.err.println("[collectStreamApiMethods] Got expression from lambda.getExpression()");
                                                } catch (Throwable ignored) {
                                                    // If getExpression() doesn't exist or returns null, try getBody()
                                                    CtStatement body = lambda.getBody();
                                                    if (body instanceof CtReturn<?>) {
                                                        bodyExpr = ((CtReturn<?>) body).getReturnedExpression();
                                                        System.err.println("[collectStreamApiMethods] Got expression from return statement");
                                                    } else if (body instanceof CtExpression) {
                                                        bodyExpr = (CtExpression<?>) body;
                                                        System.err.println("[collectStreamApiMethods] Got expression from body (CtExpression)");
                                                    }
                                                }
                                                
                                                System.err.println("[collectStreamApiMethods] Processing thenApply lambda, bodyExpr type: " + 
                                                    (bodyExpr != null ? bodyExpr.getClass().getSimpleName() : "null"));
                                                
                                                if (bodyExpr != null) {
                                                    // Check for string concatenation: "x" + y or y + "x"
                                                    if (bodyExpr instanceof CtBinaryOperator) {
                                                        CtBinaryOperator<?> bin = (CtBinaryOperator<?>) bodyExpr;
                                                        System.err.println("[collectStreamApiMethods] Found binary operator, kind: " + bin.getKind());
                                                        if (bin.getKind() == BinaryOperatorKind.PLUS) {
                                                            try {
                                                                CtExpression<?> left = bin.getLeftHandOperand();
                                                                CtExpression<?> right = bin.getRightHandOperand();
                                                                CtTypeReference<?> leftType = left != null ? left.getType() : null;
                                                                CtTypeReference<?> rightType = right != null ? right.getType() : null;
                                                                
                                                                // Check if either operand is a String literal
                                                                boolean leftIsStringLiteral = (left instanceof CtLiteral && 
                                                                    ((CtLiteral<?>) left).getValue() instanceof String);
                                                                boolean rightIsStringLiteral = (right instanceof CtLiteral && 
                                                                    ((CtLiteral<?>) right).getValue() instanceof String);
                                                                
                                                                // Check if either operand type is String
                                                                boolean leftIsStringType = (leftType != null && "java.lang.String".equals(safeQN(leftType)));
                                                                boolean rightIsStringType = (rightType != null && "java.lang.String".equals(safeQN(rightType)));
                                                                
                                                                boolean leftIsString = leftIsStringLiteral || leftIsStringType;
                                                                boolean rightIsString = rightIsStringLiteral || rightIsStringType;
                                                                
                                                                System.err.println("[collectStreamApiMethods] String check - leftIsString: " + leftIsString + 
                                                                    " (literal: " + leftIsStringLiteral + ", type: " + leftIsStringType + 
                                                                    "), rightIsString: " + rightIsString + 
                                                                    " (literal: " + rightIsStringLiteral + ", type: " + rightIsStringType + ")");
                                                                
                                                                if (leftIsString || rightIsString) {
                                                                    returnType = f.Type().createReference("java.lang.String");
                                                                    System.err.println("[collectStreamApiMethods] Detected string concatenation, setting return type to String");
                                                                }
                                                            } catch (Throwable t) {
                                                                System.err.println("[collectStreamApiMethods] Error in string concatenation detection: " + t.getMessage());
                                                                t.printStackTrace();
                                                            }
                                                        }
                                                    }
                                                    
                                                    // If not string concatenation, try to get type directly
                                                    String returnTypeQn = safeQN(returnType);
                                                    if (returnType == null || returnTypeQn == null || "java.lang.Object".equals(returnTypeQn)) {
                                                        try {
                                                            CtTypeReference<?> bodyType = bodyExpr.getType();
                                                            if (bodyType != null && !safeQN(bodyType).equals("void")) {
                                                                returnType = bodyType;
                                                                System.err.println("[collectStreamApiMethods] Using bodyExpr.getType(): " + safeQN(bodyType));
                                                            }
                                                        } catch (Throwable t) {
                                                            System.err.println("[collectStreamApiMethods] Error getting bodyExpr type: " + t.getMessage());
                                                        }
                                                    }
                                                }
                                                
                                                System.err.println("[collectStreamApiMethods] Final returnType for thenApply: " + safeQN(returnType));
                                            } catch (Throwable t) {
                                                System.err.println("[collectStreamApiMethods] Error processing lambda: " + t.getMessage());
                                                t.printStackTrace();
                                            }
                                        }
                                    }
                                    
                                    // Create Function<T, R> parameter type
                                    CtTypeReference<?> functionType = f.Type().createReference("java.util.function.Function");
                                    functionType.addActualTypeArgument(elementType.clone());
                                    functionType.addActualTypeArgument(returnType.clone());
                                    
                                    // Check if method already exists
                                    boolean exists = out.methodPlans.stream().anyMatch(p -> {
                                        try {
                                            String pOwnerQn = safeQN(p.ownerType);
                                            String ownerQn = safeQN(owner);
                                            return ownerQn != null && ownerQn.equals(pOwnerQn) && "thenApply".equals(p.name);
                                        } catch (Throwable ignored) {
                                            return false;
                                        }
                                    });
                                    if (!exists) {
                                        // Create AsyncResult<R> return type
                                        CtTypeReference<?> returnFutureType = owner.clone();
                                        if (returnFutureType.getActualTypeArguments().isEmpty()) {
                                            returnFutureType.addActualTypeArgument(returnType.clone());
                                        } else {
                                            returnFutureType.getActualTypeArguments().set(0, returnType.clone());
                                        }
                                        
                                        List<CtTypeReference<?>> params = Collections.singletonList(functionType);
                                        out.methodPlans.add(new MethodStubPlan(
                                                owner, "thenApply", returnFutureType, params,
                                                false, MethodStubPlan.Visibility.PUBLIC, Collections.emptyList()
                                        ));
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
    
    /**
     * Infer the element type from a Collection type (e.g., List<String> -> String).
     */
    private CtTypeReference<?> inferCollectionElementType(CtTypeReference<?> collectionType) {
        if (collectionType == null) return null;
        try {
            // First, check if it has generic type arguments
            List<CtTypeReference<?>> typeArgs = collectionType.getActualTypeArguments();
            if (typeArgs != null && !typeArgs.isEmpty()) {
                return typeArgs.get(0);
            }
            
            // If no generic args, try to infer from type name
            // Common patterns: StringList -> String, IntList -> Integer/int, ItemList -> Item, etc.
            String simpleName = collectionType.getSimpleName();
            if (simpleName != null) {
                // Check for common type name patterns
                if (simpleName.endsWith("List") || simpleName.endsWith("Collection") || 
                    simpleName.endsWith("Set") || simpleName.endsWith("Queue")) {
                    String elementName = simpleName.substring(0, simpleName.length() - 
                        (simpleName.endsWith("List") ? 4 : 
                         simpleName.endsWith("Collection") ? 10 :
                         simpleName.endsWith("Queue") ? 5 : 3));
                    
                    if (!elementName.isEmpty()) {
                        // Try to find the element type in the model
                        // First check if it's a primitive wrapper
                        if ("Int".equals(elementName) || "Integer".equals(elementName)) {
                            return f.Type().INTEGER_PRIMITIVE;
                        } else if ("Long".equals(elementName)) {
                            return f.Type().LONG_PRIMITIVE;
                        } else if ("Double".equals(elementName)) {
                            return f.Type().DOUBLE_PRIMITIVE;
                        } else if ("Float".equals(elementName)) {
                            return f.Type().FLOAT_PRIMITIVE;
                        } else if ("Boolean".equals(elementName)) {
                            return f.Type().BOOLEAN_PRIMITIVE;
                        } else if ("Char".equals(elementName) || "Character".equals(elementName)) {
                            return f.Type().CHARACTER_PRIMITIVE;
                        } else if ("Byte".equals(elementName)) {
                            return f.Type().BYTE_PRIMITIVE;
                        } else if ("Short".equals(elementName)) {
                            return f.Type().SHORT_PRIMITIVE;
                        } else {
                            // Try to find the type in the model
                            // First, try same package as collection
                            String collectionPkg = collectionType.getPackage() != null ? 
                                collectionType.getPackage().getQualifiedName() : "";
                            String elementQn = collectionPkg.isEmpty() ? elementName : collectionPkg + "." + elementName;
                            
                            // Check if the element type exists in the model (same package first)
                            CtType<?> elementType = f.Type().get(elementQn);
                            if (elementType != null) {
                                return elementType.getReference();
                            }
                            
                            // Try to find by simple name in the model (any package)
                            CtModel model = f.getModel();
                            if (model != null) {
                                for (CtType<?> type : model.getAllTypes()) {
                                    if (elementName.equals(type.getSimpleName())) {
                                        return type.getReference();
                                    }
                                }
                            }
                            
                            // Try java.lang. only for common types
                            if ("String".equals(elementName) || "Object".equals(elementName) || 
                                "Integer".equals(elementName) || "Long".equals(elementName) ||
                                "Double".equals(elementName) || "Float".equals(elementName) ||
                                "Boolean".equals(elementName) || "Character".equals(elementName) ||
                                "Byte".equals(elementName) || "Short".equals(elementName)) {
                                try {
                                    CtTypeReference<?> ref = f.Type().createReference("java.lang." + elementName);
                                    if (ref != null) {
                                        return ref;
                                    }
                                } catch (Throwable ignored) {}
                            }
                            
                            // If type not found, return null (will default to Object in caller)
                            return null;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    
    /**
     * Infer the element type from a Stream type or from method call context.
     */
    private CtTypeReference<?> inferStreamElementType(CtTypeReference<?> streamType, CtInvocation<?> inv) {
        if (streamType == null) return null;
        try {
            List<CtTypeReference<?>> typeArgs = streamType.getActualTypeArguments();
            if (typeArgs != null && !typeArgs.isEmpty()) {
                return typeArgs.get(0);
            }
        } catch (Throwable ignored) {}
        
        // Try to infer from parent invocation (e.g., items.stream().forEach(...))
        CtElement parent = inv.getParent();
        if (parent instanceof CtInvocation<?>) {
            CtInvocation<?> parentInv = (CtInvocation<?>) parent;
            CtExpression<?> parentTarget = parentInv.getTarget();
            if (parentTarget != null) {
                try {
                    CtTypeReference<?> parentType = parentTarget.getType();
                    return inferCollectionElementType(parentType);
                } catch (Throwable ignored) {}
            }
        }
        
        return null;
    }
    
    /**
     * Determine the return type for a Stream API method.
     */
    private CtTypeReference<?> determineStreamMethodReturnType(String methodName, CtTypeReference<?> elementType, CtInvocation<?> inv) {
        switch (methodName) {
            case "forEach":
                return f.Type().VOID_PRIMITIVE;
            case "map":
            case "flatMap":
                // Infer return type from lambda/function argument
                return inferMapReturnType(inv, elementType);
            case "filter":
            case "distinct":
            case "sorted":
            case "peek":
            case "limit":
            case "skip":
                // These return Stream<T>
                CtTypeReference<?> streamType = f.Type().createReference("java.util.stream.Stream");
                streamType.addActualTypeArgument(elementType);
                return streamType;
            case "collect":
                // Infer from context (usually List<T> or Collection<T>)
                return inferCollectReturnType(inv, elementType);
            case "findFirst":
            case "findAny":
                CtTypeReference<?> optionalType = f.Type().createReference("java.util.Optional");
                optionalType.addActualTypeArgument(elementType);
                return optionalType;
            case "anyMatch":
            case "allMatch":
            case "noneMatch":
                return f.Type().BOOLEAN_PRIMITIVE;
            case "count":
                return f.Type().LONG_PRIMITIVE;
            case "max":
            case "min":
                CtTypeReference<?> optType = f.Type().createReference("java.util.Optional");
                optType.addActualTypeArgument(elementType);
                return optType;
            default:
                return f.Type().OBJECT;
        }
    }
    
    /**
     * Infer return type for map/flatMap from the function argument.
     */
    private CtTypeReference<?> inferMapReturnType(CtInvocation<?> inv, CtTypeReference<?> inputType) {
        // Try to get type from function argument (lambda or method reference)
        List<CtExpression<?>> args = inv.getArguments();
        if (args != null && !args.isEmpty()) {
            CtExpression<?> funcArg = args.get(0);
            if (funcArg != null) {
                try {
                    CtTypeReference<?> funcType = funcArg.getType();
                    if (funcType != null) {
                        String funcQn = safeQN(funcType);
                        // If it's Function<T, R>, extract R
                        if (funcQn != null && funcQn.contains("Function")) {
                            List<CtTypeReference<?>> typeArgs = funcType.getActualTypeArguments();
                            if (typeArgs != null && typeArgs.size() >= 2) {
                                return typeArgs.get(1); // R is the second type parameter
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        // Default: return Stream<Object>
        CtTypeReference<?> streamType = f.Type().createReference("java.util.stream.Stream");
        streamType.addActualTypeArgument(f.Type().OBJECT);
        return streamType;
    }
    
    /**
     * Infer return type for collect() from context.
     */
    private CtTypeReference<?> inferCollectReturnType(CtInvocation<?> inv, CtTypeReference<?> elementType) {
        // Try to infer from context:
        // 1. Check if collect() is used in a method - use the method's return type
        // 2. Check the collector argument (e.g., IntListCollector.toList() -> IntList)
        // 3. Fallback to List<T>
        
        // First, try to get return type from parent method
        CtElement parent = inv.getParent();
        while (parent != null) {
            if (parent instanceof CtMethod<?>) {
                CtMethod<?> method = (CtMethod<?>) parent;
                try {
                    CtTypeReference<?> returnType = method.getType();
                    if (returnType != null && !isJdkType(returnType)) {
                        // Check if it's a collection type that matches the element type
                        String returnQn = safeQN(returnType);
                        if (returnQn != null && (returnQn.contains("List") || returnQn.contains("Collection") || 
                            returnQn.contains("Set"))) {
                            return returnType;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            parent = parent.getParent();
        }
        
        // Second, try to infer from collector argument (e.g., IntListCollector.toList())
        List<CtExpression<?>> args = inv.getArguments();
        if (args != null && !args.isEmpty()) {
            CtExpression<?> collectorArg = args.get(0);
            if (collectorArg instanceof CtInvocation<?>) {
                CtInvocation<?> collectorInv = (CtInvocation<?>) collectorArg;
                CtExecutableReference<?> collectorEx = collectorInv.getExecutable();
                if (collectorEx != null) {
                    String methodName = collectorEx.getSimpleName();
                    if ("toList".equals(methodName) || "toCollection".equals(methodName) || 
                        "toSet".equals(methodName)) {
                        // Try to infer from the collector class name
                        CtExpression<?> collectorTarget = collectorInv.getTarget();
                        if (collectorTarget != null) {
                            try {
                                CtTypeReference<?> collectorClassType = collectorTarget.getType();
                                if (collectorClassType != null) {
                                    String collectorClassQn = safeQN(collectorClassType);
                                    // For static method calls like IntListCollector.toList(),
                                    // the target type is the class itself
                                    if (collectorClassQn != null && !isJdkType(collectorClassType)) {
                                        // Try to infer return type from collector class name
                                        // e.g., IntListCollector -> IntList
                                        String simpleName = collectorClassType.getSimpleName();
                                        if (simpleName != null && simpleName.endsWith("Collector")) {
                                            String returnTypeName = simpleName.substring(0, simpleName.length() - 9);
                                            // Try to find the return type
                                            String returnTypeQn = collectorClassType.getPackage() != null ?
                                                collectorClassType.getPackage().getQualifiedName() + "." + returnTypeName :
                                                returnTypeName;
                                            
                                            try {
                                                CtType<?> returnType = f.Type().get(returnTypeQn);
                                                if (returnType != null) {
                                                    return returnType.getReference();
                                                }
                                                // Try creating a reference
                                                CtTypeReference<?> ref = f.Type().createReference(returnTypeQn);
                                                if (ref != null) {
                                                    return ref;
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
        
        // Fallback: Usually collect() returns List<T>
        CtTypeReference<?> listType = f.Type().createReference("java.util.List");
        listType.addActualTypeArgument(elementType);
        return listType;
    }
    
    /**
     * Infer parameters for Stream API methods.
     */
    private List<CtTypeReference<?>> inferStreamMethodParams(String methodName, CtTypeReference<?> elementType, CtInvocation<?> inv) {
        List<CtExpression<?>> args = inv.getArguments();
        List<CtTypeReference<?>> params = new ArrayList<>();
        
        switch (methodName) {
            case "forEach":
                // forEach(Consumer<? super T> action)
                CtTypeReference<?> consumerType = f.Type().createReference("java.util.function.Consumer");
                consumerType.addActualTypeArgument(elementType);
                params.add(consumerType);
                break;
            case "map":
            case "flatMap":
                // map(Function<? super T, ? extends R> mapper)
                CtTypeReference<?> functionType = f.Type().createReference("java.util.function.Function");
                functionType.addActualTypeArgument(elementType);
                functionType.addActualTypeArgument(f.Type().OBJECT); // R is unknown, use Object
                params.add(functionType);
                break;
            case "filter":
                // filter(Predicate<? super T> predicate)
                CtTypeReference<?> predicateType = f.Type().createReference("java.util.function.Predicate");
                predicateType.addActualTypeArgument(elementType);
                params.add(predicateType);
                break;
            case "collect":
                // collect(Collector<? super T, A, R> collector)
                CtTypeReference<?> collectorType = f.Type().createReference("java.util.stream.Collector");
                collectorType.addActualTypeArgument(elementType);
                collectorType.addActualTypeArgument(f.Type().OBJECT); // A
                collectorType.addActualTypeArgument(f.Type().OBJECT); // R
                params.add(collectorType);
                break;
            case "sorted":
                // sorted() or sorted(Comparator<? super T> comparator)
                if (args != null && !args.isEmpty()) {
                    CtTypeReference<?> comparatorType = f.Type().createReference("java.util.Comparator");
                    comparatorType.addActualTypeArgument(elementType);
                    params.add(comparatorType);
                }
                break;
            case "limit":
            case "skip":
                // limit(long maxSize) or skip(long n)
                params.add(f.Type().LONG_PRIMITIVE);
                break;
            case "anyMatch":
            case "allMatch":
            case "noneMatch":
                // anyMatch(Predicate<? super T> predicate)
                CtTypeReference<?> predType = f.Type().createReference("java.util.function.Predicate");
                predType.addActualTypeArgument(elementType);
                params.add(predType);
                break;
            case "max":
            case "min":
                // max(Comparator<? super T> comparator)
                CtTypeReference<?> compType = f.Type().createReference("java.util.Comparator");
                compType.addActualTypeArgument(elementType);
                params.add(compType);
                break;
        }
        
        return params;
    }

    private void collectForEachLoops(CtModel model, CollectResult out) {
        for (CtForEach fe : model.getElements(new TypeFilter<>(CtForEach.class))) {
            try {
                // Skip arrays – they’re already iterable without interface
                CtExpression<?> expr = fe.getExpression();
                if (expr == null) continue;
                CtTypeReference<?> ownerRef = expr.getType();
                if (ownerRef == null) continue;
                try { if (ownerRef.isArray()) continue; } catch (Throwable ignored) {}

                // Don’t stub JDK types
                if (isJdkType(ownerRef)) continue;

                // Owner FQN (respect your owner-picking heuristic)
                ownerRef = chooseOwnerPackage(ownerRef, fe);
                String ownerQn = safeQN(ownerRef);
                if (ownerQn == null) continue;

                // Element type = loop variable type (default to Object)
                CtTypeReference<?> elem = (fe.getVariable() != null ? fe.getVariable().getType() : null);
                if (elem == null) elem = f.Type().OBJECT;

                CtTypeReference<?> iterableRef = f.Type().createReference("java.lang.Iterable");
                iterableRef.addActualTypeArgument(elem.clone());

                out.implementsPlans
                        .computeIfAbsent(ownerQn, k -> new LinkedHashSet<>())
                        .add(iterableRef);

                CtTypeReference<?> iteratorRef = f.Type().createReference("java.util.Iterator");
                iteratorRef.addActualTypeArgument(elem.clone());

                out.methodPlans.add(new MethodStubPlan(
                        ownerRef,
                        "iterator",
                        iteratorRef,
                        Collections.emptyList(),
                        /*isStatic*/ false,
                        MethodStubPlan.Visibility.PUBLIC,
                        Collections.emptyList(),
                        /*defaultOnInterface*/ false,
                        /*varargs*/ false,
                        /*mirror*/ false,
                        /*mirrorOwnerRef*/ null
                ));

                // Ensure the owner type will exist (class by default)
                addTypePlanIfNonJdk(out, ownerQn, TypeStubPlan.Kind.CLASS);

            } catch (Throwable ignored) { /* be defensive in collector */ }
        }
    }






}
