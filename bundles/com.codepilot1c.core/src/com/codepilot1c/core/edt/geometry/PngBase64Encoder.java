/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.geometry;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * Encodes a {@link BufferedImage} as a base64 PNG string for inclusion
 * in a tool result payload. Pure-Java; uses {@link ImageIO} which is
 * available in the standard JRE.
 */
public final class PngBase64Encoder {

    private PngBase64Encoder() {
        // static utility
    }

    /**
     * @return base64-encoded PNG bytes
     * @throws IllegalArgumentException if {@code image} is null
     * @throws UncheckedIOException     if PNG encoding fails
     */
    public static String encode(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write((RenderedImage) image, "png", out)) {
                throw new UncheckedIOException(
                        "PNG writer not available", new IOException("ImageIO.write returned false"));
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
