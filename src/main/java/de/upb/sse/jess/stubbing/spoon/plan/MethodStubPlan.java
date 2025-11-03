package de.upb.sse.jess.stubbing.spoon.plan;

import spoon.reflect.reference.CtTypeReference;
import java.util.List;

public class MethodStubPlan {
    public enum Visibility { PUBLIC, PROTECTED, PACKAGE, PRIVATE }

    public final CtTypeReference<?> ownerType;
    public final String name;
    public final CtTypeReference<?> returnType;
    public final List<CtTypeReference<?>> paramTypes;
    public final boolean isStatic;
    public final Visibility visibility;
    public final List<CtTypeReference<?>> thrownTypes;
    public final boolean defaultOnInterface;

    // NEW: needed by the mirror block + varargs
    public final boolean varargs;                  // make last param varargs
    public final boolean mirror;                   // also create the method under unknown.*
    public final CtTypeReference<?> mirrorOwnerRef;// the unknown.* owner to mirror into

    // keep your old ctor (back-compat)
    public MethodStubPlan(CtTypeReference<?> ownerType, String name,
                          CtTypeReference<?> returnType, List<CtTypeReference<?>> paramTypes,
                          boolean isStatic, Visibility visibility,
                          List<CtTypeReference<?>> thrownTypes) {
        this.ownerType = ownerType;
        this.name = name;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.isStatic = isStatic;
        this.visibility = visibility;
        this.thrownTypes = (thrownTypes == null ? java.util.Collections.emptyList() : thrownTypes);
        this.defaultOnInterface = false;
        this.varargs = false;
        this.mirror = false;
        this.mirrorOwnerRef = null;
    }

    // keep your existing extended ctor (defaultOnInterface)
    public MethodStubPlan(CtTypeReference<?> ownerType,
                          String name,
                          CtTypeReference<?> returnType,
                          List<CtTypeReference<?>> paramTypes,
                          boolean isStatic,
                          Visibility visibility,
                          List<CtTypeReference<?>> thrownTypes,
                          boolean defaultOnInterface) {
        this.ownerType = ownerType;
        this.name = name;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.isStatic = isStatic;
        this.visibility = visibility;
        this.thrownTypes = (thrownTypes == null ? java.util.Collections.emptyList() : thrownTypes);
        this.defaultOnInterface = defaultOnInterface;
        this.varargs = false;
        this.mirror = false;
        this.mirrorOwnerRef = null;
    }

    // NEW: full ctor used by the collector when it knows about varargs/mirroring
    public MethodStubPlan(CtTypeReference<?> ownerType,
                          String name,
                          CtTypeReference<?> returnType,
                          List<CtTypeReference<?>> paramTypes,
                          boolean isStatic,
                          Visibility visibility,
                          List<CtTypeReference<?>> thrownTypes,
                          boolean defaultOnInterface,
                          boolean varargs,
                          boolean mirror,
                          CtTypeReference<?> mirrorOwnerRef) {
        this.ownerType = ownerType;
        this.name = name;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.isStatic = isStatic;
        this.visibility = visibility;
        this.thrownTypes = (thrownTypes == null ? java.util.Collections.emptyList() : thrownTypes);
        this.defaultOnInterface = defaultOnInterface;
        this.varargs = varargs;
        this.mirror = mirror;
        this.mirrorOwnerRef = mirrorOwnerRef;
    }
}
