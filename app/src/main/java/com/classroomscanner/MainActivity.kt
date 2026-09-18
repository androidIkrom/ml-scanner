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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.classroomscanner.databinding.ActivityMainBinding
import com.classroomscanner.core.ItemKind
import com.classroomscanner.core.ScanMode
import com.classroomscanner.core.ScreenHelp
import com.classroomscanner.core.VoiceCommand
import com.classroomscanner.core.VoiceCommandParser
import com.classroomscanner.guide.LearnerMode
import com.classroomscanner.guide.VoiceCommandTarget
import com.classroomscanner.guide.VoiceGuide
import com.classroomscanner.guide.VoiceListener

/**
 * Main entry point into our app. This app follows the single-activity pattern, and all
 * functionality is implemented in the form of fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var activityMainBinding: ActivityMainBinding

    /** Spoken help for blind users; dialogs shown over this activity use it too. */
    lateinit var voiceGuide: VoiceGuide
        private set

    private lateinit var learner: LearnerMode
    private lateinit var navController: NavController
    private var voiceListener: VoiceListener? = null

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setVoiceCommands(true) else voiceGuide.say(getString(R.string.mic_permission_needed))
        }

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
        navController = navHostFragment.navController
        activityMainBinding.toolbar.setupWithNavController(navController)

        learner = LearnerMode(this)
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
            if (learner.enabled) {
                // After the screen has settled, so its own first announcement is not cut off.
                val help = learner.helpFor(destination.id)
                if (help != null) {
                    activityMainBinding.root.postDelayed({ voiceGuide.say(help) }, SCREEN_HELP_DELAY_MS)
                }
            }
        }

        voiceListener = VoiceListener(this, ::onVoiceText) { voiceGuide.say(it) }
        showVoiceCommandState()
        activityMainBinding.voiceCommandToggle.setOnClickListener { toggleVoiceCommands() }
    }

    val learnerOn: Boolean get() = learner.enabled

    /** Turns the spoken screen introductions on or off and says what happened. */
    fun toggleLearnerMode(on: Boolean = !learner.enabled) {
        learner.enabled = on
        val here = learner.helpFor(navController.currentDestination?.id ?: 0)
        val text = getString(if (on) R.string.learner_on else R.string.learner_off)
        voiceGuide.say(if (on && here != null) "$text $here" else text)
    }

    /** Switches the always-on microphone on or off; asks for the permission the first time. */
    fun toggleVoiceCommands() {
        val listener = voiceListener ?: return
        if (listener.listening) {
            setVoiceCommands(false)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            setVoiceCommands(true)
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setVoiceCommands(on: Boolean) {
        val listener = voiceListener ?: return
        if (on && !listener.available) {
            voiceGuide.say(getString(R.string.voice_input_unavailable))
            return
        }
        voiceGuide.say(getString(if (on) R.string.voice_commands_on else R.string.voice_commands_off))
        if (on) listener.start() else listener.stop()
        showVoiceCommandState()
    }

    private fun showVoiceCommandState() {
        val on = voiceListener?.listening == true
        activityMainBinding.voiceCommandToggle.setImageResource(
            if (on) R.drawable.ic_mic_24 else R.drawable.ic_mic_off_24
        )
        activityMainBinding.voiceCommandToggle.contentDescription =
            getString(if (on) R.string.voice_commands_desc_on else R.string.voice_commands_desc_off)
    }

    /** The screen on top gets the command first; what it does not handle moves the app around. */
    private fun onVoiceText(text: String) {
        when (val command = VoiceCommandParser.parse(text)) {
            VoiceCommand.StopListening -> setVoiceCommands(false)
            VoiceCommand.Unknown -> voiceGuide.say(getString(R.string.voice_command_unknown))
            is VoiceCommand.Learner -> toggleLearnerMode(command.on)
            is VoiceCommand.Help -> explain(command.topic)
            else -> {
                val screen = currentFragment() as? VoiceCommandTarget
                if (screen?.onVoiceCommand(command) != true && !navigate(command)) {
                    voiceGuide.say(getString(R.string.voice_command_not_here))
                }
            }
        }
    }

    /** Answers a spoken question; an empty topic means "what is on this screen". */
    private fun explain(topic: String) {
        val text = if (topic.isEmpty()) {
            learner.helpFor(navController.currentDestination?.id ?: 0)
        } else {
            ScreenHelp.describe(topic)
        }
        voiceGuide.say(text ?: getString(R.string.help_unknown))
    }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragment_container)
            ?.childFragmentManager?.primaryNavigationFragment

    private fun navigate(command: VoiceCommand): Boolean {
        val directions = when (command) {
            VoiceCommand.Home -> return navController.popBackStack(R.id.home_fragment, false)
            VoiceCommand.Back, VoiceCommand.Stop -> return navController.popBackStack()
            VoiceCommand.FullScan -> NavGraphDirections.actionGlobalSettings(ScanMode.FULL)
            VoiceCommand.LiveScan -> NavGraphDirections.actionGlobalSettings(ScanMode.LIVE)
            VoiceCommand.History -> NavGraphDirections.actionGlobalHistory()
            VoiceCommand.Saved -> NavGraphDirections.actionGlobalSaved()
            is VoiceCommand.Search -> NavGraphDirections.actionGlobalSearch()
                .setQuery(command.query.ifEmpty { null })
            VoiceCommand.AddPerson -> NavGraphDirections.actionGlobalAddPerson()
            VoiceCommand.AddCar -> NavGraphDirections.actionGlobalAddItem(ItemKind.CAR.name)
            VoiceCommand.AddObject -> NavGraphDirections.actionGlobalAddItem(ItemKind.OBJECT.name)
            else -> return false
        }
        navController.navigate(directions)
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (this::voiceGuide.isInitialized) voiceGuide.onTouch(window.decorView, ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        // The microphone is not held while the app is in the background.
        if (voiceListener?.listening == true) {
            voiceListener?.stop()
            showVoiceCommandState()
        }
    }

    override fun onDestroy() {
        voiceListener?.release()
        if (this::voiceGuide.isInitialized) voiceGuide.shutdown()
        super.onDestroy()
    }

    private companion object {
        /** Lets the new screen finish opening before it introduces itself. */
        const val SCREEN_HELP_DELAY_MS = 700L
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
