package com.example.retroclone;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Random;

/**
 * Splash screen activity that runs at app launch. Displays a random vibe phrase
 * for a designated delay duration, then redirects users to the MainActivity.
 */
public class SplashActivity extends AppCompatActivity {

    /** List of random splash screen subtitle phrases that set the user's initial mood. */
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

    /** Delay duration in milliseconds before moving to the main player view. */
    private static final int SPLASH_DELAY_MS = 1500;

    /**
     * Initializes the splash layout, selects a random subtitle vibe phrase,
     * and sets up a delayed handler to launch MainActivity.
     *
     * @param savedInstanceState Saved instance state bundle.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        TextView splashVibeTextView = findViewById(R.id.txtSplashVibe);

        // Select a random phrase index from the list of vibe options
        int randomIndex = new Random().nextInt(vibePhrases.length);
        splashVibeTextView.setText(vibePhrases[randomIndex]);

        // Post a delayed runnable to shift execution contexts to MainActivity
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, SPLASH_DELAY_MS);
    }
}