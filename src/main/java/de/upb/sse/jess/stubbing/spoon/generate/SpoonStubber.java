// de/upb/sse/jess/stubbing/spoon/generate/SpoonStubber.java
package de.upb.sse.jess.stubbing.spoon.generate;

import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtPackage;

import java.util.*;

import static com.github.javaparser.utils.CodeGenerationUtils.f;

public final class SpoonStubber {

    /* ======================================================================
     *                                 FIELDS
     * ====================================================================== */

    private final Factory f;

    private final Set<String> createdTypes  = new LinkedHashSet<>();
    private final List<String> createdFields = new ArrayList<>();
    private final List<String> createdCtors  = new ArrayList<>();
    private final List<String> createdMethods= new ArrayList<>();

    /* ======================================================================
     *                              CONSTRUCTION
     * ====================================================================== */

    /** Create a stubber bound to a Spoon Factory. */
    public SpoonStubber(Factory f) { this.f = f; }

    /* ======================================================================
     *                                TYPES
     * ====================================================================== */

    /**
     * Apply all TypeStubPlans; creates missing classes/interfaces/annotations.
     * @return number of newly created types
     */
    public int applyTypePlans(Collection<TypeStubPlan> plans) {
        int created = 0;
        for (TypeStubPlan p : plans) {
            if (ensureTypeExists(p)) created++;
        }
        return created;
    }

    /**
     * Ensure a type exists for the given plan (class/interface/annotation).
     * Handles generic arity inference and exception/error superclasses.
     * @return true if a new type was created
     */
    private boolean ensureTypeExists(TypeStubPlan p) {
        String qn = p.qualifiedName;
        // --- member type fast-path: plan says Outer$Inner (or deeper) ---
        if (qn != null && qn.contains("$")) {
            int lastDot = qn.lastIndexOf('.');
            String pkg = (lastDot >= 0 ? qn.substring(0, lastDot) : "");
            String afterPkg = (lastDot >= 0 ? qn.substring(lastDot + 1) : qn); // e.g., Outer$Inner$Deeper

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
                    if (existing == null) {
                        CtType<?> created;
                        switch (p.kind) {
                            case INTERFACE:
                                created = f.Interface().create(parent, simple);
                                break;
                            case ANNOTATION:
                                created = f.Annotation().create((CtPackage) parent, simple);
                                // make sure it has public and a default 'value()' (like your top-level path)
                                created.addModifier(ModifierKind.PUBLIC);
                                CtAnnotationType<?> at = (CtAnnotationType<?>) created;
                                if (at.getMethods().stream().noneMatch(m -> "value".equals(m.getSimpleName()))) {
                                    CtAnnotationMethod<?> am = f.Core().createAnnotationMethod();
                                    am.setSimpleName("value");
                                    am.setType(f.Type().STRING);
                                    at.addMethod(am);
                                }
                                break;
                            default:
                                created = f.Class().create((CtClass<?>) parent, simple);
                        }
                        created.addModifier(ModifierKind.PUBLIC);
                        parent = created;
                        createdTypes.add(qn); // record once; harmless if repeated
                    } else {
                        parent = existing;
                    }
                }

                // Optional: ensure member classes are NON-STATIC by default
                if (parent instanceof CtClass) {
                    parent.removeModifier(ModifierKind.STATIC);
                }

                return false; // we created nested under outer; nothing to add as top-level
            }
        }

        String pkg = "";
        String name = "Missing";
        int i = qn.lastIndexOf('.');
        if (i >= 0) { pkg = qn.substring(0, i); name = qn.substring(i + 1); }
        else { pkg = "unknown"; name = qn; }

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
        int arity = inferGenericArityFromUsages(qn);
        if (arity > 0) addTypeParameters(created, arity);

        // If it looks like an exception/error, set a throwable superclass.
        if (created instanceof CtClass) {
            CtClass<?> cls = (CtClass<?>) created;
            String simple = name;

            boolean looksException = simple.matches(".*Exception(\\d+)?$") || simple.contains("Exception");
            boolean looksError     = simple.matches(".*Error(\\d+)?$")     || simple.endsWith("Error");

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
     * @return number of newly created fields
     */
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

    /* ======================================================================
     *                             CONSTRUCTORS
     * ====================================================================== */

    /**
     * Apply all ConstructorStubPlans; creates public constructors with normalized params.
     * Adds explicit import for unknown.Unknown when parameters use it.
     * @return number of newly created constructors
     */
    public int applyConstructorPlans(Collection<ConstructorStubPlan> plans) {
        int created = 0;
        for (ConstructorStubPlan p : plans) {
            String ownerFqn = p.ownerType.getQualifiedName();
            CtClass<?> owner = ensurePublicClass(f.Type().createReference(ownerFqn));

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
     * @return number of newly created methods
     */
    public int applyMethodPlans(Collection<MethodStubPlan> plans) {
        int created = 0;
        for (MethodStubPlan p : plans) {
            // choose correct owner: interface if defaultOnInterface, else class
            CtType<?> owner = ensurePublicOwnerForMethod(p);

            if (hasMethod(owner, p.name, p.paramTypes)) continue;

            CtTypeReference<?> rt0 = normalizeUnknownRef(
                    (p.returnType != null ? p.returnType : f.Type().VOID_PRIMITIVE));
            @SuppressWarnings({"rawtypes","unchecked"})
            CtTypeReference rt = (CtTypeReference) rt0;

            List<CtTypeReference<?>> normParams = new ArrayList<>();
            for (CtTypeReference<?> t : p.paramTypes) normParams.add(normalizeUnknownRef(t));
            List<CtParameter<?>> params = makeParams(normParams);

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

            boolean ownerIsInterface = owner instanceof CtInterface;

            // ------- BODY & interface default handling (Spoon 10) -------
            if (ownerIsInterface) {
                if (p.defaultOnInterface) {
                    // default method on interface
                    m.setDefaultMethod(true);          // <-- correct way in Spoon 10.x
                    m.addModifier(ModifierKind.PUBLIC);
                    CtBlock<?> body = f.Core().createBlock();
                    CtReturn<?> ret = defaultReturn(rt0);
                    if (ret != null) body.addStatement(ret);
                    m.setBody(body);
                    m.removeModifier(ModifierKind.ABSTRACT);
                } else {
                    // abstract interface method
                    m.setBody(null);
                    m.addModifier(ModifierKind.PUBLIC);   // optional; interfaces imply public
                    m.addModifier(ModifierKind.ABSTRACT);
                }
            } else {
                // concrete class method
                CtBlock<?> body = f.Core().createBlock();
                CtReturn<?> ret = defaultReturn(rt0);
                if (ret != null) body.addStatement(ret);
                m.setBody(body);
                m.removeModifier(ModifierKind.ABSTRACT);
            }
            // -----------------------------------------------------------

            // foreach/Iterable glue (unchanged)
            if ("iterator".equals(p.name) && p.paramTypes.isEmpty()) {
                CtTypeReference<?> rtForIterable = rt0;
                String rtQN = safeQN(rtForIterable);
                if (rtQN.startsWith("java.util.Iterator")) {
                    CtTypeReference<?> iterableRef = f.Type().createReference("java.lang.Iterable");
                    try {
                        List<CtTypeReference<?>> args = rtForIterable.getActualTypeArguments();
                        if (args != null && !args.isEmpty() && args.get(0) != null) {
                            iterableRef.addActualTypeArgument(args.get(0));
                        }
                    } catch (Throwable ignored) {}
                    boolean already = false;
                    try {
                        for (CtTypeReference<?> si : owner.getSuperInterfaces()) {
                            if (safeQN(si).startsWith("java.lang.Iterable")) { already = true; break; }
                        }
                    } catch (Throwable ignored) {}
                    if (!already) owner.addSuperInterface(iterableRef);
                    ensureImport(owner, iterableRef);
                    ensureImport(owner, rt0);
                }
            }

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
        }
        return created;
    }



    /* ======================================================================
     *                               REPORTING
     * ====================================================================== */

    /** Print a simple creation report to stdout. */
    public void report() {
        System.out.println("\n== SPOON STUBS generated ==");
        for (String t : createdTypes)   System.out.println(" +type  "  + t);
        for (String s : createdFields)  System.out.println(" +field "  + s);
        for (String s : createdCtors)   System.out.println(" +ctor  "  + s);
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

    /** Returns true for null/NullType-like references. */
    private boolean isNullish(CtTypeReference<?> t) {
        if (t == null) return true;
        String qn = t.getQualifiedName();
        return qn == null || "null".equals(qn) || qn.contains("NullType");
    }

    /** Create CtParameter list for the provided types; assigns arg0..argN. */
    private List<CtParameter<?>> makeParams(List<CtTypeReference<?>> types) {
        List<CtParameter<?>> params = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            CtParameter<?> par = f.Core().createParameter();
            CtTypeReference<?> raw = (i < types.size() ? types.get(i) : null);
            CtTypeReference<?> safe = (raw == null || isNullish(raw))
                    ? f.Type().createReference(de.upb.sse.jess.generation.unknown.UnknownType.CLASS)
                    : normalizeUnknownRef(raw); // ensure normalization survives

            par.setType(safe);
            par.setSimpleName("arg" + i);
            params.add(par);
        }
        return params;
    }

    /** Check if a method with name and parameter signature exists. */
    private boolean hasMethod(CtType<?> owner, String name, List<CtTypeReference<?>> paramTypes) {
        for (CtMethod<?> m : owner.getMethods()) {
            if (!m.getSimpleName().equals(name)) continue;
            List<CtParameter<?>> ps = m.getParameters();
            if (ps.size() != paramTypes.size()) continue;
            boolean all = true;
            for (int i = 0; i < ps.size(); i++) {
                String a = readable(ps.get(i).getType());
                String b = readable(paramTypes.get(i));
                if (!Objects.equals(a, b)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }



    /** Check if a constructor with parameter signature exists. */
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

    /** Produce a default return statement for the given return type; null for void. */
    private CtReturn<?> defaultReturn(CtTypeReference<?> t) {
        if (t == null || t.equals(f.Type().VOID_PRIMITIVE)) return null;

        CtCodeSnippetExpression<Object> expr;
        if (t.equals(f.Type().BOOLEAN_PRIMITIVE))        expr = f.Code().createCodeSnippetExpression("false");
        else if (t.equals(f.Type().CHARACTER_PRIMITIVE)) expr = f.Code().createCodeSnippetExpression("'\\0'");
        else if (t.equals(f.Type().BYTE_PRIMITIVE))      expr = f.Code().createCodeSnippetExpression("(byte)0");
        else if (t.equals(f.Type().SHORT_PRIMITIVE))     expr = f.Code().createCodeSnippetExpression("(short)0");
        else if (t.equals(f.Type().INTEGER_PRIMITIVE))   expr = f.Code().createCodeSnippetExpression("0");
        else if (t.equals(f.Type().LONG_PRIMITIVE))      expr = f.Code().createCodeSnippetExpression("0L");
        else if (t.equals(f.Type().FLOAT_PRIMITIVE))     expr = f.Code().createCodeSnippetExpression("0f");
        else if (t.equals(f.Type().DOUBLE_PRIMITIVE))    expr = f.Code().createCodeSnippetExpression("0d");
        else                                             expr = f.Code().createCodeSnippetExpression("null");

        CtReturn<Object> r = f.Core().createReturn();
        r.setReturnedExpression(expr);
        return r;
    }

    /** Safe readable name for types (falls back to "void"). */
    private static String readable(CtTypeReference<?> t) {
        return (t == null ? "void" : String.valueOf(t.getQualifiedName()));
    }

    /** Build a signature string like Owner#name(T1, T2). */
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

    /** Safely get a type's qualified name; returns empty string on failure. */
    private static String safeQN(CtTypeReference<?> t) {
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
     */
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
     */
    private CtTypeReference<?> normalizeUnknownRef(CtTypeReference<?> t) {
        if (t == null) return null;
        String qn = safeQN(t);
        String simple = t.getSimpleName();

        if ("Unknown".equals(simple) && (qn.isEmpty() || !qn.contains("."))) {
            CtTypeReference<?> u = f.Type().createReference(
                    de.upb.sse.jess.generation.unknown.UnknownType.CLASS
            );
            u.setImplicit(false);
            u.setSimplyQualified(false);   // simple name, rely on the explicit import
            return u;
        }
        if (qn.startsWith("unknown.")) {
            t.setImplicit(false);
            t.setSimplyQualified(false);   // simple name, rely on the explicit import
        }
        return t;
    }

    /** Adds `import unknown.Unknown;` to the owner's CU once (idempotent). */
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
    public void qualifyAmbiguousSimpleTypes() {
        Map<String, Set<String>> map = simpleNameToPkgs();
        // only keep ambiguous entries
        map.entrySet().removeIf(e -> e.getValue().size() < 2);
        if (map.isEmpty()) return;

        // choose a package per simple name (prefer 'unknown' if present)
        Map<String, String> chosen = new HashMap<>();
        for (var e : map.entrySet()) {
            Set<String> pkgs = e.getValue();
            String pick = pkgs.contains("unknown") ? "unknown" : pkgs.iterator().next(); // deterministic
            chosen.put(e.getKey(), pick);
        }

        // walk all type refs and qualify simple ones that are ambiguous
        f.getModel().getAllTypes().forEach(owner -> {
            owner.getElements(el -> el instanceof CtTypeReference<?>).forEach(refEl -> {
                CtTypeReference<?> ref = (CtTypeReference<?>) refEl;
                String simple = ref.getSimpleName();
                if (simple == null || simple.isEmpty()) return;

                // already qualified? skip
                String qn = safeQN(ref);
                if (qn.contains(".")) return;

                String pkg = chosen.get(simple);
                if (pkg == null) return; // not ambiguous

                // qualify to the chosen pkg
                if (!pkg.isEmpty()) {
                    ref.setPackage(f.Package().createReference(pkg));
                } else {
                    ref.setPackage(null);
                }

                // make sure it prints as FQN (avoids import games)
                ref.setImplicit(false);
                ref.setSimplyQualified(true);
            });
        });
    }

    /**
     * Inspect model usages of a type FQN to infer maximum number of generic type arguments.
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
            try { n = ref.getActualTypeArguments().size(); } catch (Throwable ignored) {}
            if (n > max) max = n;
        }
        return max;
    }

    /**
     * Add type parameters T0..T{arity-1} to a newly created type if it can declare formals.
     */
    private void addTypeParameters(CtType<?> created, int arity) {
        if (!(created instanceof CtFormalTypeDeclarer) || arity <= 0) return;
        CtFormalTypeDeclarer decl = (CtFormalTypeDeclarer) created;

        // don’t duplicate if already has params
        if (decl.getFormalCtTypeParameters() != null && !decl.getFormalCtTypeParameters().isEmpty()) return;

        for (int i = 0; i < arity; i++) {
            CtTypeParameter tp = f.Core().createTypeParameter();
            tp.setSimpleName("T" + i);
            decl.addFormalCtTypeParameter(tp);
        }
    }

    private CtInterface<?> ensurePublicInterface(CtTypeReference<?> ref) {
        String qn = ref.getQualifiedName();
        String pkg = "";
        String name = "Missing";
        int i = (qn != null ? qn.lastIndexOf('.') : -1);

        if (qn != null && !qn.isEmpty()) {
            if (i >= 0) { pkg = qn.substring(0, i); name = qn.substring(i + 1); }
            else { pkg = "unknown"; name = qn; }
        }

        CtPackage p = f.Package().getOrCreate(pkg);
        CtType<?> ex = p.getType(name);

        // If something exists with that name:
        if (ex != null) {
            if (ex instanceof CtInterface) {
                CtInterface<?> itf = (CtInterface<?>) ex;
                // ensure public
                if (!itf.getModifiers().contains(ModifierKind.PUBLIC)) {
                    itf.addModifier(ModifierKind.PUBLIC);
                }
                // ensure generic arity (once)
                if (itf.getFormalCtTypeParameters().isEmpty()) {
                    int arity = inferGenericArityFromUsages((pkg.isEmpty() ? name : pkg + "." + name));
                    if (arity > 0) addTypeParameters(itf, arity);
                }
                return itf;
            }

            // If it was created as a class, remove it and recreate as a public interface
            if (ex instanceof CtClass) {
                // remove the old type from the package
                p.removeType(ex);
            }
        }

        // Create a new interface
        CtInterface<?> itf = f.Interface().create(p, name);
        itf.addModifier(ModifierKind.PUBLIC);

        // Add generic parameters if usages imply arity (e.g., ArrMaker<String>)
        int arity = inferGenericArityFromUsages((pkg.isEmpty() ? name : pkg + "." + name));
        if (arity > 0) addTypeParameters(itf, arity);


        createdTypes.add((pkg.isEmpty() ? name : (pkg + "." + name)));
        return itf;
    }


    private CtType<?> ensurePublicOwnerForMethod(MethodStubPlan p) {
        // If collector already asked for a default method on an interface, respect that.
        if (p.defaultOnInterface) return ensurePublicInterface(p.ownerType);

        // If the owner type is used in a method-reference or lambda context, it must be an interface.
        if (isFunctionalInterfaceContext(p.ownerType)) {
            return ensurePublicInterface(p.ownerType);
        }
        // Otherwise keep the old behavior (class)
        return ensurePublicClass(p.ownerType);
    }

    // SpoonStubber.java

    private boolean isFunctionalInterfaceContext(CtTypeReference<?> ownerRef) {
        String want = safeQN(ownerRef);
        if (want == null || want.isEmpty()) want = ownerRef.getSimpleName();
        if (want == null || want.isEmpty()) return false;

        // scan locals
        for (CtLocalVariable<?> lv : f.getModel().getElements((CtLocalVariable<?> v) -> true)) {
            CtTypeReference<?> t = lv.getType();
            if (t == null) continue;
            String qn = safeQN(t);
            if (!want.equals(qn) && !t.getSimpleName().equals(ownerRef.getSimpleName())) continue;

            CtExpression<?> init = lv.getDefaultExpression();
            if (init == null) continue;

            // method reference: e.g., String[]::new, SomeType::factory, this::m
            if (init instanceof spoon.reflect.code.CtExecutableReferenceExpression) return true;

            // lambda: x -> ..., (a,b) -> ...
            if (init instanceof spoon.reflect.code.CtLambda) return true;
        }

        // scan fields too (for completeness)
        for (CtField<?> fd : f.getModel().getElements((CtField<?> v) -> true)) {
            CtTypeReference<?> t = fd.getType();
            if (t == null) continue;
            String qn = safeQN(t);
            if (!want.equals(qn) && !t.getSimpleName().equals(ownerRef.getSimpleName())) continue;

            CtExpression<?> init = fd.getDefaultExpression();
            if (init == null) continue;

            if (init instanceof spoon.reflect.code.CtExecutableReferenceExpression) return true;
            if (init instanceof spoon.reflect.code.CtLambda) return true;
        }

        return false;
    }



    /** Returns an existing type if present. If missing, creates an interface when preferInterface==true, else a class. */
    private CtType<?> ensurePublicOwner(CtTypeReference<?> ref, boolean preferInterface) {
        String qn = safeQN(ref);
        String pkg = "";
        String name = "Missing";
        int i = (qn != null ? qn.lastIndexOf('.') : -1);
        if (qn != null && !qn.isEmpty()) {
            if (i >= 0) { pkg = qn.substring(0, i); name = qn.substring(i + 1); }
            else { pkg = "unknown"; name = qn; }
        }
        CtPackage p = f.Package().getOrCreate(pkg);
        CtType<?> ex = p.getType(name);
        if (ex != null) return ex;

        if (preferInterface) {
            CtInterface<?> itf = f.Interface().create(p, name);
            itf.addModifier(ModifierKind.PUBLIC);
            createdTypes.add((pkg.isEmpty() ? name : (pkg + "." + name)));
            return itf;
        } else {
            CtClass<?> cls = f.Class().create(p, name);
            cls.addModifier(ModifierKind.PUBLIC);
            createdTypes.add((pkg.isEmpty() ? name : (pkg + "." + name)));
            return cls;
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
                    try { return "java.lang.annotation.Repeatable"
                            .equals(a.getAnnotationType().getQualifiedName()); }
                    catch (Throwable ignored) { return false; }
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

    /** Force JDK meta-annotation types on a given annotation instance. */
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
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
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
                        } catch (Throwable t2) { name = "value"; }
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






}
