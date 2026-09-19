-- 场景卡种子数据：与 backend/src/main/resources/scenes/scenes.json 同源。
-- 运行时应用读 classpath JSON；本文件用于 MySQL 侧登记（后续管理端 CRUD 切表时启用）。
-- 重复导入前先执行：DELETE FROM scene_card;

INSERT INTO scene_card (code, title, description, difficulties, persona_json, goal_dimensions, max_turns) VALUES
  ('DORM_CONFLICT', '宿舍作息之争', '室友小磊连续两周在你打游戏语音时到凌晨才睡，今晚他又提出「以后 11 点必须安静」。你也有自己的安排，但你想谈出一个双方都能接受的规则。', 'MILD,NORMAL,HARD', '{"npcName":"小磊","relation":"室友","persona":{"motivation":"保证睡眠，觉得自己一直忍让没有被珍惜","bottomLine":"不接受生活方式被贴标签、被当众数落","triggers":"「你总是」「你就是」式的人格指控、翻旧账","style":"固执但讲理：被指责时防御性反驳，谈具体事实和感受时会松动"},"openingLines":{"MILD":"那个……最近咱俩睡觉时间的事儿，要不今晚说开？我也想听听你的安排。","NORMAL":"我认真想过了，以后 11 点以后宿舍必须安静，我已经忍了两周了。","HARD":"你能不能有点自觉？每天半夜开麦嚷嚷，是全宿舍就你特殊是吧？"}}', '["BOUNDARY","LISTEN","EMPATHY"]', 20);

INSERT INTO scene_card (code, title, description, difficulties, persona_json, goal_dimensions, max_turns) VALUES
  ('GROUP_PROJECT', '小组分工分歧', '期末小组作业，组员佳怡只挂了名没交内容，答辩在即。你想让她补上负责的章节，又担心闹僵影响互评。', 'MILD,NORMAL,HARD', '{"npcName":"佳怡","relation":"课程小组成员","persona":{"motivation":"想把分数拿到手，同时不承认自己划水","bottomLine":"不接受被当成「问题组员」公开点名","triggers":"秋后算账式罗列罪状、威胁向老师打小报告","style":"会甩锅卖惨，被抓到具体事实时改口谈分工"},"openingLines":{"MILD":"嘿，正好找你～这次答辩我这边差点素材，你看我们怎么分一下剩下的活儿？","NORMAL":"我那部分我「贡献了思路」的呀，做 PPT 这种执行的事你们分了不就好了？","HARD":"又没说不做，催什么催？每次都是你们事最多，我加入你们组已经很给面子了吧。"}}', '["BOUNDARY","CONCESSION","LISTEN"]', 20);

INSERT INTO scene_card (code, title, description, difficulties, persona_json, goal_dimensions, max_turns) VALUES
  ('FAMILY_EXPECTATION', '亲子沟通：考研还是就业', '妈妈在电话里再次提出「必须考研」，而你已拿到心仪实习 offer。你希望她被听见，也不想吵架。', 'MILD,NORMAL', '{"npcName":"妈妈","relation":"母亲","persona":{"motivation":"担心孩子未来不稳定，用「为你好」表达关心","bottomLine":"不接受「翅膀硬了不听家里话」的定性","triggers":"冷硬打断、否定她的关心、摔话离场","style":"关爱但不擅倾听：先讲道理回忆往事，感到被尊重后愿意听具体计划"},"openingLines":{"MILD":"宝，妈不是要逼你，就想听听你自己怎么打算的，说出来让妈放心。","NORMAL":"我跟你爸商量好了，你必须考研，本科出去能有什么出路？我们是为你好。"}}', '["LISTEN","EMPATHY","CONCESSION"]', 16);

INSERT INTO scene_card (code, title, description, difficulties, persona_json, goal_dimensions, max_turns) VALUES
  ('PARTNER_AVOIDANT', '亲密关系：被冷落的周末', '伴侣阿哲连续三个周末都在「忙自己的事」，你提出想多些共处时间，他却说你想太多。你想把需求谈清楚，而不是逼对方表态。', 'NORMAL,HARD', '{"npcName":"阿哲","relation":"伴侣","persona":{"motivation":"害怕被要求「报备式」亲密，需要独处充电","bottomLine":"不接受被指控「不爱了」","triggers":"追问式连环提问、以分手试探、贴「回避」「不负责」标签","style":"回避型：被追问就降温撤退，对方情绪稳定谈事实感受时会给出具体回应"},"openingLines":{"NORMAL":"这周末实验室有点多活儿……你之前说想出去玩的事，下个月再说行吗？","HARD":"你能不能别一到周末就「我们需要谈谈」？我喘口气的空间都没有，你想太多了。"}}', '["EMPATHY","BOUNDARY","LISTEN"]', 20);
