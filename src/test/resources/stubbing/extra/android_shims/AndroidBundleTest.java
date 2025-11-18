package fixtures.android;

import android.os.Bundle;
import android.content.Intent;

class AndroidBundleTest {
    @TargetMethod
    void useBundle(Bundle bundle) {
        bundle.putString("key", "value");
        String value = bundle.getString("key");
        bundle.putInt("intKey", 42);
        int intValue = bundle.getInt("intKey");
        bundle.putBoolean("boolKey", true);
        boolean boolValue = bundle.getBoolean("boolKey");
        boolean hasKey = bundle.containsKey("key");
    }
    
    @TargetMethod
    void useIntent(Intent intent) {
        String action = intent.getAction();
        android.net.Uri data = intent.getData();
        Bundle extras = intent.getExtras();
        intent.putExtra("key", "value");
        String extra = intent.getStringExtra("key");
        intent.setAction("ACTION");
    }
}

