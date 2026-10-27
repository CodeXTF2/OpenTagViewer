package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Reading a DULT accessory's answer to Sound_Start.
 *
 * <p>The app used to disconnect 20 ms after writing the command and report success, so an
 * accessory that refused - AirPods near their owner do - looked as if it had rung.
 */
public class DultCommandResponseTest {

    /** Command_Response 0x0302, answering Sound_Start 0x0300, with the given status. */
    private static byte[] response(final int status) {
        return new byte[] {0x02, 0x03, 0x00, 0x03, (byte) (status & 0xFF), (byte) (status >> 8)};
    }

    @Test
    public void successIsNotARefusal() {
        assertNull(BleGattSoundTrigger.dultRefusal(response(0)));
    }

    @Test
    public void invalidStateIsARefusal() {
        assertEquals(Integer.valueOf(1), BleGattSoundTrigger.dultRefusal(response(1)));
        assertEquals("invalid state", BleGattSoundTrigger.dultStatusName(1));
    }

    @Test
    public void invalidCommandIsARefusal() {
        assertEquals(Integer.valueOf(0xFFFF), BleGattSoundTrigger.dultRefusal(response(0xFFFF)));
    }

    @Test
    public void otherIndicationsAreNotRefusals() {
        // Sound_Completed, and anything too short to be a Command_Response.
        assertNull(BleGattSoundTrigger.dultRefusal(new byte[] {0x03, 0x03}));
        assertNull(BleGattSoundTrigger.dultRefusal(new byte[] {0x02, 0x03, 0x00}));
        assertNull(BleGattSoundTrigger.dultRefusal(null));
    }

    @Test
    public void hexIsUppercaseAndUnseparated() {
        assertEquals("02030003", BleGattSoundTrigger.hex(new byte[] {0x02, 0x03, 0x00, 0x03}));
    }
}
