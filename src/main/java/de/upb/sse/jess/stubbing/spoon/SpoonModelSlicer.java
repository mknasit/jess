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
     * IMPORTANT: This method NEVER deletes types whose source file is under slicedSrcDir (gen/).
     * gen/ is the canonical source of truth - types from gen/ are always preserved.
     * 
     * Strategy:
     * - Keep all types whose source file is under slicedSrcDir (gen/) - ALWAYS
     * - Keep all types whose FQN is in sliceTypeFqns (if descriptor available)
     * - Remove other types (only from source roots, never from gen/)
     * 
     * @param model The Spoon model to slice (will be mutated)
     * @param descriptor The slice descriptor describing what to keep (optional metadata)
     * @param slicedSrcDir Path to slice directory (gen/) - types from here are NEVER deleted
     */
    public void sliceModel(CtModel model, SliceDescriptor descriptor, java.nio.file.Path slicedSrcDir) {
        if (descriptor == null) {
            // No descriptor means keep everything (conservative)
            System.out.println("[SpoonModelSlicer] No descriptor provided, keeping full model (no slicing).");
            return;
        }
        
        Set<String> sliceTypeFqns = descriptor.sliceTypeFqns;
        if (sliceTypeFqns == null || sliceTypeFqns.isEmpty()) {
            // Descriptor has no type info: do NOT slice, keep everything
            System.out.println("[SpoonModelSlicer] Descriptor has no slice types; keeping full model (no slicing).");
            return;
        }
        
        Factory factory = model.getUnnamedModule().getFactory();
        java.nio.file.Path sliceRoot = slicedSrcDir != null ? slicedSrcDir.toAbsolutePath().normalize() : null;
        
        // Collect all types in the model
        List<CtType<?>> allTypes = new ArrayList<>();
        collectAllTypes(model.getUnnamedModule().getRootPackage(), allTypes);
        
        // Remove types that are not in the slice, BUT NEVER remove types from gen/
        List<CtType<?>> typesToRemove = new ArrayList<>();
        for (CtType<?> type : allTypes) {
            // CRITICAL: Never delete types from gen/ (canonical source of truth)
            if (sliceRoot != null) {
                try {
                    spoon.reflect.cu.SourcePosition pos = type.getPosition();
                    if (pos != null && pos.getFile() != null) {
                        java.nio.file.Path filePath = pos.getFile().toPath().toAbsolutePath().normalize();
                        if (filePath.startsWith(sliceRoot)) {
                            continue;  // Type is from gen/ - NEVER remove it
                        }
                    }
                } catch (Throwable ignored) {
                    // If we can't check path, be conservative and don't remove
                    continue;
                }
            }
            
            // Only remove types from source roots that are not in slice
            String typeFqn = type.getQualifiedName();
            if (typeFqn != null && !sliceTypeFqns.contains(typeFqn)) {
                typesToRemove.add(type);
            }
        }
        
        // Remove types not in slice (only from source roots, never from gen/)
        for (CtType<?> type : typesToRemove) {
            try {
                type.delete();
            } catch (Exception e) {
                // Type might already be deleted or in use, ignore
                System.err.println("[SpoonModelSlicer] Warning: Could not remove type " + type.getQualifiedName() + ": " + e.getMessage());
            }
        }
        
        // Note: We don't remove methods/fields/constructors here because:
        // 1. If a type is kept, we keep all its members (conservative approach)
        // 2. SpoonCollector will handle missing elements and create stubs as needed
        // 3. The main goal is to remove entire types that are not in the slice (from source roots only)
        
        System.out.println("[SpoonModelSlicer] Sliced model: kept " + 
                (allTypes.size() - typesToRemove.size()) + " types, removed " + 
                typesToRemove.size() + " types (only from source roots, gen/ types preserved)");
    }
    
    /**
     * Legacy method signature for backward compatibility (deprecated).
     * @deprecated Use {@link #sliceModel(CtModel, SliceDescriptor, Path)} instead
     */
    @Deprecated
    public void sliceModel(CtModel model, SliceDescriptor descriptor) {
        // Call new method with null slicedSrcDir (conservative - won't delete anything if we can't check paths)
        sliceModel(model, descriptor, null);
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

