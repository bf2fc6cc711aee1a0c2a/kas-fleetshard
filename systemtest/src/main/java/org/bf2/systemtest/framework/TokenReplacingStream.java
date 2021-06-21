package org.bf2.systemtest.framework;

import java.io.IOException;
import java.io.InputStream;

/**
 * Search and replace array of bytes in an InputStream.
 */
public class TokenReplacingStream extends InputStream {

    private final InputStream source;
    private final byte[] oldBytes;
    private final byte[] newBytes;
    private int tokenMatchIndex = 0;
    private int bytesIndex = 0;
    private boolean unwinding;
    private int mismatch;

    public TokenReplacingStream(InputStream source, byte[] oldBytes, byte[] newBytes) {
        if (oldBytes.length == 0) {
            throw new RuntimeException("Nothing to replace");
        }
        this.source = source;
        this.oldBytes = oldBytes;
        this.newBytes = newBytes;
    }

    @Override
    public int read() throws IOException {
        if (unwinding) {
            if (bytesIndex < tokenMatchIndex) {
                return oldBytes[bytesIndex++];
            } else {
                bytesIndex = 0;
                tokenMatchIndex = 0;
                unwinding = false;
                return mismatch;
            }
        } else if (tokenMatchIndex == oldBytes.length) {
            if (bytesIndex == newBytes.length) {
                bytesIndex = 0;
                tokenMatchIndex = 0;
            } else {
                return newBytes[bytesIndex++];
            }
        }

        int b = source.read();
        if (b == oldBytes[tokenMatchIndex]) {
            tokenMatchIndex++;
        } else if (tokenMatchIndex > 0) {
            mismatch = b;
            unwinding = true;
        } else {
            return b;
        }
        return read();
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
