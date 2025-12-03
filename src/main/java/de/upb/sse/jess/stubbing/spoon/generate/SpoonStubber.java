// de/upb/sse/jess/stubbing/spoon/generate/SpoonStubber.java
package de.upb.sse.jess.stubbing.spoon.generate;

import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtArrayTypeReference;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtPackage;

import java.util.*;
import java.nio.file.Path;

import static com.github.javaparser.utils.CodeGenerationUtils.f;

public final class SpoonStubber {

    /* ======================================================================
     *                                 FIELDS
     * ====================================================================== */

    private final Factory f;
    private final java.nio.file.Path slicedSrcDir;
    private final Set<String> sliceTypeFqns;

    private final Set<String> createdTypes  = new LinkedHashSet<>();
    private final List<String> createdFields = new ArrayList<>();
    private final List<String> createdCtors  = new ArrayList<>();
    private final List<String> createdMethods= new ArrayList<>();
    
    // Centralized unknown package constant
    private static final String UNKNOWN_PACKAGE = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
    private static final String UNKNOWN_CLASS = de.upb.sse.jess.generation.unknown.UnknownType.CLASS;

    /* ======================================================================
     *                              CONSTRUCTION
     * ====================================================================== */

    /** Create a stubber bound to a Spoon Factory. */
    public SpoonStubber(Factory f) { 
        this.f = f;
        this.slicedSrcDir = null;
        this.sliceTypeFqns = null;
    }
    
    /** Create a stubber bound to a Spoon Factory with slice filtering. */
    public SpoonStubber(Factory f, java.nio.file.Path slicedSrcDir, Set<String> sliceTypeFqns) { 
        this.f = f;
        this.slicedSrcDir = slicedSrcDir;
        this.sliceTypeFqns = sliceTypeFqns != null ? sliceTypeFqns : new HashSet<>();
    }

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
                            case ENUM:
                                // For nested enums, use Core API and addNestedType
                                if (parent instanceof CtClass) {
                                    created = f.Core().createEnum();
                                    created.setSimpleName(simple);
                                    ((CtClass<?>) parent).addNestedType(created);
                                } else {
                                    // Fallback: create as class if parent is not a class
                                    created = f.Class().create((CtClass<?>) parent, simple);
                                }
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
        else { pkg = UNKNOWN_PACKAGE; name = qn; }

        CtPackage packageObj = f.Package().getOrCreate(pkg);
        CtType<?> existing = packageObj.getType(name);
        
        // If type exists in model, check if it's from slice or context
        if (existing != null) {
            // If it's from slice, it will be pretty-printed, so no stub needed
            if (isFromSlice(existing)) {
                return false;
            }
            // CRITICAL FIX: Even if type exists in context, we should NOT copy the full source code
            // because it may have dependencies that don't exist (e.g., cn.wildfirechat.common).
            // Instead, we should generate a minimal stub based on what's actually needed.
            // The collection results tell us what members are needed, so we'll create a minimal stub.
            // 
            // Example: Utility class exists in context with full implementation, but we only need
            // printExecption(Logger, Exception). Copying the full class would bring in dependencies
            // like IMExceptionEvent, Server, Gson, etc. that don't exist in the stub environment.
            //
            // Solution: Always generate minimal stub for context types, don't copy full source.
            // Fall through to create minimal stub below.
            System.out.println("[SpoonStubber] DEBUG: Type " + qn + " exists in context, but generating minimal stub instead of copying full source");
        }

        CtType<?> created;

        switch (p.kind) {
            case INTERFACE:
                created = f.Interface().create(packageObj, name);
                break;
            case ENUM:
                created = f.Enum().create(packageObj, name);
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
        if (arity > 0) {
            addTypeParameters(created, arity);
        } else {
            // DEBUG: Log when we're not adding type parameters
            System.out.println("[SpoonStubber] DEBUG: Not adding type parameters for " + qn + " (arity=" + arity + ")");
        }

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
            String fieldTypeQn = readable(fieldType);
            if ((UNKNOWN_PACKAGE + "." + UNKNOWN_CLASS).equals(fieldTypeQn) || "Unknown".equals(fieldType.getSimpleName())) {
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
                    (UNKNOWN_PACKAGE + "." + UNKNOWN_CLASS).equals(readable(d.getType()))
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
            
            // Detect varargs: if we have 2 params and second is Object, and method name suggests varargs,
            // make it varargs (String, Object...)
            // Also check if we have 3+ params with first being String (logging pattern)
            boolean isVarargs = false;
            if (normParams.size() == 2 && normParams.get(1) != null) {
                String secondParamQn = safeQN(normParams.get(1));
                if ("java.lang.Object".equals(secondParamQn)) {
                    // Check if method name suggests varargs (logging methods)
                    if (p.name != null && (p.name.equals("info") || p.name.equals("debug") || p.name.equals("warn") 
                            || p.name.equals("error") || p.name.equals("trace") || p.name.equals("log"))) {
                        isVarargs = true;
                    }
                }
            } else if (normParams.size() >= 3 && normParams.get(0) != null) {
                // If we have 3+ params and first is String, likely varargs (should have been converted in collector)
                // But if it wasn't, convert here
                String firstParamQn = safeQN(normParams.get(0));
                if ("java.lang.String".equals(firstParamQn)) {
                    if (p.name != null && (p.name.equals("info") || p.name.equals("debug") || p.name.equals("warn") 
                            || p.name.equals("error") || p.name.equals("trace") || p.name.equals("log"))) {
                        // Convert to varargs: keep first, convert rest to Object...
                        normParams = new ArrayList<>();
                        normParams.add(p.paramTypes.get(0)); // Keep original first param
                        normParams.add(f.Type().createReference("java.lang.Object")); // Varargs param
                        isVarargs = true;
                    }
                }
            }
            
            List<CtParameter<?>> params = makeParams(normParams, isVarargs);

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
                    (UNKNOWN_PACKAGE + "." + UNKNOWN_CLASS).equals(readable(rt0))
                            || "Unknown".equals(rt0.getSimpleName())
                            || params.stream().anyMatch(d ->
                            (UNKNOWN_PACKAGE + "." + UNKNOWN_CLASS).equals(readable(d.getType()))
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
    
    /** Get the set of created stub type FQNs. */
    public Set<String> getCreatedTypes() {
        return new LinkedHashSet<>(createdTypes);
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
        return makeParams(types, false);
    }
    
    private List<CtParameter<?>> makeParams(List<CtTypeReference<?>> types, boolean lastIsVarargs) {
        List<CtParameter<?>> params = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            CtParameter<?> par = f.Core().createParameter();
            CtTypeReference<?> raw = (i < types.size() ? types.get(i) : null);
            CtTypeReference<?> safe = (raw == null || isNullish(raw))
                    ? f.Type().createReference(de.upb.sse.jess.generation.unknown.UnknownType.CLASS)
                    : normalizeUnknownRef(raw); // ensure normalization survives

            // For varargs, we need to create an array type reference
            if (lastIsVarargs && i == types.size() - 1) {
                // Create array type reference for varargs (e.g., Object[] becomes Object...)
                // Ensure we get a proper array type reference
                CtTypeReference<?> arrayType = f.Type().createArrayReference(safe);
                // Verify it's actually an array type
                if (!(arrayType instanceof spoon.reflect.reference.CtArrayTypeReference)) {
                    // If createArrayReference didn't return the right type, try creating it differently
                    try {
                        spoon.reflect.reference.CtArrayTypeReference<?> arrayRef = 
                            f.Core().createArrayTypeReference();
                        arrayRef.setComponentType(safe);
                        arrayType = arrayRef;
                    } catch (Throwable ignored) {
                        // Fallback: use createArrayReference result anyway
                    }
                }
                par.setType(arrayType);
                par.setVarArgs(true);
            } else {
                par.setType(safe);
            }
            
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
                
                if (isSliceType(type)) {
                    sliceTypes.add(type);
                }
            }
        } catch (Throwable e) {
            System.err.println("[SpoonStubber] Error getting slice types: " + e.getMessage());
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
            System.err.println("[SpoonStubber] StackOverflowError getting all types - likely circular dependencies");
            System.err.println("[SpoonStubber] Returning empty collection - some slice types may be missing");
            return Collections.emptyList();
        } catch (Throwable e) {
            System.err.println("[SpoonStubber] Error getting all types: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * De-qualify unresolved type refs that only appear to be in the current package,
     * unless they already belong to 'unknown.' space.
     * Only processes slice types.
     */
    public void dequalifyCurrentPackageUnresolvedRefs() {
        CtModel model = f.getModel();
        getSliceTypes(model).forEach(t -> {
                
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
                        // Don't dequalify unknown.* types - they need their package
                        if (qn2.isEmpty() || !qn2.startsWith(UNKNOWN_PACKAGE + ".")) {
                            ref.setPackage(null);
                        } else {
                            // Ensure unknown.* types have their package set correctly
                            if (ref.getPackage() == null && qn2.startsWith(UNKNOWN_PACKAGE + ".")) {
                                ref.setPackage(f.Package().createReference(UNKNOWN_PACKAGE));
                            }
                            // Ensure it will use import, not FQN
                            ref.setImplicit(false);
                            ref.setSimplyQualified(false);
                        }
                    }
                });
        });
    }
    
    /**
     * Check if a type is from the slice directory.
     */
    private boolean isSliceType(CtType<?> type) {
        if (slicedSrcDir == null || sliceTypeFqns == null || sliceTypeFqns.isEmpty()) {
            return true; // If no filtering info, process everything (backward compat)
        }
        
        String qn = type.getQualifiedName();
        if (qn != null && sliceTypeFqns.contains(qn)) {
            return true;
        }
        
        // Also check by file path
        try {
            spoon.reflect.cu.SourcePosition pos = type.getPosition();
            if (pos != null && pos.getFile() != null) {
                java.nio.file.Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                java.nio.file.Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
                return filePath.startsWith(sliceRoot);
            }
        } catch (Throwable ignored) {}
        
        return false;
    }
    
    /**
     * Check if a type is from the slice directory (alias for isSliceType for clarity).
     */
    private boolean isFromSlice(CtType<?> type) {
        return isSliceType(type);
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
     * Ensure proper import handling for type references.
     * For unknown.* types: add explicit import and use simple name.
     * For other non-JDK types: force FQN printing (no import).
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

        // Special handling for unknown.* types: add import and use simple name
        String UNKNOWN_PACKAGE = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
        if (qn.startsWith(UNKNOWN_PACKAGE + ".")) {
            // Ensure package is set
            if (ref.getPackage() == null) {
                ref.setPackage(f.Package().createReference(UNKNOWN_PACKAGE));
            }
            // Use simple name (not FQN) and ensure import exists
            ref.setImplicit(false);
            ref.setSimplyQualified(false); // simple name, rely on import
            ensureExplicitImport(owner, ref);
            return;
        }

        // If it already has a package (qn contains '.'), keep it qualified.
        // If it is a simple name (no package), DO NOT add any import—just force FQN printing.
        if (!qn.contains(".")) {
            ref.setImplicit(false);
            ref.setSimplyQualified(true); // print FQN, avoid imports entirely
            return;
        }

        // For other non-JDK, non-primitive, with a package: force FQN printing (no import).
        if (ref.getPackage() == null) {
            int i = qn.lastIndexOf('.');
            if (i > 0) ref.setPackage(f.Package().createReference(qn.substring(0, i)));
        }
        ref.setImplicit(false);
        ref.setSimplyQualified(true); // always print FQN
    }

    /**
     * Add an explicit import for a type reference to the owner's compilation unit.
     */
    private void ensureExplicitImport(CtType<?> owner, CtTypeReference<?> ref) {
        String qn = safeQN(ref);
        if (qn == null || qn.isEmpty() || !qn.contains(".")) return;

        CtCompilationUnit cu = f.CompilationUnit().getOrCreate(owner);
        if (cu == null) return;

        // Check if import already exists
        boolean present = cu.getImports().stream().anyMatch(imp -> {
            try {
                var r = imp.getReference();
                return (r instanceof CtTypeReference)
                        && qn.equals(((CtTypeReference<?>) r).getQualifiedName());
            } catch (Throwable ignored) { return false; }
        });

        if (!present) {
            cu.getImports().add(f.createImport(f.Type().createReference(qn)));
        }
    }

    /**
     * Normalize a reference to 'unknown.Unknown' when it appears as bare 'Unknown'
     * or inside the 'unknown.' package; makes it rely on an explicit import.
     * Also handles all unknown.* types (not just Unknown).
     */
    private CtTypeReference<?> normalizeUnknownRef(CtTypeReference<?> t) {
        if (t == null) return null;
        String qn = safeQN(t);
        String simple = t.getSimpleName();
        String UNKNOWN_PACKAGE = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;

        if ("Unknown".equals(simple) && (qn.isEmpty() || !qn.contains("."))) {
            CtTypeReference<?> u = f.Type().createReference(
                    UNKNOWN_PACKAGE + "." + de.upb.sse.jess.generation.unknown.UnknownType.CLASS
            );
            u.setImplicit(false);
            u.setSimplyQualified(false);   // simple name, rely on the explicit import
            return u;
        }
        if (qn.startsWith(UNKNOWN_PACKAGE + ".")) {
            t.setImplicit(false);
            t.setSimplyQualified(false);   // simple name, rely on the explicit import
        }
        return t;
    }

    /** Adds `import unknown.Unknown;` to the owner's CU once (idempotent). */
    private void ensureExplicitUnknownImport(CtType<?> owner) {
        final String UNKNOWN_PACKAGE = de.upb.sse.jess.generation.unknown.UnknownType.PACKAGE;
        final String FQN = UNKNOWN_PACKAGE + "." + de.upb.sse.jess.generation.unknown.UnknownType.CLASS;
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
     * Only considers slice types for performance.
     */
    private Map<String, Set<String>> simpleNameToPkgs() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        getSliceTypes(f.getModel()).forEach(t -> {
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
     * Only processes slice types.
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

        // walk all type refs and qualify simple ones that are ambiguous (only in slice types)
        getSliceTypes(f.getModel()).forEach(owner -> {
            
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
     * Ensure that all type references to unknown.* types have proper imports added.
     * This fixes cases where types like IMHandler (unknown.IMHandler) are referenced
     * but the import statement is missing or malformed.
     */
    public void ensureUnknownPackageImports() {
        CtModel model = f.getModel();
        getSliceTypes(model).forEach(t -> {
            try {
                spoon.reflect.declaration.CtCompilationUnit cu = f.CompilationUnit().getOrCreate(t);
                if (cu == null) return;
                
                String typePkg = t.getPackage() != null ? t.getPackage().getQualifiedName() : "";
                
                // Find all type references to unknown.* types
                Set<String> unknownTypeFqns = new HashSet<>();
                
                // Check superclass (extends clause) - this is often where the issue occurs
                if (t instanceof CtClass) {
                    CtClass<?> cls = (CtClass<?>) t;
                    CtTypeReference<?> superclass = cls.getSuperclass();
                    if (superclass != null) {
                        // Check the raw type (without generics)
                        try {
                            String superQn = superclass.getQualifiedName();
                            if (superQn == null || superQn.isEmpty()) {
                                // Try to get it from the declaration
                                CtType<?> superDecl = superclass.getTypeDeclaration();
                                if (superDecl != null) {
                                    superQn = superDecl.getQualifiedName();
                                }
                            }
                            // If still empty, check if it's a simple name that should be unknown.*
                            if ((superQn == null || superQn.isEmpty()) || 
                                (!superQn.contains(".") && superclass.getSimpleName() != null)) {
                                String simple = superclass.getSimpleName();
                                // Check if this type exists in unknown package
                                CtType<?> unknownType = f.Type().get(UNKNOWN_PACKAGE + "." + simple);
                                if (unknownType != null) {
                                    superQn = UNKNOWN_PACKAGE + "." + simple;
                                    // Fix the reference
                                    superclass.setPackage(f.Package().createReference(UNKNOWN_PACKAGE));
                                    superclass.setImplicit(false);
                                    superclass.setSimplyQualified(false);
                                }
                            }
                            if (superQn != null && superQn.startsWith(UNKNOWN_PACKAGE + ".") && !superQn.equals(UNKNOWN_PACKAGE + "." + UNKNOWN_CLASS)) {
                                unknownTypeFqns.add(superQn);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                
                // Also check all type references in the type
                t.getElements(e -> e instanceof CtTypeReference<?>).forEach(refEl -> {
                    CtTypeReference<?> ref = (CtTypeReference<?>) refEl;
                    String qn = safeQN(ref);
                    // If qn is empty or doesn't contain '.', check if it should be unknown.*
                    if ((qn == null || qn.isEmpty() || !qn.contains(".")) && ref.getSimpleName() != null) {
                        String simple = ref.getSimpleName();
                        // Check if this type exists in unknown package
                        try {
                            CtType<?> unknownType = f.Type().get(UNKNOWN_PACKAGE + "." + simple);
                            if (unknownType != null) {
                                qn = UNKNOWN_PACKAGE + "." + simple;
                                // Fix the reference
                                ref.setPackage(f.Package().createReference(UNKNOWN_PACKAGE));
                                ref.setImplicit(false);
                                ref.setSimplyQualified(false);
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (qn != null && qn.startsWith(UNKNOWN_PACKAGE + ".") && !qn.equals(UNKNOWN_PACKAGE + "." + UNKNOWN_CLASS)) {
                        unknownTypeFqns.add(qn);
                    }
                });
                
                // First, remove any invalid imports (imports without package names)
                cu.getImports().removeIf(imp -> {
                    try {
                        if (imp.getImportKind() == spoon.reflect.declaration.CtImportKind.TYPE) {
                            var ref = imp.getReference();
                            if (ref instanceof CtTypeReference) {
                                String impQn = ((CtTypeReference<?>) ref).getQualifiedName();
                                // Remove imports that don't have a package (invalid)
                                if (impQn == null || impQn.isEmpty() || !impQn.contains(".")) {
                                    return true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    return false;
                });
                
                // Add imports for unknown.* types (only if not in same package)
                for (String fqn : unknownTypeFqns) {
                    if (fqn.startsWith(UNKNOWN_PACKAGE + ".") && !typePkg.equals(UNKNOWN_PACKAGE)) {
                        // Check if import already exists
                        boolean exists = cu.getImports().stream().anyMatch(imp -> {
                            try {
                                if (imp.getImportKind() == spoon.reflect.declaration.CtImportKind.TYPE) {
                                    var ref = imp.getReference();
                                    if (ref instanceof CtTypeReference) {
                                        String impQn = ((CtTypeReference<?>) ref).getQualifiedName();
                                        return fqn.equals(impQn);
                                    }
                                }
                            } catch (Throwable ignored) {}
                            return false;
                        });
                        
                        if (!exists) {
                            try {
                                CtTypeReference<?> ref = f.Type().createReference(fqn);
                                if (ref != null) {
                                    cu.getImports().add(f.createImport(ref));
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        });
    }
    
    /**
     * Inspect model usages of a type FQN to infer maximum number of generic type arguments.
     * Only scans slice types for performance.
     * @return maximum arity observed (0 if none)
     */
    private int inferGenericArityFromUsages(String fqn) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        String pkg = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
        int max = 0;
        
        // Only scan slice types for performance
        Collection<CtType<?>> sliceTypes = getSliceTypes(f.getModel());
        for (CtType<?> sliceType : sliceTypes) {
            // Check superclass (extends clause) - this is critical for generic detection
            if (sliceType instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) sliceType;
                CtTypeReference<?> superclass = cls.getSuperclass();
                if (superclass != null) {
                    String qn = safeQN(superclass);
                    String sn = superclass.getSimpleName();
                    
                    // Match if:
                    // 1. Exact FQN match
                    // 2. Simple name matches AND (qn is empty OR qn equals simple name OR qn equals fqn)
                    //    This handles cases where the reference doesn't have a package set
                    // 3. Also check if the type exists in the model with the expected FQN
                    boolean sameType = fqn.equals(qn) 
                            || (simple.equals(sn) && (qn.isEmpty() || qn.equals(simple) || qn.equals(fqn)));
                    
                    // If simple name matches but qn doesn't match fqn, check if type exists in model
                    if (!sameType && simple.equals(sn)) {
                        try {
                            CtType<?> typeInModel = f.Type().get(fqn);
                            if (typeInModel != null) {
                                sameType = true;
                            }
                        } catch (Throwable ignored) {}
                    }
                    
                    if (sameType) {
                        try {
                            // Check actual type arguments
                            List<CtTypeReference<?>> typeArgs = superclass.getActualTypeArguments();
                            int n = (typeArgs != null) ? typeArgs.size() : 0;
                            if (n > max) {
                                max = n;
                                System.out.println("[SpoonStubber] DEBUG: Found generic usage of " + fqn + " with " + n + " type argument(s) in superclass of " + sliceType.getQualifiedName());
                            }
                        } catch (Throwable ignored) {}
                    } else {
                        // DEBUG: Log when we're not matching
                        System.out.println("[SpoonStubber] DEBUG: Not matching superclass: qn=" + qn + ", sn=" + sn + ", fqn=" + fqn + ", simple=" + simple);
                    }
                }
            }
            
            // Check all type references in the type
            for (var el : sliceType.getElements(e -> e instanceof CtTypeReference<?>)) {
                CtTypeReference<?> ref = (CtTypeReference<?>) el;
                String qn = safeQN(ref);              // empty if unknown, or simple name if no package
                String sn = ref.getSimpleName();      // never null for normal refs

                // Match if:
                // 1. Exact FQN match
                // 2. Simple name matches AND (qn is empty OR qn equals simple name OR qn equals fqn)
                boolean sameType = fqn.equals(qn) 
                        || (simple.equals(sn) && (qn.isEmpty() || qn.equals(simple) || qn.equals(fqn)));

                // If simple name matches but qn doesn't match fqn, check if type exists in model
                if (!sameType && simple.equals(sn)) {
                    try {
                        CtType<?> typeInModel = f.Type().get(fqn);
                        if (typeInModel != null) {
                            sameType = true;
                        }
                    } catch (Throwable ignored) {}
                }

                if (!sameType) continue;

                int n = 0;
                try { n = ref.getActualTypeArguments().size(); } catch (Throwable ignored) {}
                if (n > max) max = n;
            }
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

        // Only scan slice types for performance
        Collection<CtType<?>> sliceTypes = getSliceTypes(f.getModel());
        
        // scan locals in slice types only
        for (CtType<?> sliceType : sliceTypes) {
            for (CtLocalVariable<?> lv : sliceType.getElements((CtLocalVariable<?> v) -> true)) {
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
            for (CtField<?> fd : sliceType.getElements((CtField<?> v) -> true)) {
                CtTypeReference<?> t = fd.getType();
                if (t == null) continue;
                String qn = safeQN(t);
                if (!want.equals(qn) && !t.getSimpleName().equals(ownerRef.getSimpleName())) continue;

                CtExpression<?> init = fd.getDefaultExpression();
                if (init == null) continue;

                if (init instanceof spoon.reflect.code.CtExecutableReferenceExpression) return true;
                if (init instanceof spoon.reflect.code.CtLambda) return true;
            }
        }

        return false;
    }

    public void finalizeRepeatableAnnotations() {
        // package -> simpleName -> annotation type
        Map<String, Map<String, CtAnnotationType<?>>> byPkg = new LinkedHashMap<>();
        for (CtType<?> t : getSliceTypes(f.getModel())) {
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
        for (CtType<?> t : getSliceTypes(f.getModel())) {
            if (!(t instanceof CtAnnotationType)) continue;
            CtAnnotationType<?> at = (CtAnnotationType<?>) t;
            for (CtAnnotation<?> a : at.getAnnotations()) canonicalizeMetaAnnotationType(a);
        }
    }






}
