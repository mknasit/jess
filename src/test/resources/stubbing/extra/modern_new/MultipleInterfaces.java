package fixtures.modern;

interface Readable {
    String read();
}

interface Writable {
    void write(String data);
}

class MultipleInterfaces implements Readable, Writable {
    @TargetMethod
    void transfer(MultipleInterfaces other) {
        String data = this.read();
        other.write(data);
    }
    
    @Override
    public String read() {
        return "data";
    }
    
    @Override
    public void write(String data) {
        // implementation
    }
}

