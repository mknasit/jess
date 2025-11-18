package fixtures.androidx;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

class AndroidXActivityTest {
    @TargetMethod
    void useAppCompatActivity(AppCompatActivity activity, android.os.Bundle bundle, android.view.MenuItem item) {
        activity.onCreate(bundle);
        activity.setContentView(0);
        activity.findViewById(0);
        activity.getSupportActionBar();
        activity.onOptionsItemSelected(item);
    }
    
    @TargetMethod
    void useFragment(Fragment fragment, android.os.Bundle bundle, android.view.LayoutInflater inflater, android.view.ViewGroup container) {
        fragment.onCreate(bundle);
        android.view.View view = fragment.onCreateView(inflater, container, bundle);
        fragment.onViewCreated(view, bundle);
        fragment.getActivity();
        fragment.getContext();
        fragment.requireContext();
        fragment.requireActivity();
    }
}

