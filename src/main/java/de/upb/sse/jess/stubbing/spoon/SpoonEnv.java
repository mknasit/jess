// de/upb/sse/jess/stubbing/spoon/SpoonEnv.java
package de.upb.sse.jess.stubbing.spoon;

import spoon.Launcher;

import java.nio.file.Path;
import java.util.List;

public final class SpoonEnv {
    private SpoonEnv() {}

    public static Launcher createJava11Launcher(Path outDir, List<Path> classpathJars) {
        Launcher launcher = new Launcher();
        var env = launcher.getEnvironment();
        env.setComplianceLevel(11);
        env.setAutoImports(true);
        env.setSourceOutputDirectory(outDir.toFile());

        if (classpathJars == null || classpathJars.isEmpty()) {
            env.setNoClasspath(true);
        } else {
            env.setNoClasspath(false);
            env.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
        }
        // We won’t bind a pretty-printer here; writing is a separate step.
        return launcher;
    }
}
