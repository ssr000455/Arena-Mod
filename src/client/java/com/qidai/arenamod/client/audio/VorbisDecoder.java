package com.qidai.arenamod.client.audio;

import com.jcraft.jogg.Buffer;
import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;

import java.io.*;
import java.util.*;

/**
 * Vorbis 音频解码器
 *
 * 使用 jogg 解析 OGG 容器，纯 Java 实现 Vorbis 音频解码。
 */
public class VorbisDecoder {
    private static final int INPUT_CHUNK = 4096;

    // ===== 流信息 =====
    private int channels;
    private int sampleRate;
    private int blockSize0; // 短块（如 256）
    private int blockSize1; // 长块（如 2048）

    // ===== 设置头数据 =====
    private CodeBook[] codebooks;
    private FloorConfig[] floors;
    private ResidueConfig[] residues;
    private MappingConfig[] mappings;
    private ModeConfig[] modes;

    // ===== 解码状态 =====
    private Mdct mdct0, mdct1;
    private float[][] prevWindow;        // 前一帧右半段 [ch][n/2]
    private boolean hasPrevWindow;
    private boolean prevBlockFlag;

    // ===== OGG 流 =====
    private SyncState syncState;
    private StreamState streamState;
    private boolean streamInited;
    private int packetCount;

    // ===== I/O =====
    private InputStream input;

    // ===== PCM 输出队列 =====
    private final ArrayDeque<float[]> pcmFifo = new ArrayDeque<>(); // [ch][n]
    private float[] pcmPartial;           // 当前正在读取的部分帧
    private int pcmPartialPos;            // 已读取的样本数
    private int pcmPartialChannels;       // 声道数

    // ==================== 公开方法 ====================

    public boolean open(File file) throws IOException {
        input = new BufferedInputStream(new FileInputStream(file));
        syncState = new SyncState();
        syncState.init();
        streamInited = false;
        packetCount = 0;
        hasPrevWindow = false;

        // 读 3 个头包
        int hdr = 0;
        while (hdr < 3) {
            Packet pkt = readPacket();
            if (pkt == null) break;
            if (processHeader(pkt)) hdr++;
        }
        if (hdr < 3) { close(); return false; }

        mdct0 = new Mdct(blockSize0);
        mdct1 = new Mdct(blockSize1);
        prevWindow = new float[channels][blockSize1 / 2];
        pcmPartial = null;
        pcmPartialPos = 0;
        return true;
    }

    /** 读取 16-bit 小端交错 PCM，返回写入的字节数 */
    public int readPcm(byte[] buf, int off, int maxBytes) throws IOException {
        int maxSamples = maxBytes / (2 * channels);
        int written = 0;

        while (written < maxSamples) {
            // 如果没有部分帧，尝试解码新包
            if (pcmPartial == null) {
                if (!decodeNextPacket()) break;
            }

            float[] frame = pcmPartial;
            int frameSamples = frame.length / channels;
            int avail = frameSamples - pcmPartialPos;
            int need = maxSamples - written;
            int take = Math.min(avail, need);

            for (int s = 0; s < take; s++) {
                int srcIdx = pcmPartialPos + s;
                for (int ch = 0; ch < channels; ch++) {
                    float val = frame[srcIdx * channels + ch];
                    int sample = clampToShort(val);
                    int idx = off + (written + s) * 2 * channels + ch * 2;
                    if (idx + 1 < off + maxBytes) {
                        buf[idx]     = (byte) (sample & 0xFF);
                        buf[idx + 1] = (byte) ((sample >> 8) & 0xFF);
                    }
                }
            }
            written += take;
            pcmPartialPos += take;

            if (pcmPartialPos >= frameSamples) {
                pcmPartial = null;
                pcmPartialPos = 0;
            }
        }
        return written * 2 * channels;
    }

    public int getChannels() { return channels; }
    public int getSampleRate() { return sampleRate; }

    public void close() {
        try { if (input != null) input.close(); } catch (IOException ignored) {}
        if (streamState != null) streamState.clear();
        if (syncState != null) syncState.clear();
    }

    // ==================== OGG 包读取 ====================

    private Packet readPacket() throws IOException {
        if (!streamInited) return readFirstPacket();

        Packet pkt = new Packet();
        while (true) {
            int r = streamState.packetout(pkt);
            if (r == 1) return pkt;
            if (r == -1) continue; // 丢包

            // r == 0：需要更多数据
            Page page = new Page();
            int seek = syncState.pageseek(page);
            if (seek == 0) {
                int bytesNeeded = syncState.buffer(INPUT_CHUNK);
                if (bytesNeeded < 0) return null;
                int n = input.read(syncState.data, bytesNeeded, INPUT_CHUNK);
                if (n <= 0) return null;
                syncState.wrote(n);
            } else if (seek > 0) {
                streamState.pagein(page);
            } else {
                // seek < 0：同步丢失，跳过
            }
        }
    }

    private Packet readFirstPacket() throws IOException {
        Page page = new Page();
        while (true) {
            int bytes = syncState.buffer(INPUT_CHUNK);
            if (bytes < 0) return null;
            int n = input.read(syncState.data, bytes, INPUT_CHUNK);
            if (n <= 0) return null;
            syncState.wrote(n);

            if (syncState.pageout(page) != 0) {
                streamState = new StreamState();
                streamState.init(page.serialno());
                streamState.pagein(page);
                Packet pkt = new Packet();
                streamState.packetout(pkt);
                streamInited = true;
                return pkt;
            }
        }
    }

    // ==================== 头部处理 ====================

    private boolean processHeader(Packet pkt) {
        Buffer buf = new Buffer();
        buf.readinit(pkt.packet_base, pkt.packet, pkt.bytes);

        int type = buf.read(8);
        byte[] id = new byte[6];
        for (int i = 0; i < 6; i++) id[i] = (byte) buf.read(8);
        String ident = new String(id);

        if (!ident.equals("vorbis")) return false;

        switch (type) {
            case 1: return readIdentificationHeader(pkt);
            case 3: return true; // 注释头可跳过
            case 5: return readSetupHeader(pkt);
            default: return false;
        }
    }

    private boolean readIdentificationHeader(Packet pkt) {
        Buffer buf = new Buffer();
        buf.readinit(pkt.packet_base, pkt.packet, pkt.bytes);
        buf.read(8); // type
        for (int i = 0; i < 6; i++) buf.read(8); // "vorbis"

        int version = buf.read(32);
        channels = buf.read(8);
        sampleRate = buf.read(32);
        buf.read(32); // max bitrate
        buf.read(32); // nominal bitrate
        buf.read(32); // min bitrate

        int blockSizes = buf.read(8);
        blockSize0 = 1 << (blockSizes & 0x0F);
        blockSize1 = 1 << ((blockSizes >> 4) & 0x0F);
        buf.read(1); // framing flag
        return version == 0 && channels > 0 && sampleRate > 0;
    }

    private boolean readSetupHeader(Packet pkt) {
        Buffer buf = new Buffer();
        buf.readinit(pkt.packet_base, pkt.packet, pkt.bytes);
        buf.read(8); // type
        for (int i = 0; i < 6; i++) buf.read(8); // "vorbis"

        // ---- 码本 ----
        int cbc = buf.read(8) + 1;
        codebooks = new CodeBook[cbc];
        for (int i = 0; i < cbc; i++) {
            codebooks[i] = new CodeBook();
            try { codebooks[i].read(buf); } catch (Exception e) { codebooks[i] = null; }
        }

        // ---- 时域变换 ----
        int tc = buf.read(6) + 1;
        for (int i = 0; i < tc; i++) buf.read(16); // 总是 0

        // ---- Floor ----
        int fc = buf.read(6) + 1;
        floors = new FloorConfig[fc];
        for (int i = 0; i < fc; i++) floors[i] = readFloor(buf);

        // ---- Residue ----
        int rc = buf.read(6) + 1;
        residues = new ResidueConfig[rc];
        for (int i = 0; i < rc; i++) residues[i] = readResidue(buf);

        // ---- Mapping ----
        int mc = buf.read(6) + 1;
        mappings = new MappingConfig[mc];
        for (int i = 0; i < mc; i++) mappings[i] = readMapping(buf);

        // ---- Mode ----
        int modeCount = buf.read(6) + 1;
        modes = new ModeConfig[modeCount];
        for (int i = 0; i < modeCount; i++) {
            modes[i] = new ModeConfig();
            modes[i].blockFlag  = buf.read(1) != 0;
            modes[i].windowType = buf.read(16);
            modes[i].transformType = buf.read(16);
            modes[i].mapping    = buf.read(8);
        }
        buf.read(1); // framing
        return true;
    }

    // ==================== Floor ====================

    static class FloorConfig {
        int type;
        // type 0
        int order, rate, barkMapSize;
        int amplitudeBits, amplitudeOffset;
        int numberOfBooks;
        int[] bookList;
        // type 1
        int partitions;
        int[] partitionClassList;
        int[] classDimensions, classSubs, classBook;
        int[][] classSubBook;
        boolean[] classSubP;
    }

    static class FloorData {
        boolean valid;
        // type 0
        int amplitude;
        int[] bookIndices;
        // type 1
        int[] y;
        int rangeBits; // 原始 rangeBits
    }

    private FloorConfig readFloor(Buffer buf) {
        FloorConfig fc = new FloorConfig();
        fc.type = buf.read(16);
        if (fc.type == 0) {
            fc.order          = buf.read(8);
            fc.rate           = buf.read(16);
            fc.barkMapSize    = buf.read(16);
            fc.amplitudeBits  = buf.read(6);
            fc.amplitudeOffset = buf.read(8);
            fc.numberOfBooks  = buf.read(4) + 1;
            fc.bookList = new int[fc.numberOfBooks];
            for (int i = 0; i < fc.numberOfBooks; i++) fc.bookList[i] = buf.read(8);
        } else {
            fc.partitions = buf.read(5);
            fc.partitionClassList = new int[fc.partitions];
            int maxClass = -1;
            for (int i = 0; i < fc.partitions; i++) {
                fc.partitionClassList[i] = buf.read(4);
                if (fc.partitionClassList[i] > maxClass) maxClass = fc.partitionClassList[i];
            }
            int cc = maxClass + 1;
            fc.classDimensions = new int[cc];
            fc.classSubs       = new int[cc];
            fc.classBook       = new int[cc];
            fc.classSubBook    = new int[cc][];
            fc.classSubP       = new boolean[cc];
            for (int i = 0; i < cc; i++) {
                fc.classDimensions[i] = buf.read(3) + 1;
                fc.classSubs[i]       = buf.read(2);
                fc.classBook[i]       = buf.read(8);
                fc.classSubBook[i]    = new int[fc.classSubs[i]];
                for (int j = 0; j < fc.classSubs[i]; j++) {
                    fc.classSubBook[i][j] = buf.read(8) - 1;
                    if (fc.classSubBook[i][j] >= 0) fc.classSubP[i] = true;
                }
            }
        }
        return fc;
    }

    /** 解码 Floor 数据（返回 n/2 个点的曲线系数，重复到 n） */
    private FloorData decodeFloor(Buffer buf, FloorConfig fc, int halfN) {
        FloorData fd = new FloorData();
        if (fc.type == 0) {
            int amp = buf.read(fc.amplitudeBits);
            if (amp == 0) { fd.valid = false; return fd; }
            amp += fc.amplitudeOffset;
            if (amp > 255) amp = 255;
            int bookCount = fc.numberOfBooks;
            int mapSize = fc.barkMapSize;
            int[] indices = new int[fc.order];
            int idx = 0, i = 0;
            while (i < fc.order) {
                int bookNum = fc.bookList[idx];
                idx = (idx + 1) % bookCount;
                CodeBook bk = codebooks[bookNum];
                if (bk == null) { fd.valid = false; return fd; }
                int step = Math.max(1, mapSize / bk.dim);
                for (int j = 0; j < step && i < fc.order; j++, i++) {
                    int entry = bk.decodeScalar(buf);
                    if (entry < 0) { i = fc.order; break; }
                    indices[i] = entry;
                }
            }
            fd.amplitude = amp;
            fd.bookIndices = indices;
            fd.valid = true;
        } else {
            int rangeBits = buf.read(4);
            fd.rangeBits = rangeBits;
            int maxVal = (1 << rangeBits) - 1;
            int[] yArr = new int[fc.partitions + 1];
            yArr[0] = buf.read(rangeBits);
            if (yArr[0] > maxVal) yArr[0] = maxVal;
            int yIdx = 1;

            for (int p = 0; p < fc.partitions; p++) {
                int cls = fc.partitionClassList[p];
                int cDim = fc.classDimensions[cls];
                int cSub = fc.classSubs[cls];

                // 确保 yArr 足够大
                if (yIdx + cDim > yArr.length) {
                    yArr = Arrays.copyOf(yArr, yIdx + cDim);
                }

                if (cSub == 0) {
                    // 无子码书：沿用上一个位置
                    for (int d = 0; d < cDim && yIdx < yArr.length; d++, yIdx++) {
                        yArr[yIdx] = (yIdx > 0) ? yArr[yIdx - 1] : 0;
                    }
                    continue;
                }

                for (int s = 0; s < cSub; s++) {
                    if (!fc.classSubP[cls]) continue;
                    int bkIdx = fc.classSubBook[cls][s];
                    if (bkIdx < 0 || bkIdx >= codebooks.length) continue;
                    CodeBook bk = codebooks[bkIdx];
                    if (bk == null) continue;

                    float[] vBuf = new float[bk.dim];
                    int entry = bk.decodeScalar(buf);
                    if (entry < 0) continue;

                    bk.decodeVectorEntry(entry, vBuf, 0);
                    for (int d = 0; d < cDim && yIdx < yArr.length; d++) {
                        int last = yIdx > 0 ? yArr[yIdx - 1] : 0;
                        int val = last + (int) ((d < bk.dim) ? vBuf[d] : 0);
                        if (val > maxVal) val = maxVal;
                        if (val < 0) val = 0;
                        yArr[yIdx++] = val;
                    }
                }
            }
            fd.y = yArr;
            fd.valid = true;
        }
        return fd;
    }

    /** 合成 Floor 曲线（输出 halfN 个点） */
    private void synthesizeFloor(float[] out, FloorConfig fc, FloorData fd, int halfN) {
        if (!fd.valid || fc == null) { Arrays.fill(out, 1.0f); return; }

        if (fc.type == 0) {
            int mapSize = Math.min(fc.barkMapSize, halfN);
            float[] lsp = new float[fc.order];
            int bi = 0;
            for (int i = 0; i < fc.order; ) {
                int bookNum = fc.bookList[bi % fc.numberOfBooks];
                bi++;
                CodeBook bk = codebooks[bookNum];
                if (bk == null) continue;
                float[] vals = new float[bk.dim];
                if (bi > 0 && fd.bookIndices != null && fd.bookIndices.length > 0) {
                    int idx = Math.min(i, fd.bookIndices.length - 1);
                    bk.decodeVectorEntry(fd.bookIndices[idx], vals, 0);
                    for (int j = 0; j < bk.dim && i < fc.order; j++, i++) {
                        lsp[i] = vals[j];
                    }
                } else {
                    break;
                }
            }
            for (int i = 0; i < halfN; i++) {
                float freq = (float) (i + 1) / (halfN + 1);
                float val = 1.0f;
                for (int j = 0; j < fc.order && j < lsp.length; j++) {
                    float delta = freq - lsp[j] / (float) Math.PI;
                    val *= 2.0f * (float) Math.abs(Math.sin(Math.PI * delta));
                }
                out[i] = Math.min((val < 0.001f ? 1.0f : 1.0f / val) * (fd.amplitude / 255.0f), 1.0f);
            }
        } else {
            if (fd.y == null || fd.y.length < 2) { Arrays.fill(out, 1.0f); return; }
            int rangeBits = fd.rangeBits;
            int maxVal = (1 << rangeBits) - 1;
            if (maxVal <= 0) maxVal = 1;

            int n = fd.y.length;
            float[] xPos = new float[n];
            float[] yPos = new float[n];
            xPos[0] = 0;
            yPos[0] = fd.y[0] / (float) maxVal;
            int yi = 1;
            for (int p = 0; p < fc.partitions && yi < n; p++) {
                int cls = fc.partitionClassList[p];
                int cDim = fc.classDimensions[cls];
                for (int d = 0; d < cDim && yi < n; d++, yi++) {
                    xPos[yi] = (float) (p + d + 1) / (fc.partitions + 1);
                    yPos[yi] = fd.y[yi] / (float) maxVal;
                }
            }
            if (yi > 1) xPos[yi - 1] = 1.0f;

            int xi = 0;
            for (int i = 0; i < halfN; i++) {
                float pos = (float) i / halfN;
                while (xi + 1 < n - 1 && xPos[xi + 1] < pos) xi++;
                float val;
                if (xi + 1 >= n) val = yPos[n - 1];
                else {
                    float span = xPos[xi + 1] - xPos[xi];
                    float t = span < 1e-6f ? 0 : (pos - xPos[xi]) / span;
                    val = yPos[xi] + t * (yPos[xi + 1] - yPos[xi]);
                }
                if (val < 0) val = 0;
                if (val > 1) val = 1;
                out[i] = val;
            }
        }
    }

    // ==================== Residue ====================

    static class ResidueConfig {
        int type, begin, end, partitionSize, classifications, classBook;
        int[] cascade;
        int[][] books;
    }

    private ResidueConfig readResidue(Buffer buf) {
        ResidueConfig rc = new ResidueConfig();
        rc.type = buf.read(16);
        rc.begin = buf.read(24);
        rc.end = buf.read(24);
        rc.partitionSize = buf.read(24) + 1;
        rc.classifications = buf.read(6) + 1;
        rc.classBook = buf.read(8);
        rc.cascade = new int[rc.classifications];
        rc.books = new int[rc.classifications][];
        for (int i = 0; i < rc.classifications; i++) {
            int low  = buf.read(3);
            int high = buf.read(5);
            rc.cascade[i] = (high << 3) | low;
            int bookCount = Integer.bitCount(rc.cascade[i]);
            rc.books[i] = new int[bookCount];
            int bIdx = 0;
            for (int j = 0; j < 8; j++) {
                if ((rc.cascade[i] & (1 << j)) != 0) {
                    rc.books[i][bIdx++] = buf.read(8);
                }
            }
        }
        return rc;
    }

    /** 解码 Residue（输出 n 个值 / 声道，0~n-1） */
    private void decodeResidue(Buffer buf, ResidueConfig rc, float[][] out, int chCount, int halfN) {
        for (int c = 0; c < chCount; c++) Arrays.fill(out[c], 0, halfN, 0f);

        int begin = Math.min(rc.begin, halfN);
        int end = Math.min(rc.end, halfN);
        int n = end - begin;
        if (n <= 0) return;

        int partitionSize = rc.partitionSize;
        int partitions = (n + partitionSize - 1) / partitionSize;
        int classCount = rc.classifications;

        // 读分类
        int[] classVals = new int[partitions];
        CodeBook classBook = (rc.classBook >= 0 && rc.classBook < codebooks.length) ? codebooks[rc.classBook] : null;
        if (classBook != null) {
            for (int i = 0; i < partitions; ) {
                int entry = classBook.decodeScalar(buf);
                if (entry < 0) break;
                for (int j = classBook.dim - 1; j >= 0 && i < partitions; j--) {
                    classVals[i++] = entry % classCount;
                    entry /= classCount;
                }
            }
        }

        // 解码分区
        if (rc.type == 2) {
            // type 2：所有声道交错
            for (int p = 0; p < partitions; p++) {
                int cls = classVals[p];
                if (cls >= rc.cascade.length) continue;
                int[] subBooks = rc.books[cls];
                int bookIdx = 0;
                for (int bit = 0; bit < 8 && bookIdx < subBooks.length; bit++) {
                    if ((rc.cascade[cls] & (1 << bit)) == 0) continue;
                    int bkNum = subBooks[bookIdx++];
                    if (bkNum < 0 || bkNum >= codebooks.length) continue;
                    CodeBook bk = codebooks[bkNum];
                    if (bk == null) continue;

                    float[] vec = new float[bk.dim];
                    bk.decodeVectorFull(buf, vec, 0);

                    int base = p * partitionSize;
                    for (int d = 0; d < bk.dim && base + d < n; d++) {
                        for (int c = 0; c < chCount; c++) {
                            int idx = begin + base + d;
                            if (idx < halfN) {
                                out[c][idx] += vec[d];
                            }
                        }
                    }
                }
            }
        } else {
            // type 0/1：每声道独立
            for (int p = 0; p < partitions; p++) {
                int cls = classVals[p];
                if (cls >= rc.cascade.length) continue;
                int[] subBooks = rc.books[cls];
                int bookIdx = 0;
                for (int bit = 0; bit < 8 && bookIdx < subBooks.length; bit++) {
                    if ((rc.cascade[cls] & (1 << bit)) == 0) continue;
                    int bkNum = subBooks[bookIdx++];
                    if (bkNum < 0 || bkNum >= codebooks.length) continue;
                    CodeBook bk = codebooks[bkNum];
                    if (bk == null) continue;

                    float[] vec = new float[bk.dim];
                    int base = p * partitionSize;
                    for (int c = 0; c < chCount; c++) {
                        bk.decodeVectorFull(buf, vec, 0);
                        for (int d = 0; d < bk.dim && base + d < n; d++) {
                            int idx = begin + base + d;
                            if (idx < halfN) {
                                out[c][idx] += vec[d];
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== Mapping ====================

    static class MappingConfig {
        int submaps;
        int[] channelMuxList;
        int[] floorSubMap, residueSubMap;
        int couplingSteps;
        int[] couplingMagnitude, couplingAngle;
    }

    private MappingConfig readMapping(Buffer buf) {
        MappingConfig mc = new MappingConfig();
        mc.submaps = buf.read(1) != 0 ? buf.read(4) + 1 : 1;
        if (buf.read(1) != 0) {
            mc.channelMuxList = new int[channels];
            for (int i = 0; i < channels; i++) mc.channelMuxList[i] = buf.read(4);
        }
        mc.floorSubMap   = new int[mc.submaps];
        mc.residueSubMap = new int[mc.submaps];
        for (int i = 0; i < mc.submaps; i++) {
            buf.read(8); // format
            mc.floorSubMap[i]   = buf.read(8);
            mc.residueSubMap[i] = buf.read(8);
        }
        mc.couplingSteps = buf.read(1) != 0 ? buf.read(8) + 1 : 0;
        mc.couplingMagnitude = new int[mc.couplingSteps];
        mc.couplingAngle     = new int[mc.couplingSteps];
        int chBits = Math.max(1, (int) Math.ceil(Math.log(channels) / Math.log(2)));
        for (int i = 0; i < mc.couplingSteps; i++) {
            mc.couplingMagnitude[i] = buf.read(chBits);
            mc.couplingAngle[i]     = buf.read(chBits);
        }
        return mc;
    }

    // ==================== Mode ====================

    static class ModeConfig {
        boolean blockFlag;
        int windowType, transformType, mapping;
    }

    // ==================== 音频包解码 ====================

    private boolean decodeNextPacket() throws IOException {
        while (true) {
            Packet pkt = readPacket();
            if (pkt == null) return false;
            packetCount++;

            if (pkt.bytes > 0 && (pkt.packet_base[pkt.packet] & 1) == 0) {
                decodeAudioPacket(pkt);
                return true;
            }
        }
    }

    private void decodeAudioPacket(Packet pkt) {
        Buffer buf = new Buffer();
        buf.readinit(pkt.packet_base, pkt.packet, pkt.bytes);

        int modeBits = Math.max(1, bitsForMode());
        int modeNum = buf.read(modeBits);
        if (modeNum >= modes.length) return;

        ModeConfig mode = modes[modeNum];
        MappingConfig mapping = mappings[mode.mapping];
        boolean blockFlag = mode.blockFlag;
        int n = blockFlag ? blockSize1 : blockSize0;       // 窗口长度
        int halfN = n / 2;                                  // 频谱系数个数

        if (blockFlag) { buf.read(1); buf.read(1); } // window/transform type

        // ---- 解码 Floor（halfN 个点） ----
        int submaps = mapping.submaps;
        FloorData[] floorData = new FloorData[channels];
        for (int ch = 0; ch < channels; ch++) {
            int sm = (mapping.channelMuxList != null) ? mapping.channelMuxList[ch] : 0;
            if (sm >= submaps) sm = 0;
            int fi = mapping.floorSubMap[sm];
            floorData[ch] = (fi < floors.length) ? decodeFloor(buf, floors[fi], halfN) : new FloorData();
            if (floorData[ch] == null) { floorData[ch] = new FloorData(); floorData[ch].valid = false; }
        }

        // ---- 解码 Residue（halfN 个点/声道） ----
        float[][] residue = new float[channels][halfN];
        for (int s = 0; s < submaps; s++) {
            int ri = mapping.residueSubMap[s];
            if (ri >= residues.length) continue;

            // 收集该 submap 的有效声道
            int[] chList = new int[channels];
            int chCount = 0;
            for (int ch = 0; ch < channels; ch++) {
                int sm = (mapping.channelMuxList != null) ? mapping.channelMuxList[ch] : 0;
                if (sm == s && floorData[ch] != null && floorData[ch].valid) {
                    chList[chCount++] = ch;
                }
            }
            if (chCount == 0) continue;

            float[][] subRes = new float[chCount][halfN];
            decodeResidue(buf, residues[ri], subRes, chCount, halfN);
            for (int c = 0; c < chCount; c++) {
                System.arraycopy(subRes[c], 0, residue[chList[c]], 0, halfN);
            }
        }

        // ---- 声道耦合（多声道解耦，作用于 halfN） ----
        for (int step = 0; step < mapping.couplingSteps; step++) {
            int magCh = mapping.couplingMagnitude[step];
            int angCh = mapping.couplingAngle[step];
            if (magCh >= channels || angCh >= channels) continue;
            for (int i = 0; i < halfN; i++) {
                float m = residue[magCh][i];
                float a = residue[angCh][i];
                residue[magCh][i] = (float) (m * Math.cos(a));
                residue[angCh][i] = (float) (m * Math.sin(a));
            }
        }

        // ---- 合成频谱 = floor * residue ----
        float[][] coeffs = new float[channels][halfN];
        float[] floorCurve = new float[halfN];
        for (int ch = 0; ch < channels; ch++) {
            int sm = (mapping.channelMuxList != null) ? mapping.channelMuxList[ch] : 0;
            if (sm >= submaps) sm = 0;
            int fi = mapping.floorSubMap[sm];
            synthesizeFloor(floorCurve, (fi < floors.length) ? floors[fi] : null, floorData[ch], halfN);
            for (int i = 0; i < halfN; i++) {
                coeffs[ch][i] = residue[ch][i] * floorCurve[i];
            }
        }

        // ---- IMDCT：halfN 频谱 → n 时域样本 ----
        float[][] pcm = new float[channels][n];
        Mdct mdct = blockFlag ? mdct1 : mdct0;
        float[] tempOut = new float[n * 2];
        for (int ch = 0; ch < channels; ch++) {
            mdct.imdct(coeffs[ch], tempOut, 0, 1);
            System.arraycopy(tempOut, 0, pcm[ch], 0, n);
        }

        // ---- Overlap-add ----
        int prevN = hasPrevWindow ? (prevBlockFlag ? blockSize1 : blockSize0) : 0;
        float[] interleaved = new float[channels * n];

        if (hasPrevWindow && prevN > 0) {
            for (int ch = 0; ch < channels; ch++) {
                int overlap = Math.min(prevN, n) / 2;
                for (int i = 0; i < overlap; i++) {
                    pcm[ch][i] += prevWindow[ch][i];
                }
            }
        }

        // 保存右半段用于下一帧 overlap
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(pcm[ch], n / 2, prevWindow[ch], 0, n / 2);
        }
        hasPrevWindow = true;
        prevBlockFlag = blockFlag;

        // 交错并入队列（跳过第一个包避免预回声）
        if (packetCount > 1) {
            for (int i = 0; i < n; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    interleaved[i * channels + ch] = pcm[ch][i];
                }
            }
            pcmPartial = interleaved;
            pcmPartialPos = 0;
            pcmPartialChannels = channels;
        }
    }

    private int bitsForMode() {
        int c = modes.length - 1, b = 0;
        while (c > 0) { c >>= 1; b++; }
        return b;
    }

    private static int clampToShort(float val) {
        if (val >  32767.0f / 32768.0f) val =  32767.0f / 32768.0f;
        if (val < -32768.0f / 32768.0f) val = -32768.0f / 32768.0f;
        return (int) (val * 32768.0f);
    }
}
