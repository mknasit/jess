package fixtures.shim_common;

import org.lwjgl.opengl.GL;

class LWJGLShimTest {
    @TargetMethod
    void useOpenGL() {
        GL.glClear(0);
        GL.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL.glViewport(0, 0, 800, 600);
        GL.glEnable(0);
        GL.glDisable(0);
        GL.glDrawArrays(0, 0, 0);
        GL.glUseProgram(0);
        int location = GL.glGetUniformLocation(0, "uniform");
        int attrib = GL.glGetAttribLocation(0, "attribute");
    }
}

