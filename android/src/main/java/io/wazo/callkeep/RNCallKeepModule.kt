/*
 * Copyright (c) 2016-2019 The CallKeep Authors (see the AUTHORS file)
 * SPDX-License-Identifier: ISC, MIT
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package io.wazo.callkeep

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.modules.core.DeviceEventManagerModule.*
import com.facebook.react.modules.permissions.PermissionsModule
import org.json.JSONException
import org.json.JSONObject
import java.util.Arrays

// @see https://github.com/kbagchiGWC/voice-quickstart-android/blob/9a2aff7fbe0d0a5ae9457b48e9ad408740dfb968/exampleConnectionService/src/main/java/com/twilio/voice/examples/connectionservice/VoiceConnectionServiceActivity.java
class RNCallKeepModule private constructor(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), LifecycleEventListener {
    private var legacyCallStateListener: LegacyCallStateListener? = null
    private var callStateListener: CallStateListener? = null
    private var reactContext: ReactApplicationContext?
    private var isReceiverRegistered = false
    private var voiceBroadcastReceiver: VoiceBroadcastReceiver? = null
    private var delayedEvents: WritableNativeArray
    private var hasListeners = false
    private var hasActiveCall = false

    init {
        // This line for listening to the Activity Lifecycle Events so we can end the calls onDestroy
        reactContext.addLifecycleEventListener(this)
        Log.d(TAG, "[RNCallKeepModule] constructor")

        this.reactContext = reactContext
        delayedEvents = WritableNativeArray()
    }

    private val isSelfManaged: Boolean
        get() = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && _settings.hasKey(
                "selfManaged"
            ) && _settings.getBoolean("selfManaged")
        } catch (e: Exception) {
            false
        }

    var context: ReactApplicationContext?
        get() = this.reactContext
        set(reactContext) {
            Log.d(
                TAG,
                "[RNCallKeepModule] updating react context"
            )
            this.reactContext = reactContext
        }

    fun reportNewIncomingCall(
        uuid: String,
        number: String,
        callerName: String,
        hasVideo: Boolean,
        payload: String?
    ) {
        Log.d(
            TAG,
            "[RNCallKeepModule] reportNewIncomingCall, uuid: $uuid, number: $number, callerName: $callerName"
        )

        this.displayIncomingCall(uuid, number, callerName, hasVideo)

        // Send event to JS
        val args: WritableMap = Arguments.createMap()
        args.putString("handle", number)
        args.putString("callUUID", uuid)
        args.putString("name", callerName)
        args.putString("hasVideo", hasVideo.toString())
        if (payload != null) {
            args.putString("payload", payload)
        }
        sendEventToJS("RNCallKeepDidDisplayIncomingCall", args)
    }

    fun startObserving() {
        val count: Int = delayedEvents.size()
        Log.d(
            TAG,
            "[RNCallKeepModule] startObserving, event count: $count"
        )
        if (count > 0) {
            reactContext.getJSModule<RCTDeviceEventEmitter>(RCTDeviceEventEmitter::class.java)
                .emit("RNCallKeepDidLoadWithEvents", delayedEvents)
            delayedEvents = WritableNativeArray()
        }
    }

    fun initializeTelecomManager() {
        val context = this.appContext
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][initializeTelecomManager] no react context found.")
            return
        }
        val cName = ComponentName(
            context,
            VoiceConnectionService::class.java
        )
        val appName = this.getApplicationName(context)

        handle = PhoneAccountHandle(cName, appName)
        telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    }


    /**
     * Monitors and logs phone call activities, and shows the phone state
     */
    private inner class LegacyCallStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {}
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Phone call is active -- off the hook.
                    // Check if there is active call in native
                    val isInManagedCall = this@RNCallKeepModule.checkIsInManagedCall()

                    // Only let the JS side know if there is active app call & active native call
                    if (this@RNCallKeepModule.hasActiveCall && isInManagedCall) {
                        val args: WritableMap = Arguments.createMap()
                        this@RNCallKeepModule.sendEventToJS("RNCallKeepHasActiveCall", args)
                    } else if (VoiceConnectionService.currentConnections.size > 0) {
                        // Will enter here for the first time to mark the app has active call
                        this@RNCallKeepModule.hasActiveCall = true
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {}
                else -> {}
            }
        }
    }

    private inner class CallStateListener : TelephonyCallback(),
        TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {}
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // Phone call is active -- off the hook.

                    // Check if there is active call in native
                    val isInManagedCall = this@RNCallKeepModule.checkIsInManagedCall()

                    // Only let the JS side know if there is active app call & active native call
                    if (this@RNCallKeepModule.hasActiveCall && isInManagedCall) {
                        val args: WritableMap = Arguments.createMap()
                        this@RNCallKeepModule.sendEventToJS("RNCallKeepHasActiveCall", args)
                    } else if (VoiceConnectionService.currentConnections.size > 0) {
                        // Will enter here for the first time to mark the app has active call
                        this@RNCallKeepModule.hasActiveCall = true
                    }
                }

                TelephonyManager.CALL_STATE_IDLE -> {}
                else -> {}
            }
        }
    }

    fun stopListenToNativeCallsState() {
        Log.d(TAG, "[RNCallKeepModule] stopListenToNativeCallsState")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && callStateListener != null) {
            telephonyManager!!.unregisterTelephonyCallback(
                callStateListener!!
            )
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && legacyCallStateListener != null) {
            telephonyManager!!.listen(legacyCallStateListener, PhoneStateListener.LISTEN_NONE)
            Looper.myLooper()!!.quit()
        }
    }

    fun listenToNativeCallsState() {
        Log.d(TAG, "[RNCallKeepModule] listenToNativeCallsState")
        val context = this.appContext
        val permissionCheck = ContextCompat.checkSelfPermission(
            context!!, Manifest.permission.READ_PHONE_STATE
        )

        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callStateListener = CallStateListener()
                telephonyManager!!.registerTelephonyCallback(
                    context.mainExecutor, callStateListener!!
                )
            } else {
                if (Looper.myLooper() == null) {
                    Looper.prepare()
                }
                legacyCallStateListener = LegacyCallStateListener()
                telephonyManager!!.listen(
                    legacyCallStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE
                )
                Looper.loop()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun checkIsInManagedCall(): Boolean {
        val context = this.appContext
        val permissionCheck = ContextCompat.checkSelfPermission(
            context!!, Manifest.permission.READ_PHONE_STATE
        )

        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            return telecomManager!!.isInManagedCall
        }
        return false
    }

    @ReactMethod
    fun checkIsInManagedCall(promise: Promise) {
        val isInManagedCall = this.checkIsInManagedCall()
        promise.resolve(isInManagedCall)
    }

    @ReactMethod
    fun setSettings(options: ReadableMap?) {
        Log.d(
            TAG,
            "[RNCallKeepModule] setSettings : $options"
        )
        if (options == null) {
            return
        }
        _settings = storeSettings(options)
    }

    @ReactMethod
    fun addListener(eventName: String?) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    fun removeListeners(count: Int?) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    fun setup(options: ReadableMap) {
        Log.d(
            TAG,
            "[RNCallKeepModule] setup : $options"
        )

        VoiceConnectionService.setAvailable(false)
        VoiceConnectionService.setInitialized(true)
        this.setSettings(options)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isSelfManaged) {
                Log.d(
                    TAG,
                    "[RNCallKeepModule] API Version supports self managed, and is enabled in setup"
                )
            } else {
                Log.d(
                    TAG,
                    "[RNCallKeepModule] API Version supports self managed, but it is not enabled in setup"
                )
            }
        }

        // If we're running in self managed mode we need fewer permissions.
        if (isSelfManaged) {
            Log.d(
                TAG,
                "[RNCallKeepModule] setup, adding RECORD_AUDIO in permissions in self managed"
            )
            permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
        }

        if (isConnectionServiceAvailable) {
            this.registerPhoneAccount(options)
            this.registerEvents()
            this.startObserving()
            VoiceConnectionService.setAvailable(true)
        }
    }

    @ReactMethod
    fun registerPhoneAccount(options: ReadableMap?) {
        setSettings(options)

        if (!isConnectionServiceAvailable) {
            Log.w(
                TAG,
                "[RNCallKeepModule] registerPhoneAccount ignored due to no ConnectionService"
            )
            return
        }

        Log.d(TAG, "[RNCallKeepModule] registerPhoneAccount")
        val context = this.appContext
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][registerPhoneAccount] no react context found.")
            return
        }

        this.registerPhoneAccount(context)
    }

    @ReactMethod
    fun registerEvents() {
        if (!isConnectionServiceAvailable) {
            Log.w(TAG, "[RNCallKeepModule] registerEvents ignored due to no ConnectionService")
            return
        }

        Log.d(TAG, "[RNCallKeepModule] registerEvents")

        this.hasListeners = true
        this.startObserving()
        VoiceConnectionService.setPhoneAccountHandle(handle)
    }

    @ReactMethod
    fun unregisterEvents() {
        Log.d(TAG, "[RNCallKeepModule] unregisterEvents")

        this.hasListeners = false
    }

    @ReactMethod
    fun displayIncomingCall(uuid: String, number: String, callerName: String) {
        this.displayIncomingCall(uuid, number, callerName, false, null)
    }

    @ReactMethod
    fun displayIncomingCall(uuid: String, number: String, callerName: String, hasVideo: Boolean) {
        this.displayIncomingCall(uuid, number, callerName, hasVideo, null)
    }

    fun displayIncomingCall(
        uuid: String,
        number: String,
        callerName: String,
        hasVideo: Boolean,
        payload: Bundle?
    ) {
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            Log.w(
                TAG,
                "[RNCallKeepModule] displayIncomingCall ignored due to no ConnectionService or no phone account"
            )
            return
        }

        Log.d(
            TAG,
            "[RNCallKeepModule] displayIncomingCall, uuid: $uuid, number: $number, callerName: $callerName, hasVideo: $hasVideo, payload: $payload"
        )

        val extras = Bundle()
        val uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)

        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, uri)
        extras.putString(Constants.EXTRA_CALLER_NAME, callerName)
        extras.putString(Constants.EXTRA_CALL_UUID, uuid)
        extras.putString(Constants.EXTRA_HAS_VIDEO, hasVideo.toString())
        if (payload != null) {
            extras.putBundle(Constants.EXTRA_PAYLOAD, payload)
        }
        this.listenToNativeCallsState()
        telecomManager!!.addNewIncomingCall(handle, extras)
    }

    @ReactMethod
    fun answerIncomingCall(uuid: String) {
        Log.d(
            TAG,
            "[RNCallKeepModule] answerIncomingCall, uuid: $uuid"
        )
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            Log.w(
                TAG,
                "[RNCallKeepModule] answerIncomingCall ignored due to no ConnectionService or no phone account"
            )
            return
        }

        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] answerIncomingCall ignored because no connection found, uuid: $uuid"
            )
            return
        }

        conn.onAnswer()
    }

    @ReactMethod
    fun startCall(uuid: String, number: String?, callerName: String) {
        this.startCall(uuid, number, callerName, false, null)
    }

    @ReactMethod
    fun startCall(uuid: String, number: String?, callerName: String, hasVideo: Boolean) {
        this.startCall(uuid, number, callerName, hasVideo, null)
    }

    fun startCall(
        uuid: String,
        number: String?,
        callerName: String,
        hasVideo: Boolean,
        payload: Bundle?
    ) {
        Log.d(
            TAG,
            "[RNCallKeepModule] startCall called, uuid: $uuid, number: $number, callerName: $callerName, payload: $payload"
        )

        if (!isConnectionServiceAvailable || !hasPhoneAccount() || !hasPermissions() || number == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] startCall ignored: " + isConnectionServiceAvailable + ", " + hasPhoneAccount() + ", " + hasPermissions() + ", " + number
            )
            return
        }

        val extras = Bundle()
        val uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)

        val callExtras = Bundle()
        callExtras.putString(Constants.EXTRA_CALLER_NAME, callerName)
        callExtras.putString(Constants.EXTRA_CALL_UUID, uuid)
        callExtras.putString(Constants.EXTRA_CALL_NUMBER, number)
        callExtras.putString(Constants.EXTRA_HAS_VIDEO, hasVideo.toString())
        if (payload != null) {
            callExtras.putBundle(Constants.EXTRA_PAYLOAD, payload)
        }

        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        extras.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)

        Log.d(
            TAG,
            "[RNCallKeepModule] startCall, uuid: $uuid"
        )
        this.listenToNativeCallsState()
        telecomManager!!.placeCall(uri, extras)
    }

    @ReactMethod
    fun endCall(uuid: String) {
        Log.d(
            TAG,
            "[RNCallKeepModule] endCall called, uuid: $uuid"
        )
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            Log.w(
                TAG,
                "[RNCallKeepModule] endCall ignored due to no ConnectionService or no phone account"
            )
            return
        }

        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] endCall ignored because no connection found, uuid: $uuid"
            )
            return
        }
        val context = this.appContext
        val audioManager = context!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = 0
        conn.onDisconnect()
        this.stopListenToNativeCallsState()
        this.hasActiveCall = false
        Log.d(
            TAG,
            "[RNCallKeepModule] endCall executed, uuid: $uuid"
        )
    }

    @ReactMethod
    fun endAllCalls() {
        Log.d(TAG, "[RNCallKeepModule] endAllCalls called")
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            Log.w(
                TAG,
                "[RNCallKeepModule] endAllCalls ignored due to no ConnectionService or no phone account"
            )
            return
        }

        val connections =
            ArrayList<Map.Entry<String, VoiceConnection>>(VoiceConnectionService.currentConnections.entries)
        for ((_, connectionToEnd) in connections) {
            connectionToEnd.onDisconnect()
        }
        this.stopListenToNativeCallsState()
        this.hasActiveCall = false
        Log.d(TAG, "[RNCallKeepModule] endAllCalls executed")
    }

    @ReactMethod
    fun checkPhoneAccountPermission(optionalPermissions: ReadableArray, promise: Promise) {
        val currentActivity = this.currentReactActivity

        if (!isConnectionServiceAvailable) {
            val error = "ConnectionService not available for this version of Android."
            Log.w(
                TAG,
                "[RNCallKeepModule] checkPhoneAccountPermission error $error"
            )
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, error)
            return
        }
        if (currentActivity == null) {
            val error = "Activity doesn't exist"
            Log.w(
                TAG,
                "[RNCallKeepModule] checkPhoneAccountPermission error $error"
            )
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, error)
            return
        }
        val optionalPermsArr = arrayOfNulls<String>(optionalPermissions.size())
        for (i in 0 until optionalPermissions.size()) {
            optionalPermsArr[i] = optionalPermissions.getString(i)
        }

        val allPermissions = permissions.copyOf(permissions.size + optionalPermsArr.size)
        System.arraycopy(
            optionalPermsArr,
            0,
            allPermissions,
            permissions.size,
            optionalPermsArr.size
        )

        hasPhoneAccountPromise = promise

        if (!this.hasPermissions()) {
            val allPermissionaw: WritableArray = Arguments.createArray()
            for (allPermission in allPermissions) {
                allPermissionaw.pushString(allPermission)
            }

            reactContext
                .getNativeModule<PermissionsModule>(PermissionsModule::class.java)
                .requestMultiplePermissions(allPermissionaw, object : Promise {
                    override fun resolve(value: Any?) {
                        val grantedPermission: WritableMap? = value as WritableMap?
                        val grantedResult = IntArray(allPermissions.size)
                        for (i in allPermissions.indices) {
                            val perm = allPermissions[i]
                            grantedResult[i] = if (grantedPermission.getString(perm) == "granted")
                                PackageManager.PERMISSION_GRANTED
                            else
                                PackageManager.PERMISSION_DENIED
                        }
                        onRequestPermissionsResult(
                            REQUEST_READ_PHONE_STATE,
                            allPermissions,
                            grantedResult
                        )
                    }

                    override fun reject(code: String, message: String?) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(code: String, throwable: Throwable?) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(code: String, message: String?, throwable: Throwable?) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(throwable: Throwable) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(throwable: Throwable, userInfo: WritableMap) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(code: String, userInfo: WritableMap) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(
                        code: String,
                        throwable: Throwable?,
                        userInfo: WritableMap
                    ) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(code: String, message: String?, userInfo: WritableMap) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(
                        code: String?,
                        message: String?,
                        throwable: Throwable?,
                        userInfo: WritableMap?
                    ) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }

                    override fun reject(message: String) {
                        hasPhoneAccountPromise!!.resolve(false)
                    }
                })
            return
        }

        promise.resolve(!hasPhoneAccount())
    }

    @ReactMethod
    fun checkDefaultPhoneAccount(promise: Promise) {
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            promise.resolve(true)
            return
        }

        if (!Build.MANUFACTURER.equals("Samsung", ignoreCase = true)) {
            promise.resolve(true)
            return
        }

        val hasSim = telephonyManager!!.simState != TelephonyManager.SIM_STATE_ABSENT
        val hasDefaultAccount = telecomManager!!.getDefaultOutgoingPhoneAccount("tel") != null

        promise.resolve(!hasSim || hasDefaultAccount)
    }

    @ReactMethod
    fun getInitialEvents(promise: Promise) {
        promise.resolve(delayedEvents)
    }

    @ReactMethod
    fun clearInitialEvents() {
        delayedEvents = WritableNativeArray()
    }

    @ReactMethod
    fun setOnHold(uuid: String, shouldHold: Boolean) {
        Log.d(
            TAG,
            "[RNCallKeepModule] setOnHold, uuid: " + uuid + ", shouldHold: " + (if (shouldHold) "true" else "false")
        )

        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] setOnHold ignored because no connection found, uuid: $uuid"
            )
            return
        }

        if (shouldHold == true) {
            conn.onHold()
        } else {
            conn.onUnhold()
        }
    }

    @ReactMethod
    fun reportEndCallWithUUID(uuid: String, reason: Int) {
        Log.d(
            TAG,
            "[RNCallKeepModule] reportEndCallWithUUID, uuid: $uuid, reason: $reason"
        )
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            return
        }

        val conn = VoiceConnectionService.getConnection(uuid) as VoiceConnection
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] reportEndCallWithUUID ignored because no connection found, uuid: $uuid"
            )
            return
        }
        conn.reportDisconnect(reason)

        this.stopListenToNativeCallsState()
    }

    override fun onHostResume() {
    }

    override fun onHostPause() {
    }

    override fun onHostDestroy() {
        // When activity destroyed end all calls
        Log.d(TAG, "[RNCallKeepModule] onHostDestroy called")
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            Log.w(
                TAG,
                "[RNCallKeepModule] onHostDestroy ignored due to no ConnectionService or no phone account"
            )
            return
        }

        val connections =
            ArrayList<Map.Entry<String, VoiceConnection>>(VoiceConnectionService.currentConnections.entries)
        for ((_, connectionToEnd) in connections) {
            connectionToEnd.onDisconnect()
        }
        this.stopListenToNativeCallsState()
        Log.d(TAG, "[RNCallKeepModule] onHostDestroy executed")
        // This line will kill the android process after ending all calls
        Process.killProcess(Process.myPid())
    }

    @ReactMethod
    fun rejectCall(uuid: String) {
        Log.d(
            TAG,
            "[RNCallKeepModule] rejectCall, uuid: $uuid"
        )
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            Log.w(
                TAG,
                "[RNCallKeepModule] rejectCall ignored due to no ConnectionService or no phone account"
            )
            return
        }

        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] rejectCall ignored because no connection found, uuid: $uuid"
            )
            return
        }
        this.stopListenToNativeCallsState()
        conn.onReject()
    }

    @ReactMethod
    fun setConnectionState(uuid: String, state: Int) {
        Log.d(
            TAG,
            "[RNCallKeepModule] setConnectionState, uuid: $uuid, state :$state"
        )
        if (!isConnectionServiceAvailable || !hasPhoneAccount()) {
            Log.w(
                TAG,
                "[RNCallKeepModule] String ignored due to no ConnectionService or no phone account"
            )
            return
        }

        VoiceConnectionService.setState(uuid, state)
    }

    @ReactMethod
    fun setMutedCall(uuid: String, shouldMute: Boolean) {
        Log.d(
            TAG,
            "[RNCallKeepModule] setMutedCall, uuid: " + uuid + ", shouldMute: " + (if (shouldMute) "true" else "false")
        )
        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] setMutedCall ignored because no connection found, uuid: $uuid"
            )
            return
        }

        var newAudioState: CallAudioState? = null
        //if the requester wants to mute, do that. otherwise unmute
        newAudioState = if (shouldMute) {
            CallAudioState(
                true, conn.callAudioState.route,
                conn.callAudioState.supportedRouteMask
            )
        } else {
            CallAudioState(
                false, conn.callAudioState.route,
                conn.callAudioState.supportedRouteMask
            )
        }
        conn.onCallAudioStateChanged(newAudioState)
    }

    /**
     * toggle audio route for speaker via connection service function
     * @param uuid
     * @param routeSpeaker
     */
    @ReactMethod
    fun toggleAudioRouteSpeaker(uuid: String, routeSpeaker: Boolean) {
        Log.d(
            TAG,
            "[RNCallKeepModule] toggleAudioRouteSpeaker, uuid: " + uuid + ", routeSpeaker: " + (if (routeSpeaker) "true" else "false")
        )
        val conn = VoiceConnectionService.getConnection(uuid) as VoiceConnection
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] toggleAudioRouteSpeaker ignored because no connection found, uuid: $uuid"
            )
            return
        }
        if (routeSpeaker) {
            conn.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        } else {
            conn.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
        }
    }

    @ReactMethod
    fun setAudioRoute(uuid: String?, audioRoute: String, promise: Promise) {
        try {
            val conn =
                VoiceConnectionService.getConnection(uuid) as VoiceConnection
                    ?: return
            if (audioRoute == "Bluetooth") {
                Log.d(TAG, "[RNCallKeepModule] setting audio route: Bluetooth")
                conn.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                promise.resolve(true)
                return
            }
            if (audioRoute == "Headset") {
                Log.d(TAG, "[RNCallKeepModule] setting audio route: Headset")
                conn.setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET)
                promise.resolve(true)
                return
            }
            if (audioRoute == "Speaker") {
                Log.d(TAG, "[RNCallKeepModule] setting audio route: Speaker")
                conn.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                promise.resolve(true)
                return
            }
            Log.d(TAG, "[RNCallKeepModule] setting audio route: Wired/Earpiece")
            conn.setAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SetAudioRoute", e.message)
        }
    }

    @ReactMethod
    fun getAudioRoutes(promise: Promise) {
        try {
            val context = this.appContext
            if (context == null) {
                Log.w(TAG, "[RNCallKeepModule][getAudioRoutes] no react context found.")
                promise.reject("No react context found to list audio routes")
                return
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices: WritableArray = Arguments.createArray()
            val typeChecker = ArrayList<String>()
            val audioDeviceInfo =
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS + AudioManager.GET_DEVICES_OUTPUTS)
            val selectedAudioRoute = getSelectedAudioRoute(audioManager)
            for (device in audioDeviceInfo) {
                val type = getAudioRouteType(device.type)
                if (type != null && !typeChecker.contains(type)) {
                    val deviceInfo: WritableMap = Arguments.createMap()
                    deviceInfo.putString("name", type)
                    deviceInfo.putString("type", type)
                    if (type == selectedAudioRoute) {
                        deviceInfo.putBoolean("selected", true)
                    }
                    typeChecker.add(type)
                    devices.pushMap(deviceInfo)
                }
            }
            promise.resolve(devices)
        } catch (e: Exception) {
            promise.reject("GetAudioRoutes Error", e.message)
        }
    }

    private fun getAudioRouteType(type: Int): String? {
        return when (type) {
            (AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), (AudioDeviceInfo.TYPE_BLUETOOTH_SCO) -> "Bluetooth"
            (AudioDeviceInfo.TYPE_WIRED_HEADPHONES), (AudioDeviceInfo.TYPE_WIRED_HEADSET) -> "Headset"
            (AudioDeviceInfo.TYPE_BUILTIN_MIC) -> "Phone"
            (AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) -> "Speaker"
            else -> null
        }
    }

    private fun getSelectedAudioRoute(audioManager: AudioManager): String {
        if (audioManager.isBluetoothScoOn) {
            return "Bluetooth"
        }
        if (audioManager.isSpeakerphoneOn) {
            return "Speaker"
        }
        if (audioManager.isWiredHeadsetOn) {
            return "Headset"
        }
        return "Phone"
    }

    @ReactMethod
    fun sendDTMF(uuid: String, key: String) {
        Log.d(
            TAG,
            "[RNCallKeepModule] sendDTMF, uuid: $uuid, key: $key"
        )
        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] sendDTMF ignored because no connection found, uuid: $uuid"
            )
            return
        }
        val dtmf = key[0]
        conn.onPlayDtmfTone(dtmf)
    }

    @ReactMethod
    fun updateDisplay(uuid: String, displayName: String, uri: String) {
        Log.d(
            TAG,
            "[RNCallKeepModule] updateDisplay, uuid: $uuid, displayName: $displayName, uri: $uri"
        )
        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] updateDisplay ignored because no connection found, uuid: $uuid"
            )
            return
        }

        conn.setAddress(Uri.parse(uri), TelecomManager.PRESENTATION_ALLOWED)
        conn.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED)
    }

    @ReactMethod
    fun hasPhoneAccount(promise: Promise) {
        if (telecomManager == null) {
            this.initializeTelecomManager()
        }

        promise.resolve(hasPhoneAccount())
    }

    @ReactMethod
    fun hasOutgoingCall(promise: Promise) {
        promise.resolve(VoiceConnectionService.hasOutgoingCall)
    }

    @ReactMethod
    fun hasPermissions(promise: Promise) {
        promise.resolve(this.hasPermissions())
    }

    @ReactMethod
    fun setAvailable(active: Boolean) {
        VoiceConnectionService.setAvailable(active)
    }

    @ReactMethod
    fun setForegroundServiceSettings(foregroundServerSettings: ReadableMap?) {
        if (foregroundServerSettings == null) {
            return
        }

        // Retrieve settings and set the `foregroundService` value
        val settings: WritableMap? = getSettings(null)
        if (settings != null) {
            settings.putMap(
                "foregroundService",
                MapUtils.readableToWritableMap(foregroundServerSettings)
            )
        }

        setSettings(settings)
    }

    @ReactMethod
    fun canMakeMultipleCalls(allow: Boolean?) {
        VoiceConnectionService.setCanMakeMultipleCalls(allow)
    }

    @ReactMethod
    fun setReachable() {
        VoiceConnectionService.setReachable()
    }

    @ReactMethod
    fun setCurrentCallActive(uuid: String) {
        Log.d(
            TAG,
            "[RNCallKeepModule] setCurrentCallActive, uuid: $uuid"
        )
        val conn = VoiceConnectionService.getConnection(uuid)
        if (conn == null) {
            Log.w(
                TAG,
                "[RNCallKeepModule] setCurrentCallActive ignored because no connection found, uuid: $uuid"
            )
            return
        }

        conn.connectionCapabilities =
            conn.connectionCapabilities or Connection.CAPABILITY_HOLD
        conn.setActive()
    }

    @ReactMethod
    fun openPhoneAccounts() {
        Log.d(TAG, "[RNCallKeepModule] openPhoneAccounts")
        if (!isConnectionServiceAvailable) {
            Log.w(TAG, "[RNCallKeepModule] openPhoneAccounts ignored due to no ConnectionService")
            return
        }

        if (Build.MANUFACTURER.equals(
                "Samsung",
                ignoreCase = true
            ) || Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
        ) {
            val intent = Intent()
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            intent.setComponent(
                ComponentName(
                    "com.android.server.telecom",
                    "com.android.server.telecom.settings.EnableAccountPreferenceActivity"
                )
            )

            val context = this.appContext
            if (context == null) {
                Log.w(TAG, "[RNCallKeepModule][openPhoneAccounts] no react context found.")
                return
            }

            context.startActivity(intent)
            return
        }

        openPhoneAccountSettings()
    }

    @ReactMethod
    fun openPhoneAccountSettings() {
        Log.d(TAG, "[RNCallKeepModule] openPhoneAccountSettings")
        if (!isConnectionServiceAvailable) {
            Log.w(
                TAG,
                "[RNCallKeepModule] openPhoneAccountSettings ignored due to no ConnectionService"
            )
            return
        }

        val intent = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val context = this.appContext
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][openPhoneAccountSettings] no react context found.")
            return
        }
        context.startActivity(intent)
    }

    @ReactMethod
    fun isConnectionServiceAvailable(promise: Promise) {
        promise.resolve(isConnectionServiceAvailable)
    }

    @ReactMethod
    fun checkPhoneAccountEnabled(promise: Promise) {
        promise.resolve(hasPhoneAccount())
    }

    @ReactMethod
    fun backToForeground() {
        val context = appContext
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][backToForeground] no react context found.")
            return
        }
        val packageName = context.applicationContext.packageName
        val focusIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)!!
            .cloneFilter()
        val activity = currentReactActivity
        val isOpened = activity != null
        Log.d(
            TAG,
            "[RNCallKeepModule] backToForeground, app isOpened ?" + (if (isOpened) "true" else "false")
        )

        if (isOpened) {
            focusIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            activity!!.startActivity(focusIntent)
        } else {
            focusIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK +
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED +
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD +
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )

            getReactApplicationContext().startActivity(focusIntent)
        }
    }

    val currentReactActivity: Activity?
        get() = reactContext.getCurrentActivity()

    private fun registerPhoneAccount(appContext: Context) {
        if (!isConnectionServiceAvailable) {
            Log.w(
                TAG,
                "[RNCallKeepModule] registerPhoneAccount ignored due to no ConnectionService"
            )
            return
        }

        this.initializeTelecomManager()
        val context = this.appContext
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][registerPhoneAccount] no react context found.")
            return
        }
        val appName = this.getApplicationName(context)

        val builder = PhoneAccount.Builder(handle, appName)
        if (isSelfManaged) {
            builder.setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
        } else {
            builder.setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
        }

        if (_settings != null && _settings.hasKey("imageName")) {
            val identifier = appContext.resources.getIdentifier(
                _settings.getString("imageName"),
                "drawable",
                appContext.packageName
            )
            val icon = Icon.createWithResource(appContext, identifier)
            builder.setIcon(icon)
        }

        val account = builder.build()

        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        telecomManager!!.registerPhoneAccount(account)
    }

    fun sendEventToJS(eventName: String, params: WritableMap?) {
        var params: WritableMap? = params
        val isBoundToJS: Boolean = reactContext.hasActiveCatalystInstance()
        Log.v(
            TAG,
            "[RNCallKeepModule] sendEventToJS, eventName: " + eventName + ", bound: " + isBoundToJS + ", hasListeners: " + hasListeners + " args : " + (if (params != null) params.toString() else "null")
        )

        if (isBoundToJS && hasListeners) {
            reactContext.getJSModule<RCTDeviceEventEmitter>(RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        } else {
            val data: WritableMap = Arguments.createMap()
            if (params == null) {
                params = Arguments.createMap()
            }

            data.putString("name", eventName)
            data.putMap("data", params)
            delayedEvents.pushMap(data)
        }
    }

    private fun getApplicationName(appContext: Context): String {
        val applicationInfo = appContext.applicationInfo
        val stringId = applicationInfo.labelRes

        return if (stringId == 0) applicationInfo.nonLocalizedLabel.toString() else appContext.getString(
            stringId
        )
    }

    private fun hasPermissions(): Boolean {
        val context: ReactApplicationContext? = context

        var hasPermissions = true
        for (permission in permissions) {
            val permissionCheck = ContextCompat.checkSelfPermission(context, permission)
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                hasPermissions = false
            }
        }

        return hasPermissions
    }

    private fun hasPhoneAccount(): Boolean {
        if (telecomManager == null) {
            this.initializeTelecomManager()
        }

        if (isSelfManaged) {
            return true
        }

        return isConnectionServiceAvailable && telecomManager != null &&
                hasPermissions() && telecomManager!!.getPhoneAccount(handle) != null &&
                telecomManager!!.getPhoneAccount(handle).isEnabled
    }

    protected fun registerReceiver() {
        if (!isReceiverRegistered) {
            isReceiverRegistered = true
            voiceBroadcastReceiver = VoiceBroadcastReceiver()
            val intentFilter = IntentFilter()
            intentFilter.addAction(Constants.ACTION_END_CALL)
            intentFilter.addAction(Constants.ACTION_ANSWER_CALL)
            intentFilter.addAction(Constants.ACTION_MUTE_CALL)
            intentFilter.addAction(Constants.ACTION_UNMUTE_CALL)
            intentFilter.addAction(Constants.ACTION_DTMF_TONE)
            intentFilter.addAction(Constants.ACTION_UNHOLD_CALL)
            intentFilter.addAction(Constants.ACTION_HOLD_CALL)
            intentFilter.addAction(Constants.ACTION_ONGOING_CALL)
            intentFilter.addAction(Constants.ACTION_AUDIO_SESSION)
            intentFilter.addAction(Constants.ACTION_CHECK_REACHABILITY)
            intentFilter.addAction(Constants.ACTION_SHOW_INCOMING_CALL_UI)
            intentFilter.addAction(Constants.ACTION_ON_SILENCE_INCOMING_CALL)
            intentFilter.addAction(Constants.ACTION_ON_CREATE_CONNECTION_FAILED)
            intentFilter.addAction(Constants.ACTION_DID_CHANGE_AUDIO_ROUTE)

            if (this.reactContext != null) {
                LocalBroadcastManager.getInstance(this.reactContext)
                    .registerReceiver(voiceBroadcastReceiver, intentFilter)


                VoiceConnectionService.startObserving()
            } else {
                isReceiverRegistered = false
            }
        }
    }

    private val appContext: Context?
        get() = if (this.reactContext != null) reactContext.getApplicationContext() else null

    // Store all callkeep settings in JSON
    private fun storeSettings(options: ReadableMap): WritableMap? {
        val context = appContext
        if (context == null) {
            Log.w(TAG, "[RNCallKeepModule][storeSettings] no react context found.")
            return MapUtils.readableToWritableMap(options)
        }

        val sharedPref = context.getSharedPreferences("rn-callkeep", Context.MODE_PRIVATE)
        try {
            val jsonObject = MapUtils.convertMapToJson(options)
            val jsonString = jsonObject.toString()
            sharedPref.edit().putString("settings", jsonString).apply()
        } catch (e: JSONException) {
            Log.w(
                TAG,
                "[RNCallKeepModule][storeSettings] exception: $e"
            )
        }
        return MapUtils.readableToWritableMap(options)
    }

    private inner class VoiceBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val args: WritableMap = Arguments.createMap()
            val attributeMap =
                intent.getSerializableExtra("attributeMap") as HashMap<String, String>?

            Log.d(TAG, "[RNCallKeepModule][onReceive] " + intent.action)

            when (intent.action) {
                Constants.ACTION_END_CALL -> {
                    args.putString(
                        "callUUID",
                        attributeMap!![Constants.EXTRA_CALL_UUID]
                    )
                    sendEventToJS("RNCallKeepPerformEndCallAction", args)
                }

                Constants.ACTION_ANSWER_CALL -> {
                    args.putString(
                        "callUUID",
                        attributeMap!![Constants.EXTRA_CALL_UUID]
                    )
                    args.putBoolean(
                        "withVideo",
                        attributeMap[Constants.EXTRA_HAS_VIDEO].toBoolean()
                    )
                    sendEventToJS("RNCallKeepPerformAnswerCallAction", args)
                }

                Constants.ACTION_HOLD_CALL -> {
                    args.putBoolean("hold", true)
                    args.putString(
                        "callUUID",
                        attributeMap!![Constants.EXTRA_CALL_UUID]
                    )
                    sendEventToJS("RNCallKeepDidToggleHoldAction", args)
                }

                Constants.ACTION_UNHOLD_CALL -> {
                    args.putBoolean("hold", false)
                    args.putString(
                        "callUUID",
                        attributeMap!![Constants.EXTRA_CALL_UUID]
                    )
                    sendEventToJS("RNCallKeepDidToggleHoldAction", args)
                }

                Constants.ACTION_MUTE_CALL -> {
                    args.putBoolean("muted", true)
                    args.putString(
                        "callUUID",
                        attributeMap!![Constants.EXTRA_CALL_UUID]
                    )
                    sendEventToJS("RNCallKeepDidPerformSetMutedCallAction", args)
                }

                Constants.ACTION_UNMUTE_CALL -> {
                    args.putBoolean("muted", false)
                    args.putString(
                        "callUUID",
                        attributeMap!![Constants.EXTRA_CALL_UUID]
                    )
                    sendEventToJS("RNCallKeepDidPerformSetMutedCallAction", args)
                }

                Constants.ACTION_DTMF_TONE -> {
                    args.putString("digits", attributeMap!!["DTMF"])
                    args.putString(
                        "callUUID",
                        attributeMap[Constants.EXTRA_CALL_UUID]
                    )
                    sendEventToJS("RNCallKeepDidPerformDTMFAction", args)
                }

                Constants.ACTION_ONGOING_CALL -> {
                    args.putString(
                        "handle",
                        attributeMap!![Constants.EXTRA_CALL_NUMBER]
                    )
                    args.putString(
                        "callUUID",
                        attributeMap[Constants.EXTRA_CALL_UUID]
                    )
                    args.putString(
                        "name",
                        attributeMap[Constants.EXTRA_CALLER_NAME]
                    )
                    sendEventToJS("RNCallKeepDidReceiveStartCallAction", args)
                }

                Constants.ACTION_AUDIO_SESSION -> sendEventToJS(
                    "RNCallKeepDidActivateAudioSession",
                    null
                )

                Constants.ACTION_CHECK_REACHABILITY -> sendEventToJS(
                    "RNCallKeepCheckReachability",
                    null
                )

                Constants.ACTION_SHOW_INCOMING_CALL_UI -> {
                    args.putString(
                        "handle",
                        attributeMap!![Constants.EXTRA_CALL_NUMBER]
                    )
                    args.putString(
                        "callUUID",
                        attributeMap[Constants.EXTRA_CALL_UUID]
                    )
                    args.putString(
                        "name",
                        attributeMap[Constants.EXTRA_CALLER_NAME]
                    )
                    args.putString(
                        "hasVideo",
                        attributeMap[Constants.EXTRA_HAS_VIDEO]
                    )
                    sendEventToJS("RNCallKeepShowIncomingCallUi", args)
                }

                Constants.ACTION_WAKE_APP -> {
                    val headlessIntent = Intent(
                        reactContext,
                        RNCallKeepBackgroundMessagingService::class.java
                    )
                    headlessIntent.putExtra(
                        "callUUID",
                        attributeMap!![Constants.EXTRA_CALL_UUID]
                    )
                    headlessIntent.putExtra(
                        "name",
                        attributeMap[Constants.EXTRA_CALLER_NAME]
                    )
                    headlessIntent.putExtra(
                        "handle",
                        attributeMap[Constants.EXTRA_CALL_NUMBER]
                    )
                    Log.d(
                        TAG,
                        "[RNCallKeepModule] wakeUpApplication: " + attributeMap[Constants.EXTRA_CALL_UUID] + ", number : " + attributeMap[Constants.EXTRA_CALL_NUMBER] + ", displayName:" + attributeMap[Constants.EXTRA_CALLER_NAME]
                    )

                    val name: ComponentName = reactContext.startService(headlessIntent)
                    if (name != null) {
                        HeadlessJsTaskService.acquireWakeLockNow(reactContext)
                    }
                }

                Constants.ACTION_ON_SILENCE_INCOMING_CALL -> {
                    args.putString(
                        "handle",
                        attributeMap!![Constants.EXTRA_CALL_NUMBER]
                    )
                    args.putString(
                        "callUUID",
                        attributeMap[Constants.EXTRA_CALL_UUID]
                    )
                    args.putString(
                        "name",
                        attributeMap[Constants.EXTRA_CALLER_NAME]
                    )
                    sendEventToJS("RNCallKeepOnSilenceIncomingCall", args)
                }

                Constants.ACTION_ON_CREATE_CONNECTION_FAILED -> {
                    args.putString(
                        "handle",
                        attributeMap!![Constants.EXTRA_CALL_NUMBER]
                    )
                    args.putString(
                        "callUUID",
                        attributeMap[Constants.EXTRA_CALL_UUID]
                    )
                    args.putString(
                        "name",
                        attributeMap[Constants.EXTRA_CALLER_NAME]
                    )
                    sendEventToJS("RNCallKeepOnIncomingConnectionFailed", args)
                }

                Constants.ACTION_DID_CHANGE_AUDIO_ROUTE -> {
                    args.putString(
                        "handle",
                        attributeMap!![Constants.EXTRA_CALL_NUMBER]
                    )
                    args.putString(
                        "callUUID",
                        attributeMap[Constants.EXTRA_CALL_UUID]
                    )
                    args.putString("output", attributeMap["output"])
                    sendEventToJS("RNCallKeepDidChangeAudioRoute", args)
                }
            }
        }
    }

    companion object {
        const val REQUEST_READ_PHONE_STATE: Int = 1337
        const val REQUEST_REGISTER_CALL_PROVIDER: Int = 394859

        @JvmField
        var instance: RNCallKeepModule? = null

        private const val E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST"
        const val name: String = "RNCallKeep"
            get() = Companion.field
        private var permissions = arrayOf(
            if (Build.VERSION.SDK_INT < 30) Manifest.permission.READ_PHONE_STATE else Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO
        )

        private const val TAG = "RNCallKeep"
        private var telecomManager: TelecomManager? = null
        private var telephonyManager: TelephonyManager? = null
        private var hasPhoneAccountPromise: Promise? = null
        @JvmField
        var handle: PhoneAccountHandle? = null
        private var _settings: WritableMap? = null
        fun getInstance(
            reactContext: ReactApplicationContext?,
            realContext: Boolean
        ): RNCallKeepModule? {
            if (instance == null) {
                Log.d(
                    TAG,
                    "[RNCallKeepModule] getInstance : " + (if (reactContext == null) "null" else "ok")
                )
                instance = RNCallKeepModule(reactContext)
                instance!!.registerReceiver()
                fetchStoredSettings(reactContext)
            }
            if (realContext) {
                instance!!.context = reactContext
            }
            return instance
        }

        @JvmStatic
        fun getSettings(context: Context?): WritableMap? {
            if (_settings == null) {
                fetchStoredSettings(context)
            }

            return _settings
        }

        @JvmStatic
        val isConnectionServiceAvailable: Boolean
            get() =// PhoneAccount is available since api level 23
                Build.VERSION.SDK_INT >= 23

        fun onRequestPermissionsResult(
            requestCode: Int,
            grantedPermissions: Array<String?>,
            grantResults: IntArray
        ) {
            var permissionsIndex = 0
            val permsList = Arrays.asList(*permissions)
            for (result in grantResults) {
                if (permsList.contains(grantedPermissions[permissionsIndex]) && result != PackageManager.PERMISSION_GRANTED) {
                    hasPhoneAccountPromise!!.resolve(false)
                    return
                }
                permissionsIndex++
            }
            hasPhoneAccountPromise!!.resolve(true)
        }

        @JvmStatic
        protected fun fetchStoredSettings(fromContext: Context?) {
            if (instance == null && fromContext == null) {
                Log.w(TAG, "[RNCallKeepModule][fetchStoredSettings] no instance nor fromContext.")
                return
            }
            val context = fromContext ?: instance!!.appContext
            _settings = WritableNativeMap()
            if (context == null) {
                Log.w(TAG, "[RNCallKeepModule][fetchStoredSettings] no react context found.")
                return
            }

            val sharedPref = context.getSharedPreferences("rn-callkeep", Context.MODE_PRIVATE)
            try {
                val jsonString = sharedPref.getString("settings", (JSONObject()).toString())!!
                if (jsonString != null) {
                    val jsonObject = JSONObject(jsonString)

                    _settings = MapUtils.convertJsonToMap(jsonObject)
                }
            } catch (e: JSONException) {
            }
        }
    }
}
