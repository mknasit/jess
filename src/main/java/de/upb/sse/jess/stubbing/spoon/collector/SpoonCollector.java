package de.upb.sse.jess.stubbing.spoon.collector;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.exceptions.AmbiguityException;
import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.cu.CompilationUnit;
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

        seedOnDemandImportAnchors(model, result);


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
        if (fa.getTarget() instanceof CtTypeAccess) {
            return ((CtTypeAccess<?>) fa.getTarget()).getAccessedType();
        }
        if (fa.getTarget() != null) {
            try { return fa.getTarget().getType(); } catch (Throwable ignored) {}
        }
        return f.Type().createReference("unknown.Missing");
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
        List<CtConstructorCall<?>> unresolved = model.getElements((CtConstructorCall<?> cc) -> {
            var ex = cc.getExecutable();
            return ex == null || ex.getDeclaration() == null;
        });

        for (CtConstructorCall<?> cc : unresolved) {
            CtTypeReference<?> rawOwner = cc.getType();
            CtTypeReference<?> owner    = chooseOwnerPackage(rawOwner, cc);
            if (isJdkType(owner)) continue;

            // 2a) Always normalize truly unknown simple names to 'unknown.SimpleName'
            //CtTypeReference<?> normalizedOwner = normalizeUnknownSimple(owner, cc);
            // Use the normalized owner for the actual stub (this is the class the test checks for)
            List<CtTypeReference<?>> paramTypes = inferParamTypesFromCall(cc.getExecutable(), cc.getArguments());
            out.ctorPlans.add(new ConstructorStubPlan(owner, paramTypes));

            // 2b) ALSO create a same-package class if Spoon/printer qualified it to the local package.
            // If owner looks like it belongs to the current package, mirror it so the rewritten file compiles.

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



    private CtTypeReference<?> resolveOwnerTypeFromInvocation(CtInvocation<?> inv) {
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

        if (p instanceof CtInvocation) {
            CtInvocation<?> outer = (CtInvocation<?>) p;
            List<CtExpression<?>> args = outer.getArguments();
            int idx = -1;
            for (int i = 0; i < args.size(); i++) {
                if (Objects.equals(args.get(i), inv)) { idx = i; break; }
            }
            if (idx >= 0) {
                CtExecutableReference<?> outerEx = outer.getExecutable();
                if (outerEx != null
                        && outerEx.getParameters().size() > idx
                        && isSaneType(outerEx.getParameters().get(idx))) {
                    return outerEx.getParameters().get(idx);
                }
                // if we can’t get a sane type from the outer signature, treat it as Unknown
                return f.Type().createReference(de.upb.sse.jess.generation.unknown.UnknownType.CLASS);
            }
        }

        // If Spoon can resolve the type of the invocation, use it.
        try { return inv.getType(); } catch (Throwable ignored) {}

        // unknown → void
        return null;
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




    // ---- Star imports in order (source text first, Spoon fallback) ----
    private List<String> starImportsInOrder(CtElement ctx) {
        var type = ctx.getParent(CtType.class);
        var pos  = (type != null ? type.getPosition() : null);
        var cu   = (pos != null ? pos.getCompilationUnit() : null);
        if (cu == null) return Collections.emptyList();

        List<String> out = new ArrayList<>();

        // 1) Try original source order (best-effort)
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

        // 2) Spoon view (if any were missed)
        for (CtImport imp : cu.getImports()) {
            if (imp.getImportKind() == CtImportKind.ALL_TYPES) {
                String pkg = String.valueOf(imp.getReference());
                if (pkg.endsWith(".*")) pkg = pkg.substring(0, pkg.length() - 2);
                if (!isJdkPkg(pkg) && !out.contains(pkg)) out.add(pkg);
            }
        }
        return out;
    }

    private CtTypeReference<?> chooseOwnerPackage(CtTypeReference<?> ownerRef, CtElement ctx) {
        if (ownerRef == null) return f.Type().createReference("unknown.Missing");

        String qn = safeQN(ownerRef);

        // If Spoon assumed current package (no declaration), treat as simple so we can re-qualify.
        if (qn.contains(".") && isLocallyAssumedOrSimple(ownerRef, ctx)) {
            ownerRef = f.Type().createReference(ownerRef.getSimpleName());
            qn = ownerRef.getQualifiedName();
        }

        // Explicit FQN (and not assumed-local) → respect it.
        if (qn.contains(".")) return ownerRef;

        String simple = Optional.ofNullable(ownerRef.getSimpleName()).orElse("Missing");
        List<String> starPkgs = starImportsInOrder(ctx); // non-JDK, source order

        // JESS rule for owners: if unknown.* is present → owner goes to unknown
        if (starPkgs.contains("unknown")) {
            return f.Type().createReference("unknown." + simple);
        }

        // Otherwise, if there is exactly one candidate, use it; else default to unknown
        if (starPkgs.size() == 1) {
            return f.Type().createReference(starPkgs.get(0) + "." + simple);
        }

        // Fallback
        return f.Type().createReference("unknown." + simple);
    }



}
