package com.wdcdp.cdpscreen.validation;

import java.util.Base64;

import com.mojang.blaze3d.platform.NativeImage;
import com.wdcdp.cdpscreen.render.FrameDecoder;

/**
 * Where does a frame's CPU time actually go?
 *
 * <p>Three stages per frame: base64 decode, STB decode to a NativeImage, and the
 * pixel copy into the image. The texture upload is measured in-game because it
 * needs a GL context. Knowing which stage dominates decides what to optimise -
 * guessing at it has already cost this project two wrong "fixes".</p>
 */
public class CpuProfile {

    public static void main(String[] args) throws Exception {
        System.out.println("=== per-frame CPU profile (640x360) ===");

        byte[] jpeg = syntheticJpeg(640, 360);
        String base64 = Base64.getEncoder().encodeToString(jpeg);
        System.out.printf("jpeg %d KB, base64 %d KB%n", jpeg.length / 1024,
                base64.length() / 1024);

        // Warm up so the numbers are steady-state.
        for (int i = 0; i < 40; i++) {
            byte[] raw = Base64.getDecoder().decode(base64);
            try (NativeImage image = FrameDecoder.decode(raw)) {
                if (image.getWidth() != 640) {
                    throw new IllegalStateException("bad decode");
                }
            }
        }

        int iterations = 200;

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Base64.getDecoder().decode(base64);
        }
        double base64Ms = (System.nanoTime() - start) / 1e6 / iterations;

        byte[] raw = Base64.getDecoder().decode(base64);
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try (NativeImage image = FrameDecoder.decode(raw)) {
                // close is part of the cost
            }
        }
        double decodeMs = (System.nanoTime() - start) / 1e6 / iterations;

        // Raw pixel copy alone, for comparison with the decode.
        try (NativeImage source = FrameDecoder.decode(raw)) {
            start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                try (NativeImage copy = new NativeImage(source.getWidth(),
                        source.getHeight(), false)) {
                    org.lwjgl.system.MemoryUtil.memCopy(
                            source.getPointer(), copy.getPointer(),
                            (long) source.getWidth() * source.getHeight() * 4L);
                }
            }
            double copyMs = (System.nanoTime() - start) / 1e6 / iterations;

            System.out.println();
            System.out.printf("  base64 decode  %6.3f ms%n", base64Ms);
            System.out.printf("  STB decode     %6.3f ms%n", decodeMs);
            System.out.printf("  alloc + copy   %6.3f ms%n", copyMs);
            System.out.println();
            System.out.printf("  total (excl. upload) %6.3f ms  -> %5.1f fps on one thread%n",
                    base64Ms + decodeMs, 1000.0 / (base64Ms + decodeMs));
        }
    }

    private static byte[] syntheticJpeg(int width, int height) throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(new java.awt.Color(20, 40, 90));
        g.fillRect(0, 0, width, height);
        for (int i = 0; i < 300; i++) {
            g.setColor(new java.awt.Color((i * 37) % 255, (i * 91) % 255, (i * 53) % 255));
            g.fillRect((i * 17) % width, (i * 31) % height, 50, 30);
        }
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
