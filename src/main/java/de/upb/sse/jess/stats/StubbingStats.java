package de.upb.sse.jess.stats;

import lombok.Data;

@Data
public class StubbingStats {
    private int stubbedFiles;
    private int stubbedLines;
    private int stubbedFields;
    private int stubbedMethods;
    private int stubbedConstructors;

    public void addStubbedFiles(int amount) {
        stubbedFiles += amount;
    }

    public void addStubbedLines(int amount) {
        stubbedLines += amount;
    }

    public void incrementStubbedFields() {
        stubbedFields++;
    }

    public void incrementStubbedMethods() {
        stubbedMethods++;
    }

    public void incrementStubbedConstructors() {
        stubbedConstructors++;
    }


    // NEW: total and boolean flag
    public int totalStubbedMembers() {
        return stubbedFields + stubbedMethods + stubbedConstructors;
    }
    public boolean usedStubs() {
        return stubbedFiles > 0 || stubbedLines > 0 || totalStubbedMembers() > 0;
    }
    // (nice to have) reset between runs to avoid accumulation
    public void reset() {
        stubbedFiles = stubbedLines = stubbedFields = stubbedMethods = stubbedConstructors = 0;
    }
}

