package de.upb.sse.jess.stubbing.spoon.shim;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.util.*;

/**
 * Generates minimal shim classes for common libraries that are often missing
 * from the classpath but are needed for compilation.
 * 
 * These shims provide basic stub implementations to allow compilation to succeed.
 */
public class ShimGenerator {
    
    private final Factory factory;
    private final Map<String, ShimDefinition> shimDefinitions;
    
    public ShimGenerator(Factory factory) {
        this.factory = factory;
        this.shimDefinitions = new HashMap<>();
        initializeCommonShims();
    }
    
    /**
     * Initialize predefined shims for common libraries.
     */
    private void initializeCommonShims() {
        // ANTLR shims
        addShim("org.antlr.v4.runtime", "Parser", createClassShim("org.antlr.v4.runtime.Parser"));
        addShim("org.antlr.v4.runtime", "Lexer", createClassShim("org.antlr.v4.runtime.Lexer"));
        addShim("org.antlr.v4.runtime", "Token", createInterfaceShim("org.antlr.v4.runtime.Token"));
        addShim("org.antlr.v4.runtime", "ParserRuleContext", createClassShim("org.antlr.v4.runtime.ParserRuleContext"));
        addShim("org.antlr.v4.runtime.tree", "ParseTree", createInterfaceShim("org.antlr.v4.runtime.tree.ParseTree"));
        addShim("org.antlr.v4.runtime.tree", "ParseTreeVisitor", createInterfaceShim("org.antlr.v4.runtime.tree.ParseTreeVisitor"));
        
        // SLF4J shims
        addShim("org.slf4j", "Logger", createInterfaceShim("org.slf4j.Logger",
            Arrays.asList("info", "debug", "warn", "error", "trace")));
        addShim("org.slf4j", "LoggerFactory", createClassShim("org.slf4j.LoggerFactory",
            Arrays.asList("getLogger")));
        addShim("org.slf4j", "MDC", createClassShim("org.slf4j.MDC",
            Arrays.asList("put", "get", "remove")));
        
        // Apache Commons Lang shims
        // Note: Don't add toString, equals, hashCode to classes - they already exist in Object
        addShim("org.apache.commons.lang3", "StringUtils", createClassShim("org.apache.commons.lang3.StringUtils",
            Arrays.asList("isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "trim")));
        addShim("org.apache.commons.lang3", "ObjectUtils", createClassShim("org.apache.commons.lang3.ObjectUtils",
            Arrays.asList("defaultIfNull")));
        addShim("org.apache.commons.lang3", "ArrayUtils", createClassShim("org.apache.commons.lang3.ArrayUtils",
            Arrays.asList("isEmpty", "isNotEmpty", "contains")));
        
        // ASM shims
        addShim("org.objectweb.asm", "ClassVisitor", createClassShim("org.objectweb.asm.ClassVisitor"));
        addShim("org.objectweb.asm", "MethodVisitor", createClassShim("org.objectweb.asm.MethodVisitor"));
        addShim("org.objectweb.asm", "FieldVisitor", createClassShim("org.objectweb.asm.FieldVisitor"));
        addShim("org.objectweb.asm", "AnnotationVisitor", createClassShim("org.objectweb.asm.AnnotationVisitor"));
        addShim("org.objectweb.asm", "Opcodes", createInterfaceShim("org.objectweb.asm.Opcodes"));
        
        // JUnit shims (commonly used in tests)
        addShim("org.junit", "Test", createAnnotationShim("org.junit.Test"));
        addShim("org.junit", "Before", createAnnotationShim("org.junit.Before"));
        addShim("org.junit", "After", createAnnotationShim("org.junit.After"));
        addShim("org.junit", "BeforeClass", createAnnotationShim("org.junit.BeforeClass"));
        addShim("org.junit", "AfterClass", createAnnotationShim("org.junit.AfterClass"));
        addShim("org.junit", "Assert", createClassShim("org.junit.Assert",
            Arrays.asList("assertEquals", "assertNotNull", "assertTrue", "assertFalse")));
        
        // JUnit Jupiter shims
        addShim("org.junit.jupiter.api", "Test", createAnnotationShim("org.junit.jupiter.api.Test"));
        addShim("org.junit.jupiter.api", "BeforeEach", createAnnotationShim("org.junit.jupiter.api.BeforeEach"));
        addShim("org.junit.jupiter.api", "AfterEach", createAnnotationShim("org.junit.jupiter.api.AfterEach"));
        addShim("org.junit.jupiter.api", "BeforeAll", createAnnotationShim("org.junit.jupiter.api.BeforeAll"));
        addShim("org.junit.jupiter.api", "AfterAll", createAnnotationShim("org.junit.jupiter.api.AfterAll"));
        addShim("org.junit.jupiter.api", "Assertions", createClassShim("org.junit.jupiter.api.Assertions",
            Arrays.asList("assertEquals", "assertNotNull", "assertTrue", "assertFalse")));
        
        // Mockito shims
        addShim("org.mockito", "Mock", createAnnotationShim("org.mockito.Mock"));
        addShim("org.mockito", "Mockito", createClassShim("org.mockito.Mockito",
            Arrays.asList("mock", "when", "verify", "verify", "any")));
        
        // Guava shims (common types)
        addShim("com.google.common.base", "Optional", createClassShim("com.google.common.base.Optional",
            Arrays.asList("of", "absent", "isPresent", "get", "or")));
        addShim("com.google.common.collect", "ImmutableList", createClassShim("com.google.common.collect.ImmutableList",
            Arrays.asList("of", "copyOf")));
        addShim("com.google.common.collect", "ImmutableSet", createClassShim("com.google.common.collect.ImmutableSet",
            Arrays.asList("of", "copyOf")));
        addShim("com.google.common.collect", "ImmutableMap", createClassShim("com.google.common.collect.ImmutableMap",
            Arrays.asList("of", "copyOf")));
        
        // gRPC shims
        addShim("io.grpc", "MethodDescriptor", createClassShim("io.grpc.MethodDescriptor"));
        addShim("io.grpc", "ServerCall", createClassShim("io.grpc.ServerCall",
            Arrays.asList("sendHeaders", "sendMessage", "close", "isReady", "setCompression")));
        addShim("io.grpc", "Status", createClassShim("io.grpc.Status",
            Arrays.asList("ok", "fromThrowable", "getCode", "withDescription", "withCause")));
        addShim("io.grpc.stub", "StreamObserver", createInterfaceShim("io.grpc.stub.StreamObserver",
            Arrays.asList("onNext", "onCompleted", "onError")));
        addShim("io.grpc", "Channel", createInterfaceShim("io.grpc.Channel",
            Arrays.asList("newCall", "authority")));
        addShim("io.grpc", "CallOptions", createClassShim("io.grpc.CallOptions",
            Arrays.asList("withDeadline", "withWaitForReady", "withCompression")));
        
        // Protocol Buffers shims
        addShim("com.google.protobuf", "GeneratedMessageLite", 
            createClassShim("com.google.protobuf.GeneratedMessageLite",
                Arrays.asList("parseFrom", "toByteArray", "getSerializedSize")));
        addShim("com.google.protobuf", "Message", 
            createInterfaceShim("com.google.protobuf.Message",
                Arrays.asList("toByteArray", "getSerializedSize", "parseFrom")));
        addShim("com.google.protobuf", "MessageLite", 
            createInterfaceShim("com.google.protobuf.MessageLite",
                Arrays.asList("toByteArray", "getSerializedSize")));
        addShim("com.google.protobuf", "Parser", 
            createInterfaceShim("com.google.protobuf.Parser",
                Arrays.asList("parseFrom", "parseDelimitedFrom")));
    }
    
    /**
     * Create a class shim with custom return types for specific methods.
     */
    private ShimDefinition createClassShimWithReturnTypes(String fqn, Map<String, String> methodReturnTypes) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.CLASS, new ArrayList<>(methodReturnTypes.keySet()));
    }
    
    /**
     * Add a shim definition.
     */
    private void addShim(String packageName, String className, ShimDefinition shim) {
        String fqn = packageName + "." + className;
        shimDefinitions.put(fqn, shim);
    }
    
    /**
     * Create a class shim.
     */
    private ShimDefinition createClassShim(String fqn) {
        return createClassShim(fqn, Collections.emptyList());
    }
    
    /**
     * Create a class shim with methods.
     */
    private ShimDefinition createClassShim(String fqn, List<String> methodNames) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.CLASS, methodNames);
    }
    
    /**
     * Create an interface shim.
     */
    private ShimDefinition createInterfaceShim(String fqn) {
        return createInterfaceShim(fqn, Collections.emptyList());
    }
    
    /**
     * Create an interface shim with methods.
     */
    private ShimDefinition createInterfaceShim(String fqn, List<String> methodNames) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.INTERFACE, methodNames);
    }
    
    /**
     * Create an annotation shim.
     */
    private ShimDefinition createAnnotationShim(String fqn) {
        return new ShimDefinition(fqn, ShimDefinition.Kind.ANNOTATION, Collections.emptyList());
    }
    
    /**
     * Generate shim classes for all registered definitions.
     * Only generates shims that are not already present in the model.
     * @deprecated Use generateShimsForReferencedTypes instead for better control
     */
    @Deprecated
    public int generateShims() {
        return generateShimsForReferencedTypes(Collections.emptySet());
    }
    
    /**
     * Generate shim classes only for types that are referenced and missing.
     * If referencedTypes is empty, generates all shims (backward compatibility).
     */
    public int generateShimsForReferencedTypes(Set<String> referencedTypes) {
        int generated = 0;
        boolean generateAll = referencedTypes == null || referencedTypes.isEmpty();
        
        for (Map.Entry<String, ShimDefinition> entry : shimDefinitions.entrySet()) {
            String fqn = entry.getKey();
            ShimDefinition shim = entry.getValue();
            
            // If we have a set of referenced types, only generate shims for those
            if (!generateAll && !referencedTypes.contains(fqn)) {
                // Also check by simple name (in case FQN differs)
                String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                boolean matchesSimple = referencedTypes.stream()
                    .anyMatch(ref -> ref.endsWith("." + simpleName) || ref.equals(simpleName));
                if (!matchesSimple) {
                    continue; // Not referenced, skip
                }
            }
            
            // Check if type already exists in the model
            CtType<?> existing = factory.Type().get(fqn);
            if (existing != null) {
                continue; // Already exists, skip
            }
            
            // Generate the shim
            if (generateShim(shim)) {
                generated++;
            }
        }
        
        // Generate *Grpc classes for any referenced *Grpc types
        if (!generateAll && referencedTypes != null) {
            for (String refType : referencedTypes) {
                if (refType.endsWith("Grpc") && !refType.contains("StreamObserver")) {
                    // This is a *Grpc class reference
                    if (factory.Type().get(refType) == null) {
                        if (generateGrpcShim(refType)) {
                            generated++;
                        }
                    }
                }
            }
        }
        
        return generated;
    }
    
    /**
     * Generate a minimal *Grpc class shim.
     */
    private boolean generateGrpcShim(String grpcClassName) {
        try {
            int lastDot = grpcClassName.lastIndexOf('.');
            String packageName = lastDot >= 0 ? grpcClassName.substring(0, lastDot) : "";
            String className = lastDot >= 0 ? grpcClassName.substring(lastDot + 1) : grpcClassName;
            
            // Extract service name (e.g., MetadataGrpc -> Metadata)
            String serviceName = className.replace("Grpc", "");
            
            CtPackage pkg = factory.Package().getOrCreate(packageName);
            CtClass<?> grpcClass = factory.Class().create(pkg, className);
            grpcClass.addModifier(ModifierKind.PUBLIC);
            grpcClass.addModifier(ModifierKind.FINAL);
            
            // Add newStub method: public static ServiceNameStub newStub(Channel channel)
            String stubQn = packageName.isEmpty() ? (serviceName + "Stub") : (packageName + "." + serviceName + "Stub");
            CtTypeReference<?> stubType = factory.Type().createReference(stubQn);
            CtTypeReference<?> channelType = factory.Type().createReference("io.grpc.Channel");
            
            // Create method with simple body (return null for now - will be fixed by stubbing)
            CtMethod<?> newStubMethod = factory.Method().create(
                grpcClass,
                Set.of(ModifierKind.PUBLIC, ModifierKind.STATIC),
                stubType,
                "newStub",
                makeShimParams(Arrays.asList(channelType)),
                Collections.emptySet()
            );
            
            // Add simple body: return null; (will be properly stubbed later if needed)
            CtBlock<?> body = factory.Core().createBlock();
            CtReturn<?> ret = factory.Core().createReturn();
            ret.setReturnedExpression(factory.Code().createLiteral(null));
            body.addStatement(ret);
            newStubMethod.setBody(body);
            
            // Also create the Stub class if it doesn't exist
            if (factory.Type().get(stubQn) == null) {
                CtClass<?> stubClass = factory.Class().create(pkg, serviceName + "Stub");
                stubClass.addModifier(ModifierKind.PUBLIC);
                stubClass.addModifier(ModifierKind.STATIC);
                // Add constructor
                CtConstructor<?> ctor = factory.Constructor().create(
                    stubClass,
                    Set.of(ModifierKind.PUBLIC),
                    makeShimParams(Arrays.asList(channelType)),
                    Collections.emptySet()
                );
                ctor.setBody(factory.Core().createBlock());
            }
            
            return true;
        } catch (Exception e) {
            System.err.println("Failed to generate gRPC shim for " + grpcClassName + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Create parameters for shim methods.
     */
    private List<CtParameter<?>> makeShimParams(List<CtTypeReference<?>> paramTypes) {
        List<CtParameter<?>> params = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            CtParameter<?> param = factory.Core().createParameter();
            param.setType(paramTypes.get(i));
            param.setSimpleName("arg" + i);
            params.add(param);
        }
        return params;
    }
    
    /**
     * Generate a single shim class.
     */
    private boolean generateShim(ShimDefinition shim) {
        try {
            String fqn = shim.getFqn();
            int lastDot = fqn.lastIndexOf('.');
            String packageName = lastDot >= 0 ? fqn.substring(0, lastDot) : "";
            String className = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
            
            CtPackage pkg = factory.Package().getOrCreate(packageName);
            
            CtType<?> type;
            switch (shim.getKind()) {
                case CLASS:
                    type = factory.Class().create(pkg, className);
                    break;
                case INTERFACE:
                    type = factory.Interface().create(pkg, className);
                    break;
                case ANNOTATION:
                    type = factory.Annotation().create(pkg, className);
                    break;
                default:
                    return false;
            }
            
            type.addModifier(ModifierKind.PUBLIC);
            
            // Special handling for GeneratedMessageLite - make it generic
            if ("com.google.protobuf.GeneratedMessageLite".equals(shim.getFqn()) && type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Add type parameters <T, M>
                CtTypeParameter typeParam1 = factory.Core().createTypeParameter();
                typeParam1.setSimpleName("T");
                cls.addFormalCtTypeParameter(typeParam1);
                
                CtTypeParameter typeParam2 = factory.Core().createTypeParameter();
                typeParam2.setSimpleName("M");
                cls.addFormalCtTypeParameter(typeParam2);
            }
            
            // Add methods if specified
            if (!shim.getMethodNames().isEmpty()) {
                for (String methodName : shim.getMethodNames()) {
                    addShimMethod(type, methodName);
                }
            }
            
            return true;
        } catch (Exception e) {
            System.err.println("Failed to generate shim for " + shim.getFqn() + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Add a simple method to a shim type.
     */
    private void addShimMethod(CtType<?> type, String methodName) {
        try {
            // Check if method already exists in this type or parent classes
            if (type instanceof CtClass) {
                CtClass<?> cls = (CtClass<?>) type;
                // Check in current class
                if (cls.getMethod(methodName) != null) {
                    return; // Method already exists, skip
                }
                // Check in parent class (Object has toString, equals, hashCode)
                CtTypeReference<?> superclass = cls.getSuperclass();
                if (superclass != null) {
                    CtType<?> superType = superclass.getTypeDeclaration();
                    if (superType instanceof CtClass) {
                        CtClass<?> superClass = (CtClass<?>) superType;
                        if (superClass.getMethod(methodName) != null) {
                            // Method exists in parent - only add if we need to override with different signature
                            // For toString, equals, hashCode - we should NOT add them as they already exist
                            if ("toString".equals(methodName) || "equals".equals(methodName) || "hashCode".equals(methodName)) {
                                return; // Skip - already in Object
                            }
                        }
                    }
                }
            } else if (type instanceof CtInterface) {
                CtInterface<?> iface = (CtInterface<?>) type;
                if (iface.getMethod(methodName) != null) {
                    return; // Method already exists, skip
                }
            }
            
            // Determine return type based on method name
            CtTypeReference<?> returnType = inferReturnType(methodName, type);
            
            // Special handling for Protocol Buffers methods
            if (type.getQualifiedName() != null && type.getQualifiedName().contains("protobuf")) {
                if ("toByteArray".equals(methodName)) {
                    returnType = factory.Type().createArrayReference(factory.Type().BYTE_PRIMITIVE);
                }
            }
            
            // Determine parameter types based on method name
            List<CtTypeReference<?>> paramTypes = inferParameterTypes(methodName);
            
            Set<ModifierKind> mods = new HashSet<>();
            mods.add(ModifierKind.PUBLIC);
            
            // Check if this is a utility class (like StringUtils, ObjectUtils) - methods should be static
            boolean isUtilityClass = isUtilityClass(type);
            if (isUtilityClass && type instanceof CtClass) {
                mods.add(ModifierKind.STATIC);
            }
            
            if (type instanceof CtInterface && !(type instanceof CtAnnotationType)) {
                mods.add(ModifierKind.ABSTRACT);
            }
            
            // Create parameters
            List<CtParameter<?>> params = new ArrayList<>();
            for (int i = 0; i < paramTypes.size(); i++) {
                CtParameter<?> param = factory.Core().createParameter();
                param.setType(paramTypes.get(i));
                param.setSimpleName("arg" + i);
                params.add(param);
            }
            
            // Create method without body first (for interfaces) or with empty body (for classes)
            CtMethod<?> method = factory.Method().create(
                type,
                mods,
                returnType,
                methodName,
                params,
                Collections.emptySet()
            );
            
            // For classes, add a default body with return statement
            if (type instanceof CtClass) {
                CtBlock<?> body = factory.Core().createBlock();
                if (!returnType.equals(factory.Type().VOID_PRIMITIVE)) {
                    CtReturn<?> ret = factory.Core().createReturn();
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    CtExpression defaultValue = (CtExpression) createDefaultValueForType(returnType);
                    ret.setReturnedExpression(defaultValue);
                    body.addStatement(ret);
                }
                method.setBody(body);
            }
        } catch (Exception e) {
            // Ignore method creation failures
        }
    }
    
    /**
     * Check if a type is a utility class (like StringUtils, ObjectUtils).
     * Utility classes typically have static methods.
     */
    private boolean isUtilityClass(CtType<?> type) {
        if (type == null) return false;
        String fqn = type.getQualifiedName();
        if (fqn == null) return false;
        // Common utility class patterns
        return fqn.contains("Utils") || fqn.contains("Helper") || fqn.contains("Util") ||
               fqn.equals("org.apache.commons.lang3.StringUtils") ||
               fqn.equals("org.apache.commons.lang3.ObjectUtils");
    }
    
    /**
     * Infer return type based on method name.
     */
    private CtTypeReference<?> inferReturnType(String methodName, CtType<?> ownerType) {
        // Special cases for common methods
        if ("toString".equals(methodName)) {
            return factory.Type().createReference("java.lang.String");
        }
        if ("equals".equals(methodName)) {
            return factory.Type().BOOLEAN_PRIMITIVE;
        }
        if ("hashCode".equals(methodName)) {
            return factory.Type().INTEGER_PRIMITIVE;
        }
        if ("isPresent".equals(methodName) || "isEmpty".equals(methodName) || 
            "isNotEmpty".equals(methodName) || "isBlank".equals(methodName) || 
            "isNotBlank".equals(methodName) || "isParallel".equals(methodName) ||
            "assertTrue".equals(methodName) || "assertFalse".equals(methodName)) {
            return factory.Type().BOOLEAN_PRIMITIVE;
        }
        if ("getLogger".equals(methodName)) {
            return factory.Type().createReference("org.slf4j.Logger");
        }
        if ("info".equals(methodName) || "debug".equals(methodName) || 
            "warn".equals(methodName) || "error".equals(methodName) || 
            "trace".equals(methodName)) {
            // SLF4J Logger methods return void
            return factory.Type().VOID_PRIMITIVE;
        }
        if ("trim".equals(methodName)) {
            // trim() should return String
            return factory.Type().createReference("java.lang.String");
        }
        if ("get".equals(methodName) && methodName.length() == 3) {
            // Generic get() method - return Object for shims
            return factory.Type().createReference("java.lang.Object");
        }
        // Default: return Object
        return factory.Type().createReference("java.lang.Object");
    }
    
    /**
     * Infer parameter types based on method name.
     */
    private List<CtTypeReference<?>> inferParameterTypes(String methodName) {
        List<CtTypeReference<?>> params = new ArrayList<>();
        
        // Special cases for common methods
        if ("equals".equals(methodName)) {
            params.add(factory.Type().createReference("java.lang.Object"));
        } else if ("getLogger".equals(methodName)) {
            params.add(factory.Type().createReference("java.lang.String"));
        } else if ("info".equals(methodName) || "debug".equals(methodName) || 
                   "warn".equals(methodName) || "error".equals(methodName) || 
                   "trace".equals(methodName)) {
            // SLF4J Logger methods - take String message
            params.add(factory.Type().createReference("java.lang.String"));
        } else if ("put".equals(methodName) || "get".equals(methodName) || "remove".equals(methodName)) {
            // MDC methods
            params.add(factory.Type().createReference("java.lang.String"));
        } else if ("trim".equals(methodName) || "isEmpty".equals(methodName) || 
                   "isNotEmpty".equals(methodName) || "isBlank".equals(methodName) || 
                   "isNotBlank".equals(methodName)) {
            // StringUtils methods - might take String parameter
            if ("trim".equals(methodName)) {
                params.add(factory.Type().createReference("java.lang.String"));
            }
        } else if ("assertEquals".equals(methodName) || "assertNotNull".equals(methodName) ||
                   "assertTrue".equals(methodName) || "assertFalse".equals(methodName)) {
            // Assert methods - take Object parameters
            params.add(factory.Type().createReference("java.lang.Object"));
            if ("assertEquals".equals(methodName)) {
                params.add(factory.Type().createReference("java.lang.Object"));
            }
        } else if ("of".equals(methodName) || "copyOf".equals(methodName)) {
            // Immutable collection factory methods - take varargs
            // We'll create one parameter for simplicity (Object...)
            CtTypeReference<?> objectRef = factory.Type().createReference("java.lang.Object");
            params.add(factory.Type().createArrayReference(objectRef));
        } else if ("mock".equals(methodName) || "when".equals(methodName) || "any".equals(methodName)) {
            // Mockito methods
            params.add(factory.Type().createReference("java.lang.Class"));
        } else if ("verify".equals(methodName)) {
            // Mockito verify
            params.add(factory.Type().createReference("java.lang.Object"));
        }
        
        return params;
    }
    
    /**
     * Create a default value expression for a type.
     */
    private CtExpression<?> createDefaultValueForType(CtTypeReference<?> type) {
        if (type == null) return factory.Code().createLiteral(null);
        
        try {
            if (type.isPrimitive()) {
                String typeName = type.getSimpleName();
                if ("boolean".equals(typeName)) {
                    return factory.Code().createLiteral(false);
                } else if ("int".equals(typeName)) {
                    return factory.Code().createLiteral(0);
                } else if ("long".equals(typeName)) {
                    return factory.Code().createLiteral(0L);
                } else if ("short".equals(typeName)) {
                    return factory.Code().createLiteral((short) 0);
                } else if ("byte".equals(typeName)) {
                    return factory.Code().createLiteral((byte) 0);
                } else if ("char".equals(typeName)) {
                    return factory.Code().createLiteral('\0');
                } else if ("float".equals(typeName)) {
                    return factory.Code().createLiteral(0.0f);
                } else if ("double".equals(typeName)) {
                    return factory.Code().createLiteral(0.0);
                }
            } else if (type.getQualifiedName() != null && type.getQualifiedName().equals("java.lang.String")) {
                return factory.Code().createLiteral("");
            }
        } catch (Throwable ignored) {}
        
        return factory.Code().createLiteral(null);
    }
    
    /**
     * Check if a shim exists for the given FQN.
     */
    public boolean hasShim(String fqn) {
        return shimDefinitions.containsKey(fqn);
    }
    
    /**
     * Get all shim FQNs.
     */
    public Set<String> getAllShimFqns() {
        return new HashSet<>(shimDefinitions.keySet());
    }
}

