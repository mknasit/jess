package de.upb.sse.jess.stubbing.spoon;

import de.upb.sse.jess.stubbing.SliceDescriptor;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.factory.Factory;

import java.util.*;

/**
 * Slices a Spoon model to keep only elements relevant to the slice described by SliceDescriptor.
 * 
 * This class removes types, methods, fields, and constructors that are not in the slice,
 * leaving only what is needed for compilation of the sliced code.
 */
public class SpoonModelSlicer {
    
    /**
     * Slice the model to keep only elements described in the SliceDescriptor.
     * 
     * Strategy:
     * - Keep all types whose FQN is in sliceTypeFqns
     * - Keep all methods/fields/constructors that are explicitly in the slice
     * - Remove everything else
     * 
     * @param model The Spoon model to slice (will be mutated)
     * @param descriptor The slice descriptor describing what to keep
     */
    public void sliceModel(CtModel model, SliceDescriptor descriptor) {
        if (descriptor == null) {
            // No descriptor means keep everything (conservative)
            return;
        }
        
        Factory factory = model.getUnnamedModule().getFactory();
        Set<String> sliceTypeFqns = descriptor.sliceTypeFqns;
        
        // Collect all types in the model
        List<CtType<?>> allTypes = new ArrayList<>();
        collectAllTypes(model.getUnnamedModule().getRootPackage(), allTypes);
        
        // Remove types that are not in the slice
        List<CtType<?>> typesToRemove = new ArrayList<>();
        for (CtType<?> type : allTypes) {
            String typeFqn = type.getQualifiedName();
            if (!sliceTypeFqns.contains(typeFqn)) {
                typesToRemove.add(type);
            }
        }
        
        // Remove types not in slice
        for (CtType<?> type : typesToRemove) {
            try {
                type.delete();
            } catch (Exception e) {
                // Type might already be deleted or in use, ignore
                System.err.println("Warning: Could not remove type " + type.getQualifiedName() + ": " + e.getMessage());
            }
        }
        
        // Note: We don't remove methods/fields/constructors here because:
        // 1. If a type is kept, we keep all its members (conservative approach)
        // 2. SpoonCollector will handle missing elements and create stubs as needed
        // 3. The main goal is to remove entire types that are not in the slice
        
        System.out.println("[SpoonModelSlicer] Sliced model: kept " + 
                (allTypes.size() - typesToRemove.size()) + " types, removed " + 
                typesToRemove.size() + " types");
    }
    
    /**
     * Recursively collect all types from a package and its subpackages.
     */
    private void collectAllTypes(CtPackage pkg, List<CtType<?>> types) {
        types.addAll(pkg.getTypes());
        for (CtPackage subPkg : pkg.getPackages()) {
            collectAllTypes(subPkg, types);
        }
    }
}

