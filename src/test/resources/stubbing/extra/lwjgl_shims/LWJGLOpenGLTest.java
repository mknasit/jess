package fixtures.lwjgl;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

class LWJGLOpenGLTest {
    @TargetMethod
    void useOpenGL() {
        GL.glClear(0);
        GL.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL.glViewport(0, 0, 800, 600);
        GL.glEnable(0);
        GL.glDisable(0);
        GL.glDrawArrays(0, 0, 0);
        GL.glDrawElements(0, 0, 0, 0);
        GL.glBindBuffer(0, 0);
        // glGenBuffers and glDeleteBuffers take int[] arrays, not null
        // For testing, we'll use array parameters
        int[] buffers = new int[1];
        GL.glGenBuffers(buffers);
        GL.glDeleteBuffers(buffers);
        GL.glUseProgram(0);
        // glUniformMatrix4fv takes FloatBuffer, not null - skip for shim test
        // GL.glUniformMatrix4fv(0, false, null);
        GL.glUniform1i(0, 0);
        GL.glUniform3f(0, 0.0f, 0.0f, 0.0f);
        int location = GL.glGetUniformLocation(0, "uniformName");
        int attrib = GL.glGetAttribLocation(0, "attributeName");
    }
    
    @TargetMethod
    void useGL11() {
        GL11.glClear(0);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL11.glBindTexture(0, 0);
        int[] textures = new int[1];
        GL11.glGenTextures(textures);
        GL11.glDeleteTextures(textures);
        // glTexImage2D takes ByteBuffer, not null - skip for shim test
        // GL11.glTexImage2D(0, 0, 0, 0, 0, 0, 0, 0, null);
        GL11.glTexParameteri(0, 0, 0);
    }
    
    @TargetMethod
    void useGL20() {
        GL20.glUseProgram(0);
        // glUniformMatrix4fv takes FloatBuffer, not null - skip for shim test
        // GL20.glUniformMatrix4fv(0, false, null);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 0, 0, false, 0, 0);
        GL20.glCreateShader(0);
        GL20.glShaderSource(0, "shader source code");
        GL20.glCompileShader(0);
        int status = GL20.glGetShaderi(0, 0);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, 0);
        GL20.glLinkProgram(program);
    }
    
    @TargetMethod
    void useGL30() {
        GL30.glBindVertexArray(0);
        int[] vaos = new int[1];
        GL30.glGenVertexArrays(vaos);
        GL30.glDeleteVertexArrays(vaos);
        GL30.glBindFramebuffer(0, 0);
        int[] fbos = new int[1];
        GL30.glGenFramebuffers(fbos);
        GL30.glDeleteFramebuffers(fbos);
        GL30.glFramebufferTexture2D(0, 0, 0, 0, 0);
        int status = GL30.glCheckFramebufferStatus(0);
    }
}

