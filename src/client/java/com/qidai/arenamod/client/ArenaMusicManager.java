package com.qidai.arenamod.client;

import com.qidai.arenamod.ArenaMod;
import com.qidai.arenamod.client.audio.OpenAlAudioPlayer;
import com.qidai.arenamod.client.audio.VorbisDecoder;
import com.qidai.arenamod.config.ArenaConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.openal.AL10;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 竞技场音乐播放器
 * <p>
 * 基于 LWJGL OpenAL 播放音频，兼容 FCL/Android 和 PC 环境。
 * 从 .minecraft/arenamod/music/ 读取音频文件。
 * 支持格式: .ogg (首选), .wav, 以及 PC 环境下通过 javax.sound 解码的 .mp3/.flac/.aac
 * </p>
 */
public class ArenaMusicManager {
    private static final List<File> musicFiles = new CopyOnWriteArrayList<>();
    private static volatile boolean playing = false;
    private static volatile boolean wasInArena = false;
    private static Thread playThread = null;
    private static volatile boolean stopRequested = false;

    // 音频系统可用性
    private static volatile boolean audioSystemAvailable = true;
    private static volatile boolean audioSystemChecked = false;

    // javax.sound 解码可用性（PC 上有，FCL/Android 无）
    private static volatile boolean javaSoundAvailable = false;
    private static volatile boolean javaSoundChecked = false;

    // 播放模式
    public enum PlayMode { LOOP, ORDER, RANDOM }
    private static volatile PlayMode playMode = PlayMode.RANDOM;

    // 当前曲目索引
    private static volatile int currentIndex = 0;

    // 已播放历史（用于 previous）
    private static final Deque<Integer> history = new ArrayDeque<>();

    // 同步锁
    private static final Object lock = new Object();

    /** 初始化：扫描音乐文件夹 */
    public static void init() {
        musicFiles.clear();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        // 检查 OpenAL 是否可用
        checkAudioSystem();

        if (!ArenaConfig.getInstance().isLoaded()) {
            ArenaConfig.getInstance().load(client.runDirectory.toPath());
        }
        Path musicDir = client.runDirectory.toPath().resolve(ArenaConfig.getInstance().getMusicDirectory());
        try {
            Files.createDirectories(musicDir);
        } catch (IOException e) {
            ArenaMod.LOGGER.warn("无法创建音乐目录: {}", musicDir);
            return;
        }

        try (var stream = Files.list(musicDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".mp3")
                        || name.endsWith(".flac") || name.endsWith(".aac");
            }).sorted().map(Path::toFile).forEach(musicFiles::add);

            if (!musicFiles.isEmpty()) {
                ArenaMod.LOGGER.info("Found {} music files", musicFiles.size());
                shufflePlaylist();
            } else {
                ArenaMod.LOGGER.info("No audio files found in: {}", musicDir);
            }
        } catch (IOException e) {
            ArenaMod.LOGGER.warn("扫描音乐目录失败", e);
        }
    }

    /** 检查 LWJGL OpenAL 是否可用 */
    private static void checkAudioSystem() {
        if (audioSystemChecked) return;
        audioSystemChecked = true;
        try {
            Class.forName("org.lwjgl.openal.AL10");
            audioSystemAvailable = true;
            ArenaMod.LOGGER.info("音频系统检查通过 (LWJGL OpenAL 可用)");
        } catch (ClassNotFoundException e) {
            audioSystemAvailable = false;
            ArenaMod.LOGGER.error("音频系统不可用：当前环境缺少 LWJGL OpenAL");
        }
        checkJavaSound();
    }

    /** 检查 javax.sound 解码是否可用（PC 环境有，FCL/Android 无） */
    private static void checkJavaSound() {
        if (javaSoundChecked) return;
        javaSoundChecked = true;
        try {
            Class.forName("javax.sound.sampled.AudioSystem");
            javaSoundAvailable = true;
            ArenaMod.LOGGER.info("javax.sound 可用，支持 .mp3/.flac/.aac 格式解码");
        } catch (ClassNotFoundException e) {
            javaSoundAvailable = false;
            ArenaMod.LOGGER.info("javax.sound 不可用，仅支持 .ogg/.wav 格式");
        }
    }

    /** 每帧调用，检测维度变化控制音乐启停 */
    public static void tick(boolean playerInArena) {
        if (playerInArena && !wasInArena) {
            if (!musicFiles.isEmpty()) {
                if (!audioSystemAvailable) {
                    ArenaMod.LOGGER.warn("音频系统不可用，无法播放音乐");
                    sendChat(Text.translatable("music.arenamod.audio_unavailable").formatted(Formatting.RED));
                    return;
                }
                startPlaying();
            }
        } else if (!playerInArena && wasInArena) {
            stopPlaying();
        }
        wasInArena = playerInArena;
    }

    /** 开始播放音乐 */
    private static synchronized void startPlaying() {
        if (playing) return;
        if (musicFiles.isEmpty()) return;

        playing = true;
        stopRequested = false;

        playThread = new Thread(() -> {
            try {
                playCurrentTrackLoop();
            } catch (Exception e) {
                ArenaMod.LOGGER.error("音乐播放异常", e);
            }
            playing = false;
        }, "ArenaMusic");
        playThread.setDaemon(true);
        playThread.start();
    }

    /** 停止播放音乐 */
    public static synchronized void stopPlaying() {
        stopRequested = true;
        playing = false;
        if (playThread != null) {
            playThread.interrupt();
            playThread = null;
        }
    }

    // ===== 播放控制 =====

    /** 根据当前模式循环播放 */
    private static void playCurrentTrackLoop() {
        while (!stopRequested && playing && !musicFiles.isEmpty()) {
            File file = musicFiles.get(currentIndex % musicFiles.size());
            try {
                ArenaMod.LOGGER.info("开始播放: {}", file.getName());
                playFile(file);
            } catch (Exception e) {
                ArenaMod.LOGGER.warn("播放失败: {} - {}", file.getName(), e.getMessage());
                sendChat(Text.translatable("music.arenamod.play_failed", file.getName() + " - " + e.getMessage()).formatted(Formatting.YELLOW));
            }

            if (stopRequested || !playing) return;

            // 根据模式选择下一首
            synchronized (lock) {
                history.push(currentIndex);
                switch (playMode) {
                    case LOOP:
                        break;
                    case ORDER:
                        currentIndex = (currentIndex + 1) % musicFiles.size();
                        break;
                    case RANDOM:
                        currentIndex = getRandomIndex();
                        break;
                }
            }
        }
    }

    /** 下一曲 */
    public static void nextTrack() {
        synchronized (lock) {
            if (musicFiles.isEmpty()) return;
            history.push(currentIndex);
            switch (playMode) {
                case LOOP:
                case ORDER:
                    currentIndex = (currentIndex + 1) % musicFiles.size();
                    break;
                case RANDOM:
                    currentIndex = getRandomIndex();
                    break;
            }
        }
        restartPlayback();
    }

    /** 上一曲 */
    public static void previousTrack() {
        synchronized (lock) {
            if (!history.isEmpty()) {
                currentIndex = history.pop();
            } else if (!musicFiles.isEmpty()) {
                currentIndex = (currentIndex - 1 + musicFiles.size()) % musicFiles.size();
            }
        }
        restartPlayback();
    }

    /** 设置播放模式 */
    public static void setPlayMode(PlayMode mode) {
        synchronized (lock) {
            playMode = mode;
            if (mode == PlayMode.RANDOM) {
                shufflePlaylist();
            }
        }
    }

    /** 获取当前播放模式 */
    public static PlayMode getPlayMode() {
        return playMode;
    }

    /** 获取当前曲目名 */
    public static String getCurrentTrackName() {
        if (musicFiles.isEmpty()) return "无音乐";
        return musicFiles.get(currentIndex % musicFiles.size()).getName();
    }

    /** 重新启动播放（切歌时） */
    private static void restartPlayback() {
        if (playing) {
            stopRequested = true;
            if (playThread != null) {
                playThread.interrupt();
                try { playThread.join(1000); } catch (InterruptedException ignored) {}
                playThread = null;
            }
            stopRequested = false;
            playing = true;
            playThread = new Thread(() -> {
                try {
                    playCurrentTrackLoop();
                } catch (Exception e) {
                    ArenaMod.LOGGER.error("音乐播放异常", e);
                }
                playing = false;
            }, "ArenaMusic");
            playThread.setDaemon(true);
            playThread.start();
        }
    }

    private static int getRandomIndex() {
        if (musicFiles.size() <= 1) return 0;
        int newIdx;
        do {
            newIdx = new Random().nextInt(musicFiles.size());
        } while (newIdx == currentIndex);
        return newIdx;
    }

    private static void shufflePlaylist() {
        if (musicFiles.size() > 1) {
            File current = musicFiles.get(currentIndex % musicFiles.size());
            Collections.shuffle(musicFiles);
            currentIndex = musicFiles.indexOf(current);
            if (currentIndex < 0) currentIndex = 0;
        }
    }

    // ===== 音频播放（基于 LWJGL OpenAL） =====

    /**
     * 播放单个音频文件
     * .ogg: 使用 VorbisDecoder + OpenAL 流式播放
     * .wav: 直接解析 WAV 头 + OpenAL 一次性播放
     * .mp3/.flac/.aac: PC 上通过 javax.sound 解码为 PCM 后经 OpenAL 播放
     */
    private static void playFile(File file) throws Exception {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".ogg")) {
            playOgg(file);
        } else if (name.endsWith(".wav")) {
            playWav(file);
        } else if (javaSoundAvailable) {
            playViaJavaSound(file);
        } else {
            ArenaMod.LOGGER.warn("当前环境不支持 {} 格式，请使用 .ogg 格式", name.substring(name.lastIndexOf('.')));
            sendChat(Text.translatable("music.arenamod.play_failed", "Format not supported, use .ogg").formatted(Formatting.YELLOW));
        }
    }

    /** 使用 VorbisDecoder + OpenAL 播放 OGG */
    private static void playOgg(File file) throws Exception {
        VorbisDecoder decoder = new VorbisDecoder();
        if (!decoder.open(file)) {
            ArenaMod.LOGGER.warn("无法打开 OGG 文件: {}", file.getName());
            return;
        }

        try (OpenAlAudioPlayer player = new OpenAlAudioPlayer()) {
            if (!player.initialize(decoder.getSampleRate(), decoder.getChannels())) {
                sendChat(Text.translatable("music.arenamod.play_failed_driver").formatted(Formatting.RED));
                return;
            }

            player.setDecoder(decoder);

            AtomicBoolean stopRef = new AtomicBoolean(false);
            Thread stopMonitor = new Thread(() -> {
                while (!stopRequested && !Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
                stopRef.set(true);
            }, "StopMonitor");
            stopMonitor.setDaemon(true);
            stopMonitor.start();

            byte[] transferBuf = new byte[65536];

            // 填充初始缓冲并开始播放
            int filled = player.fillInitial(transferBuf, stopRef);
            if (filled > 0) {
                player.play();
                player.streamContinue(transferBuf, stopRef);
            }

            stopRequested = true;
        } finally {
            decoder.close();
        }
    }

    /** 直接解析 WAV 文件头，使用 OpenAL 一次性播放 */
    private static void playWav(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             java.nio.channels.FileChannel ch = raf.getChannel()) {

            // 使用较大缓冲区以应对含多个 chunk 的复杂 WAV 文件
            ByteBuffer hdr = ByteBuffer.allocate(4096);
            ch.read(hdr);
            hdr.flip();

            // 验证 RIFF
            if (hdr.getInt() != 0x46464952) throw new IOException("Invalid WAV: missing RIFF");
            hdr.getInt(); // file size
            if (hdr.getInt() != 0x45564157) throw new IOException("Invalid WAV: missing WAVE");

            int channels = 0, sampleRate = 0, bitsPerSample = 0, dataSize = 0;
            long dataOffset = 0;
            boolean foundFmt = false, foundData = false;

            while (hdr.position() < hdr.capacity() - 8) {
                int chunkId = hdr.getInt();
                int chunkSize = hdr.getInt();
                if (chunkSize < 0 || chunkSize > 100000000) break;

                if (chunkId == 0x20746D66 && !foundFmt) { // "fmt "
                    foundFmt = true;
                    int audioFormat = hdr.getShort() & 0xFFFF;
                    channels = hdr.getShort() & 0xFFFF;
                    sampleRate = hdr.getInt();
                    hdr.getInt(); // byteRate
                    hdr.getShort(); // blockAlign
                    bitsPerSample = hdr.getShort() & 0xFFFF;
                    if (audioFormat != 1) throw new IOException("Unsupported WAV format: " + audioFormat);
                    int rem = chunkSize - 16;
                    if (rem > 0) hdr.position(Math.min(hdr.position() + rem, hdr.capacity()));
                } else if (chunkId == 0x61746164) { // "data"
                    foundData = true;
                    dataSize = chunkSize;
                    dataOffset = hdr.position();
                    break;
                } else {
                    hdr.position(Math.min(hdr.position() + chunkSize, hdr.capacity()));
                }
            }

            if (!foundFmt || !foundData || dataOffset == 0)
                throw new IOException("Invalid WAV: missing fmt/data chunk");
            if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || dataSize <= 0)
                throw new IOException("Invalid WAV parameters");

            // 一次性读取 PCM 数据
            byte[] allPcm = new byte[dataSize];
            ch.position(dataOffset);
            ch.read(ByteBuffer.wrap(allPcm));

            int openALFormat;
            if (channels == 1 && bitsPerSample == 8) openALFormat = AL10.AL_FORMAT_MONO8;
            else if (channels == 1 && bitsPerSample == 16) openALFormat = AL10.AL_FORMAT_MONO16;
            else if (channels == 2 && bitsPerSample == 8) openALFormat = AL10.AL_FORMAT_STEREO8;
            else if (channels == 2 && bitsPerSample == 16) openALFormat = AL10.AL_FORMAT_STEREO16;
            else throw new IOException("Unsupported WAV: " + channels + "ch/" + bitsPerSample + "bit");

            try (OpenAlAudioPlayer player = new OpenAlAudioPlayer()) {
                if (!player.initialize(sampleRate, channels)) {
                    sendChat(Text.translatable("music.arenamod.play_failed_driver").formatted(Formatting.RED));
                    return;
                }

                AtomicBoolean stopRef = new AtomicBoolean(false);
                Thread stopMonitor = new Thread(() -> {
                    while (!stopRequested && !Thread.currentThread().isInterrupted()) {
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                    stopRef.set(true);
                }, "StopMonitor");
                stopMonitor.setDaemon(true);
                stopMonitor.start();

                ByteBuffer directBuf = ByteBuffer.allocateDirect(allPcm.length);
                directBuf.put(allPcm);
                directBuf.flip();

                player.playOnce(openALFormat, directBuf, stopRef);
                stopRequested = true;
            }
        }
    }

    // ===== 查询接口 =====

    public static int getMusicCount() { return musicFiles.size(); }
    public static boolean isPlaying() { return playing; }
    public static boolean isAudioSystemAvailable() { return audioSystemAvailable; }

    /** 在游戏内聊天栏发送消息（从任意线程安全调用） */
    private static void sendChat(Text msg) {
        MinecraftClient cl = MinecraftClient.getInstance();
        if (cl != null && cl.player != null) {
            cl.execute(() -> cl.player.sendMessage(msg, false));
        }
    }

    // ===== javax.sound 解码（PC 环境） =====

    /**
     * 使用 javax.sound 解码音频文件为 PCM，通过 OpenAL 播放。
     * 此方法仅在 PC 环境（javax.sound 可用）下被调用。
     * 使用 Class.forName 保护，在 FCL/Android 上不会触发类加载。
     */
    private static void playViaJavaSound(File file) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(file);
        try {
            AudioFormat baseFormat = ais.getFormat();
            int sampleRate = (int) baseFormat.getSampleRate();
            int channels = baseFormat.getChannels();
            int bitsPerSample = baseFormat.getSampleSizeInBits();

            // 统一转为 16-bit PCM
            if (baseFormat.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || bitsPerSample != 16) {
                AudioFormat targetFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        sampleRate, 16, channels,
                        channels * 2, sampleRate, false);
                ais = AudioSystem.getAudioInputStream(targetFormat, ais);
                sampleRate = (int) targetFormat.getSampleRate();
                channels = targetFormat.getChannels();
                bitsPerSample = 16;
            }

            byte[] allPcm = ais.readAllBytes();

            int openALFormat;
            if (channels == 1) openALFormat = AL10.AL_FORMAT_MONO16;
            else openALFormat = AL10.AL_FORMAT_STEREO16;

            try (OpenAlAudioPlayer player = new OpenAlAudioPlayer()) {
                if (!player.initialize(sampleRate, channels)) {
                    sendChat(Text.translatable("music.arenamod.play_failed_driver").formatted(Formatting.RED));
                    return;
                }

                AtomicBoolean stopRef = new AtomicBoolean(false);
                Thread stopMonitor = new Thread(() -> {
                    while (!stopRequested && !Thread.currentThread().isInterrupted()) {
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                    stopRef.set(true);
                }, "StopMonitor");
                stopMonitor.setDaemon(true);
                stopMonitor.start();

                ByteBuffer directBuf = ByteBuffer.allocateDirect(allPcm.length);
                directBuf.put(allPcm);
                directBuf.flip();

                player.playOnce(openALFormat, directBuf, stopRef);
                stopRequested = true;
            }
        } finally {
            ais.close();
        }
    }
}
