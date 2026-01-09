package de.upb.sse.jess.stubbing.spoon.context;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lightweight ContextIndex implementation that scans source roots using JavaParser.
 * 
 * Indexes:
 * - Package + top-level type name (FQN) + type kind
 * - Extends clause (super FQN if resolvable)
 * - Implements clause (interface FQNs)
 * - Declared method names + parameter count + parameter simple names
 * - Declared field names
 * 
 * Best-effort: if parsing fails, skips the file (never crashes).
 */
public class SourceRootsContextIndex implements ContextIndex {
    
    // simpleName -> Set<FQN>
    private final Map<String, Set<String>> simpleNameToFqns = new HashMap<>();
    
    // typeFqn -> List<superclass FQNs in order>
    private final Map<String, List<String>> superChains = new HashMap<>();
    
    // typeFqn -> List<implemented interface FQNs>
    private final Map<String, List<String>> implementsFqns = new HashMap<>();
    
    // typeFqn -> TypeKind
    private final Map<String, TypeKind> typeKinds = new HashMap<>();
    
    // ownerFqn -> Set<method signatures (name:arity or name:arity:param1,param2,...)>
    private final Map<String, Set<String>> typeMethods = new HashMap<>();
    
    // ownerFqn -> Set<field names>
    private final Map<String, Set<String>> typeFields = new HashMap<>();
    
    /**
     * Build the index by scanning source roots.
     * 
     * @param sourceRoots List of source root directories to scan
     */
    public SourceRootsContextIndex(List<Path> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            System.out.println("[SourceRootsContextIndex] No source roots provided - creating empty index");
            return; // Empty index
        }
        
        System.out.println("[SourceRootsContextIndex] Indexing " + sourceRoots.size() + " source root(s)...");
        JavaParser parser = new JavaParser(new ParserConfiguration());
        
        for (Path sourceRoot : sourceRoots) {
            if (sourceRoot == null || !Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
                System.out.println("[SourceRootsContextIndex] Skipping invalid source root: " + sourceRoot);
                continue;
            }
            
            try {
                Files.walk(sourceRoot)
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("package-info"))
                    .filter(p -> !p.toString().contains("module-info"))
                    .forEach(javaFile -> {
                        try {
                            indexFile(parser, javaFile);
                        } catch (Throwable ignored) {
                            // Best-effort: skip files we can't parse
                        }
                    });
            } catch (Throwable ignored) {
                // Best-effort: skip directories we can't walk
            }
        }
        
        int totalTypes = simpleNameToFqns.values().stream().mapToInt(Set::size).sum();
        int totalMethods = typeMethods.values().stream().mapToInt(Set::size).sum();
        int totalFields = typeFields.values().stream().mapToInt(Set::size).sum();
        System.out.println("[SourceRootsContextIndex] Index complete: " + totalTypes + " types, " + totalMethods + " methods, " + totalFields + " fields indexed");
    }
    
    /**
     * Index a single Java file.
     */
    private void indexFile(JavaParser parser, Path javaFile) {
        try {
            var result = parser.parse(javaFile);
            if (!result.isSuccessful() || !result.getResult().isPresent()) {
                return;
            }
            
            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");
            
            // Index all top-level types
            for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
                String simpleName = typeDecl.getNameAsString();
                String fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
                
                // Determine type kind
                TypeKind kind = TypeKind.CLASS;
                if (typeDecl instanceof EnumDeclaration) {
                    kind = TypeKind.ENUM;
                } else if (typeDecl instanceof AnnotationDeclaration) {
                    kind = TypeKind.ANNOTATION;
                } else if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration coi = (ClassOrInterfaceDeclaration) typeDecl;
                    if (coi.isInterface()) {
                        kind = TypeKind.INTERFACE;
                    }
                }
                typeKinds.put(fqn, kind);
                
                // Index simple name -> FQN mapping
                simpleNameToFqns.computeIfAbsent(simpleName, k -> new HashSet<>()).add(fqn);
                
                if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration coi = (ClassOrInterfaceDeclaration) typeDecl;
                    
                    // Index superclass if present
                    coi.getExtendedTypes().forEach(extended -> {
                        try {
                            String superFqn = resolveFqn(extended.getNameAsString(), packageName);
                            List<String> chain = new ArrayList<>();
                            chain.add(superFqn);
                            // Build chain with visited set to avoid cycles, depth limit 20
                            buildSuperChain(superFqn, chain, new HashSet<>(), 0, 20);
                            superChains.put(fqn, chain);
                        } catch (Throwable ignored) {
                            // Best-effort: skip if we can't resolve
                        }
                    });
                    
                    // Index implemented interfaces
                    List<String> impls = new ArrayList<>();
                    coi.getImplementedTypes().forEach(impl -> {
                        try {
                            String implFqn = resolveFqn(impl.getNameAsString(), packageName);
                            impls.add(implFqn);
                        } catch (Throwable ignored) {
                            // Best-effort: skip if we can't resolve
                        }
                    });
                    if (!impls.isEmpty()) {
                        implementsFqns.put(fqn, impls);
                    }
                    
                    // Index methods: name + parameter count + parameter simple names
                    Set<String> methodSigs = new HashSet<>();
                    for (MethodDeclaration method : coi.getMethods()) {
                        String methodName = method.getNameAsString();
                        int arity = method.getParameters().size();
                        
                        // Build param simple names list
                        List<String> paramSimpleNames = method.getParameters().stream()
                            .map(p -> {
                                try {
                                    return p.getType().asString(); // Simple name or FQN
                                } catch (Throwable ignored) {
                                    return "Object"; // Fallback
                                }
                            })
                            .collect(Collectors.toList());
                        
                        // Store as name:arity or name:arity:param1,param2,...
                        String sig = methodName + ":" + arity;
                        if (!paramSimpleNames.isEmpty()) {
                            sig += ":" + String.join(",", paramSimpleNames);
                        }
                        methodSigs.add(sig);
                    }
                    if (!methodSigs.isEmpty()) {
                        typeMethods.put(fqn, methodSigs);
                    }
                    
                    // Index fields
                    Set<String> fieldNames = new HashSet<>();
                    for (FieldDeclaration field : coi.getFields()) {
                        for (VariableDeclarator var : field.getVariables()) {
                            fieldNames.add(var.getNameAsString());
                        }
                    }
                    if (!fieldNames.isEmpty()) {
                        typeFields.put(fqn, fieldNames);
                    }
                } else if (typeDecl instanceof EnumDeclaration) {
                    EnumDeclaration ed = (EnumDeclaration) typeDecl;
                    
                    // Index enum methods
                    Set<String> methodSigs = new HashSet<>();
                    for (MethodDeclaration method : ed.getMethods()) {
                        String methodName = method.getNameAsString();
                        int arity = method.getParameters().size();
                        methodSigs.add(methodName + ":" + arity);
                    }
                    if (!methodSigs.isEmpty()) {
                        typeMethods.put(fqn, methodSigs);
                    }
                } else if (typeDecl instanceof AnnotationDeclaration) {
                    AnnotationDeclaration ad = (AnnotationDeclaration) typeDecl;
                    
                    // Index annotation methods
                    Set<String> methodSigs = new HashSet<>();
                    for (MethodDeclaration method : ad.getMethods()) {
                        String methodName = method.getNameAsString();
                        int arity = method.getParameters().size();
                        methodSigs.add(methodName + ":" + arity);
                    }
                    if (!methodSigs.isEmpty()) {
                        typeMethods.put(fqn, methodSigs);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Best-effort: skip files we can't parse
        }
    }
    
    /**
     * Resolve a type name to FQN (best-effort).
     */
    private String resolveFqn(String typeName, String packageName) {
        if (typeName.contains(".")) {
            return typeName; // Already FQN
        }
        // Simple name - try same package first
        if (!packageName.isEmpty()) {
            return packageName + "." + typeName;
        }
        return typeName; // Default package
    }
    
    /**
     * Build superclass chain recursively with cycle detection and depth limit (max 20).
     */
    private void buildSuperChain(String typeFqn, List<String> chain, Set<String> visited, int depth, int maxDepth) {
        if (depth >= maxDepth || visited.contains(typeFqn)) {
            return; // Limit recursion depth or cycle detected
        }
        
        visited.add(typeFqn);
        
        // Try to find this type in our index
        String simpleName = typeFqn.contains(".") 
            ? typeFqn.substring(typeFqn.lastIndexOf('.') + 1)
            : typeFqn;
        
        Set<String> candidates = simpleNameToFqns.get(simpleName);
        if (candidates == null || candidates.isEmpty()) {
            return; // Type not found
        }
        
        // If exact match, try to get its superclass
        if (candidates.contains(typeFqn)) {
            List<String> superChain = superChains.get(typeFqn);
            if (superChain != null && !superChain.isEmpty()) {
                String nextSuper = superChain.get(0);
                if (!chain.contains(nextSuper)) {
                    chain.add(nextSuper);
                    buildSuperChain(nextSuper, chain, visited, depth + 1, maxDepth);
                }
            }
        }
    }
    
    @Override
    public Set<String> lookupBySimpleName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return Collections.emptySet();
        }
        return simpleNameToFqns.getOrDefault(simpleName, Collections.emptySet());
    }
    
    @Override
    public List<String> getSuperChain(String typeFqn) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return Collections.emptyList();
        }
        return superChains.getOrDefault(typeFqn, Collections.emptyList());
    }
    
    @Override
    public List<String> getSuperTypes(String typeFqn) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> result = new ArrayList<>();
        
        // Add superclass chain
        List<String> superChain = superChains.get(typeFqn);
        if (superChain != null) {
            result.addAll(superChain);
        }
        
        // Add implemented interfaces
        List<String> impls = implementsFqns.get(typeFqn);
        if (impls != null) {
            result.addAll(impls);
            
            // Recursively add super-interfaces of implemented interfaces
            Set<String> visited = new HashSet<>();
            visited.add(typeFqn);
            for (String impl : impls) {
                addSuperInterfaces(impl, result, visited);
            }
        }
        
        return result;
    }
    
    /**
     * Recursively add super-interfaces of an interface.
     */
    private void addSuperInterfaces(String interfaceFqn, List<String> result, Set<String> visited) {
        if (visited.contains(interfaceFqn)) {
            return; // Cycle detected
        }
        visited.add(interfaceFqn);
        
        // Get extended interfaces
        List<String> superChain = superChains.get(interfaceFqn);
        if (superChain != null) {
            for (String superIface : superChain) {
                if (!result.contains(superIface)) {
                    result.add(superIface);
                    addSuperInterfaces(superIface, result, visited);
                }
            }
        }
    }
    
    @Override
    public Optional<TypeKind> typeKindOf(String typeFqn) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(typeKinds.get(typeFqn));
    }
    
    @Override
    public boolean methodExists(String ownerFqn, String name, int arity) {
        if (ownerFqn == null || name == null || arity < 0) {
            return false;
        }
        
        Set<String> methodSigs = typeMethods.get(ownerFqn);
        if (methodSigs == null) {
            return false;
        }
        
        // Check for exact match: name:arity
        String sig = name + ":" + arity;
        if (methodSigs.contains(sig)) {
            return true;
        }
        
        // Check for matches with param names: name:arity:...
        for (String methodSig : methodSigs) {
            if (methodSig.startsWith(sig + ":")) {
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    public boolean methodExists(String ownerFqn, String name, List<String> paramSimpleNames) {
        if (ownerFqn == null || name == null) {
            return false;
        }
        
        Set<String> methodSigs = typeMethods.get(ownerFqn);
        if (methodSigs == null) {
            return false;
        }
        
        // If paramSimpleNames is empty/null, fallback to arity check
        if (paramSimpleNames == null || paramSimpleNames.isEmpty()) {
            return false; // Can't match without arity info
        }
        
        int arity = paramSimpleNames.size();
        String expectedSig = name + ":" + arity + ":" + String.join(",", paramSimpleNames);
        
        // Try exact match first
        if (methodSigs.contains(expectedSig)) {
            return true;
        }
        
        // Try match by name:arity:... pattern (param names may differ slightly)
        String prefix = name + ":" + arity + ":";
        for (String methodSig : methodSigs) {
            if (methodSig.startsWith(prefix)) {
                // Extract param names from signature
                String paramPart = methodSig.substring(prefix.length());
                String[] params = paramPart.split(",");
                if (params.length == paramSimpleNames.size()) {
                    // Check if simple names match (case-insensitive, ignoring package)
                    boolean matches = true;
                    for (int i = 0; i < params.length; i++) {
                        String sigParam = params[i].trim();
                        String expectedParam = paramSimpleNames.get(i);
                        // Extract simple name (last part after '.')
                        String sigSimple = sigParam.contains(".") 
                            ? sigParam.substring(sigParam.lastIndexOf('.') + 1)
                            : sigParam;
                        String expectedSimple = expectedParam.contains(".")
                            ? expectedParam.substring(expectedParam.lastIndexOf('.') + 1)
                            : expectedParam;
                        if (!sigSimple.equalsIgnoreCase(expectedSimple)) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) {
                        return true;
                    }
                }
            }
        }
        
        // Fallback to arity-only check
        return methodExists(ownerFqn, name, arity);
    }
    
    @Override
    public boolean fieldExists(String ownerFqn, String fieldName) {
        if (ownerFqn == null || fieldName == null) {
            return false;
        }
        
        Set<String> fields = typeFields.get(ownerFqn);
        if (fields == null) {
            return false;
        }
        
        return fields.contains(fieldName);
    }
}
