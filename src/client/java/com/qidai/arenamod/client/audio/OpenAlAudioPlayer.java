package com.qidai.arenamod.client.audio;

import com.qidai.arenamod.ArenaMod;
import org.lwjgl.openal.AL10;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 LWJGL OpenAL 的音频播放器
 * <p>
 * 使用 Minecraft 已初始化的 OpenAL 上下文播放 PCM 音频流。
 * 完全替代 javax.sound.sampled.SourceDataLine，兼容 FCL/Android 环境。
 */
public class OpenAlAudioPlayer implements AutoCloseable {
    private static final int BUFFER_COUNT = 4;
    private static final int BUFFER_SIZE = 65536;

    private int sourceId;
    private final int[] bufferIds = new int[BUFFER_COUNT];
    private boolean initialized = false;
    private int channels;
    private int sampleRate;

    /**
     * 初始化 OpenAL 源和缓冲区
     */
    public boolean initialize(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;

        try {
            sourceId = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                ArenaMod.LOGGER.error("OpenAL: 无法创建音频源");
                return false;
            }

            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);

            for (int i = 0; i < BUFFER_COUNT; i++) {
                bufferIds[i] = AL10.alGenBuffers();
            }

            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                ArenaMod.LOGGER.error("OpenAL: 无法创建缓冲区");
                cleanup();
                return false;
            }

            initialized = true;
            return true;
        } catch (Exception e) {
            ArenaMod.LOGGER.error("OpenAL 初始化失败", e);
            cleanup();
            return false;
        }
    }

    /** 开始播放 */
    public void play() {
        if (!initialized) return;
        AL10.alSourcePlay(sourceId);
    }

    /** 暂停 */
    public void pause() {
        if (!initialized) return;
        AL10.alSourcePause(sourceId);
    }

    /** 停止并清理 */
    public void stop() {
        if (!initialized) return;
        AL10.alSourceStop(sourceId);
        int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
        if (queued > 0) {
            AL10.alSourceUnqueueBuffers(sourceId, new int[queued]);
        }
    }

    /** 播放结束后等待 */
    public void waitForFinish(AtomicBoolean stopRef) {
        if (!initialized) return;
        while (!stopRef.get()) {
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
            if (queued == 0 || (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED)) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
    }

    /**
     * 播放一整段 PCM 数据（非流式，适合 WAV 等已完全加载的文件）
     *
     * @param format   OpenAL 格式常量 (AL_FORMAT_MONO16 等)
     * @param pcmData  PCM 数据（DirectBuffer）
     * @param stopRef  停止信号
     */
    public void playOnce(int format, ByteBuffer pcmData, AtomicBoolean stopRef) {
        if (!initialized) return;

        int bufId = AL10.alGenBuffers();
        AL10.alBufferData(bufId, format, pcmData, sampleRate);
        AL10.alSourceQueueBuffers(sourceId, bufId);
        AL10.alSourcePlay(sourceId);

        waitForFinish(stopRef);

        AL10.alSourceStop(sourceId);
        AL10.alSourceUnqueueBuffers(sourceId, new int[]{bufId});
        AL10.alDeleteBuffers(bufId);
    }

    /**
     * 流式播放：填充初始缓冲并开始播放
     *
     * @return 填充的缓冲数量
     */
    public int fillInitial(byte[] pcmBuf, AtomicBoolean stopRef) {
        if (!initialized) return 0;

        int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        int filled = 0;

        for (int i = 0; i < BUFFER_COUNT && !stopRef.get(); i++) {
            int bytesRead = decoder_readPcm(pcmBuf, 0, BUFFER_SIZE);
            if (bytesRead <= 0) break;

            ByteBuffer bb = ByteBuffer.allocateDirect(bytesRead);
            bb.put(pcmBuf, 0, bytesRead);
            bb.flip();
            AL10.alBufferData(bufferIds[i], format, bb, sampleRate);
            AL10.alSourceQueueBuffers(sourceId, bufferIds[i]);
            filled++;

            if (AL10.alGetError() != AL10.AL_NO_ERROR) break;
        }

        return filled;
    }

    /**
     * 流式播放：持续从解码器读取数据补充缓冲队列
     */
    public void streamContinue(byte[] pcmBuf, AtomicBoolean stopRef) {
        if (!initialized) return;

        int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        int[] processedBuf = new int[1];

        while (!stopRef.get()) {
            int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);

            if (processed > 0) {
                for (int i = 0; i < processed; i++) {
                    AL10.alSourceUnqueueBuffers(sourceId, processedBuf);
                    int bytesRead = decoder_readPcm(pcmBuf, 0, BUFFER_SIZE);
                    if (bytesRead <= 0) continue;

                    ByteBuffer bb = ByteBuffer.allocateDirect(bytesRead);
                    bb.put(pcmBuf, 0, bytesRead);
                    bb.flip();
                    AL10.alBufferData(processedBuf[0], format, bb, sampleRate);

                    if (AL10.alGetError() != AL10.AL_NO_ERROR) return;
                    AL10.alSourceQueueBuffers(sourceId, processedBuf);
                }
            }

            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING) {
                int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
                if (queued > 0) AL10.alSourcePlay(sourceId);
                else break;
            }

            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }

        waitForFinish(stopRef);
    }

    // ===== VorbisDecoder 回调 =====
    // 由 ArenaMusicManager 在播放前设置
    private VorbisDecoder decoderRef;
    public void setDecoder(VorbisDecoder decoder) { this.decoderRef = decoder; }

    private int decoder_readPcm(byte[] buf, int off, int len) {
        if (decoderRef != null) {
            try {
                return decoderRef.readPcm(buf, off, len);
            } catch (IOException e) {
                return -1;
            }
        }
        return -1;
    }

    /** 获取源 ID（供 ArenaMusicManager 直接操作） */
    public int getSourceId() { return sourceId; }

    private void cleanup() {
        if (sourceId != 0 && AL10.alIsSource(sourceId)) {
            AL10.alSourceStop(sourceId);
            AL10.alDeleteSources(sourceId);
            sourceId = 0;
        }
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (bufferIds[i] != 0 && AL10.alIsBuffer(bufferIds[i])) {
                AL10.alDeleteBuffers(bufferIds[i]);
                bufferIds[i] = 0;
            }
        }
        initialized = false;
    }

    @Override
    public void close() {
        cleanup();
    }
}
