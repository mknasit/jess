package de.upb.sse.jess;

import de.upb.sse.jess.finder.JarFinder;
import de.upb.sse.jess.util.FileUtil;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


public class CompilerInvoker {
    private String targetVersion;
    private boolean silentCompilation;
    public static String output;

    public CompilerInvoker() { this(false); }
    public CompilerInvoker(boolean silentCompilation) { this(null, silentCompilation); }
    public CompilerInvoker(String targetVersion, boolean silentCompilation) {
        this.targetVersion = targetVersion;
        this.silentCompilation = silentCompilation;
    }

    public boolean compileFile(String fileString, String output) {
        // keep static field in sync (use the *existing* param name)
        CompilerInvoker.output = output;
        CompilationResult result = this.compileFile(List.of(fileString), output);
        return result.success;
    }

    /**
     * Compile files and return both success status and error messages.
     * @param fileStrings List of file/directory paths to compile
     * @param output Output directory for compiled classes
     * @return CompilationResult containing success status and error messages
     */
    public CompilationResult compileFile(List<String> fileStrings, String output) {
        return compileFile(fileStrings, output, null);
    }

    /**
     * Compile files with explicit classpath and return both success status and error messages.
     * @param fileStrings List of file/directory paths to compile
     * @param output Output directory for compiled classes
     * @param classpathJars List of JAR file paths for classpath (null = use default)
     * @return CompilationResult containing success status and error messages
     */
    public CompilationResult compileFile(List<String> fileStrings, String output, List<Path> classpathJars) {
        // keep static field in sync
        CompilerInvoker.output = output;

        List<String> filesToCompile = new ArrayList<>();
        for (String fileString : fileStrings) {
            filesToCompile.addAll(getFileNames(new ArrayList<>(), Path.of(fileString)));
        }

        // P0: Build classpath from provided jars, or fallback to default
        // CRITICAL: javac classpath must match Spoon classpath for experiment correctness
        String classPath;
        if (classpathJars != null && !classpathJars.isEmpty()) {
            // Use provided classpath jars (EXACTLY as passed to Spoon)
            classPath = "." + (FileUtil.isWindows() ? ";" : ":") +
                    classpathJars.stream()
                            .map(Path::toString)
                            .collect(Collectors.joining(FileUtil.isWindows() ? ";" : ":"));
        } else {
            // Fallback to default (for backward compatibility with old callers)
            classPath = "." + (FileUtil.isWindows() ? ";" : ":") +
                    JarFinder.find(Jess.JAR_DIRECTORY).stream()
                            .collect(Collectors.joining(FileUtil.isWindows() ? ";" : ":"));
        }

        // Build compiler options (without file names)
        List<String> options = new ArrayList<>();
        if (targetVersion != null && !targetVersion.equals("unknown")) {
            options.add("-source");
            options.add(this.targetVersion);
            options.add("-target");
            options.add(this.targetVersion);
        }
        options.add("-Xlint:-options");
        options.add("-cp");
        options.add(classPath);
        options.add("-d");
        options.add(output);
        
        JavaCompiler comp = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        
        StandardJavaFileManager fileManager = comp.getStandardFileManager(diagnostics, null, null);
        
        // Convert file paths to JavaFileObjects
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromStrings(filesToCompile);
        
        // Create a compilation task
        PrintWriter errorWriter = silentCompilation ? null : new PrintWriter(System.err, true);
        JavaCompiler.CompilationTask task = comp.getTask(
                errorWriter,  // Writer for compiler output (null = silent)
                fileManager,
                diagnostics,
                options, // compiler options (without file names)
                null,
                compilationUnits
        );
        
        boolean success = task.call();
        
        // Collect error messages and structured diagnostics
        StringBuilder errorMessages = new StringBuilder();
        List<DiagnosticInfo> diagnosticInfos = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                if (errorMessages.length() > 0) {
                    errorMessages.append("; ");
                }
                String message = diagnostic.getMessage(null);
                String source = diagnostic.getSource() != null ? diagnostic.getSource().getName() : "unknown";
                long line = diagnostic.getLineNumber();
                long column = diagnostic.getColumnNumber();
                errorMessages.append(String.format("%s:%d:%d: %s", source, line, column, message));
                
                // Store structured diagnostic info
                String code = diagnostic.getCode();
                diagnosticInfos.add(new DiagnosticInfo(
                    diagnostic.getKind(),
                    code != null ? code : "",
                    message != null ? message : "",
                    source,
                    line,
                    column
                ));
            }
        }
        
        try {
            fileManager.close();
        } catch (IOException e) {
            // Ignore
        }
        
        return new CompilationResult(success, errorMessages.toString(), diagnosticInfos);
    }
    
    /**
     * Result of a compilation attempt, including success status and error messages.
     */
    public static class CompilationResult {
        public final boolean success;
        public final String errorMessages;
        public final List<DiagnosticInfo> diagnostics;
        
        public CompilationResult(boolean success, String errorMessages) {
            this(success, errorMessages, new ArrayList<>());
        }
        
        public CompilationResult(boolean success, String errorMessages, List<DiagnosticInfo> diagnostics) {
            this.success = success;
            this.errorMessages = errorMessages != null ? errorMessages : "";
            this.diagnostics = diagnostics != null ? diagnostics : new ArrayList<>();
        }
    }
    
    /**
     * Structured diagnostic information extracted from JavaCompiler diagnostics.
     */
    public static class DiagnosticInfo {
        public final Diagnostic.Kind kind;
        public final String code;
        public final String message;
        public final String sourcePath;
        public final long line;
        public final long column;
        
        public DiagnosticInfo(Diagnostic.Kind kind, String code, String message, String sourcePath, long line, long column) {
            this.kind = kind;
            this.code = code != null ? code : "";
            this.message = message != null ? message : "";
            this.sourcePath = sourcePath != null ? sourcePath : "";
            this.line = line;
            this.column = column;
        }
    }

    private List<String> getFileNames(List<String> fileNames, Path dir) {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if(path.toFile().isDirectory()) {
                    getFileNames(fileNames, path);
                } else {
                    fileNames.add(path.toAbsolutePath().toString());
                }
            }
        } catch(IOException e) {
            e.printStackTrace();
        }
        return fileNames;
    }

    private void cleanUp(String output) {
        File outputFile = new File(output);
        if (!outputFile.exists()) return;
        FileUtil.deleteRecursively(outputFile);
    }
}
