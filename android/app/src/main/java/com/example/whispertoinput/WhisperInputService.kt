/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var isFirstTime: Boolean = true

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            currentInputConnection?.commitText(text, 1)
            // Check if auto-switch-back is enabled and switch if so
            CoroutineScope(Dispatchers.Main).launch {
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    onSwitchIme()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()
        
        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)

        // Preload conversion table
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        // Initialize audio format based on backend setting
        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }

        // Returns the keyboard after setting it up and inflating its layout
        val view = whisperKeyboard.setup(layoutInflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { onAtSymbol() },
            { onNewline() },
            { digit -> onDigit(digit) },
            { onCursorLeft() },
            { onCursorRight() },
            { onSend() },
            { style -> onKuroppiStyleChange(style) },
            { convMode -> onKuroppiConvModeChange(convMode) },
            { onClearChunk() },
            { onClearAll() },
            { shouldShowRetry() },
        )

        // Seed Kuroppi button state from persisted preferences.
        CoroutineScope(Dispatchers.Main).launch {
            val (style, convMode) = dataStore.data.map { prefs: Preferences ->
                Pair(prefs[KUROPPI_STYLE] ?: "raw", prefs[KUROPPI_CONV_MODE] ?: false)
            }.first()
            whisperKeyboard.setKuroppiState(style, convMode)
        }

        return view
    }

    /** Persist the new Kuroppi style selection (fire-and-forget from the UI). */
    private fun onKuroppiStyleChange(style: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { it[KUROPPI_STYLE] = style }
        }
    }

    /** Persist the new Kuroppi conversation-mode selection. */
    private fun onKuroppiConvModeChange(convMode: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { it[KUROPPI_CONV_MODE] = convMode }
        }
    }

    /** 全消去ボタン short-tap: delete a chunk (5 chars back) for fast rollback
     *  of the last inserted phrase (especially くろっぴー's conversation reply). */
    private fun onClearChunk() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!TextUtils.isEmpty(selectedText)) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(5, 0)
        }
    }

    /** 全消去ボタン long-press: wipe the entire visible field.
     *  deleteSurroundingText(large, large) is the simplest portable way to
     *  clear — it reaches up to the input buffer boundary in either
     *  direction. Not all apps expose the full buffer, so on very long
     *  fields this may only clear what's visible; that's acceptable for
     *  the intended "erase kuroppi's last reply" use case. */
    private fun onClearAll() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(10000, 10000)
    }

    private fun onAtSymbol() {
        currentInputConnection?.commitText("@", 1)
    }

    private fun onNewline() {
        // Always inserts a literal newline, regardless of the destination field's
        // Enter-action. Counterpart to onEnter(), which respects the field's action.
        currentInputConnection?.commitText("\n", 1)
    }

    private fun onDigit(digit: String) {
        currentInputConnection?.commitText(digit, 1)
    }

    private fun onCursorLeft() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    private fun onCursorRight() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    private fun onSend() {
        // Force IME_ACTION_SEND regardless of the field's declared action.
        // Falls back to KEYCODE_ENTER if the field doesn't accept the editor action.
        val ic = currentInputConnection ?: return
        val accepted = ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        if (!accepted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        recorderManager!!.start(this, recordedAudioFilename, useOggFormat)
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)
    }

    private fun onCancelRecording() {
        recorderManager!!.stop()
    }

    private fun onStartTranscription(attachToEnd: String) {
        recorderManager!!.stop()
        whisperTranscriber.startAsync(this,
            recordedAudioFilename,
            audioMediaType,
            attachToEnd,
            { transcriptionCallback(it) },
            { transcriptionExceptionCallback(it) })
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        // Decide newline-vs-submit based on the destination field's IME hints.
        // - Fields that explicitly disable the Enter action (IME_FLAG_NO_ENTER_ACTION) or
        //   don't set one (IME_ACTION_NONE / UNSPECIFIED) are multi-line — insert a literal
        //   newline so the user can compose multi-paragraph text.
        // - Fields with a real action (Send, Done, Search, …) get a proper DOWN+UP key
        //   stroke so the app triggers its action naturally. (Single DOWN was silently
        //   dropped by modern editors before, which is why Enter appeared inert.)
        // Note: Discord's compose field sets IME_ACTION_SEND by default, so Enter will
        // submit there — use the explicit "改行" button (Phase 2) to insert a newline.
        val imeOptions = currentInputEditorInfo?.imeOptions ?: 0
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val hasRealAction = !noEnterAction &&
                action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED

        if (hasRealAction) {
            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        } else {
            inputConnection.commitText("\n", 1)
        }
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Update audio format based on current backend setting
            updateAudioFormat()
            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }
}
