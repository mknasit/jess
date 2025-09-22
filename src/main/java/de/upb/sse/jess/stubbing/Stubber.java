package de.upb.sse.jess.stubbing;


import de.upb.sse.jess.configuration.JessConfiguration;

import java.nio.file.Path;
import java.util.List;

public interface Stubber {
    /** Generate stubs into `slicedSrcDir`. Return number of created/updated types. */
    int run(Path slicedSrcDir, List<Path> classpathJars) throws Exception;
}

