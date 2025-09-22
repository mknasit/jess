package de.upb.sse.jess.stubbing.spoon.plan;

import spoon.reflect.reference.CtTypeReference;

import java.util.List;

// de/upb/sse/jess/stubbing/spoon/plan/MethodStubPlan.java
public class MethodStubPlan {
    public enum Visibility { PUBLIC, PROTECTED, PACKAGE, PRIVATE }

    public final CtTypeReference<?> ownerType;
    public final String name;                           // keep "name"
    public final CtTypeReference<?> returnType;
    public final List<CtTypeReference<?>> paramTypes;
    public final boolean isStatic;
    public final Visibility visibility;
    public final List<CtTypeReference<?>> thrownTypes;  // NEW

    public MethodStubPlan(CtTypeReference<?> ownerType, String name,
                          CtTypeReference<?> returnType, List<CtTypeReference<?>> paramTypes,
                          boolean isStatic, Visibility visibility,
                          List<CtTypeReference<?>> thrownTypes) {
        this.ownerType   = ownerType;
        this.name        = name;
        this.returnType  = returnType;
        this.paramTypes  = paramTypes;
        this.isStatic    = isStatic;
        this.visibility  = visibility;
        this.thrownTypes = (thrownTypes == null ? java.util.Collections.emptyList() : thrownTypes);
    }

    // Back-compat ctor → PUBLIC + no throws
    public MethodStubPlan(CtTypeReference<?> ownerType, String name,
                          CtTypeReference<?> returnType, List<CtTypeReference<?>> paramTypes,
                          boolean isStatic, Visibility visibility) {
        this(ownerType, name, returnType, paramTypes, isStatic, visibility,
                java.util.Collections.emptyList());
    }

    // Oldest back-compat ctor
    public MethodStubPlan(CtTypeReference<?> ownerType, String name,
                          CtTypeReference<?> returnType, List<CtTypeReference<?>> paramTypes,
                          boolean isStatic) {
        this(ownerType, name, returnType, paramTypes, isStatic, Visibility.PUBLIC,
                java.util.Collections.emptyList());
    }
}
