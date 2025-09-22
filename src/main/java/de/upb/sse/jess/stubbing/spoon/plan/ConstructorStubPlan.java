// de/upb/sse/jess/stubbing/spoon/plan/ConstructorStubPlan.java
package de.upb.sse.jess.stubbing.spoon.plan;

import spoon.reflect.reference.CtTypeReference;

import java.util.List;

public final class ConstructorStubPlan {
    public final CtTypeReference<?> ownerType;
    public final List<CtTypeReference<?>> parameterTypes;

    public ConstructorStubPlan(CtTypeReference<?> ownerType, List<CtTypeReference<?>> parameterTypes) {
        this.ownerType = ownerType;
        this.parameterTypes = parameterTypes;
    }
}
