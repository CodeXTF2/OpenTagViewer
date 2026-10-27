package dev.wander.android.opentagviewer.ble;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.Supplier;

import dev.wander.android.opentagviewer.python.PythonAppleService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

/**
 * Rings AirPods through iCloud first, the way iCloud.com does, and over Bluetooth when that fails.
 *
 * <p><b>Bluetooth alone cannot ring AirPods near their owner.</b> DULT's Sound_Start is the
 * unwanted-tracking command: an accessory answers it only while separated from its owner's
 * devices, and AirPods near the owner's iPad refuse or ignore it. Apple's own route is FMIP's
 * {@code playSound}, relayed to the owner's device, which plays it over its own link - one bud or
 * the case at a time. So for AirPods that goes first, and Bluetooth stays behind it for when no
 * Apple device can hear them, which is exactly when DULT works.
 *
 * <p>Everything that is not AirPods goes straight to Bluetooth, as before.
 */
public final class ICloudSoundTrigger implements AccessorySoundTrigger {
    private static final String TAG = ICloudSoundTrigger.class.getSimpleName();

    public static final String PROTOCOL = "iCloud";

    private final AccessorySoundTrigger bluetooth;
    private final Supplier<PythonAppleService> service;

    public ICloudSoundTrigger(final AccessorySoundTrigger bluetooth,
                              final Supplier<PythonAppleService> service) {
        this.bluetooth = bluetooth;
        this.service = service;
    }

    /** AirPods, by the model an iCloud read stores: "AirPods Pro (3rd generation)". */
    static boolean isAirPods(@Nullable final String accessoryJson) {
        if (accessoryJson == null) {
            return false;
        }
        try {
            return new JSONObject(accessoryJson).optString("model").startsWith("AirPods");
        } catch (final JSONException e) {
            return false;
        }
    }

    /** The outcome of iCloud's answer, or null when it did not ring and Bluetooth should try. */
    @Nullable
    static BleSoundTriggerResult fromAnswer(@Nullable final String answer) {
        try {
            final JSONObject o = new JSONObject(answer == null ? "" : answer);
            if (o.optBoolean("ok")) {
                return new BleSoundTriggerResult(BleSoundTriggerStatus.SUCCESS, PROTOCOL,
                        o.optString("message"));
            }
            Log.i(TAG, "iCloud did not ring it, trying Bluetooth: " + o.optString("message"));
        } catch (final JSONException e) {
            Log.w(TAG, "Unreadable answer from iCloud, trying Bluetooth", e);
        }
        return null;
    }

    private Observable<BleSoundTriggerUpdate> viaICloud(final String accessoryJson,
                                                        final Observable<BleSoundTriggerUpdate> otherwise) {
        final PythonAppleService apple = this.service.get();
        if (apple == null || !isAirPods(accessoryJson)) {
            return otherwise;
        }
        return Observable.just(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.TRIGGERING))
                .concatWith(apple.playSoundViaICloud(accessoryJson)
                        .onErrorReturnItem("{\"ok\": false}")
                        .concatMap(answer -> {
                            final BleSoundTriggerResult rang = fromAnswer(answer);
                            return rang != null
                                    ? Observable.just(BleSoundTriggerUpdate.done(rang))
                                    : otherwise;
                        }));
    }

    @Override
    public Observable<BleSoundTriggerUpdate> playSound(final Context context, final String accessoryJson) {
        return this.viaICloud(accessoryJson, this.bluetooth.playSound(context, accessoryJson));
    }

    /**
     * iCloud once, then the Bluetooth loop. Asking Apple to ring again every few seconds would be
     * a request a person never makes; the loop is for walking toward something out of the owner
     * device's reach, which is Bluetooth's case anyway.
     */
    @Override
    public Observable<BleSoundTriggerUpdate> playSoundContinuously(final Context context, final String accessoryJson) {
        final Observable<BleSoundTriggerUpdate> loop = this.bluetooth.playSoundContinuously(context, accessoryJson);
        if (this.service.get() == null || !isAirPods(accessoryJson)) {
            return loop;
        }
        return this.viaICloud(accessoryJson, Observable.empty()).concatWith(loop);
    }

    @Override
    public Completable stopSound(final Context context, final String accessoryJson) {
        return this.bluetooth.stopSound(context, accessoryJson);
    }
}
