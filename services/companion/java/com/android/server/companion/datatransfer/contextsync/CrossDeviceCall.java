/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.companion.datatransfer.contextsync;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import de.diamaneos.decode.client.ForeignIcons;

/** Data holder for a telecom call and additional metadata. */
public class CrossDeviceCall {

    private static final String TAG = "CrossDeviceCall";
    private static final String SEPARATOR = "::";
    // DiamaneOS: the size the calling app's icon is made at, as BitmapUtils sends it.
    private static final int APP_ICON_PX = 256;

    private final String mId;
    private final Call mCall;
    private final int mUserId;
    @VisibleForTesting boolean mIsEnterprise;
    private final String mCallingAppPackageName;
    private final String mSerializedPhoneAccountHandle;
    private String mCallingAppName;
    private volatile byte[] mCallingAppIcon;
    // DiamaneOS: the calling app's icon resource, made in the protected VM later (see loadCallingAppIcon).
    private int mCallingAppIconRes;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private int mStatus = android.companion.Telecom.Call.UNKNOWN_STATUS;
    private String mContactDisplayName;
    private Uri mHandle;
    private int mHandlePresentation;
    private int mDirection;
    private boolean mIsMuted;
    private final Set<Integer> mControls = new HashSet<>();
    private final boolean mIsCallPlacedByContextSync;

    public CrossDeviceCall(Context context, @NonNull Call call,
            CallAudioState callAudioState) {
        this(context, call, call.getDetails(), callAudioState);
    }

    CrossDeviceCall(Context context, Call.Details callDetails,
            CallAudioState callAudioState) {
        this(context, /* call= */ null, callDetails, callAudioState);
    }

    private CrossDeviceCall(Context context, @Nullable Call call,
            Call.Details callDetails, CallAudioState callAudioState) {
        mCall = call;
        final String predefinedId = callDetails.getIntentExtras() != null
                ? callDetails.getIntentExtras().getString(CrossDeviceSyncController.EXTRA_CALL_ID)
                : null;
        final String generatedId = UUID.randomUUID().toString();
        mId = predefinedId != null ? (generatedId + SEPARATOR + predefinedId) : generatedId;
        if (call != null) {
            Bundle extras = new Bundle();
            extras.putString(CrossDeviceSyncController.EXTRA_CALL_ID, mId);
            call.putExtras(extras);
        }
        final PhoneAccountHandle handle = callDetails.getAccountHandle();
        mUserId = handle != null ? handle.getUserHandle().getIdentifier() : -1;
        mIsCallPlacedByContextSync = handle != null
                && new ComponentName(context, CallMetadataSyncConnectionService.class)
                .equals(handle.getComponentName());
        mCallingAppPackageName = handle != null
                ? callDetails.getAccountHandle().getComponentName().getPackageName() : "";
        mSerializedPhoneAccountHandle = handle != null
                ? handle.getId() + SEPARATOR + handle.getComponentName().flattenToString() : "";
        mIsEnterprise = (callDetails.getCallProperties() & Call.Details.PROPERTY_ENTERPRISE_CALL)
                == Call.Details.PROPERTY_ENTERPRISE_CALL;
        final PackageManager packageManager = context.getPackageManager();
        try {
            final ApplicationInfo applicationInfo = packageManager
                    .getApplicationInfoAsUser(mCallingAppPackageName,
                            PackageManager.ApplicationInfoFlags.of(0), mUserId);
            mCallingAppName = packageManager.getApplicationLabel(applicationInfo).toString();
            // DiamaneOS: the app's icon is not loaded here: that would decode its image files natively in the
            // system server. It is made in the protected VM on an image thread (loadCallingAppIcon).
            mCallingAppIconRes = applicationInfo.icon;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Could not get application info for package " + mCallingAppPackageName, e);
        }
        mIsMuted = callAudioState != null && callAudioState.isMuted();
        updateCallDetails(callDetails);
    }

    /**
     * DiamaneOS: makes the calling app's icon on an image thread, its image files decoded in the protected VM, never
     * here, and runs {@code onIcon} on {@code executor} once the icon is there, so the call can be synced again with
     * it. The call is never held up for it. If it cannot be made, no icon is sent.
     */
    void loadCallingAppIcon(Context context, Executor executor, Runnable onIcon) {
        if (mCallingAppIconRes == 0 || TextUtils.isEmpty(mCallingAppPackageName) || mUserId < 0) {
            return;
        }
        ForeignIcons.loadAsync(context,
                Icon.createWithResource(mCallingAppPackageName, mCallingAppIconRes), mUserId,
                mCallingAppPackageName, APP_ICON_PX, Runnable::run, drawable -> {
                    if (drawable == null) {
                        return;
                    }
                    final byte[] icon;
                    try {
                        // On the image thread: pixels from the VM drawn and written out, nothing decoded.
                        icon = BitmapUtils.renderDrawableToByteArray(drawable);
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "calling app icon not made: " + e);
                        return;
                    }
                    executor.execute(() -> {
                        mCallingAppIcon = icon;
                        onIcon.run();
                    });
                });
    }

    /**
     * Update the mute state of this call. No-op if the call is not capable of being muted.
     *
     * @param isMuted true if the call should be muted, and false if the call should be unmuted.
     */
    public void updateMuted(boolean isMuted) {
        mIsMuted = isMuted;
        updateCallDetails(mCall.getDetails());
    }

    /**
     * Update the state of the call to be ringing silently if it is currently ringing. No-op if the
     * call is not
     * currently ringing.
     */
    public void updateSilencedIfRinging() {
        if (mStatus == android.companion.Telecom.Call.RINGING) {
            mStatus = android.companion.Telecom.Call.RINGING_SILENCED;
        }
        mControls.remove(android.companion.Telecom.SILENCE);
    }

    @VisibleForTesting
    void updateCallDetails(Call.Details callDetails) {
        mCallerDisplayName = callDetails.getCallerDisplayName();
        mCallerDisplayNamePresentation = callDetails.getCallerDisplayNamePresentation();
        mContactDisplayName = callDetails.getContactDisplayName();
        mHandle = callDetails.getHandle();
        mHandlePresentation = callDetails.getHandlePresentation();
        final int direction = callDetails.getCallDirection();
        if (direction == Call.Details.DIRECTION_INCOMING) {
            mDirection = android.companion.Telecom.Call.INCOMING;
        } else if (direction == Call.Details.DIRECTION_OUTGOING) {
            mDirection = android.companion.Telecom.Call.OUTGOING;
        } else {
            mDirection = android.companion.Telecom.Call.UNKNOWN_DIRECTION;
        }
        mStatus = convertStateToStatus(callDetails.getState());
        mControls.clear();
        if (mStatus == android.companion.Telecom.Call.DIALING) {
            mControls.add(android.companion.Telecom.END);
        }
        if (mStatus == android.companion.Telecom.Call.RINGING
                || mStatus == android.companion.Telecom.Call.RINGING_SILENCED) {
            mControls.add(android.companion.Telecom.ACCEPT);
            mControls.add(android.companion.Telecom.REJECT);
            if (mStatus == android.companion.Telecom.Call.RINGING) {
                mControls.add(android.companion.Telecom.SILENCE);
            }
        }
        if (mStatus == android.companion.Telecom.Call.ONGOING
                || mStatus == android.companion.Telecom.Call.ON_HOLD) {
            mControls.add(android.companion.Telecom.END);
            if (callDetails.can(Call.Details.CAPABILITY_HOLD)) {
                mControls.add(
                        mStatus == android.companion.Telecom.Call.ON_HOLD
                                ? android.companion.Telecom.TAKE_OFF_HOLD
                                : android.companion.Telecom.PUT_ON_HOLD);
            }
        }
        if (mStatus == android.companion.Telecom.Call.ONGOING && callDetails.can(
                Call.Details.CAPABILITY_MUTE)) {
            mControls.add(mIsMuted ? android.companion.Telecom.UNMUTE
                    : android.companion.Telecom.MUTE);
        }
    }

    /** Converts a Telecom call state to a Context Sync status. */
    public static int convertStateToStatus(int callState) {
        switch (callState) {
            case Call.STATE_HOLDING:
                return android.companion.Telecom.Call.ON_HOLD;
            case Call.STATE_ACTIVE:
                return android.companion.Telecom.Call.ONGOING;
            case Call.STATE_RINGING:
                return android.companion.Telecom.Call.RINGING;
            case Call.STATE_AUDIO_PROCESSING:
                return android.companion.Telecom.Call.AUDIO_PROCESSING;
            case Call.STATE_SIMULATED_RINGING:
                return android.companion.Telecom.Call.RINGING_SIMULATED;
            case Call.STATE_DISCONNECTED:
                return android.companion.Telecom.Call.DISCONNECTED;
            case Call.STATE_DIALING:
                return android.companion.Telecom.Call.DIALING;
            default:
                Slog.e(TAG, "Couldn't resolve state to status: " + callState);
                return android.companion.Telecom.Call.UNKNOWN_STATUS;
        }
    }

    /**
     * Converts a Context Sync status to a Telecom call state. Note that this is lossy for
     * and RINGING_SILENCED, as Telecom does not distinguish between RINGING and RINGING_SILENCED.
     */
    public static int convertStatusToState(int status) {
        switch (status) {
            case android.companion.Telecom.Call.ON_HOLD:
                return Call.STATE_HOLDING;
            case android.companion.Telecom.Call.ONGOING:
                return Call.STATE_ACTIVE;
            case android.companion.Telecom.Call.RINGING:
            case android.companion.Telecom.Call.RINGING_SILENCED:
                return Call.STATE_RINGING;
            case android.companion.Telecom.Call.AUDIO_PROCESSING:
                return Call.STATE_AUDIO_PROCESSING;
            case android.companion.Telecom.Call.RINGING_SIMULATED:
                return Call.STATE_SIMULATED_RINGING;
            case android.companion.Telecom.Call.DISCONNECTED:
                return Call.STATE_DISCONNECTED;
            case android.companion.Telecom.Call.DIALING:
                return Call.STATE_DIALING;
            case android.companion.Telecom.Call.UNKNOWN_STATUS:
            default:
                return Call.STATE_NEW;
        }
    }

    public String getId() {
        return mId;
    }

    public Call getCall() {
        return mCall;
    }

    public int getUserId() {
        return mUserId;
    }

    public String getCallingAppName() {
        return mCallingAppName;
    }

    public byte[] getCallingAppIcon() {
        return mCallingAppIcon;
    }

    public String getCallingAppPackageName() {
        return mCallingAppPackageName;
    }

    public String getSerializedPhoneAccountHandle() {
        return mSerializedPhoneAccountHandle;
    }

    /**
     * Get a human-readable "caller id" to display as the origin of the call.
     *
     * @param isAdminBlocked whether there is an admin that has blocked contacts over Bluetooth
     */
    public String getReadableCallerId(boolean isAdminBlocked) {
        if (mIsEnterprise && isAdminBlocked) {
            // Cannot use any contact information.
            return getNonContactString();
        }
        return TextUtils.isEmpty(mContactDisplayName) ? getNonContactString() : mContactDisplayName;
    }

    private String getNonContactString() {
        if (!TextUtils.isEmpty(mCallerDisplayName)
                && mCallerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED) {
            return mCallerDisplayName;
        }
        if (mHandle != null && mHandle.getSchemeSpecificPart() != null
                && mHandlePresentation == TelecomManager.PRESENTATION_ALLOWED) {
            return mHandle.getSchemeSpecificPart();
        }
        return null;
    }

    public int getStatus() {
        return mStatus;
    }

    public int getDirection() {
        return mDirection;
    }

    public Set<Integer> getControls() {
        return mControls;
    }

    public boolean isCallPlacedByContextSync() {
        return mIsCallPlacedByContextSync;
    }

    void doAccept() {
        mCall.answer(VideoProfile.STATE_AUDIO_ONLY);
    }

    void doReject() {
        if (mStatus == android.companion.Telecom.Call.RINGING) {
            mCall.reject(Call.REJECT_REASON_DECLINED);
        }
    }

    void doEnd() {
        mCall.disconnect();
    }

    void doPutOnHold() {
        mCall.hold();
    }

    void doTakeOffHold() {
        mCall.unhold();
    }
}
