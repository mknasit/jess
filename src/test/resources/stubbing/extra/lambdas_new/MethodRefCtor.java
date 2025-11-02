package fixtures.lambda2;

class MethodRefCtor {
    A build() {
        Maker m = A::new;
        return m.make();
    }
}
