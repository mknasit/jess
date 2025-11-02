//enum1.java
package fixtures.enums1;


public enum FS {
    A;
}


//enum2.java
package fixtures.enums2;

enum FS {
    ;

    private static final FS CUR = compute();

    static FS compute() {
        return null;
    }

    static FS get() {
        return CUR;
    }
}
