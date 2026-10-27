package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Play-sound GATT trigger for Find My / DULT-compatible accessories.
 *
 * <p>Tries three known protocols, in the same priority order as AirGuard
 * (https://github.com/seemoo-lab/AirGuard, Apache-2.0) uses - the UUIDs and opcodes are verified
 * against that project's current source ({@code database/models/device/types/AppleFindMy.kt} for
 * the DULT and AirTag-specific paths, {@code GoogleFindMyNetwork.kt} for confirming Google's own
 * sound service is byte-for-byte the same one DULT defines).
 *
 * <p>Ported from a Kotlin prototype (a personal companion project, TrackerHunter) that already
 * exercised this against real AirTags; this is the same state machine expressed as a Java
 * {@link Observable} instead of a coroutine, to match this app's RxJava3 convention. See
 * {@code BleAccessorySoundTrigger} for the honesty about what has and has not actually been run.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BleGattSoundTrigger {
    private static final String TAG = BleGattSoundTrigger.class.getSimpleName();

    /** {@link BleSoundTriggerResult#getProtocol()} for a sound started over DULT. */
    static final String PROTOCOL_DULT = "DULT";

    private static final UUID DULT_SERVICE =
            UUID.fromString("15190001-12F4-C226-88ED-2AC5579F2A85");
    private static final UUID DULT_CHARACTERISTIC =
            UUID.fromString("8E0C0001-1D68-FB92-BF61-48377421680E");
    private static final byte[] DULT_START_OPCODE = {0x00, 0x03};

    private static final String FINDMY_SERVICE_SHORT = "fd44";
    private static final UUID FINDMY_CHARACTERISTIC =
            UUID.fromString("4F860003-943B-49EF-BED4-2F730304427A");
    private static final byte[] FINDMY_START_OPCODE = {0x01, 0x00, 0x03};

    private static final UUID AIRTAG_SERVICE =
            UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C");
    private static final UUID AIRTAG_CHARACTERISTIC =
            UUID.fromString("7DFC9001-7D1C-4951-86AA-8D9728F8D66C");
    private static final byte[] AIRTAG_PLAY_VALUE = {(byte) 0xAF};

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /**
     * Connects to {@code device}, tries all three protocols, and completes once the first
     * matching one's start command has been written (or all three failed). Does not wait for the
     * sound to finish playing.
     *
     * <p>Emits a {@link BleSoundTriggerPhase#CONNECTING} update immediately, a
     * {@link BleSoundTriggerPhase#TRIGGERING} one once a matching sound service is found, then
     * exactly one {@link BleSoundTriggerPhase#DONE} - so a caller can show "connecting..."
     * instead of nothing for however long the handshake takes. Disposing before it completes
     * disconnects and closes the GATT connection rather than leaving it open in the background.
     */
    @SuppressLint("MissingPermission")
    public static Observable<BleSoundTriggerUpdate> trigger(
            final Context context, final BluetoothDevice device) {
        return Observable.create(emitter -> {
            final AtomicBoolean resumed = new AtomicBoolean(false);
            final BluetoothGatt[] gattRef = new BluetoothGatt[1];

            final BluetoothGattCallback callback = new BluetoothGattCallback() {
                private BluetoothGattCharacteristic pendingCharacteristic;
                private byte[] pendingOpcode;
                private String pendingProtocolName;

                private void finish(final BleSoundTriggerResult result) {
                    // Guards against a callback landing twice (e.g. a disconnect that follows a
                    // successful write) - only the first one reaches the emitter, matching the
                    // "exactly one DONE, then complete" contract.
                    if (!resumed.compareAndSet(false, true)) return;
                    if (emitter.isDisposed()) return;
                    emitter.onNext(BleSoundTriggerUpdate.done(result));
                    emitter.onComplete();
                }

                @Override
                public void onConnectionStateChange(
                        final BluetoothGatt gatt, final int status, final int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Connected to " + device.getAddress()
                                + ", discovering services");
                        gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Disconnected from " + device.getAddress());
                        gatt.close();
                        // A disconnect after a successful write is the normal AirTag completion
                        // signal, not a failure - finish() already resumed by then, so this call
                        // is a no-op (see the AtomicBoolean guard above).
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null,
                                "Connection closed before a sound command was sent"));
                    }
                }

                @Override
                public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null,
                                "Service discovery failed (status=" + status + ")"));
                        gatt.disconnect();
                        return;
                    }

                    final BluetoothGattCharacteristic dult = characteristicOf(gatt, DULT_SERVICE, DULT_CHARACTERISTIC);
                    final BluetoothGattCharacteristic findMy = findMyCharacteristic(gatt);
                    final BluetoothGattCharacteristic airtag = characteristicOf(gatt, AIRTAG_SERVICE, AIRTAG_CHARACTERISTIC);

                    if (dult != null || findMy != null || airtag != null) {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.TRIGGERING));
                        }
                    }

                    if (dult != null) {
                        enableNotifyThenWrite(gatt, dult, DULT_START_OPCODE, PROTOCOL_DULT);
                    } else if (findMy != null) {
                        enableNotifyThenWrite(gatt, findMy, FINDMY_START_OPCODE, "FindMy (fd44)");
                    } else if (airtag != null) {
                        pendingProtocolName = "AirTag";
                        writeCharacteristicCompat(gatt, airtag, AIRTAG_PLAY_VALUE);
                    } else {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.NO_SOUND_SERVICE, null,
                                "No known sound service found (checked DULT, FindMy, AirTag)"));
                        gatt.disconnect();
                    }
                }

                private void enableNotifyThenWrite(
                        final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic,
                        final byte[] opcode, final String protocolName) {
                    pendingProtocolName = protocolName;
                    pendingCharacteristic = characteristic;
                    pendingOpcode = opcode;

                    gatt.setCharacteristicNotification(characteristic, true);
                    final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                    if (descriptor == null) {
                        // No CCCD - just write directly, matching the AirTag path.
                        writeCharacteristicCompat(gatt, characteristic, opcode);
                        return;
                    }
                    writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                }

                @Override
                public void onDescriptorWrite(
                        final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final int status) {
                    if (pendingCharacteristic == null || pendingOpcode == null) return;
                    Log.d(TAG, "CCCD write status=" + status + ", writing start opcode to "
                            + pendingCharacteristic.getUuid());
                    writeCharacteristicCompat(gatt, pendingCharacteristic, pendingOpcode);
                }

                @Override
                public void onCharacteristicWrite(
                        final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic,
                        final int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        final String protocol = pendingProtocolName == null ? "unknown" : pendingProtocolName;
                        Log.i(TAG, "Sound command written via " + protocol + " on " + device.getAddress());
                        if ("AirTag".equals(protocol)) {
                            // AirTag disconnects on its own once the sound finishes.
                            finish(new BleSoundTriggerResult(BleSoundTriggerStatus.SUCCESS, protocol, null));
                            return;
                        }
                        // **A delivered write is not an accepted command.** DULT answers on the
                        // same characteristic with Command_Response, and an accessory that is
                        // near its owner answers "invalid state" and stays silent - which this
                        // used to report as success, because it disconnected 20 ms after writing.
                        // So wait briefly for the answer; no answer at all is still success, as
                        // before.
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (resumed.get()) return;
                            Log.i(TAG, "No command response from " + device.getAddress()
                                    + " within " + RESPONSE_WAIT_MS + " ms; assuming the sound started");
                            finish(new BleSoundTriggerResult(BleSoundTriggerStatus.SUCCESS, protocol, null));
                            gatt.disconnect();
                        }, RESPONSE_WAIT_MS);
                    } else {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null,
                                "Write failed (status=" + status + ")"));
                        gatt.disconnect();
                    }
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onCharacteristicChanged(
                        final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
                    onResponse(gatt, characteristic.getValue());
                }

                @Override
                public void onCharacteristicChanged(
                        final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic,
                        final byte[] value) {
                    onResponse(gatt, value);
                }

                private void onResponse(final BluetoothGatt gatt, final byte[] value) {
                    Log.i(TAG, "Command response from " + device.getAddress() + ": " + hex(value));
                    if (resumed.get()) return;
                    final String protocol = pendingProtocolName == null ? "unknown" : pendingProtocolName;
                    final Integer refusal = dultRefusal(value);
                    if (refusal == null) {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.SUCCESS, protocol, null));
                    } else {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null,
                                "The accessory refused to play a sound (" + dultStatusName(refusal)
                                        + "). Accessories only ring this way while away from their"
                                        + " owner's Apple devices."));
                    }
                    gatt.disconnect();
                }
            };

            emitter.onNext(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.CONNECTING));
            gattRef[0] = device.connectGatt(context, false, callback);

            emitter.setCancellable(() -> {
                if (gattRef[0] != null) {
                    gattRef[0].disconnect();
                    gattRef[0].close();
                }
            });
        });
    }

    /** How long to wait for a DULT or Find My accessory to answer the start command. */
    static final long RESPONSE_WAIT_MS = 3000;

    /** DULT Command_Response, little-endian like the opcodes above. */
    private static final int DULT_COMMAND_RESPONSE = 0x0302;

    /**
     * The status of a DULT Command_Response that refused a command, or null if this is not a
     * refusal. Layout: opcode (2), the command answered (2), status (2), all little-endian.
     */
    static Integer dultRefusal(final byte[] value) {
        if (value == null || value.length < 6) return null;
        final int opcode = (value[0] & 0xFF) | (value[1] & 0xFF) << 8;
        if (opcode != DULT_COMMAND_RESPONSE) return null;
        final int status = (value[4] & 0xFF) | (value[5] & 0xFF) << 8;
        return status == 0 ? null : status;
    }

    static String dultStatusName(final int status) {
        switch (status) {
            case 0x0001: return "invalid state";
            case 0x0002: return "invalid configuration";
            case 0x0003: return "invalid length";
            case 0x0004: return "invalid parameter";
            case 0xFFFF: return "invalid command";
            default: return String.format(Locale.ROOT, "status 0x%04X", status);
        }
    }

    static String hex(final byte[] value) {
        if (value == null) return "(null)";
        final StringBuilder sb = new StringBuilder();
        for (final byte b : value) sb.append(String.format(Locale.ROOT, "%02X", b));
        return sb.toString();
    }

    private static BluetoothGattCharacteristic characteristicOf(
            final BluetoothGatt gatt, final UUID service, final UUID characteristic) {
        final BluetoothGattService svc = gatt.getService(service);
        return svc == null ? null : svc.getCharacteristic(characteristic);
    }

    /** The FindMy/DULT service UUID is vendor-suffixed; matched on its distinguishing prefix. */
    private static BluetoothGattCharacteristic findMyCharacteristic(final BluetoothGatt gatt) {
        for (final BluetoothGattService service : gatt.getServices()) {
            if (service.getUuid().toString().toLowerCase(Locale.ROOT).contains(FINDMY_SERVICE_SHORT)) {
                return service.getCharacteristic(FINDMY_CHARACTERISTIC);
            }
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    static void writeCharacteristicCompat(
            final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            characteristic.setValue(value);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            gatt.writeCharacteristic(characteristic);
        }
    }

    @SuppressLint("MissingPermission")
    static void writeDescriptorCompat(
            final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value);
        } else {
            descriptor.setValue(value);
            gatt.writeDescriptor(descriptor);
        }
    }

    /** DULT Sound_Stop, little-endian like the start opcode. */
    private static final byte[] DULT_STOP_OPCODE = {0x01, 0x03};

    /**
     * Connects and writes DULT's Sound_Stop, if the device has the DULT service; completes
     * either way (and on any failure) within about ten seconds.
     */
    @SuppressLint("MissingPermission")
    public static Completable stopDult(
            final Context context, final BluetoothDevice device) {
        return Completable.create(emitter -> {
            final BluetoothGatt[] gattRef = new BluetoothGatt[1];
            final BluetoothGattCallback callback = new BluetoothGattCallback() {
                private BluetoothGattCharacteristic dult;

                @Override
                public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close();
                        emitter.onComplete();
                    }
                }

                @Override
                public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
                    this.dult = characteristicOf(gatt, DULT_SERVICE, DULT_CHARACTERISTIC);
                    if (this.dult == null) {
                        gatt.disconnect();
                        return;
                    }
                    gatt.setCharacteristicNotification(this.dult, true);
                    final BluetoothGattDescriptor descriptor = this.dult.getDescriptor(CCCD);
                    if (descriptor == null) {
                        writeCharacteristicCompat(gatt, this.dult, DULT_STOP_OPCODE);
                    } else {
                        writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    }
                }

                @Override
                public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor d, final int status) {
                    if (this.dult != null) {
                        writeCharacteristicCompat(gatt, this.dult, DULT_STOP_OPCODE);
                    }
                }

                @Override
                public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic c, final int status) {
                    Log.i(TAG, "DULT stop written to " + device.getAddress() + " (status=" + status + ")");
                    gatt.disconnect();
                }
            };
            gattRef[0] = device.connectGatt(context, false, callback);
            emitter.setCancellable(() -> {
                if (gattRef[0] != null) {
                    gattRef[0].disconnect();
                    gattRef[0].close();
                }
            });
        }).timeout(10, TimeUnit.SECONDS).onErrorComplete();
    }
}
