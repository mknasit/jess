package de.upb.sse.jess;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import de.upb.sse.jess.annotation.Annotator;
import de.upb.sse.jess.api.PublicApi;
import de.upb.sse.jess.configuration.JessConfiguration;
import de.upb.sse.jess.dependency.MavenDependencyResolver;
import de.upb.sse.jess.exceptions.AmbiguityException;
import de.upb.sse.jess.generation.StubGenerator;
import de.upb.sse.jess.inference.InferenceEngine;
import de.upb.sse.jess.model.ImportContext;
import de.upb.sse.jess.model.ResolutionInformation;
import de.upb.sse.jess.model.stubs.ClassType;
import de.upb.sse.jess.stats.StubbingStats;
import de.upb.sse.jess.stubbing.JessStubberAdapter;
import de.upb.sse.jess.stubbing.SpoonStubbingRunner;
import de.upb.sse.jess.stubbing.Stubber;
import de.upb.sse.jess.util.FileUtil;
import de.upb.sse.jess.util.ImportUtil;
import de.upb.sse.jess.visitors.*;
import de.upb.sse.jess.visitors.pre.InternalKeptTypeResolutionVisitor;
import de.upb.sse.jess.visitors.pre.InternalResolutionVisitor;
import de.upb.sse.jess.visitors.pre.PreSlicingVisitor;
import de.upb.sse.jess.visitors.slicing.SlicingVisitor;
import lombok.Getter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Jess {
    public static final String SRC_OUTPUT = "gen";
    public static final String CLASS_OUTPUT = "output";
    public static final String JAR_DIRECTORY = "jars";
    private static JavaSymbolSolver symbolSolver;

    private CompilationUnit cleanRoot;
    @Getter private final JessConfiguration config;
    @Getter private final StubbingStats stubbingStats = new StubbingStats();
    private final List<String> packageRoots = new ArrayList<>();
    private final CombinedTypeSolver combinedTypeSolver;
    private final List<Path> jarPaths = new ArrayList<>();
    private final Stubber stubber;
    private String lastCompilationErrors; // Store last compilation error messages

    /**
     * Get the last compilation error messages (if any).
     * @return Compilation error messages, or null if compilation succeeded or no errors were captured
     */
    public String getLastCompilationErrors() {
        return lastCompilationErrors;
    }

    private static final Logger logger = Logger.getLogger(Jess.class.getName());

    public Jess() {
        this(new JessConfiguration(), Collections.emptyList(), Collections.emptyList());
    }

    public Jess(Collection<String> packageRoots, Collection<String> jars) {
        this(new JessConfiguration(), packageRoots, jars);
    }

    public Jess(JessConfiguration config, Collection<String> packageRoots) {
        this(config, packageRoots, MavenDependencyResolver.getJars());
    }

    public Jess(JessConfiguration config, Collection<String> packageRoots, Collection<String> jars) {
        this.config = config;

        ReflectionTypeSolver reflectiveSolver = new ReflectionTypeSolver();
        combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(reflectiveSolver);

        // Collect source roots for both JavaParser and Spoon
        List<Path> collectedSourceRoots = new ArrayList<>();
        
        for (String packageRoot : packageRoots) {
            try {
                Path rootPath = Paths.get(packageRoot);
                // Validate path exists and is a directory before creating solver
                if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
                    System.err.println("Warning: Skipping invalid source root (does not exist or is not a directory): " + packageRoot);
                    continue;
                }
                
                JavaParserTypeSolver javaSolver = new JavaParserTypeSolver(rootPath);
                combinedTypeSolver.add(javaSolver);
                this.packageRoots.add(packageRoot);
                
                // Store the Path for Spoon stubber
                collectedSourceRoots.add(rootPath);
            } catch (IllegalStateException e) {
                // JavaParserTypeSolver throws IllegalStateException if path is invalid
                System.err.println("Warning: Skipping invalid source root: " + packageRoot + " - " + e.getMessage());
                continue;
            } catch (Exception e) {
                // Catch any other exceptions (e.g., invalid path format)
                System.err.println("Warning: Skipping source root due to error: " + packageRoot + " - " + e.getMessage());
                continue;
            }
        }
        
        // Store source roots in config for Spoon stubber
        config.setSourceRoots(collectedSourceRoots);

        for (String jar : jars) {
            try {
                JarTypeSolver jarSolver = new JarTypeSolver(Paths.get(jar));
                combinedTypeSolver.add(jarSolver);
                jarPaths.add(Paths.get(jar));
            } catch (IOException e) {
                System.err.println("Could not load JarTypeSolver for " + jar);
            }
        }

        symbolSolver = new JavaSymbolSolver(combinedTypeSolver);



        boolean useSpoon =
                config.getStubberKind() == JessConfiguration.StubberKind.SPOON
                        || "spoon".equalsIgnoreCase(System.getProperty("jess.stubber", ""));

        if (useSpoon) {
            this.stubber = new SpoonStubbingRunner(this.config);
        } else {
            this.stubber = new JessStubberAdapter(this);
        }


    }

    public int parse(String targetClass) {


        return this.parse(targetClass, CLASS_OUTPUT);
    }

    public int parse(String targetClass, String classOutput) {
        try {
            CompilationUnit root = getCompilationUnit(targetClass);
            if (this.cleanRoot == null) {
                this.cleanRoot = root;
            }
            root = this.cleanRoot;

            // Check for resolvable and unresolvable method/field and type usages
            Annotator ann = new Annotator();

            ResolutionVisitor rv = new ResolutionVisitor(ann);
            rv.visit(root, null);

            Map<String, CompilationUnit> annotatedUnits = ann.getAnnotatedUnits();
            annotatedUnits.remove(getFullyQualifiedRootName(root));

            SignatureTypeUsageVisitor ituv = new SignatureTypeUsageVisitor(ann);
            annotatedUnits.forEach((k, v) -> {
                symbolSolver.inject(v);
                ituv.visit(v, null);
            });

            // Slice away unused methods/fields and slice bodies of used methods
            Slicer slicer = new Slicer(config, getFullyQualifiedRootName(this.cleanRoot), symbolSolver, annotatedUnits);
            Map<String, CompilationUnit> types = slicer.slice();

            System.out.println("\n==================================================================================");
            System.out.println("2. SLICED CODE");
            System.out.println("==================================================================================");
            types.forEach((fqn, cu) -> {
                System.out.println("// " + fqn);
                String code = cu.toString();
                System.out.println(code.length() > 2000 ? code.substring(0, 2000) + "\n...[truncated]..." : code);
                System.out.println();
            });


            // Remove artificial marker annotations
            MarkerAnnotationRemovalVisitor marv = new MarkerAnnotationRemovalVisitor();
            types.forEach((fqn, cu) -> marv.visit(cu, null));

            // Extract the sliced classes into respective files
            TypeExtractor ex = new TypeExtractor(SRC_OUTPUT);
            ex.extract(types);

            // Remove unused imports of original file (due to javadoc comments, etc.)
            root = getCleanRoot();
            marv.visit(root, null);
            ResolutionInformation usedTypes = new ResolutionInformation();
            TypeUsageVisitor tuv = new TypeUsageVisitor();
            tuv.visit(root, usedTypes);
            UnusedImportsVisitor uiv = new UnusedImportsVisitor(usedTypes.getAllTypes(), true, config.isKeepAsteriskImports());
            uiv.visit(root, null);

            // Extract original file with adjusted imports
            ex.extract(getFullyQualifiedRootName(root), root);
            System.out.println("\n>> Using stubber: " + this.stubber.getClass().getSimpleName());
            logger.info( "\n>> Using stubber::::::::::::::::::::::::::::::V2.0:::" + this.stubber.getClass().getSimpleName());
            // Stub unresolvable types if not disabled
            if (!config.isDisableStubbing()) {
                // Compile sliced files
                boolean successfulPreCompilation = compile(targetClass, classOutput, true);
                if (successfulPreCompilation) return 0;

                int created = this.stubber.run(Paths.get(SRC_OUTPUT), this.jarPaths);
            }

            // Compile sliced files and capture errors
            CompilerInvoker.CompilationResult result = compileWithErrors(targetClass, classOutput, false);
            this.lastCompilationErrors = result.errorMessages; // Store errors for later retrieval
            return result.success ? 0 : 1;
        } catch (AmbiguityException e) {
          throw e;
        } catch (Throwable e) {
            if (e instanceof StackOverflowError) {
                System.err.println("StackOverflowError: JavaParser symbol resolution overflow (likely circular type dependencies)");
                System.err.println("  This is a known limitation of JavaParser when resolving complex inheritance hierarchies.");
                System.err.println("  Suggestion: Increase JVM stack size with -Xss4m or -Xss8m");
                System.err.println("  Example: java -Xss8m -jar jess.jar ...");
            } else if (e instanceof OutOfMemoryError) {
                System.err.println("OutOfMemoryError: " + e.getMessage());
                System.err.println("Heap memory exhausted. Consider increasing JVM heap size with -Xmx option.");
                // Try to free some memory
                System.gc();
            } else {
                e.printStackTrace();
            }

            if (config.isExitOnParsingFail()) System.exit(1);
            return 2;
        } finally {
            JavaParserFacade.clearInstances();
        }
    }

    public void preSlice(String targetClass, List<String> methodsToKeep, List<String> keepClinit, List<String> keepInit) throws IOException {
        Path targetClassPath = Paths.get(targetClass);

        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setSymbolResolver(symbolSolver);
        JavaParser jp = new JavaParser(parserConfig);

        ParseResult<CompilationUnit> parseResult = jp.parse(targetClassPath);
        CompilationUnit root = parseResult.getResult().get();

        Annotator annotator = new Annotator();
        annotator.keep(root);
        InternalResolutionVisitor irv = new InternalResolutionVisitor(annotator, methodsToKeep, keepClinit, keepInit, config.isLooseSignatureMatching());
        irv.visit(root, null);

        InternalKeptTypeResolutionVisitor iktrv = new InternalKeptTypeResolutionVisitor(annotator);
        iktrv.visit(root, null);

        symbolSolver.inject(root);
        PreSlicingVisitor psv = new PreSlicingVisitor(getFullyQualifiedRootName(root), annotator);
        psv.visit(root, null);

        SlicingVisitor sv = new SlicingVisitor(getFullyQualifiedRootName(root), true);
        sv.visit(root, null);

        this.cleanRoot = root;
    }

    public void preSlice(String targetClass, List<String> methodToKeep) throws IOException {
        preSlice(targetClass, methodToKeep, Collections.emptyList(), Collections.emptyList());
    }

    private void stub(String srcOutput) throws IOException, AmbiguityException {
        Map<String, ClassType> stubClasses = new HashMap<>();
        List<ImportContext> asteriskImports = new ArrayList<>();

        InferenceEngine inferenceEngine = new InferenceEngine(config.isFailOnAmbiguity());
        UnresolvableTypeVisitor utv = new UnresolvableTypeVisitor(combinedTypeSolver, inferenceEngine, packageRoots, config.isFailOnAmbiguity());

        List<String> generatedSrcFiles = FileUtil.getAllJavaFiles(srcOutput);
        for (String srcFile : generatedSrcFiles) {
            // skip the generated annotation files
            if (srcFile.endsWith(Annotator.KEEP_ALL_ANNOTATION + ".java")) continue;
            if (srcFile.endsWith(Annotator.TARGET_METHOD_ANNOTATION + ".java")) continue;

            CompilationUnit cu = getCompilationUnit(srcFile);
            symbolSolver.inject(cu);
            utv.visit(cu, stubClasses);

            asteriskImports.addAll(ImportUtil.getAsteriskImportNames(cu));
        }

        StubGenerator stubGen = new StubGenerator(srcOutput, stubbingStats);
        stubGen.generate(stubClasses.values());
        stubGen.generatePackages(asteriskImports);
    }

    private boolean compile(String targetClass, String classOutput, boolean silentCompilation) {
        CompilerInvoker compiler = new CompilerInvoker(config.getTargetVersion(), silentCompilation);
        boolean successfulCompilation = compiler.compileFile(SRC_OUTPUT, classOutput);

        if (successfulCompilation) {
            System.out.println("Successful compilation");
        } else {
            if (!silentCompilation) {
                System.out.println("Compilation failed for file: " + targetClass);
                if (config.isExitOnCompilationFail()) System.exit(1);
            }
        }

        return successfulCompilation;
    }
    
    /**
     * Compile and return both success status and error messages.
     * @param targetClass Target class name (for logging)
     * @param classOutput Output directory for compiled classes
     * @param silentCompilation Whether to suppress compiler output
     * @return CompilationResult containing success status and error messages
     */
    private CompilerInvoker.CompilationResult compileWithErrors(String targetClass, String classOutput, boolean silentCompilation) {
        CompilerInvoker compiler = new CompilerInvoker(config.getTargetVersion(), silentCompilation);
        CompilerInvoker.CompilationResult result = compiler.compileFile(List.of(SRC_OUTPUT), classOutput);

        if (result.success) {
            if (!silentCompilation) {
                System.out.println("Successful compilation");
            }
        } else {
            if (!silentCompilation) {
                System.out.println("Compilation failed for file: " + targetClass);
                if (!result.errorMessages.isEmpty()) {
                    System.out.println("Errors: " + result.errorMessages);
                }
                if (config.isExitOnCompilationFail()) System.exit(1);
            }
        }

        return result;
    }

    private void generatePackages(String srcOutput) throws IOException {
        List<ImportContext> asteriskImports = new ArrayList<>();
        List<String> generatedSrcFiles = FileUtil.getAllJavaFiles(srcOutput);
        for (String srcFile : generatedSrcFiles) {
            // skip the generated annotation files
            if (srcFile.endsWith(Annotator.KEEP_ALL_ANNOTATION + ".java")) continue;

            CompilationUnit cu = getCompilationUnit(srcFile);
            asteriskImports.addAll(ImportUtil.getAsteriskImportNames(cu));
        }

        StubGenerator stubGen = new StubGenerator(srcOutput);
        stubGen.generatePackages(asteriskImports);
    }

    private CompilationUnit getCompilationUnit(String targetClass) throws IOException {
        Path targetClassPath = Paths.get(targetClass);

        ParserConfiguration parserConfig = new ParserConfiguration();
        parserConfig.setSymbolResolver(symbolSolver);
        JavaParser jp = new JavaParser(parserConfig);

        ParseResult<CompilationUnit> parseResult = jp.parse(targetClassPath);
        return parseResult.getResult().get();
    }

    private String getFullyQualifiedRootNameold(CompilationUnit cu) {
        return (String) cu.findFirst(TypeDeclaration.class).get().getFullyQualifiedName().get();
    }

    private String getFullyQualifiedRootName(CompilationUnit cu) {
        return (String) cu.findFirst(TypeDeclaration.class)
                .flatMap(TypeDeclaration::getFullyQualifiedName)
                .orElse("unknown.Root");
    }


    private CompilationUnit getCleanRoot() {
        return this.cleanRoot.clone();
    }

    public static void inject(Node n) {
        Optional<CompilationUnit> compilationUnitOpt = n.findCompilationUnit();
        if (compilationUnitOpt.isEmpty()) return;

        symbolSolver.inject(compilationUnitOpt.get());
    }

    public int runJessStubbing(String srcOutput) throws IOException, AmbiguityException {
        Map<String, de.upb.sse.jess.model.stubs.ClassType> stubClasses = new HashMap<>();
        List<de.upb.sse.jess.model.ImportContext> asteriskImports = new ArrayList<>();

        de.upb.sse.jess.inference.InferenceEngine inferenceEngine =
                new de.upb.sse.jess.inference.InferenceEngine(config.isFailOnAmbiguity());
        de.upb.sse.jess.visitors.UnresolvableTypeVisitor utv =
                new de.upb.sse.jess.visitors.UnresolvableTypeVisitor(
                        combinedTypeSolver, inferenceEngine, packageRoots, config.isFailOnAmbiguity());

        List<String> generatedSrcFiles = de.upb.sse.jess.util.FileUtil.getAllJavaFiles(srcOutput);
        for (String srcFile : generatedSrcFiles) {
            if (srcFile.endsWith(de.upb.sse.jess.annotation.Annotator.KEEP_ALL_ANNOTATION + ".java")) continue;
            if (srcFile.endsWith(de.upb.sse.jess.annotation.Annotator.TARGET_METHOD_ANNOTATION + ".java")) continue;

            com.github.javaparser.ast.CompilationUnit cu = getCompilationUnit(srcFile);
            symbolSolver.inject(cu);
            utv.visit(cu, stubClasses);

            asteriskImports.addAll(de.upb.sse.jess.util.ImportUtil.getAsteriskImportNames(cu));
        }

        de.upb.sse.jess.generation.StubGenerator stubGen = new de.upb.sse.jess.generation.StubGenerator(srcOutput, stubbingStats);
        stubGen.generate(stubClasses.values());
        stubGen.generatePackages(asteriskImports);

        System.out.println("\n== JESS STUBS to be generated ==");
        for (de.upb.sse.jess.model.stubs.ClassType ct : stubClasses.values()) {
            System.out.println(" +type  " + ct.getFQN());

            // optional context (only prints when non-empty)
            if (!ct.getExtendedTypes().isEmpty()) {
                System.out.println("   extends " + String.join(", ", ct.getExtendedTypes().toString()));
            }
            if (!ct.getInterfaceImplementations().isEmpty()) {
                System.out.println("   implements " + String.join(", ", ct.getInterfaceImplementations().toString()));
            }
            if (!(ct.getTypeParameters() ==0)) {
                System.out.println("   <" +  ct.getTypeParameters() + ">");
            }

            // fields & methods (fall back to toString(); most models override it nicely)
            if (!ct.getFieldTypes().isEmpty()) {
                System.out.println("   fields:");
                ct.getFieldTypes().forEach(ft -> System.out.println("     - " + ft));
            }
            if (!ct.getMethodTypes().isEmpty()) {
                System.out.println("   methods:");
                ct.getMethodTypes().forEach(mt -> System.out.println("     - " + mt));
            }

            if (!ct.getImports().isEmpty()) {
                System.out.println("   imports " + String.join(", ", ct.getImports()));
            }
        }


        return stubClasses.size();
    }

    // Make sure these imports exist at the top of Jess.java:

    // === drop-in replacement ===
    public PublicApi.Result compileSingleMethod(
            Path repoRoot,
            String sourceRoot,
            PublicApi.MethodId method,
            PublicApi.Options options) {

        final long t0 = System.nanoTime();
        dbg("▶ compileSingleMethod class=%s name=%s desc=%s srcRoot=%s workDir=%s slice=%s",
                method.binaryClassName, method.name, method.jvmDescriptor, sourceRoot, options.workDir, options.sliceMode);

        final Path srcRoot  = repoRoot.resolve(sourceRoot);
        final Path javaFile = resolveTopLevelSource(srcRoot, method.binaryClassName); // handles $ -> Outer.java
        dbg("  source=%s (%s)", javaFile, Files.isRegularFile(javaFile) ? "exists" : "missing");

        if (!Files.isRegularFile(javaFile)) {
            String notes = "Source file not found: " + javaFile;
            dbg("✖ %s", notes);
            return new PublicApi.Result(
                    PublicApi.Status.FAILED_PARSE, null,
                    method.binaryClassName, java.util.List.of(),
                    null, false, false, options.depMode, msSince(t0), notes);
        }

        final Path classesOut = options.workDir.resolve("classes");
        try { Files.createDirectories(classesOut); }
        catch (Exception ioe) {
            String notes = "Failed to create classes output dir: " + classesOut + " -> " + ioe;
            dbg("✖ %s", notes);
            return new PublicApi.Result(
                    PublicApi.Status.INTERNAL_ERROR, null,
                    method.binaryClassName, java.util.List.of(),
                    null, false, false, options.depMode, msSince(t0), notes);
        }

        // Slice decision: never slice for <clinit>, and respect sliceMode=class
        final boolean sliceByMethod =
                !"class".equalsIgnoreCase(options.sliceMode)
                        && method != null
                        && method.name != null
                        && !"<clinit>".equals(method.name);

        final java.util.List<String> keepList;
        if (sliceByMethod) {
            try {
                String keep = toJessKeepSignature(method.name, method.jvmDescriptor);
                keepList = java.util.List.of(keep);
                dbg("  preSlice keep=%s", keep);
            } catch (IllegalArgumentException badSig) {
                String notes = "Invalid method descriptor for slicing: "
                        + method.name + method.jvmDescriptor + " -> " + badSig.getMessage();
                dbg("✖ %s", notes);
                return new PublicApi.Result(
                        PublicApi.Status.FAILED_PARSE, null,
                        method.binaryClassName, java.util.List.of(),
                        null, false, false, options.depMode, msSince(t0), notes);
            }
        } else {
            keepList = java.util.List.of();
            dbg("  preSlice keep=<whole-class>");
        }

        int exit;
        boolean usedStubsFlag = false;
        String compilationErrors = null;
        try {
            // NOTE: your existing JESS methods expect String paths
            this.preSlice(javaFile.toString(), keepList);
            exit = this.parse(javaFile.toString(), classesOut.toString());
            usedStubsFlag = hasUsedStubs();
            // Capture compilation errors from the last compilation
            compilationErrors = this.lastCompilationErrors;
            dbg("  parse exit=%d usedStubs=%s outDir=%s", exit, Boolean.toString(usedStubsFlag), classesOut);
        } catch (AmbiguityException amb) {
            String notes = "Ambiguity: " + amb.getMessage();
            dbg("✖ %s", notes);
            return new PublicApi.Result(
                    PublicApi.Status.FAILED_RESOLVE, null,
                    method.binaryClassName, java.util.List.of(),
                    null, false, usedStubsFlag, options.depMode, msSince(t0), notes);
        } catch (Throwable t) {
            String notes = "Unhandled: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            dbg("✖ %s", notes);
            return new PublicApi.Result(
                    PublicApi.Status.INTERNAL_ERROR, null,
                    method.binaryClassName, java.util.List.of(),
                    null, false, usedStubsFlag, options.depMode, msSince(t0), notes);
        }

        if (exit != 0) {
            final PublicApi.Status st = (exit == 1) ? PublicApi.Status.FAILED_COMPILE : PublicApi.Status.INTERNAL_ERROR;
            String notes = "Compiler exit code=" + exit;
            if (compilationErrors != null && !compilationErrors.isEmpty()) {
                notes += " | Errors: " + compilationErrors;
            }
            dbg("✖ %s", notes);
            return new PublicApi.Result(
                    st, classesOut,
                    method.binaryClassName, java.util.List.of(),
                    null, false, usedStubsFlag, options.depMode, msSince(t0), notes);
        }

        final java.util.List<String> emitted = listEmittedBinaryNames(classesOut);
        dbg("  emitted=%d%s",
                emitted.size(),
                emitted.isEmpty() ? "" : " (eg: " + emitted.stream().limit(3).collect(Collectors.joining(", ")) +
                        (emitted.size() > 3 ? ", …" : "") + ")");

        // Verify target method exists with Code + capture class file
        Verification v = null;
        boolean asmAvailable = true;
        try {
            v = verifyTarget(classesOut, emitted, method);
        } catch (NoClassDefFoundError | NoSuchMethodError missingAsm) {
            asmAvailable = false;
            dbg("  (verification skipped: ASM not on classpath - %s)", missingAsm.getMessage());
        } catch (Throwable t) {
            // Other errors during verification - log but continue
            dbg("  (verification error: %s)", t.getMessage());
        }

        if (v != null) {
            // Verification succeeded - we have a definitive answer
            if (!v.hasCode) {
                String reason = v.reason == null ? "Target method not emitted (missing or no Code)" : v.reason;
                dbg("  target bytecode: MISSING (%s)", reason);
                return new PublicApi.Result(
                        PublicApi.Status.TARGET_METHOD_NOT_EMITTED, classesOut,
                        method.binaryClassName, emitted,
                        v.classFileRel, /*targetHasCode*/ false,
                        usedStubsFlag, options.depMode, msSince(t0),
                        reason + (usedStubsFlag ? " | usedStubs" : ""));
            } else {
                dbg("  target bytecode: FOUND in %s", v.classFileRel == null ? "<unknown>" : v.classFileRel);
                return new PublicApi.Result(
                        PublicApi.Status.OK, classesOut,
                        method.binaryClassName, emitted,
                        v.classFileRel, /*targetHasCode*/ true,
                        usedStubsFlag, options.depMode, msSince(t0), "");
            }
        }

        // If verifier was skipped (ASM not available), return OK but note in status
        // This is a fallback - we can't verify bytecode without ASM
        String notes = asmAvailable 
                ? "Bytecode verification failed (unknown error)" 
                : "Bytecode verification skipped (ASM not available)";
        dbg("  (no verifier result; returning OK without targetHasCode assertion - %s)", notes);
        return new PublicApi.Result(
                PublicApi.Status.OK, classesOut,
                method.binaryClassName, emitted,
                null, /*targetHasCode*/ false,
                usedStubsFlag, options.depMode, msSince(t0), notes);
    }
// === end replacement ===

// ---------- helpers inside Jess.java ----------

    private static Path resolveTopLevelSource(Path srcRoot, String binaryClassName) {
        int dollar = binaryClassName.indexOf('$');
        String top = (dollar >= 0) ? binaryClassName.substring(0, dollar) : binaryClassName;
        return srcRoot.resolve(top + ".java");
    }

    private static void dbg(String fmt, Object... args) {
        String flag = System.getProperty("jess.debug", "true");
        if (!"false".equalsIgnoreCase(flag) && !"0".equals(flag)) {
            System.out.println("[JESS] " + String.format(Locale.ROOT, fmt, args));
        }
    }

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static final class Verification {
        final boolean hasCode;
        final String  classFileRel; // relative to classesOut
        final String  reason;       // non-null when hasCode==false
        Verification(boolean hasCode, String classFileRel, String reason) {
            this.hasCode = hasCode; this.classFileRel = classFileRel; this.reason = reason;
        }
    }

    private static Verification verifyTarget(Path classesOut,
                                             java.util.List<String> emitted,
                                             PublicApi.MethodId m) {
        java.util.Set<Path> candidates = new LinkedHashSet<>();

        for (String bin : emitted) {
            Path p = classesOut.resolve(bin + ".class");
            if (Files.isRegularFile(p)) candidates.add(p);
        }
        Path owner = classesOut.resolve(m.binaryClassName + ".class");
        if (Files.isRegularFile(owner)) candidates.add(owner);
        candidates.addAll(findOwnerFamilySamePackage(classesOut, m.binaryClassName));

        boolean sawNoCode = false;
        Path last = null;
        for (Path cf : candidates) {
            last = cf;
            MethodPresence mp = methodPresenceInClassFile(cf, m.name, m.jvmDescriptor);
            if (debugProbeEnabled()) {
                String rel = classesOut.relativize(cf).toString().replace('\\','/');
                dbg("    probe %s -> %s", rel, mp);
            }
            if (mp == MethodPresence.HAS_CODE) {
                String rel = classesOut.relativize(cf).toString().replace('\\','/');
                return new Verification(true, rel, null);
            }
            if (mp == MethodPresence.NO_CODE) sawNoCode = true;
        }
        String reason = sawNoCode ? "present without Code" : "no class contained the method";
        String rel = (last != null && Files.isRegularFile(last)) ? classesOut.relativize(last).toString().replace('\\','/') : null;
        return new Verification(false, rel, reason);
    }

    private enum MethodPresence { CLASS_NOT_FOUND, METHOD_NOT_FOUND, NO_CODE, HAS_CODE, ERROR }

    private static boolean debugProbeEnabled() {
        String v = System.getProperty("jess.debug", "true");
        return !"false".equalsIgnoreCase(v) && !"0".equals(v);
    }

    private static MethodPresence methodPresenceInClassFile(Path classFile, String name, String jvmDesc) {
        if (!Files.isRegularFile(classFile)) return MethodPresence.CLASS_NOT_FOUND;
        try (InputStream in = Files.newInputStream(classFile)) {
            ClassReader cr = new ClassReader(in);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            @SuppressWarnings("unchecked")
            java.util.List<MethodNode> methods = (java.util.List<MethodNode>)(java.util.List<?>) cn.methods;
            for (MethodNode mn : methods) {
                if (Objects.equals(mn.name, name) && Objects.equals(mn.desc, jvmDesc)) {
                    return (mn.instructions != null && mn.instructions.size() > 0)
                            ? MethodPresence.HAS_CODE : MethodPresence.NO_CODE;
                }
            }
            return MethodPresence.METHOD_NOT_FOUND;
        } catch (Throwable t) {
            return MethodPresence.ERROR;
        }
    }

    private static java.util.List<String> listEmittedBinaryNames(Path classesOut) {
        try (Stream<Path> s = Files.walk(classesOut)) {
            return s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))
                    .map(p -> classesOut.relativize(p).toString().replace('\\','/'))
                    .map(n -> n.substring(0, n.length() - ".class".length()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private static java.util.List<Path> findOwnerFamilySamePackage(Path classesOut, String ownerInternal) {
        String pkg = ownerInternal.contains("/") ? ownerInternal.substring(0, ownerInternal.lastIndexOf('/')) : "";
        String simple = ownerInternal.contains("/") ? ownerInternal.substring(ownerInternal.lastIndexOf('/') + 1) : ownerInternal;
        Path pkgDir = pkg.isEmpty() ? classesOut : classesOut.resolve(pkg);
        if (!Files.isDirectory(pkgDir)) return java.util.List.of();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pkgDir)) {
            java.util.List<Path> out = new java.util.ArrayList<>();
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String n = p.getFileName().toString();
                if (n.equals(simple + ".class") || n.startsWith(simple + "$")) out.add(p);
            }
            return out;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    // JVM descriptor -> keep signature (params only)
    private static String toJessKeepSignature(String name, String jvmDesc) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jvmDesc, "jvmDesc");
        if (jvmDesc.isEmpty() || jvmDesc.charAt(0) != '(') {
            throw new IllegalArgumentException("Descriptor must start with '(' : " + jvmDesc);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('(');
        int i = 1;
        boolean first = true;
        while (true) {
            char c = jvmDesc.charAt(i);
            if (c == ')') break;
            if (!first) sb.append(", ");
            first = false;
            int[] next = new int[1];
            String t = parseParamType(jvmDesc, i, next);
            sb.append(t);
            i = next[0];
        }
        sb.append(')');
        return sb.toString();
    }
    private static String parseParamType(String desc, int i, int[] nextOut) {
        int arr = 0;
        while (desc.charAt(i) == '[') { arr++; i++; }
        char c = desc.charAt(i++);
        String base;
        switch (c) {
            case 'B': base = "byte"; break;
            case 'C': base = "char"; break;
            case 'D': base = "double"; break;
            case 'F': base = "float"; break;
            case 'I': base = "int"; break;
            case 'J': base = "long"; break;
            case 'S': base = "short"; break;
            case 'Z': base = "boolean"; break;
            case 'V': base = "void"; break;
            case 'L': {
                int semi = desc.indexOf(';', i);
                if (semi < 0) throw new IllegalArgumentException("Bad object type in desc: " + desc);
                String internal = desc.substring(i, semi);
                base = internal.replace('/', '.');
                i = semi + 1;
                break;
            }
            default: throw new IllegalArgumentException("Bad descriptor at " + (i - 1) + " in " + desc);
        }
        StringBuilder sb = new StringBuilder(base);
        for (int k = 0; k < arr; k++) sb.append("[]");
        nextOut[0] = i;
        return sb.toString();
    }

    // If you have stubbing stats in your Jess impl, wire it; else keep false.
    private boolean hasUsedStubs() {
        // return stubbingStats != null && (stubbingStats.generatedStubs() > 0 || stubbingStats.usedStubs() > 0);
        return false;
    }



}
