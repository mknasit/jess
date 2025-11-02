package fixtures.mref;

class StaticRef {
    int k() {
        F f = A::inc;
        return f.apply(1);
    }
}
