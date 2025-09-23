// de/upb/sse/jess/stubbing/SpoonStubbingRunner.java
package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector.CollectResult;
import de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.factory.Factory;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.List;

public final class SpoonStubbingRunner implements Stubber {
    private final JessConfiguration cfg;

    public SpoonStubbingRunner(JessConfiguration cfg) {
        this.cfg = cfg;
    }

    @Override
    public int run(Path slicedSrcDir, List<Path> classpathJars) throws Exception {
        // 1) Configure Spoon for Java 11
        Launcher launcher = new Launcher();
        var env = launcher.getEnvironment();
        env.setComplianceLevel(11);
        env.setAutoImports(true);
        env.setSourceOutputDirectory(slicedSrcDir.toFile());

        if (classpathJars == null || classpathJars.isEmpty()) {
            env.setNoClasspath(true);
        } else {
            env.setNoClasspath(false);
            env.setSourceClasspath(classpathJars.stream().map(Path::toString).toArray(String[]::new));
        }

        launcher.addInputResource(slicedSrcDir.toString());
        launcher.buildModel();

        // 2) Collect unresolved elements
        CtModel model = launcher.getModel();
        Factory f = launcher.getFactory();
        SpoonCollector collector = new SpoonCollector(f, cfg);
        CollectResult plans = collector.collect(model);



        // 3) Generate stubs (separate handlers per kind)
        SpoonStubber stubber = new SpoonStubber(f);
        int created = 0;
        created += stubber.applyTypePlans(plans.typePlans);           // types (classes/interfaces/annotations)
        created += stubber.applyFieldPlans(plans.fieldPlans);         // fields
        created += stubber.applyConstructorPlans(plans.ctorPlans);    // constructors
        created += stubber.applyMethodPlans(plans.methodPlans);

        stubber.dequalifyCurrentPackageUnresolvedRefs();
        stubber.qualifyAmbiguousSimpleTypes();           // NEW pass

        stubber.report();                                             // nice summary

        // 4) Pretty-print (use default printer; safer with JDK11 snippets)
        env.setPrettyPrinterCreator(() -> new DefaultJavaPrettyPrinter(env));
        launcher.prettyprint();

        return created;
    }
}
