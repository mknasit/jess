package de.upb.sse.jess.stubbing;

import java.util.*;

/**
 * Describes the slice produced by JavaParser slicing phase.
 * Contains all information needed to identify what should be kept in the Spoon model.
 * 
 * This descriptor bridges the JavaParser world (slicing) and the Spoon world (stubbing).
 */
public class SliceDescriptor {
    
    /**
     * Describes the target method that was sliced.
     */
    public static class TargetMethodDescriptor {
        public final String classFqn;              // Fully qualified class name, e.g., "com.example.MyClass"
        public final String methodName;            // Method name, e.g., "myMethod"
        public final List<String> parameterTypes;  // Parameter types, e.g., ["int", "String"]
        public final boolean isClinit;             // true if this is <clinit>
        public final boolean isInit;               // true if this is <init>
        
        public TargetMethodDescriptor(String classFqn, String methodName, List<String> parameterTypes, 
                                     boolean isClinit, boolean isInit) {
            this.classFqn = classFqn;
            this.methodName = methodName;
            this.parameterTypes = parameterTypes != null ? new ArrayList<>(parameterTypes) : new ArrayList<>();
            this.isClinit = isClinit;
            this.isInit = isInit;
        }
        
        @Override
        public String toString() {
            if (isClinit) {
                return classFqn + ".<clinit>()";
            } else if (isInit) {
                return classFqn + ".<init>(" + String.join(", ", parameterTypes) + ")";
            } else {
                return classFqn + "." + methodName + "(" + String.join(", ", parameterTypes) + ")";
            }
        }
    }
    
    /**
     * Describes a method signature for matching with Spoon methods.
     */
    public static class MethodSignatureDescriptor {
        public final String name;
        public final List<String> parameterTypes;  // Parameter types with generics, e.g., ["List<String>", "int"]
        
        public MethodSignatureDescriptor(String name, List<String> parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes != null ? new ArrayList<>(parameterTypes) : new ArrayList<>();
        }
        
        /**
         * Build signature string for matching: "methodName(paramType1, paramType2, ...)"
         */
        public String toSignatureString() {
            return name + "(" + String.join(", ", parameterTypes) + ")";
        }
        
        @Override
        public String toString() {
            return toSignatureString();
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodSignatureDescriptor that = (MethodSignatureDescriptor) o;
            return Objects.equals(name, that.name) && Objects.equals(parameterTypes, that.parameterTypes);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(name, parameterTypes);
        }
    }
    
    // Target method information
    public final TargetMethodDescriptor targetMethod;
    
    // Slice contents (from JavaParser slicing)
    public final Set<String> sliceTypeFqns;  // All types in slice: "com.example.MyClass", "com.example.Outer$Inner"
    
    // Methods in slice: classFqn -> [method signatures]
    public final Map<String, List<MethodSignatureDescriptor>> sliceMethods;
    
    // Fields in slice: classFqn -> [field names]
    public final Map<String, List<String>> sliceFields;
    
    // Constructors in slice: classFqn -> [constructor signatures]
    public final Map<String, List<MethodSignatureDescriptor>> sliceConstructors;
    
    public SliceDescriptor(TargetMethodDescriptor targetMethod,
                          Set<String> sliceTypeFqns,
                          Map<String, List<MethodSignatureDescriptor>> sliceMethods,
                          Map<String, List<String>> sliceFields,
                          Map<String, List<MethodSignatureDescriptor>> sliceConstructors) {
        this.targetMethod = targetMethod;
        this.sliceTypeFqns = sliceTypeFqns != null ? new LinkedHashSet<>(sliceTypeFqns) : new LinkedHashSet<>();
        this.sliceMethods = sliceMethods != null ? new HashMap<>(sliceMethods) : new HashMap<>();
        this.sliceFields = sliceFields != null ? new HashMap<>(sliceFields) : new HashMap<>();
        this.sliceConstructors = sliceConstructors != null ? new HashMap<>(sliceConstructors) : new HashMap<>();
    }
    
    /**
     * Check if a type FQN is in the slice.
     */
    public boolean isSliceType(String typeFqn) {
        return sliceTypeFqns.contains(typeFqn);
    }
    
    /**
     * Check if a method is in the slice.
     */
    public boolean isSliceMethod(String classFqn, MethodSignatureDescriptor methodSig) {
        List<MethodSignatureDescriptor> methods = sliceMethods.get(classFqn);
        return methods != null && methods.contains(methodSig);
    }
    
    /**
     * Check if a field is in the slice.
     */
    public boolean isSliceField(String classFqn, String fieldName) {
        List<String> fields = sliceFields.get(classFqn);
        return fields != null && fields.contains(fieldName);
    }
    
    /**
     * Check if a constructor is in the slice.
     */
    public boolean isSliceConstructor(String classFqn, MethodSignatureDescriptor ctorSig) {
        List<MethodSignatureDescriptor> constructors = sliceConstructors.get(classFqn);
        return constructors != null && constructors.contains(ctorSig);
    }
    
    @Override
    public String toString() {
        return "SliceDescriptor{" +
                "targetMethod=" + targetMethod +
                ", sliceTypeFqns=" + sliceTypeFqns.size() + " types" +
                ", sliceMethods=" + sliceMethods.values().stream().mapToInt(List::size).sum() + " methods" +
                ", sliceFields=" + sliceFields.values().stream().mapToInt(List::size).sum() + " fields" +
                ", sliceConstructors=" + sliceConstructors.values().stream().mapToInt(List::size).sum() + " constructors" +
                '}';
    }
}

