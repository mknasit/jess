package fixtures.bridge;

interface Box<T> {
    T get();
}

class Use {
    String direct() {
        return new S().get();
    }

    Object erasedUse() {
        Box raw = new S();
        return raw.get();
    }

    String viaParam() {
        Box<String> b = new S();
        return b.get();
    }
}
