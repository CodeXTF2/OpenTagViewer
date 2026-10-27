package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which rings go to iCloud first, and how iCloud's answer decides whether Bluetooth tries.
 *
 * <p>AirPods near their owner refuse DULT's Sound_Start, so for them iCloud's playSound is the
 * route that works; for everything else nothing changes.
 */
public class ICloudSoundTriggerTest {

    @Test
    public void airPodsAreRecognisedByTheModelAnICloudReadStores() {
        assertTrue(ICloudSoundTrigger.isAirPods("{\"model\": \"AirPods Pro (3rd generation)\"}"));
    }

    @Test
    public void anAirTagOrAnUnreadableAccessoryIsNot() {
        assertFalse(ICloudSoundTrigger.isAirPods("{\"model\": \"\"}"));
        assertFalse(ICloudSoundTrigger.isAirPods("{\"model\": \"iPad12,1\"}"));
        assertFalse(ICloudSoundTrigger.isAirPods("not json"));
        assertFalse(ICloudSoundTrigger.isAirPods(null));
    }

    @Test
    public void anAcceptedRingIsASuccessThroughICloud() {
        final BleSoundTriggerResult rang = ICloudSoundTrigger.fromAnswer(
                "{\"ok\": true, \"message\": \"sent through iCloud to ['case']\"}");

        assertEquals(BleSoundTriggerStatus.SUCCESS, rang.getStatus());
        assertEquals(ICloudSoundTrigger.PROTOCOL, rang.getProtocol());
        assertFalse("iCloud has no stop command for this", rang.hasStopCommand());
    }

    @Test
    public void aRefusalOrNonsenseLeavesItToBluetooth() {
        assertNull(ICloudSoundTrigger.fromAnswer("{\"ok\": false, \"message\": \"HTTP 403\"}"));
        assertNull(ICloudSoundTrigger.fromAnswer("{}"));
        assertNull(ICloudSoundTrigger.fromAnswer("garbage"));
        assertNull(ICloudSoundTrigger.fromAnswer(null));
    }
}
