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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SpoonCollector {

    public static final class CollectResult {
        public final List<TypeStubPlan> typePlans = new ArrayList<>();
        public final List<FieldStubPlan> fieldPlans = new ArrayList<>();
        public final List<ConstructorStubPlan> ctorPlans = new ArrayList<>();
        public final List<MethodStubPlan> methodPlans = new ArrayList<>();
    }

    private final Factory f;
    private final JessConfiguration cfg;

    // add near the top
    private static final String UNKNOWN_TYPE_FQN = de.upb.sse.jess.generation.unknown.UnknownType.CLASS;



    public SpoonCollector(Factory f, JessConfiguration cfg) {
        this.f = f; this.cfg = cfg;
    }

    public CollectResult collect(CtModel model) {
        CollectResult result = new CollectResult();

        collectUnresolvedFields(model, result);
        collectUnresolvedCtorCalls(model, result);
        collectUnresolvedMethodCalls(model, result);
        collectExceptionTypes(model, result);
        collectSupertypes(model, result);
        collectFromInstanceofCastsClassLiteralsAndForEach(model, result);
        collectUnresolvedDeclaredTypes(model, result);
        collectOverloadGaps(model, result);

        seedOnDemandImportAnchors(model, result);
        seedExplicitTypeImports(model, result);




        result.typePlans.addAll(ownersNeedingTypes(result));

        return result;
    }

    /* -------------------- FIELDS -------------------- */

    private void collectUnresolvedFields(CtModel model, CollectResult out) {
        List<CtFieldAccess<?>> unresolved = model.getElements((CtFieldAccess<?> fa) -> {
            var ref = fa.getVariable();
            return ref == null || ref.getDeclaration() == null;
        });

        for (CtFieldAccess<?> fa : unresolved) {
            // Skip class-literals like Foo.class (modeled as a field 'class' on a CtTypeAccess)
            String vname = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : null);
            if ("class".equals(vname) && fa.getTarget() instanceof CtTypeAccess) {
                continue; // handled by the type-access / class-literal pass
            }

            if (fa.getParent(CtAnnotation.class) != null) continue; // enum const in annotation

            if (isStandaloneFieldStatement(fa)) {
                if (cfg.isFailOnAmbiguity()) {
                    String owner = Optional.ofNullable(resolveOwnerTypeFromFieldAccess(fa))
                            .map(CtTypeReference::getQualifiedName).orElse("<unknown>");
                    String name  = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "<missing>");
                    throw new AmbiguityException("Ambiguous field access with no type context: " + owner + "#" + name);
                } else {
                    // lenient mode: skip — stubbing can't fix a non-compilable bare access anyway
                    continue;
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
                    String simple  = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "<missing>");
                    throw new AmbiguityException(
                            "Ambiguous field (no usable type context): " + ownerQN + "#" + simple
                    );
                }
                // lenient fallback
                fieldType = f.Type().createReference(UNKNOWN_TYPE_FQN);
            }


            out.fieldPlans.add(new FieldStubPlan(ownerRef, fieldName, fieldType, isStatic));
        }
    }


    /** Returns true when the field access is used as a bare statement (e.g., `x.f;`). */
    private boolean isStandaloneFieldStatement(CtFieldAccess<?> fa) {
        CtElement p = fa.getParent();
        if (p == null) return false;

        // If it's in any typed context we can use, it's not standalone
        if (p instanceof CtAssignment
                || p instanceof CtVariable         // covers local/field declarations
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

        // Bare `x.f;` typically sits directly in a block as a statement
        try {
            if (p instanceof CtBlock && fa.getRoleInParent() == spoon.reflect.path.CtRole.STATEMENT) {
                return true;
            }
        } catch (Throwable ignored) {}

        // Final safety net: if there’s no typed parent at all, treat as standalone
        return !(p instanceof CtExpression); // i.e., expression not embedded in a typed expression
    }


    private CtTypeReference<?> resolveOwnerTypeFromFieldAccess(CtFieldAccess<?> fa) {
        // Static: TypeName.f
        if (fa.getTarget() instanceof CtTypeAccess) {
            return ((CtTypeAccess<?>) fa.getTarget()).getAccessedType();
        }

        // Array element access: so[1].number  → owner is ELEMENT type
        if (fa.getTarget() instanceof CtArrayAccess) {
            CtArrayAccess<?, ?> aa = (CtArrayAccess<?, ?>) fa.getTarget();

            // 1) array variable type -> component
            CtTypeReference<?> arrType = null;
            try { arrType = ((CtExpression<?>) aa.getTarget()).getType(); } catch (Throwable ignored) {}
            CtTypeReference<?> elem = componentOf(arrType);
            if (elem != null) {
                CtTypeReference<?> owner = chooseOwnerPackage(elem, fa);
                if (owner != null) return owner;
            }

            // 2) type Spoon gives to the array access expression itself (often the element type)
            try {
                CtTypeReference<?> t = ((CtExpression<?>) aa).getType();
                if (t != null) {
                    CtTypeReference<?> owner = chooseOwnerPackage(t, fa);
                    if (owner != null) return owner;
                }
            } catch (Throwable ignored) {}
        }

        // General instance access
        if (fa.getTarget() != null) {
            try {
                CtTypeReference<?> t = fa.getTarget().getType();
                CtTypeReference<?> base = componentOf(t); // defensive unwrap in case Spoon gave an array ref
                return (base != null ? chooseOwnerPackage(base, fa) : chooseOwnerPackage(t, fa));
            } catch (Throwable ignored) {}
        }

        // FINAL fallback: use a *named* unknown to avoid creating a bogus "Missing" owner
        String simple = (fa.getVariable() != null ? fa.getVariable().getSimpleName() : "Owner");
        return f.Type().createReference("unknown." + simple);
    }


    private CtTypeReference<?> componentOf(CtTypeReference<?> t) {
        if (t == null) return null;
        try {
            if (t instanceof CtArrayTypeReference) {
                return ((CtArrayTypeReference<?>) t).getComponentType();
            }
            if (t.isArray()) {
                // defensive: drop one [] if Spoon only encodes it in the QN
                String qn = t.getQualifiedName();
                if (qn != null && qn.endsWith("[]")) {
                    String base = qn.substring(0, qn.indexOf('['));
                    return f.Type().createReference(base);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }



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

        if (parent instanceof CtVariable) {
            return ((CtVariable<?>) parent).getType();
        }

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

    /* -------------------- CONSTRUCTORS -------------------- */


    private void collectUnresolvedCtorCalls(CtModel model, CollectResult out) {
        var unresolved = model.getElements((CtConstructorCall<?> cc) -> {
            var ex = cc.getExecutable();
            return ex == null || ex.getDeclaration() == null;
        });

        for (CtConstructorCall<?> cc : unresolved) {
            CtTypeReference<?> rawOwner = cc.getType();
            CtTypeReference<?> owner    = chooseOwnerPackage(rawOwner, cc);
            if (isJdkType(owner)) continue;

            // also walk its type args to stub nested generic types
            collectTypeRefDeep(cc, rawOwner, out);

            List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
            out.ctorPlans.add(new ConstructorStubPlan(owner, ps));
        }
    }


    private static String safeQN(CtTypeReference<?> t) {
        try {
            String s = (t == null ? null : t.getQualifiedName());
            return (s == null ? "" : s);
        } catch (Throwable ignored) {
            return "";
        }
    }


    /* -------------------- METHODS -------------------- */
    private void collectUnresolvedMethodCalls(CtModel model, CollectResult out) {
        List<CtInvocation<?>> unresolved = model.getElements((CtInvocation<?> inv) -> {
            var ex = inv.getExecutable();
            return ex == null || ex.getDeclaration() == null;
        });

        for (CtInvocation<?> inv : unresolved) {
            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : "m");

            if ("<init>".equals(name)) {
                CtTypeReference<?> ownerForCtor =
                        chooseOwnerPackage(resolveOwnerTypeFromInvocation(inv), inv);
                if (!isJdkType(ownerForCtor)) {
                    List<CtTypeReference<?>> ps = inferParamTypesFromCall(ex, inv.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(ownerForCtor, ps));
                }
                continue;
            }


            // CtTypeReference<?> owner = resolveOwnerTypeFromInvocation(inv);
            CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
            CtTypeReference<?> owner    = chooseOwnerPackage(rawOwner, inv);
            if (isJdkType(owner)) continue;

            boolean isStatic    = inv.getTarget() instanceof CtTypeAccess<?>;
            boolean isSuperCall = inv.getTarget() instanceof CtSuperAccess<?>;

            CtTypeReference<?> returnType = inferReturnTypeFromContext(inv);
            List<CtTypeReference<?>> paramTypes = inferParamTypesFromCall(ex, inv.getArguments());

            // Ambiguity checks (as you already added)
            if (returnType == null && isStandaloneInvocation(inv) && cfg.isFailOnAmbiguity()) {
                String ownerQN = owner != null ? owner.getQualifiedName() : "<unknown>";
                throw new AmbiguityException("Ambiguous method return (used as statement): " + ownerQN + "#" + name + "(...)");
            }
            List<CtTypeReference<?>> fromRef = (ex != null ? ex.getParameters() : java.util.Collections.emptyList());
            boolean refSane = fromRef != null && !fromRef.isEmpty() && fromRef.stream().allMatch(this::isSaneType);
            if (!refSane && argsContainNullLiteral(inv.getArguments()) && cfg.isFailOnAmbiguity()) {
                String ownerQN = owner != null ? owner.getQualifiedName() : "<unknown>";
                throw new AmbiguityException("Ambiguous method parameters (null argument): " + ownerQN + "#" + name + "(...)");
            }
            if (returnType == null) returnType = f.Type().VOID_PRIMITIVE;

            MethodStubPlan.Visibility vis = isSuperCall ? MethodStubPlan.Visibility.PROTECTED
                    : MethodStubPlan.Visibility.PUBLIC;

            // NEW: if it's a super-call, mirror the enclosing method's throws
            java.util.List<CtTypeReference<?>> thrown =
                    isSuperCall
                            ? new ArrayList<>(
                            Optional.ofNullable(inv.getParent(CtMethod.class))
                                    .map(CtMethod::getThrownTypes)
                                    .orElse(Collections.emptySet()) // ← correct default
                    )
                            : Collections.emptyList();

            out.methodPlans.add(new MethodStubPlan(owner, name, returnType, paramTypes, isStatic, vis, thrown));
        }
    }



    private CtTypeReference<?> resolveOwnerTypeFromInvocationOLD(CtInvocation<?> inv) {
        if (inv.getTarget() instanceof CtTypeAccess) {
            return ((CtTypeAccess<?>) inv.getTarget()).getAccessedType();
        }
        if (inv.getTarget() != null) {
            try { return inv.getTarget().getType(); } catch (Throwable ignored) {}
        }
        // static import call with no obvious target → fall back to declaring type on ref (if present)
        if (inv.getExecutable() != null && inv.getExecutable().getDeclaringType() != null) {
            return inv.getExecutable().getDeclaringType();
        }
        return f.Type().createReference("unknown.Missing");
    }
    // SpoonCollector.java
    private CtTypeReference<?> resolveOwnerTypeFromInvocation(CtInvocation<?> inv) {
        // 1) Static call: TypeName.m(...)
        if (inv.getTarget() instanceof CtTypeAccess) {
            return ((CtTypeAccess<?>) inv.getTarget()).getAccessedType();
        }

        // 2) If the target is a field access, prefer the field's declared type
        if (inv.getTarget() instanceof CtFieldAccess) {
            CtFieldAccess<?> fa = (CtFieldAccess<?>) inv.getTarget();
            try {
                if (fa.getVariable() != null && fa.getVariable().getType() != null) {
                    return fa.getVariable().getType(); // e.g., org.example.Log
                }
            } catch (Throwable ignored) { }
            // fall through to generic expression type
        }

        // 3) Generic: use target expression type
        if (inv.getTarget() != null) {
            try { return inv.getTarget().getType(); } catch (Throwable ignored) {}
        }

        // 4) Static-import or unresolved — use declaring type if present
        if (inv.getExecutable() != null && inv.getExecutable().getDeclaringType() != null) {
            return inv.getExecutable().getDeclaringType();
        }

        // 5) Fallback
        return f.Type().createReference("unknown.Missing");
    }


    private CtTypeReference<?> inferReturnTypeFromContext(CtInvocation<?> inv) {
        CtElement p = inv.getParent();

        // 1) v = foo();  => return type of v
        if (p instanceof CtVariable && Objects.equals(((CtVariable<?>) p).getDefaultExpression(), inv)) {
            return ((CtVariable<?>) p).getType();
        }
        // 2) x = foo();  => type of x
        if (p instanceof CtAssignment && Objects.equals(((CtAssignment<?, ?>) p).getAssignment(), inv)) {
            try { return ((CtExpression<?>) ((CtAssignment<?, ?>) p).getAssigned()).getType(); } catch (Throwable ignored) {}
        }
        // 3) return foo(); => method return type
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

                if (isStringy(other)) {
                    return f.Type().STRING; // java.lang.String
                }
            }
        }

        // 4) existing: parent is CtInvocation => infer from arg position (your current logic)
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

        // 5) fallback to Spoon’s own type if available
        try { return inv.getType(); } catch (Throwable ignored) {}
        return null;
    }

    // Helper
    private boolean isStringy(CtExpression<?> e) {
        if (e == null) return false;
        // literal "abc"
        if (e instanceof CtLiteral) {
            Object v = ((CtLiteral<?>) e).getValue();
            if (v instanceof String) return true;
        }
        // typed as java.lang.String
        try {
            CtTypeReference<?> t = e.getType();
            return t != null && "java.lang.String".equals(t.getQualifiedName());
        } catch (Throwable ignored) { }
        return false;
    }

    private List<CtTypeReference<?>> inferParamTypesFromCall(CtExecutableReference<?> ex,
                                                             List<CtExpression<?>> args) {
        // Prefer the declared signature if Spoon has it — but only if all types are sane.
        List<CtTypeReference<?>> fromRef = (ex != null ? ex.getParameters() : Collections.emptyList());
        if (fromRef != null && !fromRef.isEmpty() && fromRef.stream().allMatch(this::isSaneType)) {
            return fromRef;
        }

        // Otherwise infer from arguments, normalizing null → Object.
        return args.stream()
                .map(this::paramTypeOrObject)
                .collect(Collectors.toList());
    }



    private boolean isSaneType(CtTypeReference<?> t) {
        if (t == null) return false;
        String qn = t.getQualifiedName();
        if (qn == null || "null".equals(qn) || qn.contains("NullType")) return false;
        // NEW: bare simple "Unknown" should NOT be trusted
        if (!qn.contains(".") && "Unknown".equals(t.getSimpleName())) return false;
        return true;
    }

/*------------------------Exceptions -----------------*/

    /** Collect exception types that appear only in throws/catch/throw contexts. */
    private void collectExceptionTypes(CtModel model, CollectResult out) {
        // ----- methods: throws -----
        List<CtMethod<?>> methods = model.getElements((CtMethod<?> mm) -> true);
        for (CtMethod<?> m : methods) {
            for (CtTypeReference<?> t : m.getThrownTypes()) {
                if (t == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(t, m);
                if (isJdkType(owner)) continue;
                out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                out.ctorPlans.add(new ConstructorStubPlan(owner, java.util.Collections.emptyList()));
            }
        }

        // ----- constructors: throws -----
        List<CtConstructor<?>> ctors = model.getElements((CtConstructor<?> cc) -> true);
        for (CtConstructor<?> c : ctors) {
            for (CtTypeReference<?> t : c.getThrownTypes()) {
                if (t == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(t, c);
                if (isJdkType(owner)) continue;
                out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                out.ctorPlans.add(new ConstructorStubPlan(owner, java.util.Collections.emptyList()));
            }
        }

        // ----- catch (single & multi-catch) -----
        List<CtCatch> catches = model.getElements((CtCatch k) -> true);
        for (CtCatch cat : catches) {
            var par = cat.getParameter();
            if (par == null) continue;

            java.util.List<CtTypeReference<?>> types = new java.util.ArrayList<>();
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
                out.ctorPlans.add(new ConstructorStubPlan(owner, java.util.Collections.emptyList()));
            }
        }

        // ----- throw statements -----
        List<CtThrow> throwsList = model.getElements((CtThrow th) -> true);
        for (CtThrow thr : throwsList) {
            CtExpression<?> ex = thr.getThrownExpression();
            if (ex instanceof CtConstructorCall) {
                CtConstructorCall<?> cc = (CtConstructorCall<?>) ex;
                CtTypeReference<?> owner = chooseOwnerPackage(cc.getType(), thr);
                if (!isJdkType(owner)) {
                    out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                    java.util.List<CtTypeReference<?>> ps = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
                    out.ctorPlans.add(new ConstructorStubPlan(owner, ps));
                }
            } else if (ex != null) {
                try {
                    CtTypeReference<?> t = ex.getType();
                    if (t != null && !isJdkType(t) && t.getDeclaration() == null) {
                        CtTypeReference<?> owner = chooseOwnerPackage(t, thr);
                        out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                        out.ctorPlans.add(new ConstructorStubPlan(owner, java.util.Collections.emptyList()));
                    }
                } catch (Throwable ignored) {}
            }
        }
    }


/*-----------------------Fixed Imports-----------*/

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
            for (CtTypeReference<? extends Throwable> thr : m.getThrownTypes())
                collectTypeRefDeep(m, thr, out);
        }
        for (CtConstructor<?> c : model.getElements((CtConstructor<?> cc) -> true)) {
            for (CtTypeReference<? extends Throwable> thr : c.getThrownTypes())
                collectTypeRefDeep(c, thr, out);
        }
    }

    @SuppressWarnings("unchecked")
    private void maybePlanDeclaredType(CtElement ctx, CtTypeReference<?> t, CollectResult out) {
        if (t == null) return;

        // NEW: if it's an array, handle its component type instead of skipping
        try {
            if (t.isArray() || t instanceof CtArrayTypeReference) {
                CtTypeReference<?> comp = componentOf(t);
                if (comp != null) {
                    // treat the component as the declared type for planning
                    maybePlanDeclaredType(ctx, comp, out);
                }
                return; // nothing more to do for the array wrapper itself
            }
        } catch (Throwable ignored) {}

        // primitives / void => skip
        try {
            if (t.isPrimitive()) return;
            if (t.equals(f.Type().VOID_PRIMITIVE)) return;
        } catch (Throwable ignored) { }

        // already resolvable => skip
        try { if (t.getDeclaration() != null) return; } catch (Throwable ignored) {}

        String qn = safeQN(t);
        String simple = t.getSimpleName();
        if (simple == null || simple.isEmpty()) return;

        if (isJdkType(t)) return;

        if (!qn.contains(".")) {
            // try explicit single-type imports first
            CtTypeReference<?> explicit = resolveFromExplicitTypeImports(ctx, simple);
            if (explicit != null) {
                out.typePlans.add(new TypeStubPlan(explicit.getQualifiedName(), TypeStubPlan.Kind.CLASS));
                return;
            }

            List<String> starPkgs = starImportsInOrder(ctx);
            List<String> nonUnknown = starPkgs.stream().filter(p -> !"unknown".equals(p)).collect(java.util.stream.Collectors.toList());

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

        // already qualified (but unresolved) -> create where Spoon says
        out.typePlans.add(new TypeStubPlan(qn, TypeStubPlan.Kind.CLASS));
    }



    /* -------------------- HELPERS -------------------- */

    private boolean isJdkType(CtTypeReference<?> t) {
        if (t == null) return false;
        String qn = t.getQualifiedName();
        return qn != null && (qn.startsWith("java.") || qn.startsWith("javax.") ||
                qn.startsWith("jakarta.") || qn.startsWith("sun.") || qn.startsWith("jdk."));
    }

    private List<TypeStubPlan> ownersNeedingTypes(CollectResult res) {
        Set<String> fqns = new LinkedHashSet<>();

        for (FieldStubPlan p : res.fieldPlans) addIfNonJdk(fqns, p.ownerType);

        for (ConstructorStubPlan p : res.ctorPlans) {
            addIfNonJdk(fqns, p.ownerType);
            for (CtTypeReference<?> t : p.parameterTypes) addIfNonJdk(fqns, t);
        }

        for (MethodStubPlan p : res.methodPlans) {
            addIfNonJdk(fqns, p.ownerType);
            addIfNonJdk(fqns, p.returnType);                 // return type
            for (CtTypeReference<?> t : p.paramTypes) addIfNonJdk(fqns, t);   // params
            // optional but useful to ensure thrown types' owners exist too:
            for (CtTypeReference<?> t : p.thrownTypes) addIfNonJdk(fqns, t);  // throws
        }


        return fqns.stream()
                .map(fqn -> new TypeStubPlan(fqn, TypeStubPlan.Kind.CLASS))
                .collect(Collectors.toList());
    }


    private void addIfNonJdk(Set<String> out, CtTypeReference<?> t) {
        if (t == null) return;

        // Skip primitives, void, and arrays
        try {
            if (t.isPrimitive()) return;
            if (t.equals(f.Type().VOID_PRIMITIVE)) return;
            if (t.isArray()) return;
        } catch (Throwable ignored) {}

        String qn = t.getQualifiedName();
        if (qn == null || qn.isEmpty()) return;

        // Skip JDK / javax / jakarta / sun / jdk
        if (qn.startsWith("java.") || qn.startsWith("javax.")
                || qn.startsWith("jakarta.") || qn.startsWith("sun.")
                || qn.startsWith("jdk.")) return;

        out.add(qn);
    }




    private CtTypeReference<?> paramTypeOrObject(CtExpression<?> arg) {
        if (arg == null) return f.Type().createReference(UNKNOWN_TYPE_FQN);

        // --- NEW: handle String concatenation as String ---
        if (arg instanceof CtBinaryOperator) {
            CtBinaryOperator<?> bin = (CtBinaryOperator<?>) arg;
            if (bin.getKind() == BinaryOperatorKind.PLUS && (isStringy(bin) ||
                    isStringy(bin.getLeftHandOperand()) || isStringy(bin.getRightHandOperand()))) {
                return f.Type().createReference("java.lang.String");
            }
        }

        // --- END NEW ---

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







    private boolean isStandaloneInvocation(CtInvocation<?> inv) {
        return (inv.getParent() instanceof CtBlock)
                && (inv.getRoleInParent() == CtRole.STATEMENT);
    }


    private boolean argsContainNullLiteral(List<CtExpression<?>> args) {
        for (CtExpression<?> a : args) {
            if (a instanceof CtLiteral && ((CtLiteral<?>) a).getValue() == null) return true;
        }
        return false;
    }



    private void seedOnDemandImportAnchors(CtModel model, CollectResult out) {
        // Regex to capture: import some.pkg.* ;
        final Pattern STAR_IMPORT = Pattern.compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\.\\*\\s*;");

        model.getAllTypes().forEach(t -> {
            var pos = t.getPosition();
            var cu  = (pos != null) ? pos.getCompilationUnit() : null;
            if (cu == null) return;

            Set<String> starPkgs = new LinkedHashSet<>();

            // 1) Try Spoon’s view of imports (works for resolvable star imports)
            for (CtImport imp : cu.getImports()) {
                if (imp.getImportKind() == CtImportKind.ALL_TYPES) {
                    String raw = String.valueOf(imp.getReference()); // usually "org.example"
                    if (raw != null && !raw.isEmpty() && !isJdkPkg(raw)) {
                        starPkgs.add(raw);
                    }
                }
            }

            // 2) Fallback: parse original source and grab ANY star imports (even unresolved)
            try {
                String src = cu.getOriginalSourceCode(); // deprecated but safe to read
                if (src != null) {
                    Matcher m = STAR_IMPORT.matcher(src);
                    while (m.find()) {
                        String pkg = m.group(1);          // "org.example"
                        if (!isJdkPkg(pkg)) starPkgs.add(pkg);
                    }
                }
            } catch (Throwable ignored) {
                // best-effort only; keep going
            }

            // Create anchors
            for (String pkg : starPkgs) {
                out.typePlans.add(new TypeStubPlan(pkg + ".PackageAnchor", TypeStubPlan.Kind.CLASS));
            }
        });
    }


    // ---- star-import helpers ----
    private boolean isJdkPkg(String pkg) {
        return pkg.startsWith("java.") || pkg.startsWith("javax.") ||
                pkg.startsWith("jakarta.") || pkg.startsWith("sun.") || pkg.startsWith("jdk.");
    }


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



    // replace starImportsInOrder(ctx) with this *source-only* version
    private List<String> starImportsInOrder(CtElement ctx) {
        var type = ctx.getParent(CtType.class);
        var pos  = (type != null ? type.getPosition() : null);
        var cu   = (pos != null ? pos.getCompilationUnit() : null);
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
        } catch (Throwable ignored) {}
        return out;
    }


    private CtTypeReference<?> chooseOwnerPackage(CtTypeReference<?> ownerRef, CtElement ctx) {
        if (ownerRef == null) return f.Type().createReference("unknown.Missing");

        String qn = safeQN(ownerRef);

        // Treat assumed-local as simple so we can re-qualify from star imports
        if (qn.contains(".") && isLocallyAssumedOrSimple(ownerRef, ctx)) {
            ownerRef = f.Type().createReference(ownerRef.getSimpleName());
            qn = ownerRef.getQualifiedName();
        }

        if (qn.contains(".")) return ownerRef; // explicit → keep

        String simple = Optional.ofNullable(ownerRef.getSimpleName()).orElse("Missing");
        List<String> starPkgs = starImportsInOrder(ctx); // non-JDK, source order

        if (starPkgs.contains("unknown")) {
            return f.Type().createReference("unknown." + simple);
        }

        if (starPkgs.size() == 1) {
            return f.Type().createReference(starPkgs.get(0) + "." + simple);
        }

        // NEW: multiple candidates
        if (starPkgs.size() > 1) {
            if (cfg.isFailOnAmbiguity()) {
                // leave it to callers that may throw; or pick 'unknown' if you prefer
                return f.Type().createReference("unknown." + simple);
            } else {
                // lenient: pick FIRST in source order
                return f.Type().createReference(starPkgs.get(0) + "." + simple);
            }
        }

        // fallback
        return f.Type().createReference("unknown." + simple);
    }


    private CtTypeReference<?> resolveFromExplicitTypeImports(CtElement ctx, String simple) {
        var type = ctx.getParent(CtType.class);
        var pos  = (type != null ? type.getPosition() : null);
        var cu   = (pos != null ? pos.getCompilationUnit() : null);
        if (cu == null) return null;

        for (CtImport imp : cu.getImports()) {
            if (imp.getImportKind() == CtImportKind.TYPE) {
                try {
                    var ref = imp.getReference();
                    if (ref instanceof CtTypeReference) {
                        String qn = ((CtTypeReference<?>) ref).getQualifiedName();
                        if (qn != null && qn.endsWith("." + simple)) {
                            return f.Type().createReference(qn); // exact FQN from explicit import
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }


    // SpoonCollector.java
    private void collectOverloadGaps(CtModel model, CollectResult out) {
        // Find invocations whose owner/type exists and method name exists,
        // but there is NO applicable overload for the provided arguments.
        List<CtInvocation<?>> invocations = model.getElements((CtInvocation<?> inv) -> {
            // Ignore constructor calls
            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : null);
            if (name == null || "<init>".equals(name)) return false;

            // owner must be resolvable type (the class exists)
            CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
            if (rawOwner == null) return false;
            CtTypeReference<?> owner = chooseOwnerPackage(rawOwner, inv);
            if (owner == null || isJdkType(owner)) return false;

            CtType<?> ownerDecl = null;
            try { ownerDecl = owner.getTypeDeclaration(); } catch (Throwable ignored) {}
            if (!(ownerDecl instanceof CtClass)) return false; // enhance only classes

            // Same-name methods present?
            List<CtMethod<?>> sameName = ((CtClass<?>) ownerDecl).getMethods().stream()
                    .filter(m -> name.equals(m.getSimpleName()))
                    .collect(java.util.stream.Collectors.toList());
            if (sameName.isEmpty()) return false; // then it's not an "overload gap", it's simply unresolved

            // If any existing overload is applicable, it's NOT a gap
            if (hasApplicableOverload(sameName, inv.getArguments())) return false;

            return true; // gap detected
        });

        for (CtInvocation<?> inv : invocations) {
            CtTypeReference<?> rawOwner = resolveOwnerTypeFromInvocation(inv);
            CtTypeReference<?> owner    = chooseOwnerPackage(rawOwner, inv);
            if (owner == null || isJdkType(owner)) continue;

            CtExecutableReference<?> ex = inv.getExecutable();
            String name = (ex != null ? ex.getSimpleName() : "m");

            boolean isStatic    = inv.getTarget() instanceof CtTypeAccess<?>;
            boolean isSuperCall = inv.getTarget() instanceof CtSuperAccess<?>;

            CtTypeReference<?> returnType = inferReturnTypeFromContext(inv);
            if (returnType == null) returnType = f.Type().VOID_PRIMITIVE;

            // infer parameter types from the *arguments* directly (not from ex.getParameters(), which
            // would reflect an existing but incompatible overload, e.g., String when we need int)
            List<CtTypeReference<?>> paramTypes = inv.getArguments().stream()
                    .map(this::paramTypeOrObject)
                    .collect(Collectors.toList());

            // visibility: mirror the most common (or first) visibility from existing same-name methods
            MethodStubPlan.Visibility vis = MethodStubPlan.Visibility.PACKAGE;
            try {
                CtType<?> ownerDecl = owner.getTypeDeclaration();
                if (ownerDecl instanceof CtClass) {
                    @SuppressWarnings("unchecked")
                    List<CtMethod<?>> sameName = ((CtClass<?>) ownerDecl).getMethods().stream()
                            .filter(m -> name.equals(m.getSimpleName()))
                            .collect(Collectors.toList());
                    if (!sameName.isEmpty()) {
                        // pick first's visibility
                        Set<ModifierKind> mods = sameName.get(0).getModifiers();
                        if (mods.contains(ModifierKind.PUBLIC))    vis = MethodStubPlan.Visibility.PUBLIC;
                        else if (mods.contains(ModifierKind.PROTECTED)) vis = MethodStubPlan.Visibility.PROTECTED;
                        else if (mods.contains(ModifierKind.PRIVATE))   vis = MethodStubPlan.Visibility.PRIVATE;
                        else vis = MethodStubPlan.Visibility.PACKAGE;
                    }
                }
            } catch (Throwable ignored) {}

            List<CtTypeReference<?>> thrown = java.util.Collections.emptyList();
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

    private boolean hasApplicableOverload(List<CtMethod<?>> methods, List<CtExpression<?>> args) {
        // very lightweight applicability: arity must match and every arg “fits” the parameter
        for (CtMethod<?> m : methods) {
            List<CtParameter<?>> ps = m.getParameters();
            if (ps.size() != args.size()) continue;

            boolean allOk = true;
            for (int i = 0; i < ps.size(); i++) {
                CtTypeReference<?> pt = null;
                try { pt = ps.get(i).getType(); } catch (Throwable ignored) {}
                CtTypeReference<?> at = paramTypeOrObject(args.get(i)); // your existing helper

                // If either type is missing/null-ish, be conservative: treat as non-applicable
                if (!isSaneType(pt) || !isSaneType(at)) { allOk = false; break; }

                // Primitive int vs java.lang.Integer: consider a basic equality/boxed match
                String pqn = safeQN(pt), aqn = safeQN(at);
                if (pqn.equals(aqn)) continue;

                // allow boxed vs primitive basic matches
                if (isPrimitiveBoxPair(pqn, aqn)) continue;

                allOk = false; break;
            }
            if (allOk) return true;
        }
        return false;
    }

    private boolean isPrimitiveBoxPair(String a, String b) {
        // crude but effective
        return (a.equals("int") && b.equals("java.lang.Integer")) || (b.equals("int") && a.equals("java.lang.Integer")) ||
                (a.equals("long") && b.equals("java.lang.Long"))     || (b.equals("long") && a.equals("java.lang.Long")) ||
                (a.equals("double") && b.equals("java.lang.Double")) || (b.equals("double") && a.equals("java.lang.Double")) ||
                (a.equals("float") && b.equals("java.lang.Float"))   || (b.equals("float") && a.equals("java.lang.Float")) ||
                (a.equals("short") && b.equals("java.lang.Short"))   || (b.equals("short") && a.equals("java.lang.Short")) ||
                (a.equals("byte") && b.equals("java.lang.Byte"))     || (b.equals("byte") && a.equals("java.lang.Byte")) ||
                (a.equals("char") && b.equals("java.lang.Character"))|| (b.equals("char") && a.equals("java.lang.Character")) ||
                (a.equals("boolean") && b.equals("java.lang.Boolean"))|| (b.equals("boolean") && a.equals("java.lang.Boolean"));
    }


    // SpoonCollector.java

    // walk a type reference + its actual type args (handles wildcards with bounds)
    private void collectTypeRefDeep(CtElement ctx, CtTypeReference<?> t, CollectResult out) {
        if (t == null) return;

        // 1) plan the outer type as you do today
        maybePlanDeclaredType(ctx, t, out);

        // 2) recurse into actual type arguments
        try {
            for (CtTypeReference<?> arg : t.getActualTypeArguments()) {
                if (arg == null) continue;

                // wildcard? use its bounding type if present
                if (arg instanceof spoon.reflect.reference.CtWildcardReference) {
                    var w = (spoon.reflect.reference.CtWildcardReference) arg;
                    CtTypeReference<?> bound = w.getBoundingType();
                    if (bound != null) collectTypeRefDeep(ctx, bound, out);
                    // (unbounded ?: nothing to stub)
                } else {
                    collectTypeRefDeep(ctx, arg, out);
                }
            }
        } catch (Throwable ignored) {}
    }



    // SpoonCollector.java
    private void collectSupertypes(CtModel model, CollectResult out) {
        // classes: superclass + superinterfaces
        for (CtClass<?> c : model.getElements((CtClass<?> cc) -> true)) {
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
        for (CtInterface<?> i : model.getElements((CtInterface<?> ii) -> true)) {
            for (CtTypeReference<?> si : safe(i.getSuperInterfaces())) {
                if (si == null) continue;
                CtTypeReference<?> owner = chooseOwnerPackage(si, i);
                if (owner != null && !isJdkType(owner)) {
                    out.typePlans.add(new TypeStubPlan(owner.getQualifiedName(), TypeStubPlan.Kind.INTERFACE));
                }
            }
        }

        // Also pick up generic type arguments used *in* extends/implements
        for (CtType<?> t : model.getAllTypes()) {
            // superclass type args
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
            // superinterfaces type args
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

    @SuppressWarnings("unchecked")
    private <T> Collection<T> safe(Collection<T> c) { return (c == null ? Collections.emptyList() : c); }

// Put this anywhere in SpoonCollector (e.g., near other collectors)

    private void collectFromInstanceofCastsClassLiteralsAndForEach(CtModel model, CollectResult out) {
        // --- instanceof: BinaryOperatorKind.INSTANCEOF ---
        for (CtBinaryOperator<?> bo : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            if (bo.getKind() == BinaryOperatorKind.INSTANCEOF) {
                if (bo.getRightHandOperand() instanceof CtTypeAccess) {
                    CtTypeReference<?> t = ((CtTypeAccess<?>) bo.getRightHandOperand()).getAccessedType();
                    if (t != null) maybePlanDeclaredType(bo, t, out);
                }
            }
        }

        // --- Foo.class: CtTypeAccess ---
        for (CtTypeAccess<?> ta : model.getElements(new TypeFilter<>(CtTypeAccess.class))) {
            CtTypeReference<?> t = ta.getAccessedType();
            if (t != null) maybePlanDeclaredType(ta, t, out);
        }


        // --- for-each: variable type and iterable element type ---
        for (CtForEach fe : model.getElements(new TypeFilter<>(CtForEach.class))) {
            CtExpression<?> expr = fe.getExpression();
            CtTypeReference<?> iterType = (expr != null ? expr.getType() : null);

            // Prefer the variable's declared type as the element type
            CtTypeReference<?> elem = null;
            if (fe.getVariable() != null) {
                elem = fe.getVariable().getType();
            }
            if (elem == null) {
                elem = inferIterableElementType(iterType); // your existing helper
            }

            if (elem != null) {
                maybePlanDeclaredType(fe, elem, out);
            }

            // Plan iterator() and mark as Iterable<E> on the owner
            if (iterType != null) {
                CtTypeReference<?> owner = chooseOwnerPackage(iterType, fe);
                if (owner != null && !isJdkType(owner)) {
                    CtTypeReference<?> iterRef = f.Type().createReference("java.util.Iterator");
                    if (elem != null) iterRef.addActualTypeArgument(elem);

                    out.methodPlans.add(
                            new MethodStubPlan(
                                    owner,
                                    "iterator",
                                    iterRef,
                                    java.util.Collections.emptyList(),
                                    false,
                                    MethodStubPlan.Visibility.PUBLIC,
                                    java.util.Collections.emptyList()
                            )
                    );
                }
            }
        }



        // --- casts: use reflection so we don't need CtTypeCast at compile time ---
        try {
            Class<?> CT_TYPE_CAST = Class.forName("spoon.reflect.code.CtTypeCast");

            for (CtElement el : model.getElements(new TypeFilter<>(CtElement.class))) {
                if (CT_TYPE_CAST.isInstance(el)) {
                    // Prefer getTypeCasted(), fallback to getType() if absent (older Spoon)
                    CtTypeReference<?> t = null;
                    try {
                        Object typeRef = CT_TYPE_CAST.getMethod("getTypeCasted").invoke(el);
                        if (typeRef instanceof CtTypeReference) t = (CtTypeReference<?>) typeRef;
                    } catch (NoSuchMethodException nf) {
                        Object typeRef = CT_TYPE_CAST.getMethod("getType").invoke(el);
                        if (typeRef instanceof CtTypeReference) t = (CtTypeReference<?>) typeRef;
                    }
                    if (t != null) {
                        maybePlanDeclaredType(el, t, out);
                    }
                }
            }
        } catch (ClassNotFoundException ignore) {
            // OK: older/variant Spoon; other passes still work.
        } catch (Throwable ignore) { /* best-effort */ }

    }

    private CtTypeReference<?> inferIterableElementType(CtTypeReference<?> itT) {
        if (itT == null) return null;
        try {
            if (itT instanceof CtArrayTypeReference) {
                return ((CtArrayTypeReference<?>) itT).getComponentType();
            }
            var args = itT.getActualTypeArguments();
            if (args != null && !args.isEmpty()) return args.get(0); // e.g., List<Foo> -> Foo
        } catch (Throwable ignored) {}
        return null;
    }

    /** Seed types that appear only as explicit single-type imports. */
    private void seedExplicitTypeImports(CtModel model, CollectResult out) {
        // matches: import some.pkg.Type;  (filters out star imports later)
        final java.util.regex.Pattern SINGLE_IMPORT =
                java.util.regex.Pattern.compile("\\bimport\\s+([a-zA-Z_][\\w\\.]*)\\s*;");

        model.getAllTypes().forEach(t -> {
            SourcePosition pos = t.getPosition();
            CompilationUnit cu = (pos != null ? pos.getCompilationUnit() : null);
            if (cu == null) return;

            // 1) take what Spoon knows
            java.util.Set<String> fqns = new java.util.LinkedHashSet<>();
            for (CtImport imp : cu.getImports()) {
                if (imp.getImportKind() == CtImportKind.TYPE) {
                    try {
                        var r = imp.getReference();
                        if (r instanceof CtTypeReference) {
                            String qn = ((CtTypeReference<?>) r).getQualifiedName();
                            if (qn != null && !qn.isEmpty()) fqns.add(qn);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // 2) also fall back to original source (works even when Spoon didn’t resolve it)
            try {
                String src = cu.getOriginalSourceCode();
                if (src != null) {
                    var m = SINGLE_IMPORT.matcher(src);
                    while (m.find()) {
                        String fqn = m.group(1);
                        if (fqn.endsWith(".*")) continue; // not single-type
                        fqns.add(fqn);
                    }
                }
            } catch (Throwable ignored) {}

            // 3) plan those non-JDK types
            for (String fqn : fqns) {
                if (isJdkPkg(fqn)) continue;
                out.typePlans.add(new TypeStubPlan(fqn, TypeStubPlan.Kind.CLASS));
            }
        });
    }



}
