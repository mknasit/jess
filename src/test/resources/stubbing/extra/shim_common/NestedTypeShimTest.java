package fixtures.shim_common;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

class NestedTypeShimTest {
    @TargetMethod
    void useNestedTypes(RecyclerView recyclerView, android.content.Context context) {
        // Test nested type access - RecyclerView.Adapter, RecyclerView.LayoutManager
        // These are nested types that should be generated as inner classes
        Object adapter = recyclerView.getAdapter();
        Object layoutManager = recyclerView.getLayoutManager();
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
    }
}

