/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.Test;

/**
 * Tests the headless PNG → base64 round-trip used by {@code get_form_rendering}
 * to ship the rendered image inside the tool response.
 */
public class PngBase64EncoderTest {

    @Test
    public void encodedStringIsValidBase64PngWithSignature() throws Exception {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        // Paint something non-trivial so the PNG isn't a uniform pixel run.
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                img.setRGB(x, y, (x * 7 + y * 11) & 0xFFFFFF);
            }
        }
        String encoded = PngBase64Encoder.encode(img);
        assertNotNull(encoded);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        // PNG signature is 89 50 4E 47 0D 0A 1A 0A.
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 'P', bytes[1]);
        assertEquals((byte) 'N', bytes[2]);
        assertEquals((byte) 'G', bytes[3]);
    }

    @Test
    public void roundTripPreservesDimensions() throws Exception {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        String encoded = PngBase64Encoder.encode(img);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(decoded);
        assertEquals(120, decoded.getWidth());
        assertEquals(80, decoded.getHeight());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullImage() {
        PngBase64Encoder.encode(null);
    }

    @Test
    public void emptyOnePixelImageStillRoundTrips() throws Exception {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        String encoded = PngBase64Encoder.encode(img);
        assertTrue(encoded.length() > 4);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertEquals(1, decoded.getWidth());
        assertEquals(1, decoded.getHeight());
    }
}
