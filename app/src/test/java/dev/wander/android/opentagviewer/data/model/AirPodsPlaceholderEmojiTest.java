package dev.wander.android.opentagviewer.data.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * AirPods arrive from iCloud with an emoji nobody chose.
 *
 * <p>Every part of a set of AirPods Pro 3 carried U+1F600 with roleId 999 in its naming record,
 * which Apple's own apps never show. Displayed, it put the same grinning face on left, right and
 * case. The model is what tells AirPods apart, because an iCloud read names them there.
 */
public class AirPodsPlaceholderEmojiTest {

    private static final String GRINNING = "😀";

    @Test
    public void airPodsDoNotShowApplesPlaceholderEmoji() {
        final BeaconInformation airPods = BeaconInformation.builder()
                .beaconId("a").model("AirPods Pro (3rd generation)").originalEmoji(GRINNING).build();

        assertTrue(airPods.isAirPods());
        assertNull(airPods.getEmoji());
        assertFalse(airPods.isEmojiFilled());
    }

    @Test
    public void anEmojiTheUserSetOnAirPodsStillWins() {
        final BeaconInformation airPods = BeaconInformation.builder()
                .beaconId("a").model("AirPods Pro (3rd generation)").originalEmoji(GRINNING).build();
        airPods.setUserOverrideEmoji("🎧");

        assertEquals("🎧", airPods.getEmoji());
        assertTrue(airPods.isEmojiFilled());
    }

    @Test
    public void anAirTagKeepsTheEmojiItWasGiven() {
        final BeaconInformation airTag = BeaconInformation.builder()
                .beaconId("a").model("").originalEmoji(GRINNING).build();

        assertFalse(airTag.isAirPods());
        assertEquals(GRINNING, airTag.getEmoji());
    }
}
