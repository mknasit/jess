// de/upb/sse/jess/stubbing/spoon/plan/FieldStubPlan.java
package de.upb.sse.jess.stubbing.spoon.plan;

import spoon.reflect.reference.CtTypeReference;

public final class FieldStubPlan {
    public final CtTypeReference<?> ownerType;
    public final String fieldName;
    public CtTypeReference<?> fieldType;
    public final boolean isStatic;
    public FieldStubPlan(CtTypeReference<?> ownerType, String fieldName,
                         CtTypeReference<?> fieldType, boolean isStatic) {
        this.ownerType = ownerType;
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.isStatic = isStatic;
    }
}
