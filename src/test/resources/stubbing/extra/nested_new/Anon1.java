package fixtures.nested2;

class Anon1 {
    int z() {
        X x = new X() {
            @Override
            public int go() {
                return 1;
            }
        };
        return x.go();
    }
}
