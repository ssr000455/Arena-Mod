package com.qidai.arenamod.client.audio;

/**
 * Modified Discrete Cosine Transform (Vorbis IMDCT)
 * 标准 Vorbis 逆 MDCT 实现，支持长块(1024)和短块(128)
 */
public class Mdct {
    private final int n;
    private final int log2n;
    private final float[] trig;
    private final float[] window;

    public Mdct(int n) {
        this.n = n;
        this.log2n = Integer.numberOfTrailingZeros(n);
        trig = new float[n + n / 4];
        window = new float[n / 2];

        // 预计算三角函数表
        for (int i = 0; i < n + n / 4; i++) {
            trig[i] = (float) Math.sin(Math.PI / (2.0 * n) * (i + 0.5));
        }

        // Vorbis 窗口函数: sin(π/2 * sin²(π * (i + 0.5) / n))
        for (int i = 0; i < n / 2; i++) {
            float s = (float) Math.sin(Math.PI * (i + 0.5) / n);
            window[i] = (float) Math.sin(Math.PI / 2.0 * s * s);
        }
    }

    /** 执行逆 MDCT */
    public void imdct(float[] in, float[] out, int outOff, int step) {
        int halfN = n / 2;
        float[] tmp = new float[halfN];

        // 反折操作
        for (int i = 0; i < halfN / 2; i++) {
            tmp[i] = -in[halfN / 2 - 1 - i];
            tmp[halfN / 2 + i] = -in[halfN / 2 + i];
        }

        // 加窗
        for (int i = 0; i < halfN; i++) {
            tmp[i] *= trig[i + halfN / 4];
        }

        int N = halfN;
        float[] temp = new float[N];
        int N2 = N / 2;
        int N4 = N / 4;

        // 预旋转
        for (int k = 0; k < N4; k++) {
            int idx = k;
            int idx2 = N2 - k - 1;
            float angle = (float) (Math.PI / (2.0 * N) * (2 * k + 1));
            float c = (float) Math.cos(angle);
            float s = (float) Math.sin(angle);
            float a = tmp[idx];
            float b = tmp[idx2];
            temp[2 * k] = a * c + b * s;
            temp[2 * k + 1] = a * s - b * c;
        }

        // 核心 FFT
        fft(temp, false);

        // 后旋转
        for (int i = 0; i < N2; i++) {
            float angle = (float) (Math.PI / (2.0 * N) * (2 * i + 1));
            float c = (float) Math.cos(angle);
            float s = (float) Math.sin(angle);
            float re = temp[2 * i];
            float im = temp[2 * i + 1];
            float a = re * c + im * s;
            float b = im * c - re * s;
            tmp[2 * i] = a / N2;
            tmp[2 * i + 1] = b / N2;
        }

        // 将 DCT-IV 结果 (N = n/2) 展开为 IMDCT 输出 (2N = n)
        // 左半段：DCT-IV 结果反转输出
        int idx = 0;
        for (int i = 0; i < N; i++, idx++) {
            out[outOff + idx * step] = tmp[N - 1 - i] * trig[n / 4 + i] * window[i];
        }
        // 右半段：DCT-IV 结果取反后输出
        for (int i = 0; i < N; i++, idx++) {
            out[outOff + idx * step] = -tmp[i] * trig[n / 4 + N + i] * window[N - 1 - i];
        }
    }

    /** 简单的 Cooley-Tukey FFT */
    private void fft(float[] data, boolean inverse) {
        int n = data.length / 2;
        int j = 0;
        for (int i = 0; i < n - 1; i++) {
            if (i < j) {
                float tr = data[2 * i];
                float ti = data[2 * i + 1];
                data[2 * i] = data[2 * j];
                data[2 * i + 1] = data[2 * j + 1];
                data[2 * j] = tr;
                data[2 * j + 1] = ti;
            }
            int k = n / 2;
            while (k >= 1 && j >= k) {
                j -= k;
                k /= 2;
            }
            j += k;
        }

        for (int len = 2; len <= n; len *= 2) {
            int halfLen = len / 2;
            float ang = (float) (2 * Math.PI / len);
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            if (inverse) wIm = -wIm;

            for (int i = 0; i < n; i += len) {
                float curRe = 1.0f;
                float curIm = 0.0f;
                for (int k = 0; k < halfLen; k++) {
                    int idx1 = (i + k) * 2;
                    int idx2 = (i + k + halfLen) * 2;
                    float re1 = data[idx1];
                    float im1 = data[idx1 + 1];
                    float re2 = data[idx2] * curRe - data[idx2 + 1] * curIm;
                    float im2 = data[idx2] * curIm + data[idx2 + 1] * curRe;
                    data[idx1] = re1 + re2;
                    data[idx1 + 1] = im1 + im2;
                    data[idx2] = re1 - re2;
                    data[idx2 + 1] = im1 - im2;
                    float tmpRe = curRe * wRe - curIm * wIm;
                    float tmpIm = curRe * wIm + curIm * wRe;
                    curRe = tmpRe;
                    curIm = tmpIm;
                }
            }
        }
    }

    /** 获取窗口大小 */
    public int getN() { return n; }

    /** 构建 Vorbis 窗口（用于 overlap-add） */
    public static void buildWindow(float[] window, int n) {
        int halfN = n / 2;
        for (int i = 0; i < halfN; i++) {
            float s = (float) Math.sin(Math.PI * (i + 0.5) / n);
            window[i] = (float) Math.sin(Math.PI / 2.0 * s * s);
        }
        for (int i = halfN; i < n; i++) {
            float s = (float) Math.sin(Math.PI * (i + 0.5) / n);
            window[i] = (float) Math.sin(Math.PI / 2.0 * s * s);
        }
    }
}
