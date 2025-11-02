package fixtures.bridge;

class S implements Box<String> {
    public String get() {
        return null;
    }

    // optional, but ideal for “bridge” test:
    public Object get() {
        return get();
    }
}
