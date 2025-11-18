# Order of Operations in RandomJessHandler

## Execution Flow in `compileAll()` Method

Looking at `RandomJessHandler.java` lines 37-78:

### Step 1: Method Discovery (Line 40)
```java
List<ClassMethodPair> allClassMethodPairs = getAllClassMethodPairs();
```
- **What it does**: Finds ALL Java files in the entire project directory
- **How**: Uses `FileUtils.getAllRelevantJavaFiles(projectPath.toString())`
  - Searches entire project directory (not filtered by source roots)
  - Excludes test files (paths containing "test")
  - Excludes `package-info.java` and `module-info.java`
- **Source roots used?**: ❌ NO - searches entire project

### Step 2: Method Filtering/Selection (Line 41)
```java
List<ClassMethodPair> randomClassMethodPairs = getRandomClassMethodPairs(allClassMethodPairs);
```
- **What it does**: Randomly selects methods from all discovered methods
- **Filtering criteria**:
  - Minimum LOC (from `JPUtils.getClassMethodPairs()`)
  - Excludes anonymous classes
  - Random selection with seed 1234
- **Source roots used?**: ❌ NO - operates on already-discovered methods

### Step 3: Direct Compilation (Line 49)
```java
List<DirectCompilationResult> directResult = compilePairsDirectlyUsingJavac(randomClassMethodPairs);
```
- **What it does**: Compiles selected methods directly using javac
- **Source roots used?**: ❌ NO - javac doesn't use source roots here

### Step 4: Source Root Detection (Line 53) ⭐ **THIS IS WHERE OVERRIDE IS APPLIED**
```java
Set<String> packages = getSourceRootsWithOverride();
```
- **What it does**: Determines source roots for compilation
- **Priority**:
  1. ✅ Check CSV override file (`source-roots-override.csv`)
  2. ✅ Fall back to `PackageFinder.findPackageRoots()` (with smart filtering)
- **Source roots used?**: ✅ YES - this is where override is applied

### Step 5: Compilation with Jess (Lines 57, 61, 74)
```java
List<RandomMethodResult> slicingResult = compilePairs(randomClassMethodPairs, packages, ...);
List<RandomMethodResult> stubbingResult = compilePairs(randomClassMethodPairs, packages, ...);
List<RandomMethodResult> depsResult = compilePairs(randomClassMethodPairs, packages, jars, ...);
```
- **What it does**: Compiles selected methods using Jess
- **Source roots used?**: ✅ YES - passed to `Jess` constructor for type resolution

## Answer: Source Root Override is Applied **AFTER** Method Filtering

### Timeline:
1. ✅ **Method Discovery** - Searches entire project (no source roots)
2. ✅ **Method Filtering/Selection** - Random selection (no source roots)
3. ⭐ **Source Root Override Applied** - Determines which source roots to use
4. ✅ **Compilation** - Uses source roots for type resolution

### Important Implications:

1. **Source roots don't affect method discovery**: All Java files in the project are found, regardless of source root configuration.

2. **Source roots only affect compilation**: They help Jess resolve types and dependencies during compilation.

3. **Override fixes compilation issues**: If wrong source roots cause compilation failures, the override fixes this by providing correct source roots.

4. **Method selection is independent**: The same methods are selected whether or not source root override is used.

## Why This Matters

The source root override system fixes **compilation failures** caused by incorrect source root detection, but it doesn't change:
- Which methods are discovered
- Which methods are selected
- The order of method processing

It only fixes:
- Type resolution during compilation
- Dependency resolution during compilation
- Symbol resolution during compilation

