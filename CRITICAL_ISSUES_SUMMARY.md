# Critical Issues Summary: Quick Reference

## 🔴 **TOP 3 CRITICAL ISSUES**

### 1. **Missing Ecosystem Shims** ⚠️ **#1 REASON FOR FAILURES**
- **Missing:** Android (`android.*`, `androidx.*`), LWJGL (`org.lwjgl.*`)
- **Impact:** Camel, LWJGL, Android projects fail badly
- **Fix Time:** 4-6 days (add shims)
- **Priority:** 🔴 **HIGHEST**

### 2. **Enums Not First-Class** ⚠️ **SEMANTIC INCORRECTNESS**
- **Issue:** No `TypeStubPlan.Kind.ENUM`, handled via field stubs (hacky)
- **Impact:** Edge cases fail (reflection, EnumSet, ordinal())
- **Fix Time:** 1 week
- **Priority:** 🟡 **MEDIUM**

### 3. **Complex Generics Collapse** ⚠️ **TYPE INFORMATION LOSS**
- **Issue:** `Map<K, V>` → `Map<Object, Object>`, wildcards lost
- **Impact:** Framework-heavy repos (Spring, RxJava) have type mismatches
- **Fix Time:** 2 weeks
- **Priority:** 🟡 **MEDIUM**

---

## ⚠️ **CONFLICTS & INCONSISTENCIES**

### Conflict 1: Order-Dependent Collection Passes
- **Location:** `SpoonCollector.java` lines 179-209
- **Problem:** Order matters but is implicit (comments only)
- **Risk:** Breaking if reordered
- **Fix:** Explicit dependency graph or unified pass

### Conflict 2: SAM Method Creation/Removal
- **Location:** Multiple collection methods
- **Problem:** Methods added → removed → re-added across passes
- **Risk:** Inconsistent SAM signatures
- **Fix:** Single unified SAM collection logic

### Conflict 3: Unknown.* vs Concrete Mirroring
- **Location:** `SpoonStubber.java` mirroring logic
- **Problem:** Complex decision logic, potential duplicates
- **Risk:** Duplicate or missing methods
- **Fix:** Clear rules for mirroring

---

## 📊 **QUICK FIX PRIORITIES**

### 🔴 **Do First (High Impact, Low Effort)**
1. Add Android shims (2-3 days) → Fixes Android repos
2. Add LWJGL shims (2-3 days) → Fixes LWJGL repos

### 🟡 **Do Next (Medium Impact, Medium Effort)**
3. Proper enum support (1 week) → Semantic correctness
4. Improve generics (2 weeks) → Framework compatibility

### 🟢 **Do Later (Low Impact)**
5. Records support (1 week) → Java 14+ only
6. Sealed classes (1 week) → Java 17+ only
7. Refactor collection passes (2 weeks) → Technical debt

---

## 🎯 **BOTTOM LINE**

**Your #1 problem:** Missing Android/LWJGL shims → **Fix this first** (4-6 days)

**Your #2 problem:** Enums are hacky → **Fix for correctness** (1 week)

**Your #3 problem:** Generics collapse → **Fix for frameworks** (2 weeks)

**Everything else:** Lower priority, can be done incrementally

