package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtReturn;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.declaration.CtCompilationUnit;

import java.nio.file.Path;
import java.util.*;

/**
 * Enhanced stubber that works like JavaParser but leverages Spoon's capabilities.
 * - Creates stubs with proper package names (inferred from context)
 * - Adds imports when needed
 * - Uses inferred types for methods/fields
 */
public final class MinimalSpoonStubber {
    private final Factory f;
    private final JessConfiguration cfg;

    public MinimalSpoonStubber(Factory f, JessConfiguration cfg) {
        this.f = f;
        this.cfg = cfg;
    }

    /**
     * Apply stubs based on collection result.
     * Only processes slice types (types from slicedSrcDir), not entire model.
     * @param result Collection result
     * @param slicedSrcDir Slice directory
     * @param sliceTypeFqns Set of FQNs for slice types (only these will be processed)
     * @return number of types created/updated
     */
    public int apply(MinimalSpoonCollector.Result result, Path slicedSrcDir, Set<String> sliceTypeFqns) {
        int createdTypes = 0;
        int createdMembers = 0;

        // 1) Create missing types with inferred package names
        for (Map.Entry<String, String> entry : result.missingTypes.entrySet()) {
            String simpleName = entry.getKey();
            String inferredPackage = entry.getValue();
            
            // If no package inferred, try to find from context or use default
            if (inferredPackage == null || inferredPackage.isEmpty()) {
                // Try to infer from model - look for similar types
                inferredPackage = inferPackageFromContext(simpleName);
            }
            
            try {
                if (createStubType(inferredPackage, simpleName)) {
                    createdTypes++;
                    System.out.println("[MinimalSpoonStubber] Created stub type: " + 
                        (inferredPackage.isEmpty() ? simpleName : inferredPackage + "." + simpleName));
                }
            } catch (Throwable e) {
                System.err.println("[MinimalSpoonStubber] Error creating stub type " + 
                    (inferredPackage.isEmpty() ? simpleName : inferredPackage + "." + simpleName) + 
                    ": " + e.getMessage());
            }
        }

        // 2) Create missing members on slice owners with inferred types
        // CRITICAL: Only process owners that are slice types (from slicedSrcDir)
        for (Map.Entry<String, Set<MinimalSpoonCollector.MissingMember>> entry :
             result.missingMembersByOwner.entrySet()) {
            String ownerFqn = entry.getKey();
            
            // Only process if this owner is a slice type
            if (!sliceTypeFqns.contains(ownerFqn)) {
                System.out.println("[MinimalSpoonStubber] Skipping non-slice owner: " + ownerFqn);
                continue;
            }
            
            Set<MinimalSpoonCollector.MissingMember> members = entry.getValue();

            try {
                CtType<?> owner = f.Type().get(ownerFqn);
                if (owner == null) {
                    System.err.println("[MinimalSpoonStubber] Owner type not found: " + ownerFqn);
                    continue;
                }

                for (MinimalSpoonCollector.MissingMember member : members) {
                    try {
                        if (member.kind == MinimalSpoonCollector.MissingMember.Kind.METHOD) {
                            if (createStubMethod(owner, member.simpleName, member.paramTypes, member.returnType)) {
                                createdMembers++;
                            }
                        } else if (member.kind == MinimalSpoonCollector.MissingMember.Kind.FIELD) {
                            if (createStubField(owner, member.simpleName, member.fieldType)) {
                                createdMembers++;
                            }
                        }
                    } catch (Throwable e) {
                        System.err.println("[MinimalSpoonStubber] Error creating member " + 
                            member.simpleName + " on " + ownerFqn + ": " + e.getMessage());
                    }
                }
                
                // Add imports for the owner type's compilation unit
                addImportsForType(owner, result);
            } catch (Throwable e) {
                System.err.println("[MinimalSpoonStubber] Error processing owner " + ownerFqn + ": " + e.getMessage());
            }
        }

        System.out.println("[MinimalSpoonStubber] Created/updated " + createdTypes + " stub types and " + createdMembers + " stub members");
        return createdTypes + createdMembers;
    }

    /**
     * Infer package name from context by looking for similar types in model.
     */
    private String inferPackageFromContext(String simpleName) {
        try {
            Collection<CtType<?>> allTypes = f.getModel().getAllTypes();
            for (CtType<?> t : allTypes) {
                if (simpleName.equals(t.getSimpleName()) && t.getPackage() != null) {
                    String pkg = t.getPackage().getQualifiedName();
                    if (pkg != null && !pkg.isEmpty()) {
                        return pkg;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return ""; // Default package
    }

    /**
     * Create a stub type in the given package.
     */
    private boolean createStubType(String pkg, String simpleName) {
        String fqn = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        
        // Check if type already exists
        CtType<?> existing = f.Type().get(fqn);
        if (existing != null) {
            return false;
        }

        // Create package if needed
        CtPackage pkgObj = f.Package().getOrCreate(pkg);

        // Create stub class
        CtClass<?> stub = f.Class().create(pkgObj, simpleName);
        stub.setModifiers(java.util.EnumSet.of(ModifierKind.PUBLIC));
        
        return true;
    }

    /**
     * Create a stub method with inferred types.
     */
    private boolean createStubMethod(CtType<?> owner, String methodName, 
                                    List<CtTypeReference<?>> paramTypes, 
                                    CtTypeReference<?> returnType) {
        // Check if method already exists
        int paramCount = paramTypes != null ? paramTypes.size() : 0;
        if (owner instanceof CtClass) {
            CtClass<?> clazz = (CtClass<?>) owner;
            for (CtMethod<?> m : clazz.getMethods()) {
                if (methodName.equals(m.getSimpleName()) && 
                    m.getParameters().size() == paramCount) {
                    return false;
                }
            }
        } else if (owner instanceof CtInterface) {
            CtInterface<?> iface = (CtInterface<?>) owner;
            for (CtMethod<?> m : iface.getMethods()) {
                if (methodName.equals(m.getSimpleName()) && 
                    m.getParameters().size() == paramCount) {
                    return false;
                }
            }
        }

        // Create parameters with inferred types
        List<CtParameter<?>> params = new ArrayList<>();
        if (paramTypes != null) {
            for (int i = 0; i < paramTypes.size(); i++) {
                CtTypeReference<?> paramType = paramTypes.get(i);
                if (paramType == null) paramType = f.Type().OBJECT;
                
                CtParameter<Object> param = f.Core().createParameter();
                param.setType(paramType);
                param.setSimpleName("arg" + i);
                params.add(param);
            }
        }

        // Use inferred return type or default to Object
        CtTypeReference<?> methodReturnType = returnType != null ? returnType : f.Type().OBJECT;

        // Create method (use wildcard to avoid type inference issues)
        @SuppressWarnings("unchecked")
        CtMethod<?> method = f.Method().create(
            owner,
            java.util.EnumSet.of(ModifierKind.PUBLIC),
            methodReturnType,
            methodName,
            params,
            Collections.emptySet()
        );

        // Add body
        CtBlock<?> body = f.Core().createBlock();
        if (!f.Type().VOID_PRIMITIVE.equals(methodReturnType) && 
            !methodReturnType.equals(f.Type().createReference("void"))) {
            CtReturn<?> returnStmt = f.Core().createReturn();
            if (methodReturnType.isPrimitive()) {
                // Return default value for primitives
                if (methodReturnType.equals(f.Type().BOOLEAN_PRIMITIVE)) {
                    returnStmt.setReturnedExpression(f.createCodeSnippetExpression("false"));
                } else if (methodReturnType.equals(f.Type().INTEGER_PRIMITIVE) || 
                          methodReturnType.equals(f.Type().LONG_PRIMITIVE) ||
                          methodReturnType.equals(f.Type().SHORT_PRIMITIVE) ||
                          methodReturnType.equals(f.Type().BYTE_PRIMITIVE)) {
                    returnStmt.setReturnedExpression(f.createCodeSnippetExpression("0"));
                } else if (methodReturnType.equals(f.Type().DOUBLE_PRIMITIVE) ||
                          methodReturnType.equals(f.Type().FLOAT_PRIMITIVE)) {
                    returnStmt.setReturnedExpression(f.createCodeSnippetExpression("0.0"));
                } else if (methodReturnType.equals(f.Type().CHARACTER_PRIMITIVE)) {
                    returnStmt.setReturnedExpression(f.createCodeSnippetExpression("'\\0'"));
                } else {
                    returnStmt.setReturnedExpression(f.createCodeSnippetExpression("null"));
                }
            } else {
                returnStmt.setReturnedExpression(f.createCodeSnippetExpression("null"));
            }
            body.addStatement(returnStmt);
        }
        method.setBody(body);

        return true;
    }

    /**
     * Create a stub field with inferred type.
     */
    private boolean createStubField(CtType<?> owner, String fieldName, CtTypeReference<?> fieldType) {
        // Check if field already exists
        for (CtField<?> fld : owner.getFields()) {
            if (fieldName.equals(fld.getSimpleName())) {
                return false;
            }
        }

        // Use inferred type or default to Object
        CtTypeReference<?> actualType = fieldType != null ? fieldType : f.Type().OBJECT;

        // Create field (use wildcard to avoid type inference issues)
        @SuppressWarnings("unchecked")
        CtField<?> field = f.Field().create(
            owner,
            java.util.EnumSet.of(ModifierKind.PUBLIC),
            actualType,
            fieldName,
            null
        );

        return true;
    }

    /**
     * Add imports for stub types used in the given type.
     */
    private void addImportsForType(CtType<?> type, MinimalSpoonCollector.Result result) {
        try {
            CtCompilationUnit cu = f.CompilationUnit().getOrCreate(type);
            if (cu == null) return;

            // Add imports for all missing types that are in different packages
            String typePackage = type.getPackage() != null ? type.getPackage().getQualifiedName() : "";
            
            for (Map.Entry<String, String> entry : result.missingTypes.entrySet()) {
                String simpleName = entry.getKey();
                String stubPackage = entry.getValue();
                
                // Only add import if package is different and not empty
                if (stubPackage != null && !stubPackage.isEmpty() && !stubPackage.equals(typePackage)) {
                    String fqn = stubPackage + "." + simpleName;
                    
                    // Check if import already exists
                    boolean exists = false;
                    for (CtImport imp : cu.getImports()) {
                        if (imp.getImportKind() == spoon.reflect.declaration.CtImportKind.TYPE) {
                            try {
                                if (fqn.equals(imp.getReference().toString())) {
                                    exists = true;
                                    break;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    
                    if (!exists) {
                        try {
                            CtTypeReference<?> ref = f.Type().createReference(fqn);
                            if (ref != null) {
                                CtImport imp = f.createImport(ref);
                                cu.getImports().add(imp);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Get slice types from model (types whose source file is in slicedSrcDir).
     */
    private Set<CtType<?>> getSliceTypes(CtModel model, Path slicedSrcDir) {
        Set<CtType<?>> sliceTypes = new HashSet<>();
        Path sliceRoot = slicedSrcDir.toAbsolutePath().normalize();
        
        try {
            Collection<CtType<?>> allTypes = model.getAllTypes();
            for (CtType<?> t : allTypes) {
                try {
                    spoon.reflect.cu.SourcePosition pos = t.getPosition();
                    if (pos != null && pos.getFile() != null) {
                        Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                        if (filePath.startsWith(sliceRoot)) {
                            sliceTypes.add(t);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            System.err.println("[MinimalSpoonStubber] Error getting slice types: " + e.getMessage());
        }
        
        return sliceTypes;
    }
}
