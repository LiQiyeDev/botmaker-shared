package com.botmaker.shared.capture.linux;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure helpers in {@link LinuxController} — no X11 connection required.
 */
class LinuxControllerTest {

    @Test
    void allBlackImageIsDetected() {
        BufferedImage black = new BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB);
        // TYPE_INT_ARGB defaults to 0x00000000 — treat as black (alpha ignored by isAllBlack).
        assertTrue(LinuxController.isAllBlack(black));
    }

    @Test
    void aSingleNonBlackPixelMakesItNotAllBlack() {
        BufferedImage img = new BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB);
        // Put a lone colored pixel on the sparse sample grid (step = min(200,120)/17 = 7).
        img.setRGB(7 * 5, 7 * 3, 0xFF102030);
        assertFalse(LinuxController.isAllBlack(img));
    }

    @Test
    void nullIsTreatedAsBlack() {
        assertTrue(LinuxController.isAllBlack(null));
    }
}
