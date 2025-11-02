// static.ref
package fixtures.mref;

public class A {
    public static int inc(int x) {
        return 0;
    }
}

@FunctionalInterface
public interface F {
    int apply(int x);
}


//instref

// fixtures.mref2
package fixtures.mref2;

public class B {
    public int plus(int x) {
        return 0;
    }
}

@FunctionalInterface
public interface F {
    int apply(int x);
}


