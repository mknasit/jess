//lambada1

package fixtures.lambda;

@FunctionalInterface
public interface F<T> {
    T apply(T t);
}


//methodrefctor

package fixtures.lambda2;

public class A {
    public A() {}
}

@FunctionalInterface
public interface Maker {
    A make();
}
