package fixtures.shim_common;

import android.view.View;
import android.os.Bundle;
import android.content.Intent;

class ShimParameterTypesTest {
    @TargetMethod
    void testParameterTypes(View view, Bundle bundle, Intent intent) {
        // Test primitive parameters
        view.setVisibility(0);
        bundle.putInt("intKey", 42);
        bundle.putBoolean("boolKey", true);
        
        // Test String parameters
        bundle.putString("key", "value");
        intent.putExtra("key", "value");
        intent.setAction("ACTION");
        
        // Test object parameters
        Intent newIntent = new Intent();
        context.startActivity(newIntent);
    }
    
    private android.content.Context context;
}

