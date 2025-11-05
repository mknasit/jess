package de.upb.sse.jess.stubbing.spoon.generate;

import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector.safeQN;

public final class SpoonStubber {

    /* ======================================================================
     *                                 FIELDS
     * ====================================================================== */

    private final Factory f;
    private final CtModel model;

    private final Set<String> createdTypes = new LinkedHashSet<>();
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
     * @return number of newly created types
     */

    public int applyTypePlans(Collection<TypeStubPlan> plans) {
        List<TypeStubPlan> ordered = new ArrayList<>(plans);
        ordered.sort((a, b) -> {
            boolean au = a.qualifiedName != null && a.qualifiedName.startsWith("unknown.");
            boolean bu = b.qualifiedName != null && b.qualifiedName.startsWith("unknown.");
            return (au == bu) ? 0 : (au ? 1 : -1);
        });

        int created = 0;
        for (TypeStubPlan p : ordered) {
            String qn = p.qualifiedName;
            if (qn != null && (qn.startsWith("java.")
                    || qn.startsWith("javax.")
                    || qn.startsWith("jakarta.")
                    || qn.startsWith("sun.")
                    || qn.startsWith("jdk."))) {
                continue; // skip creating any JDK/Jakarta types
            }

            if (ensureTypeExists(p)) {
                created++;
                // optional: debug
                 System.out.println("[types] created " + p.qualifiedName);
            }
        }
        return created;
    }


    /**
     * Ensure a type exists for the given plan (class/interface/annotation).
     * Handles generic arity inference and exception/error superclasses.
     *
     * @return true if a new type was created
     */
    private boolean ensureTypeExists(TypeStubPlan p) {
        String qn = p.qualifiedName;
        if (qn != null && (qn.startsWith("java.")
                || qn.startsWith("javax.")
                || qn.startsWith("jakarta.")
                || qn.startsWith("sun.")
                || qn.startsWith("jdk."))) {
            return false;
        }
        if (qn != null && qn.startsWith("unknown.")) {
            String simple = qn.substring(qn.lastIndexOf('.') + 1);

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
        if (qn != null && qn.startsWith("unknown.")) {
            String simple = qn.substring(qn.lastIndexOf('.') + 1); // e.g., "P"
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
                    if (existing != null) {
                        // Upgrade/downgrade if the existing kind is wrong
                        if (p.kind == TypeStubPlan.Kind.INTERFACE && existing instanceof CtClass) {
                            existing.delete();
                        } else if (p.kind == TypeStubPlan.Kind.CLASS && existing instanceof CtInterface) {
                            existing.delete();
                        } else if (p.kind == TypeStubPlan.Kind.ANNOTATION && !(existing instanceof CtAnnotationType)) {
                            existing.delete();
                        } else {
                            return false;
                        }
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
        if (i >= 0) {
            pkg = qn.substring(0, i);
            name = qn.substring(i + 1);
        } else {
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
     *
     * @return number of newly created methods
     */
    public int applyMethodPlans(Collection<MethodStubPlan> plans) {
        int created = 0;

        for (MethodStubPlan p : plans) {

            // Start from the plan’s declared owner (immutable in your plan class)
            CtTypeReference<?> oref = p.ownerType;
            CtTypeReference<?> normalizedOwnerRef = oref;

            String oqn = null;
            try { oqn = (oref != null ? oref.getQualifiedName() : null); } catch (Throwable ignored) {}

            if (oqn != null && oqn.startsWith("unknown.")) {
                String simple = oref.getSimpleName();

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


            // short-circuit if already present on the owner using your current check
            if (hasMethod(owner, p.name, p.paramTypes)) continue;

            // 2) normalize return type
            CtTypeReference<?> rt0 = normalizeUnknownRef(
                    (p.returnType != null ? p.returnType : f.Type().VOID_PRIMITIVE));
            @SuppressWarnings({"rawtypes", "unchecked"})
            CtTypeReference rt = (CtTypeReference) rt0;

            // 3) normalize parameters (convert last to array if varargs)
            boolean willBeVarargs = p.varargs;
            List<CtTypeReference<?>> normParams = new ArrayList<>(p.paramTypes.size());
            for (int i = 0; i < p.paramTypes.size(); i++) {
                CtTypeReference<?> t = normalizeUnknownRef(p.paramTypes.get(i));
                if (willBeVarargs && i == p.paramTypes.size() - 1) {
                    // varargs at AST-level is an array on the last parameter
                    t = f.Type().createArrayReference(t);
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


            // 7) interface default/abstract body handling
            boolean ownerIsInterface = owner instanceof CtInterface;
            if (ownerIsInterface) {
                if (p.isStatic) {
                    m.addModifier(ModifierKind.STATIC);
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
                    m.setBody(null);
                    m.addModifier(ModifierKind.PUBLIC);
                    m.addModifier(ModifierKind.ABSTRACT);
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
                                mirrorParamRefs = java.util.Collections.emptyList();
                            } else if (isEnumValueOf) {
                                mirrorRt = mirrorElem;
                                mirrorParamRefs = java.util.List.of(f.Type().createReference("java.lang.String"));
                            } else { // name()
                                mirrorRt = f.Type().createReference("java.lang.String");
                                mirrorParamRefs = java.util.Collections.emptyList();
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
            CtTypeReference<?> safe = (raw == null || isNullish(raw))
                    ? f.Type().createReference(de.upb.sse.jess.generation.unknown.UnknownType.CLASS)
                    : normalizeUnknownRef(raw); // ensure normalization survives

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

    public void qualifyAmbiguousSimpleTypes(java.util.Set<String> onlySimples) {
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

            // always create/fetch the owner only (never the interface type)
            CtType<?> owner = ensurePublicOwnerForFqn(ownerFqn);
            if (owner == null) continue;

            Set<CtTypeReference<?>> ifaces = e.getValue();
            if (ifaces == null || ifaces.isEmpty()) continue;

            for (CtTypeReference<?> ifaceRef : ifaces) {
                if (ifaceRef == null) continue;

                String iqn = safeQN(ifaceRef);
                if (iqn == null || iqn.isEmpty()) continue;

                // 1) Never create or upgrade JDK/Jakarta/etc. interfaces – just reference them
                if (isJdkFqn(iqn)) {
                    CtTypeReference<?> jref = f.Type().createReference(iqn);
                    // dedupe by erasure/simple name
                    boolean exists = owner.getSuperInterfaces().stream()
                            .anyMatch(cur -> cur != null && cur.getSimpleName().equals(jref.getSimpleName()));
                    if (!exists) owner.addSuperInterface(jref);
                    continue;
                }

                // 2) Non-JDK: prefer an existing interface if present
                CtType<?> existing = f.Type().get(iqn);
                CtTypeReference<?> toAttach;

                if (existing instanceof CtInterface) {
                    toAttach = existing.getReference();
                } else if (existing instanceof CtClass) {
                    // Only convert if it’s one of OUR fresh empty stubs
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
                        toAttach = itf.getReference();
                    } else {
                        // don’t mutate user code – just attach a reference (javac will complain if needed)
                        toAttach = existing.getReference();
                    }
                } else {
                    // 3) Not present → create a new interface (non-JDK)
                    int dot = iqn.lastIndexOf('.');
                    String pkg = (dot >= 0 ? iqn.substring(0, dot) : "");
                    String sn  = (dot >= 0 ? iqn.substring(dot + 1) : iqn);
                    CtPackage p = f.Package().getOrCreate(pkg);
                    CtInterface<?> itf = f.Interface().create(p, sn);
                    itf.addModifier(ModifierKind.PUBLIC);
                    createdTypes.add(itf.getQualifiedName());
                    toAttach = itf.getReference();
                }

                // 4) attach if not already present (dedupe by erasure/simple)
                boolean exists = owner.getSuperInterfaces().stream()
                        .anyMatch(cur -> cur != null && cur.getSimpleName().equals(toAttach.getSimpleName()));
                if (!exists) owner.addSuperInterface(toAttach);
            }
        }
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





    private String erasureFqn(CtTypeReference<?> tr) {
        String qn = safeQN(tr);
        int lt = qn.indexOf('<');
        return (lt >= 0 ? qn.substring(0, lt) : qn);
    }


    private CtPackage ensurePackage(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            // unnamed/default package
            CtPackage root = f.getModel().getRootPackage();
            return root.getFactory().Package().getRootPackage(); // or simply root
        }
        // Spoon will create missing segments as needed:
        return f.Package().getOrCreate(fqn);
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


    public void rebindUnknownTypeReferencesToConcrete() {
        // Build a map: SimpleName -> first concrete (non-unknown) FQN we have
        Map<String, String> concreteBySimple = new LinkedHashMap<>();
        for (CtType<?> t : f.getModel().getAllTypes()) {
            CtPackage p = t.getPackage();
            String pkg = (p == null ? "" : p.getQualifiedName());
            if (!"unknown".equals(pkg)) {
                concreteBySimple.putIfAbsent(t.getSimpleName(), t.getQualifiedName());
            }
        }

        // 1) fix CtTypeReference-qualified names that point at unknown.*
        for (CtTypeReference<?> r : f.getModel().getElements(new TypeFilter<>(CtTypeReference.class))) {
            String qn = safeQN(r);
            if (qn == null || !qn.startsWith("unknown.")) continue;

            String simple = r.getSimpleName();
            String concreteFqn = concreteBySimple.get(simple);
            if (concreteFqn == null) continue; // no concrete alternative -> keep

            CtTypeReference<?> to = f.Type().createReference(concreteFqn);
            r.replace(to);
        }

// 2) fix type-accesses: unknown.F::something etc.
        for (CtTypeAccess<?> ta : f.getModel().getElements(new TypeFilter<>(CtTypeAccess.class))) {
            CtTypeReference<?> tr = null;
            try { tr = ta.getAccessedType(); } catch (Throwable ignored) {}
            if (tr == null) continue;

            String qn = safeQN(tr);
            if (qn == null || !qn.startsWith("unknown.")) continue;

            String simple = tr.getSimpleName();
            String concreteFqn = concreteBySimple.get(simple);
            if (concreteFqn == null) continue;

            CtTypeAccess<?> repl = f.Code().createTypeAccess(f.Type().createReference(concreteFqn));
            ta.replace(repl);
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
                            return qn != null && qn.startsWith("unknown.");
                        } catch (Throwable ex) {
                            return false;
                        }
                    });

            if (hasUnknownRefs) continue;

            boolean removed = cu.getImports().removeIf(imp -> {
                try {
                    spoon.reflect.reference.CtReference r = imp.getReference();
                    if (r == null) return false;
                    String s = r.toString(); // robust across CtReference subclasses
                    return "unknown.*".equals(s) || (s != null && s.startsWith("unknown."));
                } catch (Throwable ex) {
                    return false;
                }
            });

            if (removed) {
                System.out.println("[imports] removed unknown.* import from CU of " + t.getQualifiedName());
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
            // If we created this class in this run and it is still empty, replace it with an interface
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
