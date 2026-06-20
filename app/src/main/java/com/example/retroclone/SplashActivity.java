package com.example.retroclone;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Random;

public class SplashActivity extends AppCompatActivity {

    private final String[] vibePhrases = {
            "What's the vibe?",
            "All aboard the Vibe Station.",
            "Next stop: Good vibes.",
            "Punching your ticket...",
            "Boarding the Vibe Train...",
            "Now departing for Vibe City.",
            "Checking the schedule...",
            "Setting the mood...",
            "Go hit those Prs.",
            "Powered by Insomnia.",
            "It's boogie time >:)",
            "Sesame has zoomies...",
            "Someone get the preworkout...",
            "Is it chest day yet?",
            "Hopefully not a leg day...",
            "Miku is watching you...",
            "Nico Robin, my beloved..."
    };

    private static final int SPLASH_DELAY_MS = 1500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        TextView splashVibeTextView = findViewById(R.id.txtSplashVibe);

        int randomIndex = new Random().nextInt(vibePhrases.length);
        splashVibeTextView.setText(vibePhrases[randomIndex]);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, SPLASH_DELAY_MS);
    }
}