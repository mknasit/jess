package fixtures.shim_common;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

class GenericTypeShimTest {
    @TargetMethod
    void useGenericTypes(LiveData<String> liveData, MutableLiveData<String> mutableLiveData) {
        // Test generic type shims - LiveData<T>, MutableLiveData<T>
        // These should be generated with type parameter <T>
        String value = liveData.getValue();
        boolean hasObservers = liveData.hasObservers();
        
        mutableLiveData.setValue("value");
        mutableLiveData.postValue("value");
        
        // Note: observe() and observeForever() require LifecycleOwner and Observer<T>
        // which are not included in this test to avoid dependency issues
    }
}

