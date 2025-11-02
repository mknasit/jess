package fixtures.enums2;

enum FS {
    ;

    private static final FS CUR = compute();  // missing helper

    static FS get() {
        return CUR;
    }
}

class Use {
    static Object x() { return FS.get(); }
}
