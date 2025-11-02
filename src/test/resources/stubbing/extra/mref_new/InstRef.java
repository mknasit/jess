package fixtures.mref2;

class InstRef {
    int k() {
        B b = new B();
        F f = b::plus;
        return f.apply(1);
    }
}
