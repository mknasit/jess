package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.cu.SourcePosition;

import java.nio.file.Path;
import java.util.*;

/**
 * Enhanced collector that works like JavaParser stubber but leverages Spoon's better resolution.
 * - Only stubs truly unresolvable types (checks model + classpath)
 * - Infers types from context using Spoon's type inference
 * - Tracks proper package names and imports
 */
public final class MinimalSpoonCollector {
    private final Factory f;
    private final JessConfiguration cfg;
    private final CtModel model;

    public MinimalSpoonCollector(Factory f, JessConfiguration cfg) {
        this.f = f;
        this.cfg = cfg;
        this.model = f.getModel();
    }

    /**
     * Result of collection - missing types and members with inferred information.
     */
    public static class Result {
        /** Map of simple name -> inferred package name (null = default package) */
        public final Map<String, String> missingTypes = new HashMap<>();
        
        /** Missing members by owner FQN */
        public final Map<String, Set<MissingMember>> missingMembersByOwner = new HashMap<>();
    }

    /**
     * Represents a missing member (method or field) with inferred types.
     */
    public static class MissingMember {
        public final String ownerFqn;
        public final String simpleName;
        public final Kind kind;
        public final int paramCount;
        public final CtTypeReference<?> returnType; // For methods
        public final List<CtTypeReference<?>> paramTypes; // For methods
        public final CtTypeReference<?> fieldType; // For fields

        public enum Kind { METHOD, FIELD }

        public MissingMember(String ownerFqn, String simpleName, Kind kind, int paramCount,
                            CtTypeReference<?> returnType, List<CtTypeReference<?>> paramTypes,
                            CtTypeReference<?> fieldType) {
            this.ownerFqn = ownerFqn;
            this.simpleName = simpleName;
            this.kind = kind;
            this.paramCount = paramCount;
            this.returnType = returnType;
            this.paramTypes = paramTypes != null ? paramTypes : Collections.emptyList();
            this.fieldType = fieldType;
        }
    }

    /**
     * Collect missing types and members from the model.
     */
    public Result collect(CtModel model, Path slicedSrcDir) {
        Result result = new Result();

        // 1) Determine slice types (types whose source file is in slicedSrcDir)
        Set<CtType<?>> sliceTypes = new HashSet<>();
        Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
        String sliceRootStr = sliceRoot.toString();
        String sliceName = sliceRoot.getFileName() != null ? sliceRoot.getFileName().toString() : "gen";
        
        System.out.println("[MinimalSpoonCollector] Looking for slice types in: " + sliceRoot);
        System.out.println("[MinimalSpoonCollector] Slice root string: " + sliceRootStr);
        System.out.println("[MinimalSpoonCollector] Slice name: " + sliceName);
        
        int checkedTypes = 0;
        int typesWithPosition = 0;
        int samplePaths = 0;
        try {
            // Use safe wrapper like SpoonStubbingRunner to handle StackOverflowError
            Collection<CtType<?>> allTypes = safeGetAllTypes(model);
            if (allTypes == null || allTypes.isEmpty()) {
                System.err.println("[MinimalSpoonCollector] WARNING: model.getAllTypes() returned empty collection - model may not be fully built");
                // Try to get types from factory instead
                try {
                    allTypes = f.getModel().getAllTypes();
                    System.out.println("[MinimalSpoonCollector] Retried with factory.getModel().getAllTypes(): " + 
                        (allTypes != null ? allTypes.size() : 0) + " types");
                } catch (Throwable e2) {
                    System.err.println("[MinimalSpoonCollector] Error getting types from factory: " + e2.getMessage());
                    allTypes = Collections.emptyList();
                }
            }
            for (CtType<?> t : allTypes) {
                checkedTypes++;
                try {
                    SourcePosition pos = t.getPosition();
                    if (pos != null && pos.getFile() != null) {
                        typesWithPosition++;
                        Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                        String filePathStr = filePath.toString();
                        
                        // Try multiple matching strategies
                        boolean isSliceType = false;
                        if (filePath.startsWith(sliceRoot)) {
                            isSliceType = true;
                        } else if (filePathStr.contains("/" + sliceName + "/") || 
                                   filePathStr.contains("\\" + sliceName + "\\")) {
                            // Also check if path contains the slice directory name
                            isSliceType = true;
                        }
                        
                        if (isSliceType) {
                            sliceTypes.add(t);
                            if (sliceTypes.size() <= 3) {
                                System.out.println("[MinimalSpoonCollector] Found slice type: " + 
                                    t.getQualifiedName() + " at " + filePath);
                            }
                        } else if (samplePaths < 5) {
                            // Show sample paths for debugging
                            System.out.println("[MinimalSpoonCollector] Sample non-slice path: " + filePathStr);
                            samplePaths++;
                        }
                    }
                } catch (Throwable e) {
                    // Log first few errors for debugging
                    if (checkedTypes <= 5) {
                        System.err.println("[MinimalSpoonCollector] Error checking type " + 
                            (t.getQualifiedName() != null ? t.getQualifiedName() : "unknown") + ": " + e.getMessage());
                    }
                }
            }
        } catch (Throwable e) {
            System.err.println("[MinimalSpoonCollector] Error getting all types: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[MinimalSpoonCollector] Checked " + checkedTypes + " types, " + 
            typesWithPosition + " with positions, found " + sliceTypes.size() + " slice types");
        
        if (sliceTypes.isEmpty() && checkedTypes == 0) {
            System.err.println("[MinimalSpoonCollector] CRITICAL: No types found in model - model may not be built correctly");
        }

        // 2) For each slice type, scan for missing types and members
        for (CtType<?> sliceType : sliceTypes) {
            try {
                collectFromType(sliceType, result, sliceTypes);
            } catch (Throwable e) {
                System.err.println("[MinimalSpoonCollector] Error collecting from type " + 
                    (sliceType.getQualifiedName() != null ? sliceType.getQualifiedName() : "unknown") + 
                    ": " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Collect missing types and members from a single type.
     */
    private void collectFromType(CtType<?> type, Result result, Set<CtType<?>> sliceTypes) {
        String ownerFqn = type.getQualifiedName();
        if (ownerFqn == null) return;

        String currentPackage = type.getPackage() != null ? type.getPackage().getQualifiedName() : "";

        // Collect from fields
        for (CtField<?> field : type.getFields()) {
            collectTypeReference(field.getType(), result, currentPackage);
        }

        // Collect from methods
        for (CtMethod<?> method : type.getMethods()) {
            // Return type
            collectTypeReference(method.getType(), result, currentPackage);
            
            // Parameter types
            for (CtParameter<?> param : method.getParameters()) {
                collectTypeReference(param.getType(), result, currentPackage);
            }
            
            // Thrown types
            for (CtTypeReference<?> thrown : method.getThrownTypes()) {
                collectTypeReference(thrown, result, currentPackage);
            }
            
            // Method body - look for unresolved method calls and field accesses
            try {
                collectFromMethodBody(method, ownerFqn, result, currentPackage);
            } catch (Throwable ignored) {}
        }

        // Collect from constructors
        if (type instanceof CtClass) {
            CtClass<?> clazz = (CtClass<?>) type;
            for (CtConstructor<?> ctor : clazz.getConstructors()) {
                for (CtParameter<?> param : ctor.getParameters()) {
                    collectTypeReference(param.getType(), result, currentPackage);
                }
                for (CtTypeReference<?> thrown : ctor.getThrownTypes()) {
                    collectTypeReference(thrown, result, currentPackage);
                }
            }
        }

        // Collect from superclass and interfaces
        if (type instanceof CtClass) {
            CtClass<?> clazz = (CtClass<?>) type;
            try {
                CtTypeReference<?> superclass = clazz.getSuperclass();
                if (superclass != null) {
                    collectTypeReference(superclass, result, currentPackage);
                }
            } catch (Throwable ignored) {}
            for (CtTypeReference<?> iface : clazz.getSuperInterfaces()) {
                collectTypeReference(iface, result, currentPackage);
            }
        } else if (type instanceof CtInterface) {
            CtInterface<?> iface = (CtInterface<?>) type;
            for (CtTypeReference<?> superIface : iface.getSuperInterfaces()) {
                collectTypeReference(superIface, result, currentPackage);
            }
        }
    }

    /**
     * Collect from method body - find unresolved method calls and field accesses with type inference.
     */
    private void collectFromMethodBody(CtMethod<?> method, String ownerFqn, Result result, String currentPackage) {
        if (method.getBody() == null) return;

        // Find unresolved method invocations
        List<CtInvocation<?>> invocations = method.getBody().getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> inv : invocations) {
            try {
                CtExecutableReference<?> exec = inv.getExecutable();
                if (exec != null && exec.getDeclaration() == null) {
                    // Unresolved method call - use Spoon's type inference
                    String methodName = exec.getSimpleName();
                    if (methodName != null && !methodName.equals("<init>")) {
                        // Infer return type from context
                        CtTypeReference<?> returnType = inferReturnType(inv);
                        
                        // Infer parameter types from arguments
                        List<CtTypeReference<?>> paramTypes = new ArrayList<>();
                        if (inv.getArguments() != null) {
                            for (CtExpression<?> arg : inv.getArguments()) {
                                CtTypeReference<?> argType = inferExpressionType(arg);
                                paramTypes.add(argType != null ? argType : f.Type().OBJECT);
                            }
                        }
                        
                        int paramCount = paramTypes.size();
                        result.missingMembersByOwner
                            .computeIfAbsent(ownerFqn, k -> new HashSet<>())
                            .add(new MissingMember(ownerFqn, methodName, MissingMember.Kind.METHOD, 
                                paramCount, returnType, paramTypes, null));
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Find unresolved field accesses
        List<CtFieldAccess<?>> fieldAccesses = method.getBody().getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtFieldAccess.class));
        for (CtFieldAccess<?> fa : fieldAccesses) {
            try {
                if (fa.getVariable() != null && fa.getVariable().getDeclaration() == null) {
                    // Unresolved field access - infer type from context
                    String fieldName = fa.getVariable().getSimpleName();
                    if (fieldName != null) {
                        CtTypeReference<?> fieldType = inferFieldType(fa);
                        result.missingMembersByOwner
                            .computeIfAbsent(ownerFqn, k -> new HashSet<>())
                            .add(new MissingMember(ownerFqn, fieldName, MissingMember.Kind.FIELD, 
                                0, null, null, fieldType));
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Infer return type from method invocation context.
     */
    private CtTypeReference<?> inferReturnType(CtInvocation<?> inv) {
        try {
            // Try to get type from Spoon's inference
            CtTypeReference<?> type = inv.getType();
            if (type != null && type.getTypeDeclaration() != null) {
                return type;
            }
            
            // Check parent context (assignment, return, etc.)
            CtElement parent = inv.getParent();
            if (parent instanceof CtAssignment) {
                CtAssignment<?, ?> assign = (CtAssignment<?, ?>) parent;
                if (assign.getAssignment() == inv) {
                    CtTypeReference<?> assignedType = assign.getAssigned().getType();
                    if (assignedType != null) return assignedType;
                }
            } else if (parent instanceof CtReturn) {
                CtMethod<?> method = parent.getParent(CtMethod.class);
                if (method != null) {
                    return method.getType();
                }
            } else if (parent instanceof CtVariable) {
                CtVariable<?> var = (CtVariable<?>) parent;
                return var.getType();
            }
        } catch (Throwable ignored) {}
        
        // Default to Object
        return f.Type().OBJECT;
    }

    /**
     * Infer type from expression context.
     */
    private CtTypeReference<?> inferExpressionType(CtExpression<?> expr) {
        try {
            CtTypeReference<?> type = expr.getType();
            if (type != null && type.getTypeDeclaration() != null) {
                return type;
            }
        } catch (Throwable ignored) {}
        return f.Type().OBJECT;
    }

    /**
     * Infer field type from field access context.
     */
    private CtTypeReference<?> inferFieldType(CtFieldAccess<?> fa) {
        try {
            CtTypeReference<?> type = fa.getType();
            if (type != null && type.getTypeDeclaration() != null) {
                return type;
            }
            
            // Check assignment context
            CtElement parent = fa.getParent();
            if (parent instanceof CtAssignment) {
                CtAssignment<?, ?> assign = (CtAssignment<?, ?>) parent;
                if (assign.getAssigned() == fa) {
                    CtTypeReference<?> assignedType = assign.getAssignment().getType();
                    if (assignedType != null) return assignedType;
                }
            }
        } catch (Throwable ignored) {}
        return f.Type().OBJECT;
    }

    /**
     * Collect a type reference - if it's unresolved, add to missing types with inferred package.
     */
    private void collectTypeReference(CtTypeReference<?> ref, Result result, String currentPackage) {
        if (ref == null) return;
        
        // Skip primitives
        try {
            if (ref.isPrimitive()) return;
        } catch (Throwable ignored) {}
        
        // CRITICAL: Use Spoon's resolution to check if type actually exists
        // This is better than JavaParser because Spoon has access to full model + classpath
        try {
            if (ref.getTypeDeclaration() != null) {
                // Type is resolved - don't stub
                return;
            }
            
            // Also check if type exists in model by simple name (Spoon's advantage)
            String simple = ref.getSimpleName();
            if (simple != null && !simple.isEmpty()) {
                // Try to find in model
                Collection<CtType<?>> allTypes = model.getAllTypes();
                for (CtType<?> t : allTypes) {
                    if (simple.equals(t.getSimpleName()) && t.getQualifiedName() != null) {
                        // Type exists - don't stub
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
        
        // Skip arrays - collect component type instead
        try {
            if (ref.isArray()) {
                if (ref instanceof CtArrayTypeReference) {
                    CtArrayTypeReference<?> arrayRef = (CtArrayTypeReference<?>) ref;
                    CtTypeReference<?> component = arrayRef.getComponentType();
                    if (component != null) {
                        collectTypeReference(component, result, currentPackage);
                    }
                }
                return;
            }
        } catch (Throwable ignored) {}
        
        // Get simple name
        String simple = ref.getSimpleName();
        if (simple == null || simple.isEmpty()) return;
        
        // Skip JDK types
        String qn = ref.getQualifiedName();
        if (qn != null && (qn.startsWith("java.") || qn.startsWith("javax.") || 
            qn.startsWith("jakarta.") || qn.startsWith("sun.") || qn.startsWith("jdk."))) {
            return;
        }
        
        // Infer package name from qualified name or use current package
        String inferredPackage = currentPackage;
        if (qn != null && qn.contains(".")) {
            int lastDot = qn.lastIndexOf('.');
            inferredPackage = qn.substring(0, lastDot);
        }
        
        // Add to missing types (only if not already there or with better package info)
        if (!result.missingTypes.containsKey(simple) || 
            (inferredPackage != null && !inferredPackage.isEmpty())) {
            result.missingTypes.put(simple, inferredPackage);
        }
    }
    
    /**
     * Safely get all types from model, handling StackOverflowError.
     * Same approach as SpoonStubbingRunner.
     */
    private Collection<CtType<?>> safeGetAllTypes(CtModel model) {
        try {
            return model.getAllTypes();
        } catch (StackOverflowError e) {
            System.err.println("[MinimalSpoonCollector] StackOverflowError getting all types - likely circular dependencies");
            System.err.println("[MinimalSpoonCollector] Returning empty collection - some stubs may be missing");
            return Collections.emptyList();
        } catch (Throwable e) {
            System.err.println("[MinimalSpoonCollector] Error getting all types: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}
