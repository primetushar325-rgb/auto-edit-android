package com.autoedit;

import static org.junit.Assert.*;

import com.autoedit.frames.FrameUtils;

import org.junit.Test;

/**
 * Spec §10: the extracted frame must match the preview. Both the preview
 * (ZoomPanView) and the extractor (FrameExtractService) derive their crop from
 * FrameUtils.zoomPanRect, so that one function IS the guarantee. These tests
 * pin its behaviour.
 */
public class ZoomPanCropTest {

    @Test public void noZoomReturnsTheWholeFrame() {
        int[] r = FrameUtils.zoomPanRect(1920, 1080, 1f, 0f, 0f);
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, r);
    }

    @Test public void zoomHalvesTheCropWindow() {
        int[] r = FrameUtils.zoomPanRect(1920, 1080, 2f, 0f, 0f);
        assertEquals(960, r[2]);
        assertEquals(540, r[3]);
        // centred
        assertEquals(480, r[0]);
        assertEquals(270, r[1]);
    }

    /** Whatever the pan, the window must stay entirely inside the source. */
    @Test public void cropNeverLeavesTheSource() {
        int[][] sizes = {{1920, 1080}, {1080, 1920}, {1080, 1080}, {1440, 1080}, {321, 177}};
        float[] pans = {-1f, -0.5f, 0f, 0.5f, 1f, -3f, 3f};
        float[] zooms = {1f, 1.3f, 2f, 4f, 8f, 0.2f, 99f};
        for (int[] s : sizes) {
            for (float z : zooms) {
                for (float px : pans) {
                    for (float py : pans) {
                        int[] r = FrameUtils.zoomPanRect(s[0], s[1], z, px, py);
                        int x = r[0], y = r[1], w = r[2], h = r[3];
                        String ctx = s[0] + "x" + s[1] + " z=" + z + " pan=" + px + "," + py;
                        assertTrue(ctx + " -> x=" + x, x >= 0);
                        assertTrue(ctx + " -> y=" + y, y >= 0);
                        assertTrue(ctx + " -> w=" + w, w > 0);
                        assertTrue(ctx + " -> h=" + h, h > 0);
                        assertTrue(ctx + " -> x+w=" + (x + w) + " > " + s[0], x + w <= s[0]);
                        assertTrue(ctx + " -> y+h=" + (y + h) + " > " + s[1], y + h <= s[1]);
                    }
                }
            }
        }
    }

    /** Pan extremes must sit flush against opposite edges, not overshoot. */
    @Test public void panExtremesSitFlushAgainstTheEdges() {
        int[] left = FrameUtils.zoomPanRect(1920, 1080, 2f, -1f, -1f);
        assertEquals(0, left[0]);
        assertEquals(0, left[1]);

        int[] right = FrameUtils.zoomPanRect(1920, 1080, 2f, 1f, 1f);
        assertEquals(1920 - right[2], right[0]);
        assertEquals(1080 - right[3], right[1]);
    }

    /** Zoom is clamped so the window can never become zero-sized. */
    @Test public void zoomIsClampedToAUsableRange() {
        int[] tiny = FrameUtils.zoomPanRect(1920, 1080, 99f, 0f, 0f);
        assertTrue("window must stay > 0", tiny[2] > 0 && tiny[3] > 0);
        int[] none = FrameUtils.zoomPanRect(1920, 1080, 0.1f, 0f, 0f);
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, none);
    }

    /** Pan must be monotonic: pushing further right moves the window right. */
    @Test public void panIsMonotonic() {
        int prev = Integer.MIN_VALUE;
        for (int i = -10; i <= 10; i++) {
            int x = FrameUtils.zoomPanRect(1920, 1080, 3f, i / 10f, 0f)[0];
            assertTrue("pan " + (i / 10f) + " moved backwards", x >= prev);
            prev = x;
        }
    }
}
