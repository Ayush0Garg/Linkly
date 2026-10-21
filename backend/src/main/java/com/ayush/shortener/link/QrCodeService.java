package com.ayush.shortener.link;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * Draws a QR code for a short link, as PNG or SVG, using ZXing for the encoding.
 * Modules are always drawn as whole pixels, so the code stays sharp and scannable: the PNG is the largest whole
 * multiple of the module count that fits the requested size (never smaller than 1 pixel per module).
 */
@Service
public class QrCodeService {

    /** Quiet zone, in modules. 4 is the width the QR specification asks for; scanners rely on it. */
    private static final int QUIET_ZONE = 4;

    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q, // survives ~25% damage: fine for print and screens
            EncodeHintType.MARGIN, QUIET_ZONE,
            EncodeHintType.CHARACTER_SET, "UTF-8");

    /** The module grid including the quiet zone (true = dark). Package-visible so tests can decode what we draw. */
    BitMatrix matrix(String text) {
        try {
            // Width/height 0 asks for the smallest matrix: exactly the modules plus the margin, no scaling.
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, HINTS);
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode this text as a QR code", e);
        }
    }

    public byte[] png(String text, int targetSize) {
        BitMatrix grid = matrix(text);
        int scale = Math.max(1, targetSize / grid.getWidth());
        int side = grid.getWidth() * scale;
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_BINARY); // 1 bit per pixel
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, y, grid.get(x / scale, y / scale) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Vector output for print: one path, with each horizontal run of dark modules drawn as a single rectangle. */
    public String svg(String text, int displaySize) {
        BitMatrix grid = matrix(text);
        int n = grid.getWidth();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < n; y++) {
            int x = 0;
            while (x < n) {
                if (!grid.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < n && grid.get(x, y)) {
                    x++;
                }
                path.append('M').append(start).append(' ').append(y).append('h').append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + n + ' ' + n + "\" width=\"" + displaySize
                + "\" height=\"" + displaySize + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + n + "\" height=\"" + n + "\" fill=\"#fff\"/>"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }
}
