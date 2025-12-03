package de.upb.sse.jess.tool;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import de.upb.sse.jess.Jess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class TargetMethodTest {
    private static Jess jess;

    @BeforeEach
    void setupTests() {
        jess = new Jess();
        jess.getConfig().setFailOnAmbiguity(false);
    }

    @Test
    @DisplayName("Run target method")
    void testWFCMessageGetDeviceTokenBytes() throws IOException {
        // File path
        String classPath = "/Users/mitul/Documents/study/Thesis/partial compilation/jess/src/test/resources/project/server/broker/src/main/java/win/liyufan/im/HttpUtils.java";
        String methodSignature = "httpJsonPost(String, String, HttpPostType)";


        System.out.println("\n==================================================================================");
        System.out.println("1. SOURCE CODE OF METHOD (What JavaParser Got)");
        System.out.println("==================================================================================");
        
        // Extract and print the method source code
        printMethodSourceCode(classPath, methodSignature);
        
        // Create new Jess instance
        Jess jess = new Jess();
        jess.getConfig().setFailOnAmbiguity(false);
        
        System.out.println("\n==================================================================================");
        System.out.println("2. PRE-SLICE (Keep Only Target Method)");
        System.out.println("==================================================================================");
        System.out.println("Pre-slicing to keep only method: " + methodSignature);
        
        // Step 2: Pre-slice to keep only the target method
        jess.preSlice(classPath, Collections.singletonList(methodSignature));
        System.out.println("✓ Pre-slice completed");
        
        // Step 3: Parse and compile (this will print "2. SLICED CODE" internally, try pre-compilation, and stub if needed)
        // Check gen directory state before parsing to detect if stubbing occurred
        File genDirBefore = new File("gen");
        long genDirLastModifiedBefore = genDirBefore.exists() ? genDirBefore.lastModified() : 0;
        int fileCountBefore = countJavaFilesInGen();
        
        // Parse will print "2. SLICED CODE" and handle stubbing internally
        int result = jess.parse(classPath);
        
        // Check if stubbing occurred by comparing gen directory state
        File genDirAfter = new File("gen");
        long genDirLastModifiedAfter = genDirAfter.exists() ? genDirAfter.lastModified() : 0;
        int fileCountAfter = countJavaFilesInGen();
        boolean stubsWereGenerated = (genDirAfter.exists() && 
                                     (fileCountAfter > fileCountBefore || 
                                      genDirLastModifiedAfter > genDirLastModifiedBefore));
        
        // If compilation succeeded and no new stubs were generated, it means pre-compilation succeeded
        if (result == 0 && !stubsWereGenerated && fileCountBefore == fileCountAfter) {
            System.out.println("\n==================================================================================");
            System.out.println("3. COMPILATION RESULT");
            System.out.println("==================================================================================");
            System.out.println("✓ Compilation successful after slicing - no stubbing needed!");
            System.out.println("  • All dependencies were resolved from the codebase");
            System.out.println("  • Method compiled successfully without requiring stubs");
            
            System.out.println("\n==================================================================================");
            System.out.println("SUMMARY");
            System.out.println("==================================================================================");
            System.out.println("Result Code: 0 (Success)");
            System.out.println("  ✓ Method compiled successfully without stubs");
            System.out.println("==================================================================================\n");
            return;
        }
        
        // If we get here, either compilation failed or stubbing was needed
        if (stubsWereGenerated) {
            System.out.println("\n==================================================================================");
            System.out.println("3. MISSING ELEMENTS DETECTED (Before Stubbing)");
            System.out.println("==================================================================================");
            System.out.println("(Collection results showing missing types, fields, methods, and constructors");
            System.out.println(" were printed above by SpoonStubbingRunner in 'COLLECTION RESULTS' section)\n");
            
            System.out.println("\n==================================================================================");
            System.out.println("4. STUB PLANS GENERATED");
            System.out.println("==================================================================================");
            System.out.println("(Stub plans showing what will be created were printed above by SpoonStubbingRunner");
            System.out.println(" in 'COLLECTION RESULTS' section - these are the plans before stubbing)\n");
            
            System.out.println("\n==================================================================================");
            System.out.println("5. STUBBING PROCESS");
            System.out.println("==================================================================================");
            System.out.println("Generating stubs based on the plans above...");
            System.out.println("(Stubbing process details were printed above by SpoonStubbingRunner)\n");
            
            System.out.println("\n==================================================================================");
            System.out.println("6. WHAT WAS ACTUALLY STUBBED");
            System.out.println("==================================================================================");
            System.out.println("(Summary of created stubs was printed above by SpoonStubbingRunner in");
            System.out.println(" 'SPOON STUBS generated' section)\n");
            System.out.println("Generated stub files on disk:");
            
            // Print what was actually stubbed
            printStubbedFiles();
        } else {
            System.out.println("\n==================================================================================");
            System.out.println("3. STUBBING");
            System.out.println("==================================================================================");
            System.out.println("No stubs were generated (stubbing may have been disabled or not needed)");
        }
        
        System.out.println("\n==================================================================================");
        System.out.println(stubsWereGenerated ? "7. FINAL COMPILATION RESULT" : "4. FINAL COMPILATION RESULT");
        System.out.println("==================================================================================");
        System.out.println("Result Code: " + result);
        System.out.println("  0 = Success");
        System.out.println("  1 = Compilation Failed");
        System.out.println("  2 = Parsing Failed");
        
        if (result == 1) {
            System.out.println("\n✗ COMPILATION FAILED");
            System.out.println("--------------------------------------------------------------------------------");
            String errors = jess.getLastCompilationErrors();
            if (errors != null && !errors.isEmpty()) {
                System.out.println("COMPILATION ERRORS:");
                System.out.println("--------------------------------------------------------------------------------");
                // Format errors for better readability
                String[] errorLines = errors.split("\n");
                for (int i = 0; i < errorLines.length; i++) {
                    String line = errorLines[i];
                    // Highlight error lines
                    if (line.contains("error:") || line.contains("Error:") || line.contains("cannot find symbol") 
                        || line.contains("package") || line.contains("does not exist") 
                        || line.contains("actual and formal argument lists differ")
                        || line.contains("symbol:") || line.contains("location:")) {
                        System.out.println("  ✗ " + line);
                    } else if (line.trim().isEmpty()) {
                        System.out.println();
                    } else {
                        System.out.println("    " + line);
                    }
                }
                System.out.println("--------------------------------------------------------------------------------");
            } else {
                System.out.println("(No detailed error messages captured)");
                System.out.println("  • Check the gen/ directory for generated code");
                System.out.println("  • Try compiling manually: javac -cp <classpath> gen/**/*.java");
            }
        } else if (result == 0) {
            System.out.println("\n✓ COMPILATION SUCCESSFUL");
            System.out.println("--------------------------------------------------------------------------------");
            System.out.println("  • All code compiled without errors");
            if (stubsWereGenerated) {
                System.out.println("  • Stubs were generated and used successfully");
            } else {
                System.out.println("  • No stubs were needed - all dependencies resolved");
            }
        } else if (result == 2) {
            System.out.println("\n✗ PARSING FAILED");
            System.out.println("--------------------------------------------------------------------------------");
            System.out.println("  • Failed to parse the source file");
            System.out.println("  • Check if the file path is correct and the file is valid Java code");
        }
        
        System.out.println("\n==================================================================================");
        System.out.println("SUMMARY");
        System.out.println("==================================================================================");
        System.out.println("Result Code: " + result);
        if (result == 0) {
            System.out.println("  ✓ Method compiled successfully");
            if (stubsWereGenerated) {
                System.out.println("  ✓ Stubs were generated and used");
            }
        } else if (result == 1) {
            System.out.println("  ✗ Compilation failed");
            if (stubsWereGenerated) {
                System.out.println("  • Stubs were generated but compilation still failed");
                System.out.println("  • Check compilation errors above for missing dependencies");
            } else {
                System.out.println("  • No stubs were generated");
            }
        } else {
            System.out.println("  ✗ Parsing failed");
        }
        System.out.println("==================================================================================\n");
    }
    
    private void printMethodSourceCode(String classPath, String methodSignature) {
        try {
            Path path = Paths.get(classPath);
            JavaParser parser = new JavaParser(new ParserConfiguration());
            CompilationUnit cu = parser.parse(path).getResult().orElse(null);
            
            if (cu == null) {
                System.out.println("Could not parse file: " + classPath);
                return;
            }
            
            // Extract method name from signature (e.g., "getDeviceTokenBytes()" -> "getDeviceTokenBytes")
            String methodName = methodSignature.replace("()", "").split("\\(")[0];
            
            // Find the method in the compilation unit (prefer methods with body over interface declarations)
            List<MethodDeclaration> allMethods = cu.findAll(MethodDeclaration.class);
            Optional<MethodDeclaration> methodOpt = allMethods.stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .filter(m -> m.getBody().isPresent()) // Prefer methods with implementation
                .findFirst();
            
            // If no method with body found, try any method with that name
            if (!methodOpt.isPresent()) {
                methodOpt = allMethods.stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .findFirst();
            }
            
            if (methodOpt.isPresent()) {
                MethodDeclaration method = methodOpt.get();
                System.out.println("Method: " + method.getSignature());
                System.out.println("Location: " + method.getRange().map(r -> 
                    "Line " + r.begin.line + "-" + r.end.line).orElse("Unknown"));
                System.out.println("\nSource Code:");
                System.out.println(method.toString());
            } else {
                System.out.println("Method '" + methodName + "' not found in file.");
                System.out.println("Available methods (first 10):");
                allMethods.stream().limit(10).forEach(m -> 
                    System.out.println("  - " + m.getSignature()));
            }
        } catch (Exception e) {
            System.out.println("Error extracting method source: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void printStubbedFiles() {
        File genDir = new File("gen");
        if (!genDir.exists()) {
            System.out.println("No stubs generated (gen directory does not exist)");
            return;
        }
        
        List<String> javaFiles = de.upb.sse.jess.util.FileUtil.getAllJavaFiles("gen");
        System.out.println("Generated " + javaFiles.size() + " stub files:\n");
        
        for (String filePath : javaFiles) {
            try {
                Path path = Paths.get(filePath);
                String relativePath = path.toString().replace("gen/", "").replace("gen\\", "");
                String content = Files.readString(path);
                
                System.out.println("// " + relativePath);
                // Print first 500 chars or full content if shorter
                if (content.length() > 500) {
                    System.out.println(content.substring(0, 500) + "\n...[truncated]...");
                } else {
                    System.out.println(content);
                }
                System.out.println();
            } catch (IOException e) {
                System.out.println("Error reading file: " + filePath + " - " + e.getMessage());
            }
        }
    }
    
    /**
     * Count Java files in the gen directory.
     */
    private int countJavaFilesInGen() {
        File genDir = new File("gen");
        if (!genDir.exists()) {
            return 0;
        }
        try {
            List<String> javaFiles = de.upb.sse.jess.util.FileUtil.getAllJavaFiles("gen");
            return javaFiles.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
