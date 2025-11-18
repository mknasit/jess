package fixtures.androidx;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

class AndroidXRecyclerViewTest {
    @TargetMethod
    void useRecyclerView(RecyclerView recyclerView, android.content.Context context) {
        RecyclerView.Adapter adapter = recyclerView.getAdapter();
        recyclerView.setAdapter(adapter);
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
    }
    
    @TargetMethod
    void useAdapter(RecyclerView.Adapter adapter, android.view.ViewGroup parent) {
        RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);
        int count = adapter.getItemCount();
        adapter.notifyDataSetChanged();
        adapter.notifyItemInserted(0);
        adapter.notifyItemRemoved(0);
        adapter.notifyItemChanged(0);
    }
}

