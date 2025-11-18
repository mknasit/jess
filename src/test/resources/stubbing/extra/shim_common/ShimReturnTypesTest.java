package fixtures.shim_common;

import android.view.View;
import android.os.Bundle;
import android.content.Intent;

class ShimReturnTypesTest {
    @TargetMethod
    void testReturnTypes(View view, Bundle bundle, Intent intent) {
        // Test primitive return types
        int width = view.getWidth();
        int height = view.getHeight();
        
        // Test String return types
        String action = intent.getAction();
        String extra = intent.getStringExtra("key");
        String bundleValue = bundle.getString("key");
        
        // Test object return types
        android.net.Uri data = intent.getData();
        Bundle extras = intent.getExtras();
        
        // Test boolean return types
        boolean hasKey = bundle.containsKey("key");
    }
}

