package com.qidai.arenamod.client.audio;

import com.jcraft.jogg.Buffer;

/**
 * Vorbis Codebook — Huffman 码本 + VQ 向量量化解码
 */
public class CodeBook {
    int dim;
    int entries;
    int[] lengths;
    int lookupType;
    float[] lookupValues;
    int lookupDim;
    int minVal;
    int deltaVal;
    boolean seqP;
    int[][] ht; // Huffman 树: [node][0=bit0, 1=bit1]; >=0 = 子节点, <0 = 叶子节点 (-entry-1)

    public CodeBook() {}

    /** 从 Vorbis 比特流读取码本 */
    public void read(Buffer buf) {
        int sync = buf.read(24);
        if (sync != 0x564342) {
            throw new RuntimeException("Invalid codebook sync: 0x" + Integer.toHexString(sync));
        }

        dim = buf.read(16);
        entries = buf.read(24);
        lengths = new int[entries];

        // ----- 码长表 -----
        boolean ordered = buf.read(1) == 1;
        if (ordered) {
            int len = buf.read(5) + 1;
            int cur = 0;
            while (cur < entries) {
                int num = buf.read(bitsForValue(entries - cur));
                for (int i = 0; i < num && cur < entries; i++) {
                    lengths[cur++] = len;
                }
                len++;
            }
        } else {
            boolean sparse = buf.read(1) == 1;
            for (int i = 0; i < entries; i++) {
                if (sparse) {
                    if (buf.read(1) == 1) {
                        lengths[i] = buf.read(5) + 1;
                    }
                } else {
                    lengths[i] = buf.read(5) + 1;
                }
            }
        }

        // ----- 构建霍夫曼树 -----
        int[] codewords = new int[entries];
        buildCodewords(codewords);
        buildTree(codewords);

        // ----- 查找表 -----
        lookupType = buf.read(4);
        if (lookupType > 0) {
            if (lookupType == 1) {
                lookupDim = dim;
            } else {
                lookupDim = buf.read(24);
            }

            minVal = buf.read(32);
            deltaVal = buf.read(32);
            seqP = buf.read(1) == 1;

            int codeBits = buf.read(4) + 1;
            int lookupValuesCount;
            if (lookupType == 1) {
                int temp = 1;
                for (int i = 0; i < lookupDim; i++) {
                    temp *= (entries + 1); // 上限
                }
                lookupValuesCount = 0;
                int acc = 1;
                for (int i = 0; i < lookupDim; i++) {
                    acc *= (int) Math.pow(entries, 1.0 / lookupDim);
                }
                lookupValuesCount = acc * lookupDim;
                if (lookupValuesCount < entries * dim) {
                    lookupValuesCount = entries * dim;
                }
            } else {
                lookupValuesCount = entries * lookupDim;
            }

            lookupValues = new float[lookupValuesCount];
            for (int i = 0; i < lookupValuesCount; i++) {
                lookupValues[i] = buf.read(codeBits);
            }
        }
    }

    /** 解码一个霍夫曼符号，返回 entry 索引 */
    public int decodeScalar(Buffer buf) {
        int node = 0;
        while (node >= 0) {
            int bit = buf.read(1);
            int next = ht[node][bit];
            if (next < 0) {
                return -(next + 1); // 叶子节点：恢复 entry 索引
            }
            if (next == -1) {
                return -1; // 无效码字
            }
            node = next;
        }
        return -1;
    }

    /** 解码一个完整向量（一个码字对应所有 dim 个值） */
    public void decodeVectorFull(Buffer buf, float[] out, int offset) {
        int entry = decodeScalar(buf);
        if (entry < 0) {
            for (int i = 0; i < dim && offset + i < out.length; i++) {
                out[offset + i] = 0;
            }
            return;
        }
        decodeVectorEntry(entry, out, offset);
    }

    /** 将一个码字条目解码为浮点向量 */
    public void decodeVectorEntry(int entry, float[] out, int offset) {
        if (lookupType == 0) return;

        if (lookupType == 1) {
            // 隐式 VQ：使用 N-dimensional 码本
            int last = 0;
            int base = entry * lookupDim;
            for (int i = 0; i < dim && i < lookupDim; i++) {
                float val = lookupValues[base + i];
                float decoded = minVal / 65536.0f + val * (deltaVal / 65536.0f);
                if (seqP) {
                    decoded += last;
                    last = (int) (decoded * 65536);
                }
                if (offset + i < out.length) {
                    out[offset + i] = decoded;
                }
            }
        } else if (lookupType == 2) {
            // 显式 VQ
            int base = entry * dim;
            for (int i = 0; i < dim; i++) {
                if (offset + i < out.length) {
                    out[offset + i] = minVal / 65536.0f
                            + lookupValues[base + i] * (deltaVal / 65536.0f);
                }
            }
        }
    }

    // ==================== 霍夫曼树构建 ====================

    /** 标准霍夫曼码字分配 */
    private void buildCodewords(int[] codewords) {
        int maxLen = 0;
        for (int len : lengths) {
            if (len > maxLen) maxLen = len;
        }
        if (maxLen == 0) return;

        int[] lenCounts = new int[maxLen + 1];
        for (int len : lengths) {
            if (len > 0) lenCounts[len]++;
        }

        int[] firstCode = new int[maxLen + 1];
        int code = 0;
        for (int len = 1; len <= maxLen; len++) {
            code = (code + lenCounts[len - 1]) << 1;
            firstCode[len] = code;
        }

        // 按码长排序分发票号
        int[] sorted = new int[entries];
        int idx = 0;
        for (int len = 1; len <= maxLen; len++) {
            for (int i = 0; i < entries; i++) {
                if (lengths[i] == len) {
                    sorted[idx++] = i;
                }
            }
        }

        for (int entry : sorted) {
            int len = lengths[entry];
            if (len > 0) {
                codewords[entry] = firstCode[len]++;
            } else {
                codewords[entry] = -1;
            }
        }
    }

    /** 构建霍夫曼解码树 */
    private void buildTree(int[] codewords) {
        int maxNodes = entries * 2 + 16;
        ht = new int[maxNodes][2];
        for (int i = 0; i < maxNodes; i++) {
            ht[i][0] = ht[i][1] = -1;
        }

        int nextNode = 1; // 节点 0 = 根

        for (int i = 0; i < entries; i++) {
            int len = lengths[i];
            if (len == 0) continue;

            int node = 0;
            int code = codewords[i];

            for (int bitPos = len - 1; bitPos >= 0; bitPos--) {
                int b = (code >> bitPos) & 1;

                if (bitPos == 0) {
                    // 叶子：存储 -(entry + 1)
                    ht[node][b] = -(i + 1);
                } else {
                    if (ht[node][b] == -1) {
                        // 分配新节点
                        if (nextNode >= maxNodes) break;
                        ht[node][b] = nextNode++;
                    }
                    node = ht[node][b];
                }
            }
        }
    }

    private int bitsForValue(int v) {
        int bits = 0;
        v--;
        while (v > 0) {
            v >>= 1;
            bits++;
        }
        return bits;
    }
}
