package fixtures.lwjgl;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;

class LWJGLGLFWTest {
    @TargetMethod
    void useGLFW() {
        boolean initialized = GLFW.glfwInit();
        GLFW.glfwTerminate();
        long window = GLFW.glfwCreateWindow(800, 600, "Test", 0, 0);
        GLFW.glfwDestroyWindow(window);
        boolean shouldClose = GLFW.glfwWindowShouldClose(window);
        GLFW.glfwPollEvents();
        GLFW.glfwSwapBuffers(window);
        GLFW.glfwSwapInterval(1);
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSetWindowShouldClose(window, true);
        // Callbacks are typically set with actual callback instances, not null
        // For testing shim generation, we'll skip callback setters with null
        // as they cause ambiguity errors
        long monitor = GLFW.glfwGetPrimaryMonitor();
        org.lwjgl.glfw.GLFWVidMode vidMode = GLFW.glfwGetVideoMode(monitor);
        GLFW.glfwSetWindowPos(window, 0, 0);
        GLFW.glfwSetWindowSize(window, 1024, 768);
    }
    
    @TargetMethod
    void useGLFWCallbacks() {
        // Callback.create() methods take functional interfaces, not null
        // For shim testing, we'll skip these as they cause ambiguity
        // errorCallback.set(0);
        // keyCallback.set(0);
        // cursorCallback.set(0);
    }
}

