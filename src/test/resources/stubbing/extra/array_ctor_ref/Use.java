package fixtures.arrctor;

interface ArrMaker<T> {
    T[] make(int n);
}

class Use {
    String[] go() {
        ArrMaker<String> m = String[]::new;
        return m.make(3);
    }
}
