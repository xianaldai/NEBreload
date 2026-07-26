package cn.ussshenzhou.notenoughbandwidth.zstd;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import java.io.Closeable;
import java.nio.ByteBuffer;

/**
 * @author USS_Shenzhou
 */
public class Context implements Closeable {
    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;
    private final boolean useContext;

    public Context(boolean useContext) {
        AndroidZstdNativeLoader.prepare();
        compressCtx = new ZstdCompressCtx();
        compressCtx.setLevel(3);
        compressCtx.setContentSize(false);
        compressCtx.setMagicless(true);
        compressCtx.setWindowLog(NotEnoughBandwidthLegacyConfig.get().getContextLevel());
        decompressCtx = new ZstdDecompressCtx();
        decompressCtx.setMagicless(true);
        this.useContext = useContext;
    }

    public ByteBuffer compress(ByteBuffer raw) {
        if (AndroidZstdNativeLoader.DIRECT_BUFFER_UNRELIABLE) {
            byte[] rawArr = new byte[raw.remaining()];
            raw.get(rawArr);
            return ByteBuffer.wrap(compressCtx.compress(rawArr));
        }
        if (useContext) {
            int maxDstSize = (int) Zstd.compressBound(raw.remaining());
            var dst = ByteBuffer.allocateDirect(maxDstSize);
            compressCtx.compressDirectByteBufferStream(dst, raw, EndDirective.FLUSH);
            dst.flip();
            return dst;
        }
        return compressCtx.compress(raw);
    }

    public ByteBuffer decompress(ByteBuffer compressed, int originalSize) {
        var dst = ByteBuffer.allocateDirect(originalSize);
        decompressCtx.decompressDirectByteBufferStream(dst, compressed);
        dst.flip();
        return dst;
    }


    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }
}
