# Quick Reference: Source Root Override

## 📝 CSV File Location
`jess-eval/input/source-roots-override.csv`

## 📋 Format
```csv
repo_name,source_root1,source_root2
```

## ✏️ How to Edit
1. Open CSV file in text editor or spreadsheet
2. Add line: `repo_name,source_root1,source_root2`
3. Save

## 📝 Examples
```csv
# Single root
jakewharton_viewpagerindicator,library/src

# Multiple roots
apache_camel,core/camel-core/src/main/java,api/camel-api/src/main/java

# Multi-module
lwjgl_lwjgl3,modules/lwjgl/core/src/main/java
```

## ✅ Test Cases (Add to CSV)
```csv
lwjgl_lwjgl3,modules/lwjgl/core/src/main/java
apache_camel,core/camel-core/src/main/java,api/camel-api/src/main/java
jakewharton_viewpagerindicator,library/src
apache_hbase,hbase-server/src/main/java,hbase-common/src/main/java
apache_flink,flink-core/src/main/java,flink-runtime/src/main/java
```

## 🔍 Verify It Works
Check logs for: `[SourceRoot] Using override for <repo>`

## ❌ StackOverflowError
- **Cannot be fixed** - JavaParser limitation
- Spoon doesn't help (runs after error occurs)
- Focus on source roots instead

## 📁 Files Changed
1. ✅ `RandomJessHandler.java` - Uses override system
2. ✅ `SourceRootOverrideReader.java` - New utility
3. ✅ `PackageFinder.java` - Improved filtering
4. ⭐ **`source-roots-override.csv`** - **EDIT THIS**

