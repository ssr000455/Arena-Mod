package com.qidai.arenamod.wave;

import java.util.ArrayList;
import java.util.List;

/**
 * 波次定义
 * 四个波次，第一波分为五批
 */
public class WaveDefinition {

    /** 一个刷怪组：一组怪物代号 × 每个的生成数量 */
    public record SpawnGroup(List<Character> codes, int countPerCode) {}

    /** 一批：包含多个刷怪组 */
    public record Batch(List<SpawnGroup> groups) {}

    /** 一个波次：包含多批 */
    public record Wave(int waveNumber, List<Batch> batches) {}

    private static List<Wave> WAVES;

    public static List<Wave> getWaves() {
        if (WAVES == null) {
            WAVES = buildWaves();
        }
        return WAVES;
    }

    private static List<Wave> buildWaves() {
        List<Wave> waves = new ArrayList<>();

        // ===== 第一波：5批 =====
        waves.add(new Wave(1, List.of(
            // ①: ab ×30  cd ×10  h ×20
            new Batch(List.of(
                group("AB", 30),
                group("CD", 10),
                group("H", 20)
            )),
            // ②: abnl ×20  ◍☆ ×10  ◇ ×1
            new Batch(List.of(
                group("ABNL", 20),
                group("◍☆", 10),
                group("◇", 1)
            )),
            // ③: tlwuik ×10  y ×1  ◇ ×3
            new Batch(List.of(
                group("TLWUIK", 10),
                group("Y", 1),
                group("◇", 3)
            )),
            // ④: oe ×1  qp ×5  ijfbc ×10
            new Batch(List.of(
                group("OE", 1),
                group("QP", 5),
                group("IJFBC", 10)
            )),
            // ⑤: ǔk ×30  ◇lm ×20
            new Batch(List.of(
                group("ǔK", 30),
                group("◇LM", 20)
            ))
        )));

        // ===== 第二波（预留）=====
        waves.add(new Wave(2, List.of(
            new Batch(List.of(group("AB", 40))),
            new Batch(List.of(group("CD", 20), group("H", 30)))
        )));

        // ===== 第三波（预留）=====
        waves.add(new Wave(3, List.of(
            new Batch(List.of(group("ABNL", 30)))
        )));

        // ===== 第四波（预留）=====
        waves.add(new Wave(4, List.of(
            new Batch(List.of(group("Y", 1), group("◇", 5)))
        )));

        return waves;
    }

    /** 将字符串批量解析为 SpawnGroup，每个字符作为独立代号 */
    private static SpawnGroup group(String codes, int count) {
        List<Character> chars = new ArrayList<>();
        for (int i = 0; i < codes.length(); i++) {
            char c = codes.charAt(i);
            if (MonsterCodes.isValid(c)) {
                chars.add(c);
            }
        }
        return new SpawnGroup(chars, count);
    }
}
