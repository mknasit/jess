package fixtures.lwjgl;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

class LWJGLSystemTest {
    @TargetMethod
    void useMemoryStack() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long ptr = stack.malloc(1024);
            long callocPtr = stack.calloc(1024);
            long nmalloc = stack.nmalloc(1024);
            long ncalloc = stack.ncalloc(1024, 1);
        }
    }
    
    @TargetMethod
    void useMemoryUtil() {
        long ptr = MemoryUtil.memAlloc(1024);
        long realloc = MemoryUtil.memRealloc(ptr, 2048);
        MemoryUtil.memFree(realloc);
        // memAddress takes Buffer, not null - skip for shim test
        // long address = MemoryUtil.memAddress(null);
        java.nio.ByteBuffer buffer = MemoryUtil.memByteBuffer(ptr, 1024);
        MemoryUtil.memSet(ptr, 0, 1024);
        MemoryUtil.memCopy(ptr, 0, 1024);
    }
}

