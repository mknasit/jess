package fixtures.enums2;
enum FS {
    ; // utility enum (no constants)
    private static final FS CUR = compute();
    static FS compute() { return null; } // helper required by initializer
    static FS get() { return CUR; }
}
class Use {
    static Object x() { return FS.get(); }
}
