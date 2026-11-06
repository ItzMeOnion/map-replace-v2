package dev.maptooltip.client;

import net.minecraft.block.MapColor;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns any picture into 128x128 map colour bytes.
 *
 * Pipeline: centre-crop to a square -> bicubic scale to 128x128 -> Floyd-Steinberg
 * dithering onto the real Minecraft map palette. The dithering is what makes the
 * result look like a proper in-game map instead of a flat, posterised picture.
 */
public final class ImageConverter {
    public static final int SIZE = 128;

    /** ARGB colour for every possible map byte. Index 0 (and any transparent slot) is 0. */
    private static final int[] ARGB_BY_BYTE = new int[256];

    /** Usable palette entries (map byte values that actually draw something). */
    private static final byte[] PAL_BYTE;
    private static final float[] PAL_R, PAL_G, PAL_B;

    static {
        List<Integer> usable = new ArrayList<>();
        for (int b = 4; b < 256; b++) { // ids 0 (NONE) are transparent
            int c = MapColor.getRenderColor((byte) b);
            int rgb = c & 0xFFFFFF;
            if (rgb == 0 && ((c >>> 24) & 0xFF) == 0) continue;
            ARGB_BY_BYTE[b] = 0xFF000000 | rgb;
            usable.add(b);
        }

        PAL_BYTE = new byte[usable.size()];
        PAL_R = new float[usable.size()];
        PAL_G = new float[usable.size()];
        PAL_B = new float[usable.size()];
        for (int i = 0; i < usable.size(); i++) {
            int b = usable.get(i);
            int argb = ARGB_BY_BYTE[b];
            PAL_BYTE[i] = (byte) b;
            PAL_R[i] = (argb >> 16) & 0xFF;
            PAL_G[i] = (argb >> 8) & 0xFF;
            PAL_B[i] = argb & 0xFF;
        }
    }

    private ImageConverter() {}

    /** ARGB colour for a stored map byte, or 0 for transparent. */
    public static int argbFor(byte mapByte) {
        return ARGB_BY_BYTE[mapByte & 0xFF];
    }

    /** Reads an image file and returns 16384 map colour bytes. Throws with a readable message on failure. */
    public static byte[] convert(Path file) throws IOException {
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) {
            throw new IOException("Unsupported or unreadable image (use PNG, JPG, GIF or BMP)");
        }
        return convert(src);
    }

    /** Converts an image that is already loaded, such as one pasted from the clipboard. */
    public static byte[] convert(BufferedImage src) {
        // Normalise to ARGB so transparency and odd colour models survive.
        BufferedImage argb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D base = argb.createGraphics();
        base.drawImage(src, 0, 0, null);
        base.dispose();

        // Centre-crop to a square.
        int side = Math.min(argb.getWidth(), argb.getHeight());
        int sx = (argb.getWidth() - side) / 2;
        int sy = (argb.getHeight() - side) / 2;

        BufferedImage scaled = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(argb, 0, 0, SIZE, SIZE, sx, sy, sx + side, sy + side, null);
        g.dispose();

        return quantize(scaled);
    }

    /** Floyd-Steinberg dithering onto the map palette. */
    private static byte[] quantize(BufferedImage img) {
        int n = SIZE * SIZE;
        float[] r = new float[n];
        float[] g = new float[n];
        float[] b = new float[n];
        boolean[] clear = new boolean[n];

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = y * SIZE + x;
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    clear[i] = true;
                } else {
                    r[i] = (argb >> 16) & 0xFF;
                    g[i] = (argb >> 8) & 0xFF;
                    b[i] = argb & 0xFF;
                }
            }
        }

        byte[] out = new byte[n];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = y * SIZE + x;
                if (clear[i]) {
                    out[i] = 0;
                    continue;
                }
                float cr = clamp(r[i]), cg = clamp(g[i]), cb = clamp(b[i]);
                int best = nearest(cr, cg, cb);
                out[i] = PAL_BYTE[best];

                float er = cr - PAL_R[best];
                float eg = cg - PAL_G[best];
                float eb = cb - PAL_B[best];
                diffuse(r, g, b, clear, x + 1, y, er, eg, eb, 7f / 16f);
                diffuse(r, g, b, clear, x - 1, y + 1, er, eg, eb, 3f / 16f);
                diffuse(r, g, b, clear, x, y + 1, er, eg, eb, 5f / 16f);
                diffuse(r, g, b, clear, x + 1, y + 1, er, eg, eb, 1f / 16f);
            }
        }
        return out;
    }

    private static void diffuse(float[] r, float[] g, float[] b, boolean[] clear,
                                int x, int y, float er, float eg, float eb, float factor) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
        int j = y * SIZE + x;
        if (clear[j]) return;
        r[j] += er * factor;
        g[j] += eg * factor;
        b[j] += eb * factor;
    }

    /** Nearest palette entry, using a weighted distance that tracks human colour sensitivity. */
    private static int nearest(float r, float g, float b) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < PAL_BYTE.length; i++) {
            float dr = r - PAL_R[i];
            float dg = g - PAL_G[i];
            float db = b - PAL_B[i];
            float d = 2f * dr * dr + 4f * dg * dg + 3f * db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
