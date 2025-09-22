// de/upb/sse/jess/stubbing/spoon/generate/SpoonStubber.java
package de.upb.sse.jess.stubbing.spoon.generate;

import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCodeSnippetExpression;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtPackageReference;
import spoon.reflect.reference.CtTypeReference;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtPackage;




import java.util.*;

public final class SpoonStubber {

    private final Factory f;

    private final Set<String> createdTypes  = new LinkedHashSet<>();
    private final List<String> createdFields = new ArrayList<>();
    private final List<String> createdCtors  = new ArrayList<>();
    private final List<String> createdMethods= new ArrayList<>();

    public SpoonStubber(Factory f) { this.f = f; }

    /* ---------- TYPES ---------- */

    public int applyTypePlans(Collection<TypeStubPlan> plans) {
        int created = 0;
        for (TypeStubPlan p : plans) {
            if (ensureTypeExists(p)) created++;
        }
        return created;
    }

    private boolean ensureTypeExists(TypeStubPlan p) {
        String qn = p.qualifiedName;
        String pkg = "";
        String name = "Missing";
        int i = qn.lastIndexOf('.');
        if (i >= 0) { pkg = qn.substring(0, i); name = qn.substring(i + 1); }
        else {
            // simple name? put it under 'unknown'
            pkg = "unknown";
            name = qn;
        }

        CtPackage packageObj = f.Package().getOrCreate(pkg);
        CtType<?> existing = packageObj.getType(name);
        if (existing != null) return false;

        CtType<?> created;
        switch (p.kind) {
            case INTERFACE:
                created = f.Interface().create(packageObj, name);
                break;
            case ANNOTATION:
                created = f.Annotation().create(packageObj, name);
                break;
            default:
                created = f.Class().create(packageObj, name);
        }
        created.addModifier(ModifierKind.PUBLIC);
        createdTypes.add(qn);
        return true;
    }

    /* ---------- FIELDS ---------- */

    public int applyFieldPlans(Collection<FieldStubPlan> plans) {
        int created = 0;
        for (FieldStubPlan p : plans) {
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

    /* ---------- CONSTRUCTORS ---------- */
    public int applyConstructorPlans(Collection<ConstructorStubPlan> plans) {
        int created = 0;
        for (ConstructorStubPlan p : plans) {
            CtClass<?> owner = ensurePublicClass(p.ownerType);
            // normalize params first
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


    public int applyMethodPlans(Collection<MethodStubPlan> plans) {
        int created = 0;
        for (MethodStubPlan p : plans) {
            CtClass<?> owner = ensurePublicClass(p.ownerType);
            if (hasMethod(owner, p.name, p.paramTypes)) continue;

            // normalize return + params before creating the method
            CtTypeReference<?> rt0 = normalizeUnknownRef(
                    (p.returnType != null ? p.returnType : f.Type().VOID_PRIMITIVE)
            );
            @SuppressWarnings({"rawtypes","unchecked"})
            CtTypeReference rt = (CtTypeReference) rt0;

            List<CtTypeReference<?>> normParams = new ArrayList<>();
            for (CtTypeReference<?> t : p.paramTypes) normParams.add(normalizeUnknownRef(t));
            List<CtParameter<?>> params = makeParams(normParams);

            // thrown types unchanged (they normally won’t be "Unknown")
            Set<CtTypeReference<? extends Throwable>> thrown = new LinkedHashSet<>();
            for (CtTypeReference<?> t : p.thrownTypes) {
                if (t == null) continue;
                @SuppressWarnings({"rawtypes","unchecked"})
                CtTypeReference<? extends Throwable> tt = (CtTypeReference) t;
                thrown.add(tt);
            }

            Set<ModifierKind> mods = new HashSet<>();
            if (p.isStatic) mods.add(ModifierKind.STATIC);
            switch (p.visibility) {
                case PRIVATE:   mods.add(ModifierKind.PRIVATE);   break;
                case PROTECTED: mods.add(ModifierKind.PROTECTED); break;
                case PUBLIC:    mods.add(ModifierKind.PUBLIC);    break;
                case PACKAGE:   /* none */                        break;
            }

            CtMethod<?> m = f.Method().create(owner, mods, rt, p.name, params, thrown);
            System.out.println("[DEBUG] creating method " + owner.getQualifiedName() + "#" + p.name);
            System.out.println("        return: " + readable(rt0));
            for (int i = 0; i < params.size(); i++) {
                System.out.println("        param" + i + ": " + readable(params.get(i).getType()));
            }

            // right after params/thrown/mods and BEFORE or AFTER creating CtMethod (order doesn't matter for the CU)
            boolean usesUnknown =
                    "unknown.Unknown".equals(readable(rt0))
                            || "Unknown".equals(rt0.getSimpleName())
                            || params.stream().anyMatch(d ->
                            "unknown.Unknown".equals(readable(d.getType()))
                                    || "Unknown".equals(d.getType().getSimpleName())
                    );

// (rare) if you might ever put it in throws, extend the check similarly

            if (usesUnknown) {
                ensureExplicitUnknownImport(owner);
            }


            if (!owner.isInterface()) {
                CtBlock<?> body = f.Core().createBlock();
                CtReturn<?> ret = defaultReturn(rt0);
                if (ret != null) body.addStatement(ret);
                m.setBody(body);
            } else {
                m.setBody(null);
            }

            // keep references qualified so auto-imports (or FQNs) work
            ensureImport(owner, rt0);
            for (CtParameter<?> par : params) ensureImport(owner, par.getType());
            for (CtTypeReference<?> t : thrown) ensureImport(owner, t);

            createdMethods.add(sig(owner.getQualifiedName(), p.name, normParams) + " : " + readable(rt0));
            created++;
        }
        return created;
    }

    /* ---------- Reporting ---------- */

    public void report() {
        System.out.println("\n== SPOON STUBS generated ==");
        for (String t : createdTypes)   System.out.println(" +type  "  + t);
        for (String s : createdFields)  System.out.println(" +field "  + s);
        for (String s : createdCtors)   System.out.println(" +ctor  "  + s);
        for (String s : createdMethods) System.out.println(" +method " + s);
    }

    /* ---------- Helpers ---------- */

    private CtClass<?> ensurePublicClass(CtTypeReference<?> ref) {
        String qn = ref.getQualifiedName();
        String pkg = "";
        String name = "Missing";
        int i = (qn != null ? qn.lastIndexOf('.') : -1);

        if (qn != null && !qn.isEmpty()) {
            if (qn.contains(".")) {
                if (i >= 0) { pkg = qn.substring(0, i); name = qn.substring(i + 1); } else name = qn;
            }
            else { pkg = "unknown"; name = qn; }

        }
        CtPackage p = f.Package().getOrCreate(pkg);
        CtType<?> ex = p.getType(name);
        if (ex instanceof CtClass) return (CtClass<?>) ex;

        CtClass<?> cls = f.Class().create(p, name);
        cls.addModifier(ModifierKind.PUBLIC);
        createdTypes.add((pkg.isEmpty() ? name : (pkg + "." + name)));
        return cls;
    }



    // add this helper in SpoonStubber
    private boolean isNullish(CtTypeReference<?> t) {
        if (t == null) return true;
        String qn = t.getQualifiedName();
        return qn == null || "null".equals(qn) || qn.contains("NullType");
    }

    private List<CtParameter<?>> makeParams(List<CtTypeReference<?>> types) {
        List<CtParameter<?>> params = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            CtParameter<?> par = f.Core().createParameter();
            CtTypeReference<?> raw = (i < types.size() ? types.get(i) : null);
            CtTypeReference<?> safe = (raw == null || isNullish(raw))
                    ? f.Type().createReference(de.upb.sse.jess.generation.unknown.UnknownType.CLASS)
                    : normalizeUnknownRef(raw); // <— ensure normalization survives

            par.setType(safe);
            par.setSimpleName("arg" + i);
            params.add(par);
        }
        return params;
    }


    private boolean hasMethod(CtClass<?> owner, String name, List<CtTypeReference<?>> paramTypes) {
        outer:
        for (CtMethod<?> m : owner.getMethods()) {
            if (!m.getSimpleName().equals(name)) continue;
            List<CtParameter<?>> ps = m.getParameters();
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

    private CtReturn<?> defaultReturn(CtTypeReference<?> t) {
        if (t == null || t.equals(f.Type().VOID_PRIMITIVE)) return null;

        CtCodeSnippetExpression<Object> expr;
        if (t.equals(f.Type().BOOLEAN_PRIMITIVE))      expr = f.Code().createCodeSnippetExpression("false");
        else if (t.equals(f.Type().CHARACTER_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("'\\0'");
        else if (t.equals(f.Type().BYTE_PRIMITIVE))    expr = f.Code().createCodeSnippetExpression("(byte)0");
        else if (t.equals(f.Type().SHORT_PRIMITIVE))   expr = f.Code().createCodeSnippetExpression("(short)0");
        else if (t.equals(f.Type().INTEGER_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("0");
        else if (t.equals(f.Type().LONG_PRIMITIVE))    expr = f.Code().createCodeSnippetExpression("0L");
        else if (t.equals(f.Type().FLOAT_PRIMITIVE))   expr = f.Code().createCodeSnippetExpression("0f");
        else if (t.equals(f.Type().DOUBLE_PRIMITIVE))  expr = f.Code().createCodeSnippetExpression("0d");
        else                                           expr = f.Code().createCodeSnippetExpression("null");

        CtReturn<Object> r = f.Core().createReturn();
        r.setReturnedExpression(expr);
        return r;
    }

    private static String readable(CtTypeReference<?> t) {
        return (t == null ? "void" : String.valueOf(t.getQualifiedName()));
    }

    private static String sig(String ownerFqn, String name, List<CtTypeReference<?>> params) {
        String p = String.join(", ", params.stream().map(SpoonStubber::readable).toArray(String[]::new));
        return ownerFqn + "#" + name + "(" + p + ")";
    }


    // SpoonStubber.dequalifyCurrentPackageUnresolvedRefs()
    public void dequalifyCurrentPackageUnresolvedRefs() {
        CtModel model = f.getModel();
        model.getAllTypes().forEach(t -> {
            String pkg = Optional.ofNullable(t.getPackage())
                    .map(CtPackage::getQualifiedName)
                    .orElse("");
            t.getElements(e -> e instanceof CtTypeReference<?>).forEach(refEl -> {
                CtTypeReference<?> ref = (CtTypeReference<?>) refEl;
                String qn = null;
                try { qn = ref.getQualifiedName(); } catch (Throwable ignored) {}
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


    private static String safeQN(CtTypeReference<?> t) {
        try {
            String s = (t == null ? null : t.getQualifiedName());
            return (s == null ? "" : s);
        } catch (Throwable ignored) {
            return "";
        }
    }


    private void ensureImport(CtType<?> owner, CtTypeReference<?> ref) {
        if (ref == null) return;

        // Skip primitives, void, arrays
        try {
            if (ref.isPrimitive()) return;
            if (ref.equals(f.Type().VOID_PRIMITIVE)) return;
            if (ref.isArray()) return;
        } catch (Throwable ignored) { }

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

        // If it already has a package (qn contains '.'), keep it qualified.
        // If it is a simple name (no package), DO NOT add any import—just force FQN printing.
        if (!qn.contains(".")) {
            // If it's bare "Unknown", normalize to unknown.Unknown (you already do this earlier).
            // Here, just force qualified printing for whatever this is.
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



    private CtTypeReference<?> normalizeUnknownRef(CtTypeReference<?> t) {
        if (t == null) return null;
        String qn = safeQN(t);
        String simple = t.getSimpleName();

        if ("Unknown".equals(simple) && (qn.isEmpty() || !qn.contains("."))) {
            CtTypeReference<?> u = f.Type().createReference(
                    de.upb.sse.jess.generation.unknown.UnknownType.CLASS
            );
            u.setImplicit(false);
            u.setSimplyQualified(false);   // <-- simple name, rely on the explicit import
            return u;
        }
        if (qn.startsWith("unknown.")) {
            t.setImplicit(false);
            t.setSimplyQualified(false);   // <-- simple name, rely on the explicit import
        }
        return t;
    }



    /** Adds `import unknown.Unknown;` once to the owner's CU. */
    private void ensureExplicitUnknownImport(CtType<?> owner) {
        final String FQN = "unknown.Unknown";
        CtCompilationUnit cu = f.CompilationUnit().getOrCreate(owner);

        boolean present = cu.getImports().stream().anyMatch(imp -> {
            try {
                var r = imp.getReference();
                return (r instanceof CtTypeReference)
                        && FQN.equals(((CtTypeReference<?>) r).getQualifiedName());
            } catch (Throwable ignored) { return false; }
        });

        if (!present) {
            cu.getImports().add(f.createImport(f.Type().createReference(FQN)));
        }
    }


}
