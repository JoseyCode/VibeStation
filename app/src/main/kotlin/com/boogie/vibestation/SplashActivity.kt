package com.boogie.vibestation

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash screen activity that runs at app launch. Displays a random vibe phrase
 * for a designated delay duration, then redirects users to the MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    /**
     * Initializes the splash layout, selects a random subtitle vibe phrase,
     * and sets up a delayed handler to launch MainActivity.
     *
     * @param savedInstanceState Saved instance state bundle.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        findViewById<TextView>(R.id.txtSplashVibe).text = VIBE_PHRASES.random()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_DELAY_MS)
    }

    private companion object {
        /** Delay duration in milliseconds before moving to the main player view. */
        const val SPLASH_DELAY_MS = 1500L

        /** Random splash screen subtitle phrases that set the user's initial mood. */
        val VIBE_PHRASES = listOf(
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
        )
    }
}
