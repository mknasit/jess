package de.upb.sse.jess.stubbing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
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

public class SmallTest {
    private static Jess jess;

    @BeforeEach
    void setupTests() {
        jess = new Jess();
        jess.getConfig().setFailOnAmbiguity(false);
    }

    @Test
    @DisplayName("Test WFCMessage.getDeviceTokenBytes() through whole Jess pipeline")
    void testWFCMessageGetDeviceTokenBytes() throws IOException {
        // File path
        String classPath = "/Users/mitul/Documents/study/Thesis/partial compilation/jess/src/test/resources/project/server/common/src/main/java/cn/wildfirechat/proto/WFCMessage.java";
        String methodSignature = "getDeviceTokenBytes()";
        
        System.out.println("\n==================================================================================");
        System.out.println("1. SOURCE CODE OF METHOD (What JavaParser Got)");
        System.out.println("==================================================================================");
        
        // Extract and print the method source code
        printMethodSourceCode(classPath, methodSignature);
        
        // Create new Jess instance
        Jess jess = new Jess();
        jess.getConfig().setFailOnAmbiguity(false);
        
        // Step 1: Pre-slice to keep only the target method
        jess.preSlice(classPath, Collections.singletonList(methodSignature));
        
        // Step 2: Parse and compile (stubs will be generated automatically)
        // This will print sliced code, missing elements, and stubbing plan
        int result = jess.parse(classPath);
        
        System.out.println("\n==================================================================================");
        System.out.println("5. WHAT WAS STUBBED");
        System.out.println("==================================================================================");
        
        // Print what was actually stubbed
        printStubbedFiles();
        
        System.out.println("\n==================================================================================");
        System.out.println("SUMMARY");
        System.out.println("==================================================================================");
        System.out.println("Result Code: " + result);
        System.out.println("  0 = Success");
        System.out.println("  1 = Compilation Failed");
        System.out.println("  2 = Parsing Failed");
        
        if (result == 1) {
            String errors = jess.getLastCompilationErrors();
            if (errors != null && !errors.isEmpty()) {
                System.out.println("\nCOMPILATION ERRORS:");
                System.out.println(errors);
            } else {
                System.out.println("\n(No detailed error messages captured)");
            }
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
}
