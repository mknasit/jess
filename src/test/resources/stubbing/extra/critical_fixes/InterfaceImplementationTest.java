package fixtures.critical;

interface StreamObserver<T> {
    void onNext(T value);
    void onCompleted();
    void onError(Throwable error);
}

class InterfaceImplementationTest implements StreamObserver<String> {
    @TargetMethod
    void process(StreamObserver<String> observer) {
        observer.onNext("test");
        observer.onCompleted();
    }
    
    // onError should be auto-implemented
}

