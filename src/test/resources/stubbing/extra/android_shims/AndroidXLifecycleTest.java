package fixtures.androidx;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

class AndroidXLifecycleTest {
    @TargetMethod
    void useLifecycle(LifecycleOwner owner, androidx.lifecycle.LifecycleObserver observer) {
        Lifecycle lifecycle = owner.getLifecycle();
        lifecycle.addObserver(observer);
        lifecycle.removeObserver(observer);
        androidx.lifecycle.Lifecycle.State state = lifecycle.getCurrentState();
    }
    
    @TargetMethod
    void useViewModel(ViewModel viewModel) {
        viewModel.onCleared();
    }
    
    @TargetMethod
    void useLiveData(LiveData<String> liveData, MutableLiveData<String> mutableLiveData, LifecycleOwner owner, androidx.lifecycle.Observer<String> observer) {
        liveData.observe(owner, observer);
        liveData.observeForever(observer);
        liveData.removeObserver(observer);
        String value = liveData.getValue();
        liveData.postValue("value");
        boolean hasObservers = liveData.hasObservers();
        
        mutableLiveData.setValue("value");
    }
}

