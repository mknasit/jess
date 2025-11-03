// de/upb/sse/jess/stubbing/SpoonStubbingRunner.java
package de.upb.sse.jess.stubbing;

import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector.CollectResult;
import de.upb.sse.jess.stubbing.spoon.generate.SpoonStubber;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.filter.TypeFilter;

import spoon.reflect.declaration.CtType;
import java.nio.file.Path;
import java.util.List;

public final class SpoonStubbingRunner implements Stubber {
    private final JessConfiguration cfg;

    public SpoonStubbingRunner(JessConfiguration cfg) {
        this.cfg = cfg;
    }

    @Override
    public int run(Path slicedSrcDir, List<Path> classpathJars) throws Exception {
        System.out.println("\n>> Using stubber: Spoon Based Stubber" );
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
        SpoonStubber stubber = new SpoonStubber(f,model);
        int created = 0;
        created += stubber.applyTypePlans(plans.typePlans);// types (classes/interfaces/annotations)
        created += stubber.applyFieldPlans(plans.fieldPlans);         // fields
        created += stubber.applyConstructorPlans(plans.ctorPlans);    // constructors
        created += stubber.applyMethodPlans(plans.methodPlans);
        stubber.finalizeRepeatableAnnotations();

        stubber.canonicalizeAllMetaAnnotations();
        stubber.dequalifyCurrentPackageUnresolvedRefs();
        stubber.qualifyAmbiguousSimpleTypes();           // NEW pass

        stubber.report();                                             // nice summary

        // remove unknown.Helper if a real Helper exists
        model.getAllTypes().stream()
                .collect(java.util.stream.Collectors.groupingBy(CtType::getSimpleName))
                .forEach((simple, list) -> {
                    if (!"Helper".equals(simple)) return;
                    boolean hasReal = list.stream().anyMatch(t ->
                            t.getPackage() != null && !"unknown".equals(t.getPackage().getQualifiedName()));
                    if (hasReal) {
                        list.stream()
                                .filter(t -> t.getPackage() != null && "unknown".equals(t.getPackage().getQualifiedName()))
                                .forEach(CtType::delete);
                    }
                });

// rebind invocations that still target unknown.Helper
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!(inv.getTarget() instanceof CtTypeAccess)) continue;
            CtTypeAccess<?> ta = (CtTypeAccess<?>) inv.getTarget();

            CtTypeReference<?> tr;
            try { tr = ta.getAccessedType(); } catch (Throwable e) { continue; }
            if (tr == null) continue;

            String qn;
            try { qn = tr.getQualifiedName(); } catch (Throwable e) { qn = null; }
            if (!"unknown.Helper".equals(qn)) continue;

            // prefer Helper in the current package of this file
            CtType<?> ownerType = inv.getParent(CtType.class);
            String currentPkg = (ownerType != null && ownerType.getPackage() != null)
                    ? ownerType.getPackage().getQualifiedName() : "";

            CtTypeReference<?> to = null;
            if (!currentPkg.isEmpty()) {
                to = model.getAllTypes().stream()
                        .filter(t -> "Helper".equals(t.getSimpleName()))
                        .filter(t -> t.getPackage() != null && currentPkg.equals(t.getPackage().getQualifiedName()))
                        .map(CtType::getReference)
                        .findFirst().orElse(null);
            }
            if (to == null) {
                to = model.getAllTypes().stream()
                        .filter(t -> "Helper".equals(t.getSimpleName()))
                        .filter(t -> t.getPackage() != null && !"unknown".equals(t.getPackage().getQualifiedName()))
                        .map(CtType::getReference)
                        .findFirst().orElse(null);
            }

            if (to != null) {
                // *** key change to avoid the generic error ***
                inv.setTarget(f.Code().createTypeAccess(to));
            } else {
                // last resort: drop the explicit target (let auto-import resolve)
                inv.setTarget(null);
            }
        }

        // 4) Pretty-print (use default printer; safer with JDK11 snippets)
        env.setPrettyPrinterCreator(() -> new DefaultJavaPrettyPrinter(env));
        launcher.prettyprint();

        return created;
    }
}
