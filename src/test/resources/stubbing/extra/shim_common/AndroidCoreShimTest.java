package fixtures.shim_common;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

class AndroidCoreShimTest {
    @TargetMethod
    void useAndroidCore(Context context, Intent intent, Bundle bundle) {
        String packageName = context.getPackageName();
        android.content.res.Resources res = context.getResources();
        
        String action = intent.getAction();
        android.net.Uri data = intent.getData();
        Bundle extras = intent.getExtras();
        String extra = intent.getStringExtra("key");
        
        bundle.putString("key", "value");
        String value = bundle.getString("key");
        int intValue = bundle.getInt("intKey");
        boolean boolValue = bundle.getBoolean("boolKey");
    }
}

