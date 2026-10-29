package com.iqsoft.ibnseerin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

public class SplashActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openHome = () -> {
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(1024, 1024); // Full-screen splash
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.splash_screen);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setContentDescription("IbnSeerin Dream Dictionary splash screen");
        setContentView(image);
        handler.postDelayed(openHome, 1800);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openHome);
        super.onDestroy();
    }
}
