/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.classroomscanner

import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.classroomscanner.databinding.ActivityMainBinding
import com.classroomscanner.guide.VoiceGuide

/**
 * Main entry point into our app. This app follows the single-activity pattern, and all
 * functionality is implemented in the form of fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var activityMainBinding: ActivityMainBinding

    /** Spoken help for blind users; dialogs shown over this activity use it too. */
    lateinit var voiceGuide: VoiceGuide
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        ViewCompat.setOnApplyWindowInsetsListener(activityMainBinding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        // Titles come from destination labels; every screen except Home gets a back arrow.
        val navController = navHostFragment.navController
        activityMainBinding.toolbar.setupWithNavController(navController)

        voiceGuide = VoiceGuide(this)
        showVoiceGuideState()
        activityMainBinding.voiceGuideToggle.setOnClickListener {
            voiceGuide.enabled = !voiceGuide.enabled
            showVoiceGuideState()
            voiceGuide.say(getString(if (voiceGuide.enabled) R.string.voice_guide_on else R.string.voice_guide_off))
        }
        // The on/off button is shown on Home only.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            activityMainBinding.voiceGuideToggle.isVisible = destination.id == R.id.home_fragment
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (this::voiceGuide.isInitialized) voiceGuide.onTouch(window.decorView, ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        if (this::voiceGuide.isInitialized) voiceGuide.shutdown()
        super.onDestroy()
    }

    private fun showVoiceGuideState() {
        val on = voiceGuide.enabled
        activityMainBinding.voiceGuideToggle.setImageResource(
            if (on) R.drawable.ic_volume_up_24 else R.drawable.ic_volume_off_24
        )
        activityMainBinding.voiceGuideToggle.contentDescription =
            getString(if (on) R.string.voice_guide_desc_on else R.string.voice_guide_desc_off)
    }
}
