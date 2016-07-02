
/* Copyright (c) 2016, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.incallui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.DialogInterface.OnKeyListener;
import android.os.Bundle;
import android.telecom.VideoProfile;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import com.android.incallui.InCallPresenter.InCallDetailsListener;
import com.android.incallui.InCallPresenter.InCallUiListener;
import org.codeaurora.ims.QtiCallConstants;

public class InCallLowBatteryListener implements CallList.Listener, InCallDetailsListener,
        InCallUiListener {

    private static InCallLowBatteryListener sInCallLowBatteryListener;
    private PrimaryCallTracker mPrimaryCallTracker;
    private CallList mCallList = null;
    private AlertDialog mAlert = null;
    private List <Call> mLowBatteryCalls = new CopyOnWriteArrayList<>();
    // Holds TRUE if there is a user action to answer low battery video call as video else FALSE
    private boolean mIsAnswered = false;
    /**
     * Private constructor. Must use getInstance() to get this singleton.
     */
    private InCallLowBatteryListener() {
    }

    /**
     * Handles set up of the {@class InCallLowBatteryListener}.
     */
    public void setUp(Context context) {
        mPrimaryCallTracker = new PrimaryCallTracker();
        mCallList = CallList.getInstance();
        mCallList.addListener(this);
        InCallPresenter.getInstance().addListener(mPrimaryCallTracker);
        InCallPresenter.getInstance().addIncomingCallListener(mPrimaryCallTracker);
        InCallPresenter.getInstance().addDetailsListener(this);
        InCallPresenter.getInstance().addInCallUiListener(this);
    }

    /**
     * Handles tear down of the {@class InCallLowBatteryListener}.
     */
    public void tearDown() {
        if (mCallList != null) {
            mCallList.removeListener(this);
            mCallList = null;
        }
        InCallPresenter.getInstance().removeListener(mPrimaryCallTracker);
        InCallPresenter.getInstance().removeIncomingCallListener(mPrimaryCallTracker);
        InCallPresenter.getInstance().removeDetailsListener(this);
        InCallPresenter.getInstance().removeInCallUiListener(this);
        mPrimaryCallTracker = null;
        mIsAnswered = false;
    }

     /**
     * This method returns a singleton instance of {@class InCallLowBatteryListener}
     */
    public static synchronized InCallLowBatteryListener getInstance() {
        if (sInCallLowBatteryListener == null) {
            sInCallLowBatteryListener = new InCallLowBatteryListener();
        }
        return sInCallLowBatteryListener;
    }

    /**
     * This method overrides onIncomingCall method of {@interface CallList.Listener}
     */
    @Override
    public void onIncomingCall(Call call) {
        mIsAnswered = false;
        // if low battery dialog is already visible to user, dismiss it
        dismissPendingDialogs();
        /* On receiving MT call, disconnect pending MO low battery video call
           that is waiting for user input */
        maybeDisconnectPendingMoCall(mCallList.getPendingOutgoingCall());
    }

    /**
     * This method overrides onCallListChange method of {@interface CallList.Listener}
     * Added for completeness. No implementation yet.
     */
    @Override
    public void onCallListChange(CallList list) {
        // no-op
    }

    /**
     * This method overrides onUpgradeToVideo method of {@interface CallList.Listener}
     */
    @Override
    public void onUpgradeToVideo(Call call) {
        //if low battery dialog is visible to user, dismiss it
        dismissPendingDialogs();
    }

    /**
     * This method overrides onDisconnect method of {@interface CallList.Listener}
     */
    @Override
    public void onDisconnect(Call call) {
        Log.d(this, "onDisconnect call: " + call);
        updateCallInMap(call);

        //if low battery dialog is visible to user, dismiss it
        dismissPendingDialogs();
    }

    /**
     * This API conveys if incall experience is showing or not.
     *
     * @param showing TRUE if incall experience is showing else FALSE
     */
    @Override
    public void onUiShowing(boolean showing) {
        Call call = mPrimaryCallTracker.getPrimaryCall();
        Log.d(this, "onUiShowing showing: " + showing + " call = " + call +
                " mIsAnswered = " + mIsAnswered);

        if (call == null) {
            return;
        }

       if (!showing) {
           if (InCallPresenter.getInstance().isChangingConfigurations()) {
                handleConfigurationChange(call);
            }
            return;
        }

        boolean isUnAnsweredMtCall = CallUtils.isIncomingVideoCall(call) && !mIsAnswered;
        // Low battery handling for MT video calls kicks-in only after user decides to
        // answer the call as Video. So, do not process unanswered incoming video call.
        if (isUnAnsweredMtCall) {
            return;
        }

        maybeProcessLowBatteryIndication(call, call.getTelecommCall().getDetails());
    }

    /**
     * When call is answered, this API checks to see if UE is under low battery or not
     * and accordingly processes the low battery video call and returns TRUE if
     * user action to answer the call is handled by this API else FALSE.
     *
     * @param call The call that is being answered
     * @param fromHUN TRUE if call is answered from Heads-Up Notification (HUN) else FALSE
     */
    public boolean handleAnswerIncomingCall(Call call, int videoState) {
        Log.d(this, "handleAnswerIncomingCall = " + call + " videoState = " + videoState);
        if (call == null) {
            return false;
        }

        final android.telecom.Call.Details details = call.getTelecommCall().getDetails();

        if (!mPrimaryCallTracker.isPrimaryCall(call) ||
                !(CallUtils.isVideoCall(call) && isLowBattery(details)
                && CallUtils.isBidirectionalVideoCall(videoState))) {
           //return false if low battery MT VT call isn't accepted as Video
           return false;
        }

        //There is a user action to answer low battery MT Video call as Video
        mIsAnswered = true;
        maybeProcessLowBatteryIndication(call, details);
        return true;
    }

    /**
     * This API handles configuration changes done on low battery video call
     *
     * @param call The call on which configuration changes happened
     */
    private void handleConfigurationChange(Call call) {
        Log.d(this, "handleConfigurationChange Call = " + call);
        if (call == null || !mPrimaryCallTracker.isPrimaryCall(call)) {
           return;
        }

        /* If UE orientation changes with low battery dialog showing, then remove
           the call from lowbatterymap to ensure that the dialog will be shown to
           user when the InCallActivity is recreated */
        if (isLowBatteryDialogShowing()) {
            dismissPendingDialogs();
            if (mLowBatteryCalls.contains(call)) {
                Log.d(this, "remove the call from map due to orientation change");
                mLowBatteryCalls.remove(call);
            }
        }
    }

    /**
     * Handles changes to the details of the call.
     *
     * @param call The call for which the details changed.
     * @param details The new call details.
     */
    @Override
    public void onDetailsChanged(Call call, android.telecom.Call.Details details) {
        Log.d(this, " onDetailsChanged call=" + call + " details=" + details);

        if (call == null || !mPrimaryCallTracker.isPrimaryCall(call)) {
            Log.d(this," onDetailsChanged: call is null/Details not for primary call");
            return;
        }
        /* Low Battery handling for MT Video call kicks in only when user decides
           to answer the call as Video call so ignore the incoming video call
           processing here for now */
       if (CallUtils.isIncomingVideoCall(call)) {
            return;
       }

        maybeProcessLowBatteryIndication(call, details);
    }

    /**
      * disconnects pending MO video call that is waiting for user confirmation on
      * low battery dialog
      * @param call The probable call that may need to be disconnected
      **/
    private void maybeDisconnectPendingMoCall(Call call) {
        if (call == null) {
            return;
        }

        if (call.getState() == Call.State.CONNECTING && CallUtils.isVideoCall(call)
                && isLowBattery(call.getTelecommCall().getDetails())) {
            // dismiss the low battery dialog that is waiting for user input
            dismissPendingDialogs();

            String callId = call.getId();
            Log.d(this, "disconnect pending MO call");
            TelecomAdapter.getInstance().disconnectCall(callId);
        }
    }

    public boolean isLowBattery(android.telecom.Call.Details details) {
        final Bundle extras =  (details != null) ? details.getExtras() : null;
        final boolean isLowBattery = (extras != null) ? extras.getBoolean(
                QtiCallConstants.LOW_BATTERY_EXTRA_KEY, false) : false;
        Log.d(this, "isLowBattery : " + isLowBattery);
        return isLowBattery;
    }

    private void maybeProcessLowBatteryIndication(Call call,
            android.telecom.Call.Details details) {

        if (!CallUtils.isVideoCall(call)) {
            return;
        }

        if (isLowBattery(details) && updateCallInMap(call)) {
            processLowBatteryIndication(call);
        }
    }

    /*
     * processes the low battery indication for video call
     */
    private void processLowBatteryIndication(Call call) {
        Log.d(this, "processLowBatteryIndication call: " + call);
        //if low battery dialog is already visible to user, dismiss it
        dismissPendingDialogs();
        displayLowBatteryAlert(call);
    }

    /*
     * Adds/Removes the call to mLowBatteryCalls
     * Returns TRUE if call is added to mLowBatteryCalls else FALSE
     */
    private boolean updateCallInMap(Call call) {
        if (call == null) {
            Log.e(this, "call is null");
            return false;
        }

        final boolean isPresent = mLowBatteryCalls.contains(call);
        if (!Call.State.isConnectingOrConnected(call.getState())) {
            if (isPresent) {
                //we are done with the call so remove from callmap
                mLowBatteryCalls.remove(call);
                return false;
            }
        } else if (InCallPresenter.getInstance().getActivity() == null) {
            /*
             * Displaying Low Battery alert dialog requires incallactivity context
             * so return false if there is no incallactivity context
             */
            Log.i(this, "incallactivity is null");
            return false;
        } else if (CallUtils.isVideoCall(call) && !isPresent
                && call.getParentId() == null) {
            /*
             * call will be added to call map only if below conditions are satisfied:
             * 1. call is not a child call
             * 2. call is a video call
             * 3. low battery indication for that call is not yet processed
             */
            mLowBatteryCalls.add(call);
            return true;
        }
        return false;
    }

    /*
     * This method displays one of below alert dialogs when UE is in low battery
     * For Active Video Calls:
     *     1. hangup alert dialog in absence of voice capabilities
     *     2. downgrade to voice call alert dialog in the presence of voice
     *        capabilities
     * For MT Video calls wherein user decided to accept the call as Video and for MO Video Calls:
     *     1. alert dialog asking user confirmation to convert the video call to voice call or
     *        to continue the call as video call
     * For MO Video calls, seek user confirmation to continue the video call as is or convert the
     * video call to voice call
     */
    private void displayLowBatteryAlert(final Call call) {
        final InCallActivity inCallActivity = InCallPresenter.getInstance().getActivity();
        if (inCallActivity == null) {
            Log.e(this, "displayLowBatteryAlert inCallActivity is NULL");
            return;
        }

        AlertDialog.Builder alertDialog = new AlertDialog.Builder(inCallActivity);
        alertDialog.setTitle(R.string.low_battery);
        alertDialog.setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(final DialogInterface dialog) {
                Log.i(this, "displayLowBatteryAlert onDismiss");
                //mAlert = null;
            }
        });

        if (CallUtils.isIncomingVideoCall(call)) {
            alertDialog.setNegativeButton(R.string.low_battery_convert, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                     Log.d(this, "displayLowBatteryAlert answer as Voice Call");
                     TelecomAdapter.getInstance().answerCall(call.getId(),
                             VideoProfile.STATE_AUDIO_ONLY);
                }
            });

            alertDialog.setMessage(R.string.low_battery_msg);
            alertDialog.setPositiveButton(R.string.low_battery_yes, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                     Log.d(this, "displayLowBatteryAlert answer as Video Call");
                     TelecomAdapter.getInstance().answerCall(call.getId(),
                             VideoProfile.STATE_BIDIRECTIONAL);
                }
            });
        } else if (CallUtils.isOutgoingVideoCall(call)) {
            alertDialog.setNegativeButton(R.string.low_battery_convert, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Log.d(this, "displayLowBatteryAlert place Voice Call");
                    //Change the audio route to earpiece
                    InCallAudioManager.getInstance().onModifyCallClicked(call,
                            VideoProfile.STATE_AUDIO_ONLY);

                    TelecomAdapter.getInstance().continueCallWithVideoState(
                            call, VideoProfile.STATE_AUDIO_ONLY);
                }
            });

            alertDialog.setMessage(R.string.low_battery_msg);
            alertDialog.setPositiveButton(R.string.low_battery_yes, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                     Log.d(this, "displayLowBatteryAlert place Video Call");
                     TelecomAdapter.getInstance().continueCallWithVideoState(
                             call, VideoProfile.STATE_BIDIRECTIONAL);
                }
            });
        } else if (CallUtils.isActiveUnPausedVideoCall(call)) {
            if (QtiCallUtils.hasVoiceCapabilities(call)) {
                //active video call can be downgraded to voice
                alertDialog.setMessage(R.string.low_battery_msg);
                alertDialog.setPositiveButton(R.string.low_battery_yes, null);
                alertDialog.setNegativeButton(R.string.low_battery_convert, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(this, "displayLowBatteryAlert downgrading to voice call");
                        QtiCallUtils.downgradeToVoiceCall(call);
                    }
                });
            } else {
                /* video call doesn't have downgrade capabilities, so alert the user
                   with a hangup dialog*/
                alertDialog.setMessage(R.string.low_battery_hangup_msg);
                alertDialog.setNegativeButton(R.string.low_battery_no, null);
                alertDialog.setPositiveButton(R.string.low_battery_yes, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(this, "displayLowBatteryAlert hanging up the call: " + call);
                        final String callId = call.getId();
                        call.setState(Call.State.DISCONNECTING);
                        CallList.getInstance().onUpdate(call);
                        TelecomAdapter.getInstance().disconnectCall(callId);
                    }
                });
            }
        }

        mAlert = alertDialog.create();
        mAlert.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                Log.d(this, "on Alert displayLowBattery keyCode = " + keyCode);
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                   // On Back key press, disconnect pending MO low battery video call
                   // that is waiting for user input
                    maybeDisconnectPendingMoCall(call);
                    return true;
                }
                return false;
            }
        });
        mAlert.setCanceledOnTouchOutside(false);
        mAlert.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mAlert.show();
    }

    /*
     * This method returns true if dialog is showing else false
     */
    private boolean isLowBatteryDialogShowing() {
        return mAlert != null && mAlert.isShowing();
    }

    /*
     * This method dismisses the low battery dialog and
     * returns true if dialog is dimissed else false
     */
    public void dismissPendingDialogs() {
        if (isLowBatteryDialogShowing()) {
            mAlert.dismiss();
            mAlert = null;
        }
    }
}
