package com.soulvoyage.domain.achievement;

import java.util.List;
import java.util.Map;

/**
 * G3 成就目录：全部指向"自我关照行为"，无排行榜/无 PVP/不指向分数竞争（V2 设计原则）。
 * 文案随 notify 模板一并供心理顾问审阅。
 */
public final class AchievementCatalog {

    public record Def(String code, String name, String desc) {}

    public static final List<Def> ALL = List.of(
            new Def("FIRST_DIARY", "第一篇日记", "你为自己翻开了内心的第一页。"),
            new Def("COMPANION_OPENED", "第一次树洞漫聊", "你愿意把话说出来，被听见就是照顾自己的开始。"),
            new Def("STREAK_7", "连续记录 7 天", "这一周，你每天都来看了看自己。"),
            new Def("STREAK_30", "连续记录 30 天", "30 天，自我关照长成了习惯。"),
            new Def("ALL_EXERCISES", "完成全部 5 种练习", "呼吸、着陆、书写、激活、命名——你都亲自试过了。"),
            new Def("FIRST_A_GRADE", "首次训练 A 档", "有一次，你把心里话说到了点子上。"),
            new Def("NO_REPEAT_DISTORTION", "不再出现的思维陷阱", "某个熟悉的思维定式，这一周没有再拦住你。")
    );

    public static Map<String, Def> byCode() {
        return ALL.stream().collect(java.util.stream.Collectors.toMap(Def::code, d -> d));
    }

    private AchievementCatalog() {}
}
