// de/upb/sse/jess/stubbing/spoon/diagnostics/DiagnosticsPlanExtractor.java
package de.upb.sse.jess.stubbing.spoon.diagnostics;

import de.upb.sse.jess.CompilerInvoker;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector;
import de.upb.sse.jess.stubbing.spoon.collector.SpoonCollector.CollectResult;
import de.upb.sse.jess.stubbing.spoon.context.ContextIndex;
import de.upb.sse.jess.stubbing.spoon.plan.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts stub plans from JavaCompiler diagnostics.
 * 
 * Handles patterns like:
 * - "cannot find symbol: variable X" -> FieldPlan
 * - "cannot find symbol: method m(...)" -> MethodPlan
 * - "cannot find symbol: class Y" -> TypePlan (nested type)
 */
public final class DiagnosticsPlanExtractor {
    
    private final Factory factory;
    private final ContextIndex contextIndex;
    private final SpoonCollector collector; // For canonicalization access
    
    // Patterns for parsing diagnostics
    private static final Pattern CANNOT_FIND_SYMBOL = Pattern.compile("cannot find symbol");
    private static final Pattern SYMBOL_VARIABLE = Pattern.compile("symbol:\\s*variable\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_METHOD = Pattern.compile("symbol:\\s*method\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL_CLASS = Pattern.compile("symbol:\\s*class\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_CLASS = Pattern.compile("location:\\s*class\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    
    public DiagnosticsPlanExtractor(Factory factory, ContextIndex contextIndex, SpoonCollector collector) {
        this.factory = factory;
        this.contextIndex = contextIndex;
        this.collector = collector;
    }
    
    /**
     * Extract stub plans from diagnostics.
     * @param diagnostics List of diagnostic info from compilation
     * @return CollectResult containing extracted plans
     */
    public CollectResult extractPlans(List<CompilerInvoker.DiagnosticInfo> diagnostics) {
        // Create CollectResult via collector (since it's an inner class)
        CollectResult result = collector.new CollectResult();
        result.setFactory(factory);
        
        // P3: Dedup keys to prevent re-applying same plans
        Set<String> seenPlans = new HashSet<>();
        
        for (CompilerInvoker.DiagnosticInfo diag : diagnostics) {
            if (diag.kind != javax.tools.Diagnostic.Kind.ERROR) {
                continue; // Only process errors
            }
            
            String message = diag.message;
            if (message == null || !CANNOT_FIND_SYMBOL.matcher(message).find()) {
                continue; // Not a "cannot find symbol" error
            }
            
            // P1: Extract owner FQN using sourcePath + line (improved)
            String ownerFqn = extractOwnerFqnFromSource(diag, message);
            if (ownerFqn == null) {
                // Fallback to message parsing
                ownerFqn = extractOwnerFqn(message);
            }
            if (ownerFqn == null) {
                continue; // Can't determine owner
            }
            
            // Canonicalize owner FQN (convert Outer.Inner to Outer$Inner if needed)
            ownerFqn = canonicalizeNestedTypeFqn(ownerFqn);
            if (ownerFqn == null) {
                continue; // Primitive/void - skip
            }
            
            // Try to match patterns
            Matcher varMatcher = SYMBOL_VARIABLE.matcher(message);
            if (varMatcher.find()) {
                // Pattern A: Missing variable (field)
                String fieldName = varMatcher.group(1);
                
                // P3: Check dedup key
                String dedupKey = "FIELD:" + ownerFqn + ":" + fieldName + ":false";
                if (seenPlans.contains(dedupKey)) {
                    continue; // Already seen
                }
                seenPlans.add(dedupKey);
                
                CtTypeReference<?> fieldType = inferFieldType(fieldName);
                CtTypeReference<?> ownerRef = factory.Type().createReference(ownerFqn);
                result.fieldPlans.add(new FieldStubPlan(ownerRef, fieldName, fieldType, false));
                System.out.println("[DiagnosticsPlanExtractor] Extracted FieldPlan: " + ownerFqn + "." + fieldName);
                continue;
            }
            
            Matcher methodMatcher = SYMBOL_METHOD.matcher(message);
            if (methodMatcher.find()) {
                // Pattern B: Missing method
                String methodName = methodMatcher.group(1);
                String paramList = methodMatcher.group(2);
                int paramCount = countParameters(paramList);
                
                // P3: Check dedup key
                String dedupKey = "METHOD:" + ownerFqn + ":" + methodName + ":" + paramCount + ":false";
                if (seenPlans.contains(dedupKey)) {
                    continue; // Already seen
                }
                seenPlans.add(dedupKey);
                
                // P4: Improved return type inference
                CtTypeReference<?> returnType = inferReturnTypeFromDiagnostic(diag, message);
                List<CtTypeReference<?>> paramTypes = createParamTypes(paramCount);
                
                CtTypeReference<?> ownerRef = factory.Type().createReference(ownerFqn);
                result.methodPlans.add(new MethodStubPlan(
                    ownerRef,
                    methodName,
                    returnType,
                    paramTypes,
                    false, // isStatic
                    MethodStubPlan.Visibility.PUBLIC,
                    Collections.emptyList() // thrownTypes
                ));
                System.out.println("[DiagnosticsPlanExtractor] Extracted MethodPlan: " + ownerFqn + "." + methodName + "(" + paramCount + " params)");
                continue;
            }
            
            Matcher classMatcher = SYMBOL_CLASS.matcher(message);
            if (classMatcher.find()) {
                // Pattern C: Missing class
                String className = classMatcher.group(1);
                
                // P2: Try to resolve as top-level type first
                String resolvedFqn = resolveMissingClassAsTopLevel(diag, className, ownerFqn);
                if (resolvedFqn == null) {
                    // Fallback: treat as nested type
                    resolvedFqn = ownerFqn + "." + className;
                }
                
                // Canonicalize nested type FQN
                resolvedFqn = canonicalizeNestedTypeFqn(resolvedFqn);
                if (resolvedFqn == null) {
                    continue; // Primitive/void - skip
                }
                
                // P3: Check dedup key
                String dedupKey = "TYPE:" + resolvedFqn;
                if (seenPlans.contains(dedupKey)) {
                    continue; // Already seen
                }
                seenPlans.add(dedupKey);
                
                result.addTypePlanIfNew(new TypeStubPlan(resolvedFqn, TypeStubPlan.Kind.CLASS), factory);
                System.out.println("[DiagnosticsPlanExtractor] Extracted TypePlan: " + resolvedFqn);
            }
        }
        
        return result;
    }
    
    /**
     * Extract owner FQN from diagnostic using sourcePath + line (P1 improvement).
     * Falls back to message parsing if source location unavailable.
     */
    private String extractOwnerFqnFromSource(CompilerInvoker.DiagnosticInfo diag, String message) {
        if (diag.sourcePath == null || diag.sourcePath.isEmpty() || diag.line <= 0) {
            return null; // No source location available
        }
        
        try {
            Path sourceFile = Paths.get(diag.sourcePath);
            if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
                return null; // File doesn't exist
            }
            
            // Read file and find enclosing type at the diagnostic line
            List<String> lines = Files.readAllLines(sourceFile);
            if (lines.size() < diag.line) {
                return null; // Line number out of range
            }
            
            // Find package declaration
            String packageName = "";
            for (int i = 0; i < Math.min(diag.line, lines.size()); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("package ")) {
                    packageName = line.substring(8, line.indexOf(';')).trim();
                    break;
                }
            }
            
            // Find enclosing type(s) by scanning from start to diagnostic line
            // Track type stack using regex and brace counting
            List<String> typeStack = new ArrayList<>();
            int braceCount = 0;
            Pattern typePattern = Pattern.compile("(?:public\\s+|private\\s+|protected\\s+)?(?:static\\s+)?(?:final\\s+)?(class|interface|enum|@interface)\\s+([a-zA-Z_][a-zA-Z0-9_]*)");
            
            for (int i = 0; i < diag.line && i < lines.size(); i++) {
                String line = lines.get(i);
                
                // Count braces to track nesting
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }
                
                // Check for type declarations
                Matcher typeMatcher = typePattern.matcher(line);
                if (typeMatcher.find()) {
                    String typeKind = typeMatcher.group(1);
                    String typeName = typeMatcher.group(2);
                    
                    // If we're at brace level 0 or 1, it's a top-level type
                    // Otherwise, it's nested
                    if (braceCount <= 1) {
                        typeStack.clear(); // Reset stack for top-level type
                        typeStack.add(typeName);
                    } else {
                        // Nested type - add to stack
                        typeStack.add(typeName);
                    }
                }
            }
            
            if (typeStack.isEmpty()) {
                return null; // No type found
            }
            
            // Build FQN from package + type stack
            String fqn;
            if (typeStack.size() == 1) {
                fqn = packageName.isEmpty() ? typeStack.get(0) : packageName + "." + typeStack.get(0);
            } else {
                // Nested type: Outer$Inner$Deeper
                String topLevel = packageName.isEmpty() ? typeStack.get(0) : packageName + "." + typeStack.get(0);
                StringBuilder nested = new StringBuilder(topLevel);
                for (int i = 1; i < typeStack.size(); i++) {
                    nested.append("$").append(typeStack.get(i));
                }
                fqn = nested.toString();
            }
            
            System.out.println("[DiagnosticsPlanExtractor] P1: Resolved owner from source: " + fqn + " (line " + diag.line + ")");
            return fqn;
            
        } catch (IOException | RuntimeException e) {
            // Robust: fall back to message parsing on any error
            System.out.println("[DiagnosticsPlanExtractor] P1: Failed to resolve owner from source: " + e.getMessage() + ", falling back to message parsing");
            return null;
        }
    }
    
    /**
     * Extract owner FQN from diagnostic message (fallback).
     * Looks for "location: class <OWNER>" pattern.
     */
    private String extractOwnerFqn(String message) {
        Matcher matcher = LOCATION_CLASS.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * Canonicalize nested type FQN using SpoonCollector's helper.
     * This converts Outer.Inner to Outer$Inner if Outer is a known TYPE.
     */
    private String canonicalizeNestedTypeFqn(String fqn) {
        // Use SpoonCollector's canonicalization method
        return collector.canonicalizeNestedTypeFqn(fqn);
    }
    
    /**
     * Infer field type from field name.
     * Special case: bitField\\d+_ -> int
     */
    private CtTypeReference<?> inferFieldType(String fieldName) {
        if (fieldName.matches("bitField\\d+_")) {
            return factory.Type().INTEGER_PRIMITIVE;
        }
        // Default to Object
        return factory.Type().OBJECT;
    }
    
    /**
     * Infer return type from diagnostic using source context (P4 improvement).
     * Falls back to message parsing if source unavailable.
     */
    private CtTypeReference<?> inferReturnTypeFromDiagnostic(CompilerInvoker.DiagnosticInfo diag, String message) {
        if (diag.sourcePath == null || diag.sourcePath.isEmpty() || diag.line <= 0) {
            // Fallback to void if no source location
            return factory.Type().VOID_PRIMITIVE;
        }
        
        try {
            Path sourceFile = Paths.get(diag.sourcePath);
            if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
                return factory.Type().VOID_PRIMITIVE;
            }
            
            List<String> lines = Files.readAllLines(sourceFile);
            if (lines.size() < diag.line) {
                return factory.Type().VOID_PRIMITIVE;
            }
            
            // Get the line with the error (0-indexed, so diag.line - 1)
            String errorLine = lines.get((int)diag.line - 1);
            
            // P4 Rule 1: If call is a statement (ends with `;` on same line), return void
            if (errorLine.trim().endsWith(";")) {
                return factory.Type().VOID_PRIMITIVE;
            }
            
            // P4 Rule 2: If used in `if (...)` condition, return boolean
            // Check if line contains "if (" before the method call
            int ifIndex = errorLine.indexOf("if (");
            if (ifIndex >= 0) {
                // Check if method call appears after "if ("
                String afterIf = errorLine.substring(ifIndex + 4);
                if (afterIf.contains("(") && afterIf.contains(")")) {
                    return factory.Type().BOOLEAN_PRIMITIVE;
                }
            }
            
            // P4 Rule 3: If assigned `T x = m(...)`, return T
            // Look for assignment pattern: Type name = methodCall
            Pattern assignmentPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_.<>\\[\\]\\s]*)\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*=\\s*[^;]*");
            Matcher assignMatcher = assignmentPattern.matcher(errorLine);
            if (assignMatcher.find()) {
                String typeStr = assignMatcher.group(1).trim();
                // Map common types
                if (typeStr.equals("boolean")) return factory.Type().BOOLEAN_PRIMITIVE;
                if (typeStr.equals("int")) return factory.Type().INTEGER_PRIMITIVE;
                if (typeStr.equals("long")) return factory.Type().LONG_PRIMITIVE;
                if (typeStr.equals("double")) return factory.Type().DOUBLE_PRIMITIVE;
                if (typeStr.equals("float")) return factory.Type().FLOAT_PRIMITIVE;
                if (typeStr.equals("String") || typeStr.equals("java.lang.String")) return factory.Type().STRING;
                // For other types, try to create reference
                try {
                    return factory.Type().createReference(typeStr);
                } catch (Exception e) {
                    // Fall through to Object
                }
            }
            
            // P4 Fallback: Return Object (not void) for expression contexts
            return factory.Type().OBJECT;
            
        } catch (IOException | RuntimeException e) {
            // Fallback to void on any error
            return factory.Type().VOID_PRIMITIVE;
        }
    }
    
    /**
     * Infer return type from method diagnostic message (legacy fallback).
     * @deprecated Use inferReturnTypeFromDiagnostic instead
     */
    @Deprecated
    private CtTypeReference<?> inferReturnType(String message) {
        return factory.Type().VOID_PRIMITIVE;
    }
    
    /**
     * Count parameters from parameter list string.
     */
    private int countParameters(String paramList) {
        if (paramList == null || paramList.trim().isEmpty()) {
            return 0;
        }
        // Count commas + 1 (simple heuristic)
        return paramList.split(",").length;
    }
    
    /**
     * Create parameter type references for given count.
     */
    private List<CtTypeReference<?>> createParamTypes(int count) {
        List<CtTypeReference<?>> paramTypes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            paramTypes.add(factory.Type().OBJECT);
        }
        return paramTypes;
    }
    
    /**
     * P2: Resolve missing class Y as top-level type first, before assuming nested.
     * 
     * @param diag Diagnostic info
     * @param className Simple class name (e.g., "Y")
     * @param ownerFqn Owner FQN from location (e.g., "com.example.Outer")
     * @return Resolved FQN if found as top-level, null if should be treated as nested
     */
    private String resolveMissingClassAsTopLevel(CompilerInvoker.DiagnosticInfo diag, String className, String ownerFqn) {
        // P2 Rule 1: Parse explicit imports in the diagnostic source file
        if (diag.sourcePath != null && !diag.sourcePath.isEmpty()) {
            try {
                Path sourceFile = Paths.get(diag.sourcePath);
                if (Files.exists(sourceFile) && Files.isRegularFile(sourceFile)) {
                    List<String> lines = Files.readAllLines(sourceFile);
                    // Look for import statements
                    Pattern importPattern = Pattern.compile("import\\s+(?:static\\s+)?([a-zA-Z_][a-zA-Z0-9_.]*\\.)?" + Pattern.quote(className) + "\\s*;");
                    for (String line : lines) {
                        Matcher importMatcher = importPattern.matcher(line);
                        if (importMatcher.find()) {
                            String importFqn = line.substring(line.indexOf("import") + 6, line.indexOf(";")).trim();
                            // Remove "static" if present
                            importFqn = importFqn.replaceFirst("^static\\s+", "");
                            System.out.println("[DiagnosticsPlanExtractor] P2: Found explicit import: " + importFqn);
                            return importFqn;
                        }
                    }
                    
                    // Check for star imports and try anchor packages
                    Pattern starImportPattern = Pattern.compile("import\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\s*\\*\\s*;");
                    Set<String> starImportPackages = new HashSet<>();
                    for (String line : lines) {
                        Matcher starMatcher = starImportPattern.matcher(line);
                        if (starMatcher.find()) {
                            starImportPackages.add(starMatcher.group(1));
                        }
                    }
                    
                    // Try star import packages
                    if (contextIndex != null && !starImportPackages.isEmpty()) {
                        for (String pkg : starImportPackages) {
                            String candidateFqn = pkg + "." + className;
                            Set<String> candidates = contextIndex.lookupBySimpleName(className);
                            if (candidates.contains(candidateFqn)) {
                                System.out.println("[DiagnosticsPlanExtractor] P2: Found via star import: " + candidateFqn);
                                return candidateFqn;
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Fall through to ContextIndex check
            }
        }
        
        // P2 Rule 2: Use ContextIndex if available
        if (contextIndex != null) {
            Set<String> candidates = contextIndex.lookupBySimpleName(className);
            if (candidates.size() == 1) {
                String candidate = candidates.iterator().next();
                System.out.println("[DiagnosticsPlanExtractor] P2: Found single candidate via ContextIndex: " + candidate);
                return candidate;
            } else if (candidates.size() > 1) {
                // Multiple candidates - check if one matches a star import package
                // (Already handled above, but could add more heuristics here)
                System.out.println("[DiagnosticsPlanExtractor] P2: Multiple candidates for " + className + ", treating as nested");
            }
        }
        
        // P2 Rule 3: Check if it matches common nested patterns (Builder, OrBuilder, etc.)
        String[] nestedPatterns = {"Builder", "OrBuilder", "Internal", "Impl", "Default"};
        for (String pattern : nestedPatterns) {
            if (className.endsWith(pattern) || className.contains(pattern)) {
                System.out.println("[DiagnosticsPlanExtractor] P2: Matches nested pattern, treating as nested: " + className);
                return null; // Treat as nested
            }
        }
        
        // P2 Rule 4: If usage looks like Outer.Y (contains dot in message), treat as nested
        if (ownerFqn != null && ownerFqn.contains("." + className)) {
            System.out.println("[DiagnosticsPlanExtractor] P2: Usage pattern suggests nested: " + ownerFqn + "." + className);
            return null; // Treat as nested
        }
        
        // Default: Could be top-level, but we can't determine - return null to treat as nested
        // (Conservative approach - better to create nested than wrong top-level)
        return null;
    }
}

