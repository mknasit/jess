package fixtures.android;

import android.content.Context;
import android.app.Activity;

class AndroidContextTest {
    @TargetMethod
    void useContext(Context context) {
        String packageName = context.getPackageName();
        android.content.res.Resources res = context.getResources();
        context.startActivity(new android.content.Intent());
        context.sendBroadcast(new android.content.Intent());
    }
    
    @TargetMethod
    void useActivity(Activity activity, android.os.Bundle bundle) {
        activity.onCreate(bundle);
        activity.setContentView(0);
        activity.findViewById(0);
        activity.finish();
    }
}

