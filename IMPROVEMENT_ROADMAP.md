# JESS Spoon-Based Stubbing: Improvement Roadmap

This document identifies missing features, incomplete implementations, conflicts, and improvement opportunities based on codebase analysis.

---

## 🔴 **CRITICAL: Missing Features**

### 1. **Proper Enum Type Support**
**Status:** ❌ **MISSING** - Currently hacky workaround

**Current State:**
- `TypeStubPlan.Kind` only has `CLASS`, `INTERFACE`, `ANNOTATION` (NO `ENUM`)
- Enums are handled via:
  - Field stubs for enum constants (`SpoonCollector.java` lines 303-350)
  - Special-case handling of `values()`, `valueOf()`, `name()` in mirroring code
  - `fixEnumConstantsFromSwitches()` post-processing (`SpoonStubber.java` lines 5549-5615)

**Problem:**
- Enum constants work but enum types are not first-class
- Missing enum semantics: `ordinal()`, `compareTo()`, `EnumSet`/`EnumMap` integration
- Reflection code that checks `instanceof Enum` may fail
- No proper enum type hierarchy (`Enum<T extends Enum<T>>`)

**Impact:**
- Medium - Most enum usage works, but edge cases fail
- Affects: Switch statements, enum collections, reflection-based code

**Fix Required:**
```java
// In TypeStubPlan.java
public enum Kind { CLASS, INTERFACE, ANNOTATION, ENUM }  // ADD ENUM

// In SpoonCollector.java
// Detect enum types and create TypeStubPlan with Kind.ENUM
// In SpoonStubber.java
// Use f.Enum().create() instead of f.Class().create() for enum types
```

**Priority:** 🟡 **MEDIUM** (works for most cases, but not semantically correct)

---

### 2. **Records Support**
**Status:** ❌ **MISSING**

**Current State:**
- No `CtRecord` handling found in collector/stubber
- Records are stubbed as regular classes
- Missing auto-generated methods: `equals()`, `hashCode()`, `toString()`
- Missing record semantics: component accessors, canonical constructors

**Problem:**
- Records introduced in Java 14, widely used in modern codebases
- Stubbing as classes loses record semantics
- Code expecting record behavior fails

**Impact:**
- High for modern Java projects (Java 14+)
- Affects: Data classes, DTOs, value objects

**Fix Required:**
```java
// In SpoonCollector.java
// Detect CtRecord types and mark as record
// In SpoonStubber.java
// Use f.Record().create() and generate component accessors
```

**Priority:** 🟡 **MEDIUM** (only affects Java 14+ projects)

---

### 3. **Sealed Classes Support**
**Status:** ❌ **MISSING**

**Current State:**
- No `sealed`/`permits` keyword handling found
- Sealed hierarchies stubbed as regular classes
- Missing sealed semantics: restricted inheritance, pattern matching

**Problem:**
- Sealed classes introduced in Java 17
- Stubbing as regular classes breaks sealed hierarchy constraints
- Pattern matching code fails

**Impact:**
- Medium for Java 17+ projects
- Affects: Restricted inheritance hierarchies, pattern matching

**Fix Required:**
```java
// In SpoonCollector.java
// Detect sealed classes and collect permits clause
// In SpoonStubber.java
// Use f.Class().setSealed(true) and setPermittedSubtypes()
```

**Priority:** 🟢 **LOW** (only affects Java 17+ projects, less common)

---

### 4. **Module-Info.java Handling**
**Status:** ❌ **MISSING** (but probably OK)

**Current State:**
- `module-info.java` files are excluded from file discovery
- No module system support

**Problem:**
- Java 9+ module system not handled
- Module boundaries and exports not respected

**Impact:**
- Low - Module system is rarely critical for partial compilation
- Most projects don't use modules extensively

**Priority:** 🟢 **LOW** (probably not needed for partial compilation use case)

---

### 5. **Missing Ecosystem Shims**
**Status:** ❌ **CRITICAL GAP**

**Current State:**
- Has shims for: SLF4J, ANTLR, Commons Lang, Guava, ASM, JUnit, Mockito, Spring, gRPC, Servlets, MyBatis Plus
- **NO shims for:**
  - Android (`android.*`, `androidx.*`)
  - LWJGL (`org.lwjgl.*`, OpenGL, GLFW, Vulkan)
  - Deep Spring internals
  - Hibernate
  - UI frameworks (JavaFX, Swing extensions)
  - Reactive stacks (RxJava, Reactor internals)

**Problem:**
- These ecosystems have thousands of core API classes
- Without shims, stubbing alone can't recover from missing types
- **This is why Camel, LWJGL, Android projects fail badly**

**Impact:**
- 🔴 **CRITICAL** - This is the #1 reason for failures on framework-heavy repos

**Fix Required:**
```java
// In ShimGenerator.java
// Add comprehensive Android shims
addShim("android", "Context", createClassShim(...));
addShim("androidx", "Activity", createClassShim(...));
// Add LWJGL shims
addShim("org.lwjgl", "GL", createClassShim(...));
addShim("org.lwjgl.glfw", "GLFW", createClassShim(...));
```

**Priority:** 🔴 **HIGH** - This directly addresses your failing repos

---

## 🟡 **INCOMPLETE / PARTIALLY HANDLED**

### 6. **Generics: Complex Cases Collapse**
**Status:** ⚠️ **PARTIALLY HANDLED**

**Current State:**
- ✅ Basic generics work: `List<String>` → extracts `String`
- ✅ `GENERIC_T` markers in `METHOD_RETURN_TYPES`
- ✅ Type parameter extraction from generic owners
- ❌ Multi-parameter generics collapse: `Map<K, V>` → `Map<Object, Object>`
- ❌ Wildcards collapse: `List<? extends T>` → `List<Object>`
- ❌ Nested generics collapse: `List<List<String>>` → `List<Object>`
- ❌ Complex bounds collapse: `<T extends Comparable<T> & Serializable>` → `Object`

**Evidence:**
- `SpoonStubber.java` lines 1620-1621, 1653-1654: Fallback to `Object`/`UnknownType`
- `SpoonStubber.java` lines 1693-1696: Parameter type fallback to `unknown.Unknown`

**Problem:**
- Type information becomes blurry in complex generic flows
- Overload resolution by javac differs from real libraries
- Bytecode structure differs more from Maven's → worse equality in RQ3

**Impact:**
- High for generic-heavy frameworks (Spring, RxJava, Reactor, gRPC)
- Affects: Type safety, method resolution, bytecode quality

**Fix Required:**
- Implement proper generic type inference system
- Preserve wildcards and bounds in stub plans
- Handle nested generics correctly
- Type parameter propagation through method chains

**Priority:** 🟡 **MEDIUM** - Works for simple cases, fails on complex frameworks

---

### 7. **Type Inference: Heuristic-Based, Not Semantic**
**Status:** ⚠️ **PARTIALLY HANDLED**

**Current State:**
- ✅ Context-based inference: assignment, variable declarations
- ✅ Method name mappings (`METHOD_RETURN_TYPES`)
- ✅ Generic hints from owner types
- ❌ No full type propagation system
- ❌ No flow-sensitive type inference
- ❌ Limited backward type inference

**Evidence:**
- `SpoonCollector.java` lines 1542-1600: `inferReturnTypeFromContext()` - basic context only
- `SpoonCollector.java` lines 1689-1730: `inferReturnTypeFromMethodName()` - heuristic mappings only

**Problem:**
- Can't infer types from complex expressions
- No type flow analysis across method calls
- Falls back to `Object`/`UnknownType` too quickly

**Impact:**
- Medium - Most simple cases work, complex expressions fail
- Affects: Method chaining, complex expressions, nested calls

**Priority:** 🟡 **MEDIUM** - Would improve success rate but not critical

---

### 8. **Order-Dependent Collection Passes**
**Status:** ⚠️ **CONFLICTING LOGIC**

**Current State:**
- Collection passes have explicit order dependencies:
  1. `collectMethodReferences()` - FIRST (creates SAM methods)
  2. `collectUnresolvedMethodCalls()` - SECOND (may create SAM methods)
  3. `collectLambdas()` - LAST (removes/replaces SAM methods)
  4. `collectOverloadGaps()` - AFTER lambdas (may re-add methods)
  5. `removeDuplicateSamMethods()` - Final cleanup

**Evidence:**
- `SpoonCollector.java` lines 179-209: Explicit ordering with comments
- `SpoonCollector.java` line 201: Comment "This runs AFTER collectLambdas, so it might re-add methods"

**Problem:**
- Order matters but is implicit (comments, not enforced)
- Easy to break by reordering passes
- Hard to reason about correctness
- Non-monotonic behavior (methods added, removed, re-added)

**Impact:**
- Medium - Works but fragile and hard to maintain
- Affects: Code maintainability, correctness guarantees

**Fix Required:**
- Make order dependencies explicit (dependency graph)
- Or: Make passes independent (idempotent, order-independent)
- Or: Single unified collection pass with proper conflict resolution

**Priority:** 🟡 **MEDIUM** - Technical debt, doesn't affect functionality

---

### 9. **SAM Method Deduplication Conflicts**
**Status:** ⚠️ **CONFLICTING LOGIC**

**Current State:**
- Multiple collection passes can create SAM methods:
  - `collectMethodReferences()` creates `make()` or `apply()`
  - `collectUnresolvedMethodCalls()` creates `apply()` from calls
  - `collectLambdas()` removes/replaces with correct signature
  - `collectOverloadGaps()` may re-add if missing
- Final `removeDuplicateSamMethods()` cleanup

**Evidence:**
- `SpoonCollector.java` lines 2582-2613: Functional interface method deduplication logic
- `SpoonCollector.java` lines 4101-4111: Method reference removes existing SAM methods
- `SpoonCollector.java` lines 206-209: Final cleanup

**Problem:**
- Methods added, removed, re-added in different passes
- Complex deduplication logic scattered across multiple methods
- Hard to reason about which SAM method signature is "correct"

**Impact:**
- Medium - Works but complex and fragile
- Affects: Functional interface correctness

**Fix Required:**
- Single source of truth for SAM method signatures
- Unified SAM method collection/creation logic
- Clear priority: Lambda > Method Reference > Method Call

**Priority:** 🟡 **MEDIUM** - Works but could be cleaner

---

### 10. **Unknown.* vs Concrete Owner Mirroring**
**Status:** ⚠️ **CONFLICTING LOGIC**

**Current State:**
- Methods can be mirrored to both `unknown.*` and concrete owners
- `MethodStubPlan` has `mirror` and `mirrorOwnerRef` fields
- Logic to prevent conflicts but complex

**Evidence:**
- `SpoonStubber.java` lines 1319-1323: Mirroring logic
- `SpoonStubber.java` lines 793-799: Unknown.* owner handling

**Problem:**
- Complex logic to decide when to mirror
- Potential for duplicate methods
- Hard to reason about which owner is "correct"

**Impact:**
- Low - Works but adds complexity
- Affects: Code clarity, potential for bugs

**Priority:** 🟢 **LOW** - Works, minor technical debt

---

## 🟢 **MINOR ISSUES / TECHNICAL DEBT**

### 11. **Heavy Logging / Noise**
**Status:** ⚠️ **MINOR ISSUE**

**Current State:**
- Extensive `System.err.println()` statements throughout
- Debug logging in lambda/method reference collection
- Verbose output on large corpora

**Evidence:**
- `SpoonCollector.java` lines 4163, 4008-4092: Extensive debug logging
- `SpoonStubber.java`: Many print statements

**Problem:**
- Spams logs on big corpora
- Harder to isolate true errors
- I/O overhead

**Fix Required:**
- Use proper logging framework (SLF4J)
- Configurable log levels
- Structured logging

**Priority:** 🟢 **LOW** - Doesn't affect functionality

---

### 12. **No Explicit Type Compatibility Checking**
**Status:** ⚠️ **LIMITATION**

**Current State:**
- Type compatibility checks are basic (exact match, Object/Unknown equivalence)
- No subtype checking, no generic variance

**Evidence:**
- `SpoonStubber.java` lines 1615-1631: Basic type compatibility
- `SpoonStubber.java` lines 1659-1662: Comment "More sophisticated type compatibility checking could be added here"

**Problem:**
- May create incompatible method signatures
- Overload resolution may fail

**Impact:**
- Low - Most cases work, edge cases fail

**Priority:** 🟢 **LOW** - Would improve but not critical

---

## 📊 **PRIORITY SUMMARY**

### 🔴 **HIGH PRIORITY** (Directly addresses failing repos)
1. **Missing Ecosystem Shims** (Android, LWJGL) - #1 reason for failures

### 🟡 **MEDIUM PRIORITY** (Improves success rate)
2. **Proper Enum Type Support** - Semantic correctness
3. **Generics: Complex Cases** - Framework-heavy repos
4. **Type Inference Improvements** - Complex expressions
5. **Order-Dependent Passes** - Technical debt
6. **SAM Method Deduplication** - Code clarity

### 🟢 **LOW PRIORITY** (Nice to have)
7. **Records Support** - Java 14+ only
8. **Sealed Classes** - Java 17+ only, less common
9. **Module-Info.java** - Probably not needed
10. **Logging Improvements** - Quality of life
11. **Type Compatibility** - Edge cases

---

## 🎯 **RECOMMENDED ACTION PLAN**

### Phase 1: Quick Wins (High Impact, Low Effort)
1. **Add Android Shims** (2-3 days)
   - Core Android types: `Context`, `Activity`, `View`, `Bundle`
   - AndroidX types: `AppCompatActivity`, `Fragment`
   - Impact: Fixes Android project failures

2. **Add LWJGL Shims** (2-3 days)
   - Core LWJGL: `GL`, `GLFW`, `Vulkan`
   - Impact: Fixes LWJGL project failures

### Phase 2: Semantic Improvements (Medium Impact, Medium Effort)
3. **Proper Enum Support** (1 week)
   - Add `TypeStubPlan.Kind.ENUM`
   - Use `f.Enum().create()` in stubber
   - Impact: Semantic correctness, better enum handling

4. **Improve Generic Type Inference** (2 weeks)
   - Preserve wildcards and bounds
   - Handle nested generics
   - Impact: Better type safety, framework compatibility

### Phase 3: Modern Java Features (Low Impact, Medium Effort)
5. **Records Support** (1 week)
   - Detect `CtRecord` types
   - Generate component accessors
   - Impact: Java 14+ projects

6. **Sealed Classes** (1 week)
   - Detect sealed classes
   - Handle permits clause
   - Impact: Java 17+ projects

### Phase 4: Technical Debt (Low Impact, High Effort)
7. **Refactor Collection Passes** (2 weeks)
   - Make passes independent or explicit dependencies
   - Impact: Code maintainability

8. **Unified SAM Method Handling** (1 week)
   - Single source of truth
   - Impact: Code clarity

---

## 🔍 **CONFLICTS IDENTIFIED**

### Conflict 1: Collection Pass Order
- **Location:** `SpoonCollector.java` lines 179-209
- **Issue:** Order matters but is implicit
- **Risk:** Breaking changes if reordered
- **Solution:** Explicit dependency graph or unified pass

### Conflict 2: SAM Method Creation
- **Location:** Multiple collection methods
- **Issue:** Multiple passes create/remove SAM methods
- **Risk:** Inconsistent SAM signatures
- **Solution:** Single unified SAM collection logic

### Conflict 3: Unknown.* Mirroring
- **Location:** `SpoonStubber.java` mirroring logic
- **Issue:** Complex decision logic for mirroring
- **Risk:** Duplicate methods or missing methods
- **Solution:** Clear rules for when to mirror

### Conflict 4: Generic Type Fallback
- **Location:** Multiple type inference points
- **Issue:** Complex generics collapse to `Object`/`UnknownType`
- **Risk:** Type information loss
- **Solution:** Proper generic type system

---

## 📝 **NOTES**

- **Most Critical:** Missing ecosystem shims (Android, LWJGL) - this is why your failing repos fail
- **Most Impactful Quick Win:** Add Android + LWJGL shims (4-6 days total, fixes multiple repos)
- **Biggest Technical Debt:** Order-dependent collection passes (hard to maintain)
- **Best ROI:** Phase 1 (shims) → Phase 2 (enums, generics) → Phase 3 (modern features) → Phase 4 (debt)

