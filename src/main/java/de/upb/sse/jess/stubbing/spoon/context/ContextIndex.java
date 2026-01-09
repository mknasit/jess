package de.upb.sse.jess.stubbing.spoon.context;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Lightweight, read-only index of types and methods from source roots.
 * Used only for tie-breaking (type ambiguity, owner resolution) - does NOT change what is compiled.
 * 
 * The index is built by scanning source roots using JavaParser (lightweight, no Spoon model).
 */
public interface ContextIndex {
    
    /**
     * Enum for type kinds.
     */
    enum TypeKind {
        CLASS, INTERFACE, ENUM, ANNOTATION
    }
    
    /**
     * Look up all FQNs that match a simple name.
     * 
     * @param simpleName Simple type name (e.g., "Project")
     * @return Set of fully qualified names (e.g., {"org.apache.tools.ant.Project", ...})
     *         Empty set if no matches found
     */
    Set<String> lookupBySimpleName(String simpleName);
    
    /**
     * Get the superclass chain for a type (best-effort).
     * 
     * @param typeFqn Fully qualified type name
     * @return List of superclass FQNs in order (direct superclass first, then its superclass, etc.)
     *         Empty list if type not found or has no superclass
     * @deprecated Use {@link #getSuperTypes(String)} instead, which includes interfaces
     */
    @Deprecated
    List<String> getSuperChain(String typeFqn);
    
    /**
     * Get all super types (superclass chain + implemented interfaces and their super-interfaces).
     * 
     * @param typeFqn Fully qualified type name
     * @return List of super type FQNs: [direct superclass, ...superclass chain..., implemented interfaces, ...interface super-interfaces...]
     *         Empty list if type not found or has no super types
     */
    List<String> getSuperTypes(String typeFqn);
    
    /**
     * Get the type kind (CLASS, INTERFACE, ENUM, ANNOTATION) if known.
     * 
     * @param typeFqn Fully qualified type name
     * @return Optional type kind, empty if type not found or kind unknown
     */
    Optional<TypeKind> typeKindOf(String typeFqn);
    
    /**
     * Check if a method exists on a type (minimal signature: name + parameter count).
     * 
     * @param ownerFqn Fully qualified owner type name
     * @param name Method name
     * @param arity Number of parameters
     * @return true if a method with the given name and parameter count exists on the type
     */
    boolean methodExists(String ownerFqn, String name, int arity);
    
    /**
     * Check if a method exists on a type with parameter simple names (more precise).
     * 
     * @param ownerFqn Fully qualified owner type name
     * @param name Method name
     * @param paramSimpleNames List of parameter simple names (e.g., ["String", "int"])
     * @return true if a method with the given name and parameter simple names exists on the type
     *         Falls back to arity check if paramSimpleNames is empty or null
     */
    boolean methodExists(String ownerFqn, String name, List<String> paramSimpleNames);
    
    /**
     * Check if a field exists on a type.
     * 
     * @param ownerFqn Fully qualified owner type name
     * @param fieldName Field name
     * @return true if a field with the given name exists on the type
     */
    boolean fieldExists(String ownerFqn, String fieldName);
}

