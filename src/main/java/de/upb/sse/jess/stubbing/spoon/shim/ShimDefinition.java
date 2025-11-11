package de.upb.sse.jess.stubbing.spoon.shim;

import java.util.List;

/**
 * Definition of a shim class to be generated.
 */
public class ShimDefinition {
    public enum Kind {
        CLASS,
        INTERFACE,
        ANNOTATION,
        ENUM
    }
    
    private final String fqn;
    private final Kind kind;
    private final List<String> methodNames;
    
    public ShimDefinition(String fqn, Kind kind, List<String> methodNames) {
        this.fqn = fqn;
        this.kind = kind;
        this.methodNames = methodNames;
    }
    
    public String getFqn() {
        return fqn;
    }
    
    public Kind getKind() {
        return kind;
    }
    
    public List<String> getMethodNames() {
        return methodNames;
    }
}

