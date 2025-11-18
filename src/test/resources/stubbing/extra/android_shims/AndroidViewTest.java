package fixtures.android;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ImageView;

class AndroidViewTest {
    @TargetMethod
    void useViews(View view, ViewGroup parent) {
        View child = view.findViewById(0);
        int visible = 0; // View.VISIBLE
        view.setVisibility(visible);
        int width = view.getWidth();
        int height = view.getHeight();
        
        parent.addView(view);
        parent.removeView(view);
        int count = parent.getChildCount();
    }
    
    @TargetMethod
    void useWidgets(TextView textView, Button button, ImageView imageView) {
        textView.setText("Hello");
        String text = textView.getText().toString();
        textView.setTextColor(0);
        textView.setTextSize(14.0f);
        
        imageView.setImageResource(0);
    }
}

