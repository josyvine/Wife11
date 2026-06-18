package com.wife.app;

import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class CompressionUtils {

    private CompressionUtils() {
        // Prevent instantiation of utility class
    }

    /**
     * Wraps an existing OutputStream into an LZ4FrameOutputStream for high-speed,
     * on-the-fly streaming compression.
     *
     * @param out The raw target OutputStream.
     * @return A compressed LZ4FrameOutputStream.
     * @throws IOException If the LZ4 stream header initialization fails.
     */
    public static LZ4FrameOutputStream wrapOutputStream(OutputStream out) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("Target OutputStream cannot be null.");
        }
        // Utilizing default 4MB block size with block independence for stream stability
        return new LZ4FrameOutputStream(out);
    }

    /**
     * Wraps an existing InputStream into an LZ4FrameInputStream for high-speed,
     * on-the-fly streaming decompression.
     *
     * @param in The raw compressed source InputStream.
     * @return A decompressed LZ4FrameInputStream.
     * @throws IOException If the LZ4 stream frame validation fails.
     */
    public static LZ4FrameInputStream wrapInputStream(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("Source InputStream cannot be null.");
        }
        return new LZ4FrameInputStream(in);
    }

    /**
     * Compresses data directly from an input stream to a destination output stream.
     * Highly optimized using an 8KB buffer to match high-speed memory boundaries.
     *
     * @param in  The raw source data InputStream.
     * @param out The destination stream where compressed data will be written.
     * @throws IOException If any read or write I/O error occurs.
     */
    public static void compress(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) {
            throw new IllegalArgumentException("Streams cannot be null.");
        }
        LZ4FrameOutputStream lz4Out = new LZ4FrameOutputStream(out);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > 0) {
            lz4Out.write(buffer, 0, len);
        }
        // It is critical to close the LZ4 stream to finalize the frame.
        // This also closes the underlying 'out' stream.
        lz4Out.close();
    }

    /**
     * Decompresses data directly from an LZ4 compressed input stream to a destination output stream.
     * Highly optimized using an 8KB buffer to match high-speed memory boundaries.
     *
     * @param in  The compressed source data InputStream.
     * @param out The destination stream where decompressed data will be written.
     * @throws IOException If any read or write I/O error occurs.
     */
    public static void decompress(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) {
            throw new IllegalArgumentException("Streams cannot be null.");
        }
        LZ4FrameInputStream lz4In = new LZ4FrameInputStream(in);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = lz4In.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        // Closing the wrapper stream is good practice and also closes the underlying 'in' stream.
        lz4In.close();
    }
}