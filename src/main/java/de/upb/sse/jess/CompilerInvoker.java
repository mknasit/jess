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
        // keep static field in sync
        CompilerInvoker.output = output;

        List<String> filesToCompile = new ArrayList<>();
        for (String fileString : fileStrings) {
            filesToCompile.addAll(getFileNames(new ArrayList<>(), Path.of(fileString)));
        }

        String classPath = "." + (FileUtil.isWindows() ? ";" : ":") +
                JarFinder.find(Jess.JAR_DIRECTORY).stream()
                        .collect(Collectors.joining(FileUtil.isWindows() ? ";" : ":"));

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
        
        // Collect error messages
        StringBuilder errorMessages = new StringBuilder();
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
            }
        }
        
        try {
            fileManager.close();
        } catch (IOException e) {
            // Ignore
        }
        
        return new CompilationResult(success, errorMessages.toString());
    }
    
    /**
     * Result of a compilation attempt, including success status and error messages.
     */
    public static class CompilationResult {
        public final boolean success;
        public final String errorMessages;
        
        public CompilationResult(boolean success, String errorMessages) {
            this.success = success;
            this.errorMessages = errorMessages != null ? errorMessages : "";
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
