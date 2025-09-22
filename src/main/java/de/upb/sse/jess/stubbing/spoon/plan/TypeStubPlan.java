// de/upb/sse/jess/stubbing/spoon/plan/TypeStubPlan.java
package de.upb.sse.jess.stubbing.spoon.plan;

public final class TypeStubPlan {
    public enum Kind { CLASS, INTERFACE, ANNOTATION }
    public final String qualifiedName;
    public final Kind kind;

    public TypeStubPlan(String qualifiedName, Kind kind) {
        this.qualifiedName = qualifiedName;
        this.kind = kind;
    }
}
