package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.Jess;
import java.nio.file.Path;
import java.util.List;

public final class JessStubberAdapter implements Stubber {
    private final Jess jess;
    public JessStubberAdapter(Jess jess) { this.jess = jess; }

    @Override
    public int run(Path slicedSrcDir, List<Path> classpathJars) {
        System.out.println("\n>> Using stubber: Jess Javaparser Based Stubber" );
        try {
            return jess.runJessStubbing(slicedSrcDir.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
