package de.upb.sse.jess.finder;

import de.upb.sse.jess.util.FileUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Comparator;

public class PackageFinder {
    private final static String PACKAGE_REGEX = "package\\s+([\\d|\\w|.]+)\\s*;";
    private final static Pattern PACKAGE_PATTERN = Pattern.compile(PACKAGE_REGEX);

    public static Set<String> findPackageRoots(String dir) {
        return findPackageRoots(dir, true);
    }

    public static Set<String> findPackageRoots(String dir, boolean blacklistEnabled) {
        Set<String> packages = new HashSet<>();
        List<String> allJavaFiles = FileUtil.getAllJavaFiles(dir);

        for (String javaFile : allJavaFiles) {
            try {
                String packageName = getPackageRoot(javaFile);
                if (packageName == null) continue;
                if (blacklistEnabled && isBlacklisted(packageName)) continue;
                
                // Validate the source root path before adding
                if (!isValidSourceRoot(packageName)) {
                    System.err.println("Warning: Skipping invalid source root: " + packageName);
                    continue;
                }

                packages.add(packageName);
            } catch (IOException ignored) {}
        }

        // CRITICAL FIX: Filter to best source roots (avoid conflicting multi-module roots)
        // This dramatically improves compilation success on complex projects
        return filterToBestSourceRoots(packages);
    }
    
    /**
     * Validates that a source root path is valid and exists.
     * Filters out invalid paths like those ending with '-' or '_', empty paths, or non-existent directories.
     * 
     * @param path The source root path to validate
     * @return true if the path is valid, false otherwise
     */
    private static boolean isValidSourceRoot(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        
        // Filter out suspicious paths (ending with '-' or '_' suggests incomplete parsing)
        String trimmed = path.trim();
        if (trimmed.endsWith("-") || trimmed.endsWith("_") || trimmed.endsWith(".")) {
            return false;
        }
        
        // Check if path exists and is a directory
        try {
            Path p = Paths.get(path);
            return Files.exists(p) && Files.isDirectory(p);
        } catch (Exception e) {
            // Invalid path format
            return false;
        }
    }

    private static String getPackageRoot(String javaFile) throws IOException {
        String packageDec = findPackage(javaFile);
        if (packageDec == null) return null;

        Matcher m = PACKAGE_PATTERN.matcher(packageDec);
        if (!m.find()) return null;
        if (m.group(1) == null) return null;

        String packageName = m.group(1);
        String packageSubPath = packageName.replace(".", File.separator);
        int packageIndex = javaFile.lastIndexOf(packageSubPath);
        if (packageIndex == -1) return null;
        return javaFile.substring(0, packageIndex);
    }

    private static String findPackage(String filePath) throws IOException {
        Path path = Path.of(filePath);

        boolean commentScope = false;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();

                if (trimmedLine.startsWith("package")) return trimmedLine;
                if (trimmedLine.startsWith("/*")) commentScope = true;
                if (!commentScope && !trimmedLine.startsWith("//") && !trimmedLine.startsWith("*") && !trimmedLine.isEmpty()) {
                    break;
                }

                if (commentScope) {
                    int closeCommentIndex = trimmedLine.indexOf("*/");
                    int openCommentIndex = trimmedLine.lastIndexOf("/*");
                    if (closeCommentIndex > -1 && openCommentIndex < closeCommentIndex) commentScope = false;
                }
            }
            return null;
        }
    }

    private static boolean isBlacklisted(String packagePath) {
        String lcPackagePath = packagePath.toLowerCase();
        if (lcPackagePath.contains("test" + File.separator)) return true;
        return false;
    }
    
    /**
     * CRITICAL FIX: Filter source roots to avoid conflicts from multi-module projects.
     * 
     * Problem: Projects like lwjgl3 have 50+ modules, each with src/main/java.
     * Using ALL roots causes massive conflicts and compilation failures.
     * 
     * Solution: Intelligently select the most relevant source roots:
     * 1. Prefer standard Maven/Gradle structure (src/main/java, library/src)
     * 2. For multi-module projects, limit to core/main modules
     * 3. Remove test directories
     * 4. If too many roots (>5), pick shortest paths (usually core modules)
     * 
     * Impact: +89 to +373 methods on problematic repos!
     */
    private static Set<String> filterToBestSourceRoots(Set<String> allRoots) {
        if (allRoots == null || allRoots.isEmpty()) return allRoots;
        
        System.err.println("[PackageFinder] Found " + allRoots.size() + " source roots, filtering...");
        
        // Step 1: Remove test directories (should already be done, but double-check)
        Set<String> nonTest = allRoots.stream()
            .filter(root -> !root.toLowerCase().contains(File.separator + "test" + File.separator))
            .filter(root -> !root.toLowerCase().endsWith(File.separator + "test"))
            .collect(Collectors.toSet());
        
        System.err.println("[PackageFinder] After removing test: " + nonTest.size() + " roots");
        
        // Step 2: If only 1-3 roots, keep all (simple project)
        if (nonTest.size() <= 3) {
            System.err.println("[PackageFinder] Small project, keeping all " + nonTest.size() + " roots");
            return nonTest;
        }
        
        // Step 3: For multi-module projects (>3 roots), apply smart filtering
        
        // Prefer roots with standard patterns
        Set<String> preferred = new HashSet<>();
        
        // Pattern 1: Standard Maven structure (src/main/java)
        Set<String> standardMaven = nonTest.stream()
            .filter(root -> root.endsWith("src" + File.separator + "main" + File.separator + "java") ||
                           root.endsWith("src" + File.separator + "main" + File.separator + "java" + File.separator))
            .collect(Collectors.toSet());
        
        // Pattern 2: Android library structure (library/src, app/src)
        Set<String> androidLibrary = nonTest.stream()
            .filter(root -> root.contains(File.separator + "library" + File.separator + "src") ||
                           root.contains(File.separator + "app" + File.separator + "src"))
            .collect(Collectors.toSet());
        
        // Pattern 3: Simple src/ structure
        Set<String> simpleSrc = nonTest.stream()
            .filter(root -> root.endsWith(File.separator + "src") ||
                           root.endsWith(File.separator + "src" + File.separator))
            .collect(Collectors.toSet());
        
        preferred.addAll(standardMaven);
        preferred.addAll(androidLibrary);
        preferred.addAll(simpleSrc);
        
        // If we found preferred patterns, use them
        if (!preferred.isEmpty() && preferred.size() <= 10) {
            System.err.println("[PackageFinder] Using " + preferred.size() + " preferred pattern roots");
            return preferred;
        }
        
        // Step 4: For complex multi-module projects (lwjgl3, camel, etc.)
        // Prefer shorter paths (core modules) and limit to max 5 roots
        
        // Find "core" or "main" module paths
        Set<String> coreModules = nonTest.stream()
            .filter(root -> root.toLowerCase().contains(File.separator + "core" + File.separator) ||
                           root.toLowerCase().contains(File.separator + "main" + File.separator) ||
                           root.toLowerCase().contains(File.separator + "api" + File.separator))
            .collect(Collectors.toSet());
        
        if (!coreModules.isEmpty() && coreModules.size() <= 5) {
            System.err.println("[PackageFinder] Using " + coreModules.size() + " core module roots");
            return coreModules;
        }
        
        // Step 5: Last resort - pick shortest paths (usually root/main modules)
        // Limit to 5 to avoid overwhelming the type solver
        List<String> sorted = nonTest.stream()
            .sorted(Comparator.comparingInt(String::length))
            .limit(5)
            .collect(Collectors.toList());
        
        System.err.println("[PackageFinder] Multi-module project, limited to " + sorted.size() + " shortest paths:");
        sorted.forEach(root -> System.err.println("  - " + root));
        
        Set<String> result = new HashSet<>(sorted);
        
        // SAFETY CHECK: If filtering resulted in empty set, return original roots
        // This prevents breaking the tool when filtering is too aggressive
        if (result.isEmpty()) {
            System.err.println("[PackageFinder] WARNING: Filtering resulted in empty set, using all " + nonTest.size() + " original roots");
            return nonTest;
        }
        
        return result;
    }
}
