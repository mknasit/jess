// de/upb/sse/jess/stubbing/spoon/plan/TypeStubPlan.java
package de.upb.sse.jess.stubbing.spoon.plan;

public final class TypeStubPlan {
    public enum Kind { CLASS, INTERFACE, ANNOTATION }
    public final String qualifiedName;
    public final Kind kind;
    public final boolean isNonStaticInner;  // true if inner class should be non-static (for o.new Inner() syntax)

    public TypeStubPlan(String qualifiedName, Kind kind) {
        this(qualifiedName, kind, false);
    }

    public TypeStubPlan(String qualifiedName, Kind kind, boolean isNonStaticInner) {
        this.qualifiedName = qualifiedName;
        this.kind = kind;
        this.isNonStaticInner = isNonStaticInner;
    }
}
