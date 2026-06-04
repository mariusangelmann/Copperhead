package com.copperhead.gateway

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.copperhead.gateway.util.Preferences

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val icon = findViewById<ImageView>(R.id.splashIcon)
        val title = findViewById<TextView>(R.id.splashTitle)
        val subtitle = findViewById<TextView>(R.id.splashSubtitle)

        // Fade in + slight slide up
        val fadeIcon = ObjectAnimator.ofFloat(icon, "alpha", 0f, 1f).setDuration(600)
        val slideIcon = ObjectAnimator.ofFloat(icon, "translationY", 40f, 0f).setDuration(600)
        val fadeTitle = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f).setDuration(500)
        val slideTitle = ObjectAnimator.ofFloat(title, "translationY", 30f, 0f).setDuration(500)
        val fadeSub = ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f).setDuration(400)

        AnimatorSet().apply {
            playTogether(fadeIcon, slideIcon)
            interpolator = DecelerateInterpolator()
            start()
        }

        AnimatorSet().apply {
            playTogether(fadeTitle, slideTitle)
            interpolator = DecelerateInterpolator()
            startDelay = 200
            start()
        }

        fadeSub.apply {
            startDelay = 400
            start()
        }

        // Navigate after delay
        icon.postDelayed({
            val prefs = Preferences(this)
            val target = if (prefs.setupCompleted) {
                Intent(this, MainActivity::class.java)
            } else {
                Intent(this, SetupActivity::class.java)
            }
            startActivity(target)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1500)
    }
}
