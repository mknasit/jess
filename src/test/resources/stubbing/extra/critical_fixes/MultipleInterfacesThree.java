package fixtures.critical;

interface Readable {
    String read();
}

interface Writable {
    void write(String data);
}

interface Serializable {
    byte[] serialize();
    void deserialize(byte[] data);
}

class MultipleInterfacesThree implements Readable, Writable, Serializable {
    @TargetMethod
    void transfer(MultipleInterfacesThree other) {
        String data = this.read();
        other.write(data);
        byte[] serialized = this.serialize();
        other.deserialize(serialized);
    }
    
    // Only read() is implemented - write(), serialize(), deserialize() should be auto-implemented
    @Override
    public String read() {
        return "data";
    }
}

