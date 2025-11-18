package fixtures.critical;

import io.grpc.Channel;
import io.grpc.stub.StreamObserver;

// Simulate gRPC generated class
class MetadataGrpc {
    @TargetMethod
    static MetadataStub newStub(Channel channel) {
        return new MetadataStub(channel);
    }
}

// Simulate gRPC stub class
class MetadataStub {
    MetadataStub(Channel channel) {
        // Constructor
    }
    
    void getMetadata(com.google.protobuf.Message request, StreamObserver<com.google.protobuf.Message> responseObserver) {
        // gRPC method
    }
}

// Test class that uses gRPC
class GrpcTest {
    @TargetMethod
    void callGrpcService(Channel channel) {
        MetadataStub stub = MetadataGrpc.newStub(channel);
        stub.getMetadata(null, new StreamObserver<com.google.protobuf.Message>() {
            @Override
            public void onNext(com.google.protobuf.Message value) {
                // Handle response
            }
            
            @Override
            public void onError(Throwable t) {
                // Handle error
            }
            
            @Override
            public void onCompleted() {
                // Handle completion
            }
        });
    }
}

