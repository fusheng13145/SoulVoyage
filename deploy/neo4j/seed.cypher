// SoulVoyage · Neo4j 知识图谱种子（与 backend/src/main/resources/kg/*.json 同源，由脚本生成后人工核对）
// 用法（Neo4j 就绪后）: cypher-shell -u neo4j -p <pw> -f deploy/neo4j/seed.cypher
// 引入动机见手册 §5.3：真实模型上线后由 Neo4jKgService 实现 KgSearchService，替换内存版。

CREATE CONSTRAINT distortion_id IF NOT EXISTS FOR (d:CognitiveDistortion) REQUIRE d.kgNodeId IS UNIQUE;
CREATE CONSTRAINT stressor_name IF NOT EXISTS FOR (s:Stressor) REQUIRE s.name IS UNIQUE;
CREATE CONSTRAINT emotion_name IF NOT EXISTS FOR (e:Emotion) REQUIRE e.name IS UNIQUE;
CREATE CONSTRAINT eventtag_name IF NOT EXISTS FOR (t:EventTag) REQUIRE t.name IS UNIQUE;
CREATE CONSTRAINT psytopic_id IF NOT EXISTS FOR (p:PsyTopic) REQUIRE p.kgNodeId IS UNIQUE;
CREATE CONSTRAINT commcase_id IF NOT EXISTS FOR (c:CommCase) REQUIRE c.kgNodeId IS UNIQUE;
CREATE CONSTRAINT strength_id IF NOT EXISTS FOR (t:StrengthTechnique) REQUIRE t.kgNodeId IS UNIQUE;

// —— 情绪节点（与 16 情绪闭集一致，先行创建供 TRIGGERS 关联）——
UNWIND ['喜悦','愤怒','悲伤','恐惧','焦虑','惊讶','厌恶','羞耻','内疚','委屈','孤独','麻木','平静','压力','期待','其他'] AS name
MERGE (:Emotion {name: name});

// —— 认知误区卡（30）——
UNWIND [
  {kgNodeId: 'cd_all_or_nothing', name: '非黑即白', definition: '以两个极端类别概括事物，忽视中间地带', typicalSignature: '要么全好要么全坏 / 一次失败=彻底失败 / 我就是个废物', socraticTemplates: ['如果一位好朋友经历了完全相同的事，你也会用「彻底失败」来评价他吗？','这件事有没有可能既不是满分也不是零分？中间那部分占多少？'], emotions: ['羞耻','悲伤','麻木','焦虑']},
  {kgNodeId: 'cd_catastrophizing', name: '灾难化思维', definition: '把可能的负面结果放大为不可避免的灾难', typicalSignature: '完了 / 撑不住了 / 一定会出大事', socraticTemplates: ['最坏、最好、最可能发生的结果分别是什么？各自的概率有多大？','就算最坏情况真的发生，一个月后的你可以做些什么让自己缓过来？'], emotions: ['焦虑','恐惧','压力']},
  {kgNodeId: 'cd_mind_reading', name: '读心术', definition: '没有证据便断定别人对自己有负面看法', typicalSignature: '他肯定觉得我傻 / 大家都不喜欢我', socraticTemplates: ['除了你猜测的解释，对方的行为还有哪两种可能的原因？','如果要把「他觉得我傻」写成法庭证据，你能拿出什么实际材料？'], emotions: ['愤怒','羞耻','孤独','焦虑']},
  {kgNodeId: 'cd_personalization', name: '过度归己', definition: '把不完全由自己造成的结果全部归责于自己', typicalSignature: '都是我的错 / 要不是我就好了', socraticTemplates: ['这件事的结果里，有哪些因素是你无法控制的？如果责任是一百份，你实际拿几份？','如果你的同学遇到同样的结果，你会把全部责任算在他头上吗？'], emotions: ['内疚','悲伤','羞耻']},
  {kgNodeId: 'cd_should_statements', name: '「应该」句式', definition: '用僵化的规则要求自己和他人，违反即愤怒或自责', typicalSignature: '他应该知道 / 我必须做到完美', socraticTemplates: ['把「他应该」换成「我希望」，你的感受会有什么不同？','这条「应该」是谁定的？如果换一个人不遵守，他真的该被责怪吗？'], emotions: ['愤怒','内疚','压力']},
  {kgNodeId: 'cd_labeling', name: '贴标签', definition: '用一个固化标签定义整个人，而非评价具体行为', typicalSignature: '我就是个懒人 / 他是个小人', socraticTemplates: ['「这次没做好一件事」和「我这个人不行」，哪个更符合事实？','你身上有没有和这个标签相反的时刻？那个「标签外的你」算什么？'], emotions: ['羞耻','愤怒','麻木']},
  {kgNodeId: 'cd_emotional_reasoning', name: '情绪推理', definition: '把感觉当作事实证据：我这么觉得，所以一定如此', typicalSignature: '我感觉很糟，所以一切都完了', socraticTemplates: ['如果睡个好觉之后再看这件事，你的判断会改变吗？','感觉在告诉你什么？事实又支持什么？两者一定相同吗？'], emotions: ['悲伤','麻木','孤独']},
  {kgNodeId: 'cd_discounting_positive', name: '否定正面', definition: '把好的反馈与成果排除在外，只保留负面证据', typicalSignature: '这次只是运气 / 不算什么', socraticTemplates: ['如果这些成绩写在别人的简历上，你会承认它们的价值吗？','什么样的成果才算「真的」？这个标准是不是刚好把每次成功都排除在外？'], emotions: ['悲伤','羞耻','平静']},
  {kgNodeId: 'cd_overgeneralization', name: '过度概括', definition: '由单一事件得出普遍性、永久性结论', typicalSignature: '总是 / 每次 / 永远都', socraticTemplates: ['「总是」是真的没有一次例外，还是今天特别难受？能想到一个反例吗？','把「每次都」换成「这一次」，句子里剩下的事实是什么？'], emotions: ['悲伤','愤怒','孤独','焦虑']},
  {kgNodeId: 'cd_heavenly_reward', name: '应该回报思维', definition: '认为自己的付出必然应得到对等回报，落空即强烈不公感', typicalSignature: '我付出这么多，凭什么', socraticTemplates: ['对方知道你的付出并把它计入账本了吗？你们对「公平」的定义一致吗？','如果付出没有换来想要的回报，有没有一种方式能先让这份付出对你自己值回票价？'], emotions: ['愤怒','委屈','内疚']},
  {kgNodeId: 'cd_change_fallacy', name: '改变谬误', definition: '相信只要自己足够努力，就能改变别人的行为与选择', typicalSignature: '只要我再劝几次他就改了', socraticTemplates: ['如果对方一年后仍然不变，你能接受并保护自己的方案是什么？','回顾过去：你的劝说真正改变过对方几次？改变的部分是出于他的意愿吗？'], emotions: ['愤怒','压力','悲伤']},
  {kgNodeId: 'cd_always_be_right', name: '坚持到底谬误', definition: '为避免认错，把争论的输赢置于关系与事实之上', typicalSignature: '这次绝不能先低头', socraticTemplates: ['在这件事上，你更想赢这场争论，还是更想宿舍关系变好一些？','如果承认自己有一部分错了，实际会失去什么？那个代价你承担得起吗？'], emotions: ['愤怒','孤独']},
  {kgNodeId: 'cd_filtering', name: '心理过滤', definition: '只提取细节中的负面片段当作整体画面，筛掉其余全部信息', typicalSignature: '整场汇报就记住那一句口误', socraticTemplates: ['如果给你的这次经历写一份完整纪要，除了那一个坏点，还会记下哪些内容？','你的注意力像一台滤镜，只放行负面片段——如果把滤镜关掉十分钟，会看到什么？'], emotions: ['羞耻','悲伤','焦虑']},
  {kgNodeId: 'cd_fortune_telling', name: '预言未来', definition: '在没有依据的情况下断定事情一定会变糟，并据此放弃尝试', typicalSignature: '投了也没用 / 肯定选不上', socraticTemplates: ['你这个预测的依据是什么？过去你的「肯定不行」兑现过几次？','如果有个 60% 的机会，只是不确定，你会愿意试一次吗？现在的判断里有这个余地吗？'], emotions: ['焦虑','麻木','悲伤']},
  {kgNodeId: 'cd_minimizing', name: '缩小化', definition: '把自己的优点、能力和成就看得微不足道，反复轻描淡写', typicalSignature: '这没什么 / 谁都能做到', socraticTemplates: ['你说「谁都能做到」——那你认识的人里有多少真的做到了？','如果这件事放在朋友身上，你会给它打几分？为什么轮到自己就自动缩水？'], emotions: ['悲伤','羞耻','平静']},
  {kgNodeId: 'cd_maximizing', name: '夸大化', definition: '把小问题、小失误的分量放大到与实际严重不符', typicalSignature: '一个平时分丢了就全完了', socraticTemplates: ['这件事满分 10 分，客观来看它值几分？你现在给它是几分？','一年后回看今天这个问题，它还会占这么大比重吗？'], emotions: ['焦虑','恐惧','压力']},
  {kgNodeId: 'cd_blaming', name: '过度责备', definition: '把问题的原因全部指向他人或环境，免除自己的部分也否定自己的掌控力', typicalSignature: '都怪老师出题太偏', socraticTemplates: ['责怪之外，这件事里有没有一小部分是你下次可以不同做的？','如果责任全在外面，那是不是也意味着你什么都改变不了？你真的是这么看的吗？'], emotions: ['愤怒','委屈','压力']},
  {kgNodeId: 'cd_control_fallacy', name: '控制谬误', definition: '要么觉得一切自己无能为力，要么觉得别人的事全该由自己负责', typicalSignature: '我说了也没用 / 室友的状态全靠我维系', socraticTemplates: ['这件事里哪些部分在你手里，哪些不在？你正把力气花在哪一边？','如果室友的人生课题不由你负责，你会轻松一点还是更内疚？'], emotions: ['压力','孤独','内疚']},
  {kgNodeId: 'cd_fairness_fallacy', name: '公平谬误', definition: '因为觉得世界不公平，就拒绝做任何妥协与付出', typicalSignature: '反正不公平，努力也没意义', socraticTemplates: ['「不公平」是真的，但它推导出「你什么都不必做」吗？这两步之间少了什么？','在一个不完全公平的环境里，有没有人依然为自己争取到了还过得去的结果？他们做对了哪一步？'], emotions: ['愤怒','麻木','悲伤']},
  {kgNodeId: 'cd_approval_fallacy', name: '求认可思维', definition: '把自我价值建立在他人认可上，未被认可即觉得自己失败', typicalSignature: '老师没夸我就是不行', socraticTemplates: ['如果没有人给你点赞，这件事本身的完成度会改变一分吗？','你上一次真正满意自己的时刻，当时有别人在场认可吗？'], emotions: ['羞耻','焦虑','孤独']},
  {kgNodeId: 'cd_avoidance_belief', name: '逃避信念', definition: '相信回避能消除恐惧，短期缓解换来长期恐惧放大', typicalSignature: '不去面试就不会被拒', socraticTemplates: ['回避之后那几分钟的轻松，和一周后压在心底的这件事，哪个更重？','如果最小的第一步只是「打开招聘页面看 5 分钟」，它和「去面试」之间差了什么？'], emotions: ['恐惧','焦虑','麻木']},
  {kgNodeId: 'cd_comparison_trap', name: '比较陷阱', definition: '拿自己的幕后对比他人的台前，越比越低落并以此证明自己不行', typicalSignature: '同学都拿offer了只有我', socraticTemplates: ['你比较的是他的朋友圈和你的生活——素材公平吗？','如果比下去的人今天也在和别人比呢？这条比较的链条终点在哪里？'], emotions: ['羞耻','悲伤','焦虑','内疚']},
  {kgNodeId: 'cd_immutable_self', name: '自我固化', definition: '认为性格与能力天生且永远不变，因此努力无意义', typicalSignature: '我天生就是这样改不了', socraticTemplates: ['回想三年前：你有没有哪一点其实和现在不一样了？那它是怎么变的？','「天生」和「练熟了但忘了过程」，这两种解释你怎么区分？'], emotions: ['麻木','悲伤','羞耻']},
  {kgNodeId: 'cd_urgency_illusion', name: '紧急错觉', definition: '把非紧急的事感知为火烧眉毛，在人为紧迫感中失去判断力', typicalSignature: '今晚必须搞定，不然来不及了', socraticTemplates: ['这件事推迟两天，真实世界里会失去什么？失去的部分有多大？','你现在的「必须」是外界要求的，还是焦虑替你拟定的？'], emotions: ['焦虑','压力','恐惧']},
  {kgNodeId: 'cd_self_fulfillment', name: '自我实现预言', definition: '负面预期改变行为，行为使预期成真，再反过来「证明」预期', typicalSignature: '反正聊不来，干脆少说话', socraticTemplates: ['如果你先相信「对方会讨厌你」，你的行为会跟着变成什么样？对方又是回应哪个版本的？','有没有哪一次，你预期很糟结果却还行？那次你的行为有什么不同？'], emotions: ['孤独','悲伤','焦虑']},
  {kgNodeId: 'cd_rumination', name: '反刍思维', definition: '反复咀嚼已发生的事与负面情绪，误以为空想等于解决问题', typicalSignature: '翻来覆去想那天的场面', socraticTemplates: ['过去一小时你的重复思考，产出了哪一条可以行动的下一步？','「想清楚」和「想很久」是一回事吗？怎么判断现在属于哪一种？'], emotions: ['悲伤','内疚','麻木']},
  {kgNodeId: 'cd_worth_by_achievement', name: '成就定价值', definition: '只用成绩、offer、点赞等产出衡量自己值不值得被爱', typicalSignature: '没有成果我就是空壳', socraticTemplates: ['如果明天你生一场病什么都不产出，你作为一个人就归零了吗？','你在意的人爱你，是因为你「考得不错」还是因为你就是你？哪条证据更硬？'], emotions: ['羞耻','焦虑','内疚','悲伤']},
  {kgNodeId: 'cd_intolerance', name: '无法忍受', definition: '把「很不舒服」升级为「绝对受不了」，高估困难低估自己的承受力', typicalSignature: '我受不了了 / 撑不下去', socraticTemplates: ['「受不了」和「很难受但正在承受」哪个更符合你现在的事实？','过去有没有一件当时觉得绝对撑不过去的事？后来是怎么过去的？'], emotions: ['恐惧','压力','焦虑']},
  {kgNodeId: 'cd_hindsight', name: '后见之明', definition: '用事后才知道的信息苛责当时的自己，仿佛一切本可预知', typicalSignature: '我早就该看出来', socraticTemplates: ['你正在用只有结局之后才有的信息审判当时的自己——那时候你真的知道吗？','如果你的朋友做了同样的选择，你会说他「早该想到」吗？'], emotions: ['内疚','羞耻','悲伤']},
  {kgNodeId: 'cd_sunk_cost', name: '沉没成本思维', definition: '因为已经投入太多而拒绝止损，把过去的投入当作未来的理由', typicalSignature: '都坚持这么久了不能放弃', socraticTemplates: ['如果今天第一次让你选，以现在的状况你还选它吗？「已经三年」算新投入还是旧账？','继续下去和放手，各自一年后最坏的结果是什么？哪个你更受得了？'], emotions: ['压力','悲伤','内疚']}
] AS row
MERGE (d:CognitiveDistortion {kgNodeId: row.kgNodeId})
SET d.name = row.name, d.definition = row.definition, d.typicalSignature = row.typicalSignature, d.socraticTemplates = row.socraticTemplates
WITH d, row
UNWIND row.emotions AS en
MATCH (e:Emotion {name: en})
MERGE (e)-[:TRIGGERS]->(d);

// —— 科普主题（20，供给 SUPPORT 与每日一读）——
UNWIND [
  {kgNodeId: 'psy_self_regulation_body', title: '先安顿身体，再讲道理', summary: '情绪上头时，大脑负责理智的部分会暂时「掉线」，此时硬逼自己想通往往越想越乱。身体是情绪的把手：心率降下来，思维才有空间。先喝口温水、放松肩膀、放慢呼吸，等身体退出警报状态，再回头看那件让你烦的事，常常会发现它没有刚才那么大。', microAction: '现在做 3 次「吸气 4 秒、呼气 8 秒」，然后说出此刻你能听到的两种声音。', aboutTags: ['学业压力','人际冲突','焦虑','愤怒','恐惧'], readingSec: 45},
  {kgNodeId: 'psy_amygdala_hijack', title: '情绪「劫持」是怎么回事', summary: '脑内的杏仁核像烟雾报警器，遇到威胁会抢先接管身体，让理智脑慢半拍——心理学家称之为「杏仁核劫持」。这就是为什么气头上说出的话事后常后悔：不是人品问题，是大脑的时序问题。认识它之后，你可以给自己一个许可：警报响的时候，先不做重要决定。', microAction: '下次感到血往上涌时，心里默念「这是报警器在响」，推迟 10 分钟再回复那条消息。', aboutTags: ['人际冲突','亲密关系','愤怒','压力'], readingSec: 40},
  {kgNodeId: 'psy_6second_wave', title: '情绪浪头只有几秒', summary: '一次情绪的化学波从涌起到退去，在血液里大约只持续六秒左右。之所以感觉「一直很气」，往往是我们不断用想法给浪头续杯。允许自己感受一个完整的浪：不行动、不反刍，只是呼吸着等它过去。浪退了再做决定，你会感谢这个六秒的自己。', microAction: '下次情绪涌上来时，安静地数 6 个慢呼吸，不分析、不回复、不做决定。', aboutTags: ['亲密关系','学业压力','焦虑','悲伤'], readingSec: 35},
  {kgNodeId: 'psy_naming_feelings', title: '说出名字，情绪减半', summary: '研究显示，把情绪用词精确地说出来（「这是委屈，混着一点羞耻」），能显著降低情绪脑的激活强度——这叫「情绪标注」。模糊的「烦死了」像一团雾，精确的命名像把雾拆成小水滴。词汇越细，你对自己的掌控感越强。', microAction: '用「我感到____，因为____」造句，给此刻的情绪找到一个准确的名字。', aboutTags: ['生活节奏','其他','焦虑','麻木','委屈'], readingSec: 40},
  {kgNodeId: 'psy_sleep_emotion', title: '睡不够时，情绪脑会过热', summary: '睡眠不足会让杏仁核的反应强度上升一大截——同样的小事，缺觉的你会有更激烈的情绪，这不是你变脆弱了，是大脑的缓冲垫被拿走了。深睡眠相当于情绪的夜间清理。与其在凌晨评判人生，先把觉睡回来，很多「想不通」会自己松动。', microAction: '今晚把手机放到离床两米外，比平时早 15 分钟躺下。', aboutTags: ['身体健康','生活节奏','麻木','焦虑'], readingSec: 40},
  {kgNodeId: 'psy_self_compassion', title: '自我关怀的三件套', summary: '心理学家 Kristin Neff 提出自我关怀三要素：善待自己（像对朋友那样对自己）、共通人性（这份难处很多人都经历过，不是你独有的失败）、正念觉察（承认痛，但不被故事卷走）。自我关怀不是躺平借口，研究反而发现它让人更有力量面对问题。', microAction: '把手放在胸口，对现在的自己说一句你会对好朋友说的话。', aboutTags: ['自我期待','学业压力','羞耻','悲伤'], readingSec: 45},
  {kgNodeId: 'psy_cbt_triangle', title: '想法不是事实：认知三角', summary: 'CBT 的核心模型：情境、想法、情绪、行为互相影响。刺痛你的往往不是事件本身，而是事件经过「想法」这道滤镜后的版本。同一句「老师没回消息」，接上「他对我失望」是焦虑，接上「他在开会」是平静。改变不了情境时，检验想法是高杠杆的一步。', microAction: '写下最近一件烦心事，把它和你对它的「解读」分成两栏看。', aboutTags: ['学业压力','人际冲突','焦虑','内疚'], readingSec: 45},
  {kgNodeId: 'psy_worry_vs_problem', title: '焦虑分两种：有用的和空转的', summary: '心理学把担忧分成「可实现的问题」和「假设性威胁」。前者可以拆出下一步行动；后者关于不可控的未来，越想越空转。检验方法：这个担忧能翻译成「我现在能做的一个动作」吗？能，就记下来去做；不能，就练习把它放回云层里。', microAction: '列出此刻让你担心的三件事，标记哪些可控、哪些不可控，只处理可控的那列。', aboutTags: ['就业压力','学业压力','焦虑','压力'], readingSec: 45},
  {kgNodeId: 'psy_breath_brake', title: '慢呼吸是身体的刹车片', summary: '呼气比吸气长时，副交感神经被激活，心率下降、肌肉松绑——这是少数能手动接管自主神经系统的开关。它不能消灭压力源，但能把你从「战或逃」拉回「还能思考」。所有呼吸法的核心只有一条：慢下来，呼气长于吸。', microAction: '做 4 轮 4-7-8 呼吸：吸 4 秒、屏 7 秒、呼 8 秒。', aboutTags: ['身体健康','人际冲突','恐惧','压力'], readingSec: 35},
  {kgNodeId: 'psy_spotlight_effect', title: '聚光灯效应：没那么多人看你', summary: '我们总以为自己的失误被全场直播，实验却反复证明：别人注意到的程度远低于想象。演讲时卡壳的那 5 秒，同学多半没听见，听见的人也早忘了。把聚光灯调暗一点，你会自由一点——大家忙着找自己头上的聚光灯呢。', microAction: '回忆上周别人的一次「出糗」，如果你能轻易想起，说明你也在高估别人的出糗被记住的程度。', aboutTags: ['自我期待','人际冲突','羞耻','焦虑'], readingSec: 40},
  {kgNodeId: 'psy_emotion_info', title: '情绪是信息，不是命令', summary: '愤怒提醒你边界被踩了，内疚提示你在乎的关系出了偏差，焦虑押注着你在意的未来。情绪是仪表盘上的灯，亮灯不等于「必须立刻按它说的做」。读懂信息，然后由你决定行动——而不是把方向盘整个交给情绪。', microAction: '问此刻的情绪：「你想提醒我什么？」再问自己：「收到提醒后，我想怎么做？」', aboutTags: ['生活节奏','其他','愤怒','内疚','焦虑'], readingSec: 40},
  {kgNodeId: 'psy_action_first', title: '先动起来，动力才会来', summary: '我们习惯「等有状态了再做」，但行为激活研究给出的顺序恰好相反：行动在前，情绪和动力跟在后。低落时等 motivation 上门，往往越等越瘫。把任务缩到小得荒谬的第一步——打开文档、穿上跑鞋——启动本身就是情绪的解药。', microAction: '选一件拖着的事，只做 2 分钟，2 分钟后允许自己停下。', aboutTags: ['生活节奏','学业压力','麻木','悲伤'], readingSec: 40},
  {kgNodeId: 'psy_perfectionism_cost', title: '完美主义的隐形账单', summary: '高标准本身不伤人，伤人的是「不完美=没有价值」的等式。完美主义者用焦虑当燃料，代价是拖延、失眠和永远不够。更可持续的替代是「优秀主义」：认真定标准，允许执行有波纹。交出去的 80 分，胜过留在脑子里的 100 分。', microAction: '找一件小事刻意做到「够用就好」，观察世界有没有因此崩塌。', aboutTags: ['自我期待','学业压力','焦虑','羞耻'], readingSec: 45},
  {kgNodeId: 'psy_rumination_exit', title: '反刍的出口：从「为什么」到「怎么做」', summary: '反复咀嚼「为什么我这么差」是反刍，它让大脑误以为在解决问题，实际在加深情绪沟槽。研究者发现一个简单开关：把「为什么」换成「怎么做」。「为什么我总是搞砸」→「下次开场前我能做的一个准备是什么」。前者向下钻，后者向外走。', microAction: '抓住你最近循环播放的一句话，把句首的「为什么」改成「下一步怎么」。', aboutTags: ['人际冲突','自我期待','悲伤','内疚'], readingSec: 45},
  {kgNodeId: 'psy_no_say_skill', title: '拒绝是可以练习的', summary: '很多人把「拒绝」等同于「伤害关系」，于是硬着头皮接下所有请求，攒成一肚子委屈。成熟的拒绝其实有公式：肯定关系 + 明确边界 + 可选替代。「我很想帮你，但这次时间真的排不开，要不下次？」——立场是硬的，态度是软的，关系反而更稳。', microAction: '准备一句你自己的拒绝模板，写下来，这周找机会用一次。', aboutTags: ['人际冲突','家庭关系','愤怒','委屈'], readingSec: 45},
  {kgNodeId: 'psy_loneliness_signal', title: '孤独是信号，不是缺陷', summary: '孤独感像饥饿感，是进化装在你身上的社交饥饿信号，提示「连接不足」，而不是宣判「你不讨喜」。把它读成信号，行动就有了方向：一条主动的消息、一次食堂拼桌、一个固定的球局。破解孤独的最小单位，是发起而不是等待。', microAction: '今天给一个很久没联系的人发一句不带目的的话：「刚想到你，最近怎么样？」', aboutTags: ['亲密关系','生活节奏','孤独','悲伤'], readingSec: 45},
  {kgNodeId: 'psy_nvc_four_steps', title: '非暴力沟通四步', summary: '观察（只说事实不评判）→ 感受 → 需要 → 请求。四步的力量在于把「你怎么总迟到」翻译成「这周三次里约我会面你都晚到 20 分钟（观察），我有点不被重视的感觉（感受），因为我需要这段友谊（需要），以后能提前告诉我吗（请求）」：评判变成信息，指责变成邀请。', microAction: '挑一个你反复不满的场景，用四步各写一句话。', aboutTags: ['亲密关系','家庭关系','人际冲突','愤怒','委屈'], readingSec: 50},
  {kgNodeId: 'psy_real_rest', title: '刷手机不是休息', summary: '心理恢复研究提出休息的质量看两个维度：心理脱离（真的不想它）和掌控感（是我选择做的）。课间刷短视频常常两者皆无：脑子没离开烦心事，手又不完全是自愿的。真正的充电更可能来自：十分钟闭眼、楼下一圈、和人说几句闲话。', microAction: '下次休息时段，试一次「不带屏幕的十分钟」，记录之后的状态分数。', aboutTags: ['生活节奏','学业压力','麻木','压力'], readingSec: 40},
  {kgNodeId: 'psy_help_seeking', title: '求助是资源决策，不是示弱', summary: '对求助的污名感会让人在崩溃边缘硬撑。换一个框架：找朋友聊聊、找心理咨询中心，和身体不舒服去校医院是同一种能力——识别问题、调度资源。评估过、尝试过、依然扛着，这时候伸手，恰恰说明你已经独自走了很远。', microAction: '在心里列一张「可以说话的人」清单，哪怕只有两个人的名字。', aboutTags: ['其他','学业压力','悲伤','孤独'], readingSec: 40},
  {kgNodeId: 'psy_exam_anxiety_curve', title: '适度紧张是盟友', summary: '表现焦虑与成绩的关系是一条倒 U 形曲线：完全不紧张则涣散，适度紧张表现最佳，过度焦虑才滑坡。所以目标不是「一点都不慌」，而是把焦虑压回可用区间。给紧张改个名字也有帮助：「这是我认真准备、在乎这件事的证据。」', microAction: '下次考前心跳加速时，默念「我的身体在给我加载能量」，然后做三个长呼气。', aboutTags: ['学业压力','就业压力','焦虑','恐惧'], readingSec: 45}
] AS row
MERGE (p:PsyTopic {kgNodeId: row.kgNodeId})
SET p.title = row.title, p.summary = row.summary, p.microAction = row.microAction, p.aboutTags = row.aboutTags, p.readingSec = row.readingSec;

// —— 心理力量技巧（12）——
UNWIND [
  {kgNodeId: 'st_ground_54321', name: '5-4-3-2-1 着陆', description: '用五感把注意力从情绪风暴拉回当下：5 个看到的、4 个听到的、3 个触到的、2 个闻到的、1 个尝到的。', whenToUse: '急性焦虑、愤怒上头、解离发懵时', emotions: ['焦虑','恐惧','愤怒','麻木'], steps: ['停下手中动作，双脚踩实地面','按 5-4-3-2-1 顺序说出感受到的具体事物','做完一轮后评估情绪强度，必要时再来一轮']},
  {kgNodeId: 'st_long_exhale', name: '长呼气降速', description: '呼气时长超过吸气（如 4-7-8 或 4-6），激活副交感神经，为身体手动降档。', whenToUse: '心跳快、手抖、发言或面试前', emotions: ['恐惧','焦虑','压力'], steps: ['鼻吸 4 秒','屏息（可省略）最多 7 秒','缓慢呼气 6–8 秒，重复 4 轮']},
  {kgNodeId: 'st_name_emotion', name: '情绪命名', description: '用一个精确的情绪词给感受贴标签（委屈、失落、被冒犯），标注本身即可降低情绪强度。', whenToUse: '一团模糊的烦躁堵在胸口时', emotions: ['麻木','焦虑','悲伤'], steps: ['闭眼问：此刻身体哪里最有感觉','从情绪词表里挑最接近的两个词','补一句「因为我在乎____」']},
  {kgNodeId: 'st_i_message', name: '我信息表达', description: '用「当发生（事实）时，我感到（情绪），因为我需要（原因）」代替「你总是（评判）」，把指责翻译成邀请。', whenToUse: '冲突沟通、提意见、表达不满前', emotions: ['愤怒','委屈'], steps: ['只写可摄像的客观事实，去掉「总是/故意」','说出自己的感受和需求','提出一个具体、可执行的请求']},
  {kgNodeId: 'st_pause_timeout', name: '约定暂停', description: '争吵升级前主动叫停：「我现在情绪上头，30 分钟后我一定回来谈。」暂停不是逃跑，是保护对话。', whenToUse: '争吵中想说狠话、想摔门时', emotions: ['愤怒','委屈'], steps: ['说出暂停理由与回归时间','离开现场做身体降速（走路/冷水洗脸）','到点主动回来，带着平静重新开口']},
  {kgNodeId: 'st_reality_check', name: '想法检验', description: '把自动想法当假设而非事实：找支持证据、反对证据、另一种解释，再给想法重新打分。', whenToUse: '被「他肯定讨厌我」「我完了」困住时', emotions: ['焦虑','羞耻','内疚'], steps: ['写下这个想法和相信度（0-100）','列出支持与反对的客观证据','写出一个朋友会同意的替代解释，重新打分']},
  {kgNodeId: 'st_broken_record', name: '温和复读', description: '面对施压或绕圈子的对方，不升级不转弯，用同一句温和而坚定的话反复表达边界。', whenToUse: '被反复劝说做不想做的事、被道德绑架时', emotions: ['压力','愤怒','委屈'], steps: ['先准备一句「我理解…但我这次…」','对方再施压，认可对方后重复同一句','不解释新理由，不给可攻击的话柄']},
  {kgNodeId: 'st_partial_agree', name: '部分同意卸力', description: '从对方的批评里找真实的那一小块先承认，冲突失去燃料，剩下的才有得谈。', whenToUse: '被指责、被反馈、意见冲突时', emotions: ['愤怒','羞耻'], steps: ['在指责里找出你认同的 10%','先承认这部分并说明','再平静陈述你不认同的部分与理由']},
  {kgNodeId: 'st_worst_case_plan', name: '最坏情况预案', description: '把模糊的恐惧写具体：最坏是什么、概率多大、发生了怎么办。恐惧变成预案后，焦虑失去增殖空间。', whenToUse: '考前、面试前、等待结果时反复吓自己', emotions: ['焦虑','恐惧'], steps: ['写下最坏结果的一句话版本','估计真实发生概率','写三条发生后的应对，行动清单代替反刍']},
  {kgNodeId: 'st_self_compassion_break', name: '自我关怀暂停', description: '三步练习：承认痛苦（这是难受的一刻）、共通人性（很多人也这样过）、善待自己（此刻我需要什么）。', whenToUse: '失败、自我攻击、和别人比较后低落时', emotions: ['羞耻','悲伤','内疚'], steps: ['手放胸口，说出「现在很难」','提醒自己这份难处是人之常情','像安慰朋友一样对自己说一句话']},
  {kgNodeId: 'st_behavioral_activation', name: '行为激活', description: '低落到什么都不想做时，用「先做小行动、再等感觉跟上」打破瘫-疚循环，行动是情绪的引信。', whenToUse: '提不起劲、整天瘫着自我厌恶时', emotions: ['悲伤','麻木'], steps: ['列三件 5 分钟内可完成的微行动','挑最容易的一件立刻做','做完记录情绪前后分数']},
  {kgNodeId: 'st_worry_split', name: '担忧拆控', description: '把盘旋的担忧分成「可控/不可控」两栏：可控的转成一步行动，不可控的练习放回云层，杜绝空转。', whenToUse: '对未来反复焦虑、睡前脑内开辩论会时', emotions: ['焦虑','压力'], steps: ['写下所有在转的担忧','标注可控/不可控','为可控的第一条写今天能做的一步']}
] AS row
MERGE (t:StrengthTechnique {kgNodeId: row.kgNodeId})
SET t.name = row.name, t.description = row.description, t.whenToUse = row.whenToUse, t.steps = row.steps
WITH t, row
UNWIND row.emotions AS en
MATCH (e:Emotion {name: en})
MERGE (e)-[:EASED_BY]->(t);

// —— 沟通案例（15，脱敏示例，供 REVIEW/SUPPORT 引用）——
UNWIND [
  {kgNodeId: 'cc_dorm_sleep', title: '宿舍作息冲突：怎么提才不会吵崩', scene: '宿舍', situation: '室友每晚打游戏语音到凌晨一点，你连着三天没睡好，终于忍无可忍。', unhelpful: '「你每次都这么自私，能不能有点素质？」——贴标签+翻旧账，对方立刻进入防御，吵成谁声音大。', helpful: '「这周三天凌晨的语音把我吵醒了（事实），我白天上课头疼得不行（感受+影响）。十一点后能不能戴上耳机、语音降到安静档？如果不方便，我们商量个都行的方案（具体请求+留余地）。」', distortionRefs: ['cd_should_statements','cd_labeling'], techniqueRefs: ['st_i_message','st_ground_54321'], aboutTags: ['人际冲突','愤怒','委屈']},
  {kgNodeId: 'cc_group_freerider', title: '小组里的划水同学：分工催办不伤和气', scene: '小组项目', situation: '小组作业还剩一人份 PPT 没人认领，负责的同学每次问「你那块做了吗」都得到「快了」。', unhelpful: '在群里阴阳：「某些人是不是打算让我们兜底啊」——升级为站队，中间人为难，任务更推不动。', helpful: '私聊 + 拆细 + 定时点：「PPT 第 6–8 页这周四前能出初稿吗？需要我把素材模板发你？如果周四有困难，现在说还来得及调整分工。」把模糊的「尽快」变成可确认的承诺。', distortionRefs: ['cd_mind_reading','cd_overgeneralization'], techniqueRefs: ['st_i_message','st_partial_agree'], aboutTags: ['人际冲突','学业压力','愤怒']},
  {kgNodeId: 'cc_parents_grade', title: '父母追问成绩：报忧之前先对齐', scene: '家', situation: '期中挂了一科，妈妈每晚视频都问学习，你开始不想接电话。', unhelpful: '撒谎说「都挺好的」然后回避视频——谎言维持成本越来越高，关系越来越淡。', helpful: '主动定框架：「妈，先说好消息：两科进步了。有一科这次没考好，我已经在问老师怎么补（坏消息+已有行动）。这周末视频我详细说，你不用替我着急。」给信息、给方案、给时间点，把质问变成汇报。', distortionRefs: ['cd_catastrophizing','cd_all_or_nothing'], techniqueRefs: ['st_i_message','st_worst_case_plan'], aboutTags: ['家庭关系','焦虑','内疚']},
  {kgNodeId: 'cc_partner_space', title: '亲密关系里要空间：不推开对方的说法', scene: '恋爱', situation: '伴侣希望每天视频，你需要独处充电，拒绝后被说「你是不是不在乎我」。', unhelpful: '「你能不能别这么黏人」——把需求说成人格缺陷，对方受伤后查岗更严。', helpful: '「我想要独处的时间是给自己充电，充好电的我陪你质量更高（意图澄清）。固定周二周四我主动找你视频，其他晚上我们各自安排再睡前聊十分钟（可预期的替代方案）。」安全感来自可预期，不来自时长。', distortionRefs: ['cd_mind_reading','cd_change_fallacy'], techniqueRefs: ['st_i_message','st_partial_agree'], aboutTags: ['亲密关系','压力','孤独']},
  {kgNodeId: 'cc_friend_heartbreak', title: '朋友失恋：陪伴不是当军师', scene: '友谊', situation: '好友分手后每天找你倾诉三小时，你已经听累了，还被告知「不准劝分」。', unhelpful: '理性轰炸：「早说他不靠谱，你当初就不该在一起」——否定情绪+马后炮，对方反而更黏那段关系。', helpful: '先共情后立界：「这段时间你真的很疼，我在（确认情绪）。昨晚我状态也不太好，今天只能聊半小时，但半小时里我认真听（诚实+可兑现的边界）。」陪伴的质量靠在场，不靠时长。', distortionRefs: ['cd_hindsight','cd_personalization'], techniqueRefs: ['st_name_emotion','st_i_message'], aboutTags: ['亲密关系','悲伤','麻木']},
  {kgNodeId: 'cc_classmate_debt', title: '同学借钱：温和而坚定地说不', scene: '经济', situation: '熟识的同学第三次开口借钱，前两次都拖了一个月才还，这次数目更大，你的生活费也紧张。', unhelpful: '含糊其辞：「额……我看看啊……」——不拒绝也不答应，对方当你是「有机会」，反复试探。', helpful: '肯定关系+明确边界+替代支持：「你开口说明你信得过我，这份信任我很珍惜。但钱这块我这次真的不出借，我自己的生活费也要精打细算（复读式坚定）。如果你需要，我可以帮你看看学校的临时困难补助怎么办申请。」', distortionRefs: ['cd_approval_fallacy','cd_always_be_right'], techniqueRefs: ['st_broken_record','st_partial_agree'], aboutTags: ['经济压力','人际冲突','压力']},
  {kgNodeId: 'cc_professor_extension', title: '向老师申请缓交：把请求写成方案', scene: '师生', situation: '作业截止日撞上你发烧请假的那两天，想申请缓交，又怕老师觉得你找借口。', unhelpful: '只说「老师我病了能不能晚点交」——信息太少像套近乎，被拒后更委屈。', helpful: '事实+凭证+具体方案+让步空间：「老师，我周三周四因发烧就诊（附校医院假条），落下部分进度。想申请缓交 48 小时，周五晚十点前一定提交；如果影响批改安排，我可以先交已完成章节。」带方案的请求，老师只需点头或微调。', distortionRefs: ['cd_catastrophizing','cd_emotional_reasoning'], techniqueRefs: ['st_worst_case_plan','st_reality_check'], aboutTags: ['学业压力','焦虑']},
  {kgNodeId: 'cc_roommate_chore', title: '值日卫生：让规则背锅，不让人背锅', scene: '宿舍', situation: '室友长期不倒垃圾，你提醒过两次都「忘了」，你开始怀疑对方就是不尊重你。', unhelpful: '攒到爆发：「这个家是我一个人在维持吗！」——积怨一次性倾倒，对方只记住你的态度，忘了事情。', helpful: '把人对人的要求变成人对规则：贴一张轮值表在门后，「轮到谁谁负责，忘的人补下次」。「不是我要管你，是表格排到你了」——规则做坏人，关系留好人。', distortionRefs: ['cd_mind_reading','cd_heavenly_reward'], techniqueRefs: ['st_partial_agree','st_i_message'], aboutTags: ['人际冲突','愤怒','委屈']},
  {kgNodeId: 'cc_apology_repair', title: '说重了话之后：一次有效的道歉', scene: '关系修复', situation: '你嫌室友吵，脱口而出「就你事多」，看到他愣住的那一刻你后悔了。', unhelpful: '「行行行，我错了行了吧」+「但我也是被你逼的」——敷衍+甩锅二合一，道歉变成二次伤害。', helpful: '四要素：①具体认错「我说『就你事多』这句话很伤人，不对在我不该用偏概全攻击人」；②承认影响「你当时愣住了，我能感觉到被戳到了」；③不带「但是」；④补救「昨晚十一点的语音我们定个折中时间？」只认领自己的部分，不重提对方的错。', distortionRefs: ['cd_labeling','cd_always_be_right'], techniqueRefs: ['st_i_message','st_self_compassion_break'], aboutTags: ['人际冲突','内疚','羞耻']},
  {kgNodeId: 'cc_refuse_drinking', title: '推掉不想去的聚会：不撒慌的拒绝', scene: '社交', situation: '社团聚餐又定在周三，你已经连续婉拒两次，这次组织者当众问「你必须来吧？」', unhelpful: '编造借口「要开会」——被拆穿一次，以后所有真实理由都变成借口。', helpful: '真实+肯定+留门：「谢你还想着我，这周三是真的不行，我有固定要处理的事。你们玩，多拍点照片群里发我。」不贬低聚会价值，不为自己的安排过度道歉。', distortionRefs: ['cd_approval_fallacy','cd_should_statements'], techniqueRefs: ['st_broken_record','st_name_emotion'], aboutTags: ['生活节奏','人际冲突','压力']},
  {kgNodeId: 'cc_feedback_defense', title: '收到刺耳反馈：先接住再分拣', scene: '社团', situation: '社团负责人当众指出你海报做得不认真，你觉得委屈——那版方案是按他意见改的。', unhelpful: '当场反驳「你上次说的就是这样的」——当众对线没有赢家，只有观众。', helpful: '先接住：「收到，我回去看哪里没做好。」散会后私聊澄清：「想跟你对一下海报的事，中间那版是按你 X 点的意见调的，可能我们理解有偏差，一起看看哪步出错了？」把当众的评判转成私下的核对。', distortionRefs: ['cd_personalization','cd_filtering'], techniqueRefs: ['st_pause_timeout','st_partial_agree'], aboutTags: ['人际冲突','委屈','羞耻']},
  {kgNodeId: 'cc_long_distance_friend', title: '异地友谊：主动不用多，但要规律', scene: '友谊', situation: '高中最好的朋友去了外地，聊天从每天变成每周，你怕打扰，对方也说「都忙」，联系越来越少。', unhelpful: '等对方先找我，然后偷偷数日子——双方都在等，沉默被各自解读成「他不在乎了」。', helpful: '建立低成本固定频道：每周日晚上发一条语音说说这周的事，不求秒回。「规律的小剂量」比「偶尔的大动静」更能维持亲密。把「在不在乎」的考试改成「习不习惯」的安排。', distortionRefs: ['cd_mind_reading','cd_self_fulfillment'], techniqueRefs: ['st_reality_check','st_name_emotion'], aboutTags: ['亲密关系','生活节奏','孤独']},
  {kgNodeId: 'cc_argument_cooling', title: '吵到一半怎么收场：暂停协议', scene: '冲突降温', situation: '和好友争论越说越大声，你感觉到自己想说那句最伤人的话了。', unhelpful: '硬撑着把话说完「赢」这一轮——赢了回合，输了关系，回家路上全是后悔。', helpful: '提前和身边重要的人约定「暂停暗号」（一个手势或一句话）。触发后：「我现在很上头，半小时后我回来，这个问题对我们很重要。」关键在第二句——暂停必须带回归承诺，否则对方体验为冷暴力。', distortionRefs: ['cd_always_be_right','cd_intolerance'], techniqueRefs: ['st_pause_timeout','st_long_exhale'], aboutTags: ['人际冲突','亲密关系','愤怒']},
  {kgNodeId: 'cc_study_buddy_invite', title: '开口组队：把「被拒」拆小', scene: '学业', situation: '考研复习想约同学一起，又怕对方嫌麻烦或者已有搭子，犹豫两周没开口。', unhelpful: '在心里预演一百次拒绝然后放弃——预言没发生，行为已经跟着剧本走了（自我实现）。', helpful: '给对方台阶+给自己退路：「想问问你考研要不要搭子？你已经有固定队友或者想自己复习都完全 OK，我就随口一问。」请求越具体、门槛越低，开口越不费劲；被拒也只是选项之一。', distortionRefs: ['cd_fortune_telling','cd_avoidance_belief'], techniqueRefs: ['st_worst_case_plan','st_behavioral_activation'], aboutTags: ['学业压力','孤独','焦虑']},
  {kgNodeId: 'cc_interview_thanks', title: '面试后的感谢跟进：真诚不出戏', scene: '求职', situation: '面试聊得不错，你想发一封跟进邮件，又怕显得刻意讨好。', unhelpful: '复制模板群发「尊敬的面试官，感谢贵司给我宝贵机会……」——空话谁都能看出是模板。', helpful: '一个具体细节+一个增量信息+一句不催促的收尾：「您提到团队在做 X 方向，回来我读了 Y 这篇文章，其中 Z 点和我的课程设计经历很像，附件是我的一点想法。无论结果如何，这次交流对我都很有收获。」把「求结果」变成「留印象」。', distortionRefs: ['cd_discounting_positive','cd_immutable_self'], techniqueRefs: ['st_reality_check','st_worry_split'], aboutTags: ['就业压力','自我期待','焦虑']}
] AS row
MERGE (c:CommCase {kgNodeId: row.kgNodeId})
SET c.title = row.title, c.scene = row.scene, c.situation = row.situation, c.unhelpful = row.unhelpful, c.helpful = row.helpful, c.aboutTags = row.aboutTags
WITH c, row
UNWIND row.distortionRefs AS dr
MATCH (d:CognitiveDistortion {kgNodeId: dr})
MERGE (c)-[:SHOWS_DISTORTION]->(d);

UNWIND [
  {kgNodeId: 'cc_dorm_sleep', techniqueRefs: ['st_i_message','st_ground_54321']},
  {kgNodeId: 'cc_group_freerider', techniqueRefs: ['st_i_message','st_partial_agree']},
  {kgNodeId: 'cc_parents_grade', techniqueRefs: ['st_i_message','st_worst_case_plan']},
  {kgNodeId: 'cc_partner_space', techniqueRefs: ['st_i_message','st_partial_agree']},
  {kgNodeId: 'cc_friend_heartbreak', techniqueRefs: ['st_name_emotion','st_i_message']},
  {kgNodeId: 'cc_classmate_debt', techniqueRefs: ['st_broken_record','st_partial_agree']},
  {kgNodeId: 'cc_professor_extension', techniqueRefs: ['st_worst_case_plan','st_reality_check']},
  {kgNodeId: 'cc_roommate_chore', techniqueRefs: ['st_partial_agree','st_i_message']},
  {kgNodeId: 'cc_apology_repair', techniqueRefs: ['st_i_message','st_self_compassion_break']},
  {kgNodeId: 'cc_refuse_drinking', techniqueRefs: ['st_broken_record','st_name_emotion']},
  {kgNodeId: 'cc_feedback_defense', techniqueRefs: ['st_pause_timeout','st_partial_agree']},
  {kgNodeId: 'cc_long_distance_friend', techniqueRefs: ['st_reality_check','st_name_emotion']},
  {kgNodeId: 'cc_argument_cooling', techniqueRefs: ['st_pause_timeout','st_long_exhale']},
  {kgNodeId: 'cc_study_buddy_invite', techniqueRefs: ['st_worst_case_plan','st_behavioral_activation']},
  {kgNodeId: 'cc_interview_thanks', techniqueRefs: ['st_reality_check','st_worry_split']}
] AS row
MATCH (c:CommCase {kgNodeId: row.kgNodeId})
UNWIND row.techniqueRefs AS tr
MATCH (t:StrengthTechnique {kgNodeId: tr})
MERGE (c)-[:USES_TECHNIQUE]->(t);

// —— 事件标签 → 压力源（MAPS_TO，与 stressorMap 同源）——
UNWIND [['宿舍','人际冲突'],['小组项目','人际冲突'],['人际冲突','人际冲突'],['亲子沟通','家庭关系'],['亲密关系','亲密关系'],['关系失落','亲密关系'],['学业压力','学业压力'],['就业焦虑','就业压力'],['经济压力','经济压力'],['躯体健康','身体健康'],['成就事件','自我期待'],['日常','生活节奏'],['其他','其他']] AS pair
MERGE (:EventTag {name: pair[0]})
MERGE (:Stressor {name: pair[1]});

UNWIND [['宿舍','人际冲突'],['小组项目','人际冲突'],['人际冲突','人际冲突'],['亲子沟通','家庭关系'],['亲密关系','亲密关系'],['关系失落','亲密关系'],['学业压力','学业压力'],['就业焦虑','就业压力'],['经济压力','经济压力'],['躯体健康','身体健康'],['成就事件','自我期待'],['日常','生活节奏'],['其他','其他']] AS pair
MATCH (t:EventTag {name: pair[0]}), (s:Stressor {name: pair[1]})
MERGE (t)-[:MAPS_TO]->(s);

// —— 科普/案例标签 → 压力源或情绪（ABOUT）——
// psy_self_regulation_body: 学业压力、人际冲突、焦虑、愤怒、恐惧
// psy_amygdala_hijack: 人际冲突、亲密关系、愤怒、压力
// psy_6second_wave: 亲密关系、学业压力、焦虑、悲伤
// psy_naming_feelings: 生活节奏、其他、焦虑、麻木、委屈
// psy_sleep_emotion: 身体健康、生活节奏、麻木、焦虑
// psy_self_compassion: 自我期待、学业压力、羞耻、悲伤
// psy_cbt_triangle: 学业压力、人际冲突、焦虑、内疚
// psy_worry_vs_problem: 就业压力、学业压力、焦虑、压力
// psy_breath_brake: 身体健康、人际冲突、恐惧、压力
// psy_spotlight_effect: 自我期待、人际冲突、羞耻、焦虑
// psy_emotion_info: 生活节奏、其他、愤怒、内疚、焦虑
// psy_action_first: 生活节奏、学业压力、麻木、悲伤
// psy_perfectionism_cost: 自我期待、学业压力、焦虑、羞耻
// psy_rumination_exit: 人际冲突、自我期待、悲伤、内疚
// psy_no_say_skill: 人际冲突、家庭关系、愤怒、委屈
// psy_loneliness_signal: 亲密关系、生活节奏、孤独、悲伤
// psy_nvc_four_steps: 亲密关系、家庭关系、人际冲突、愤怒、委屈
// psy_real_rest: 生活节奏、学业压力、麻木、压力
// psy_help_seeking: 其他、学业压力、悲伤、孤独
// psy_exam_anxiety_curve: 学业压力、就业压力、焦虑、恐惧
// cc_dorm_sleep: 人际冲突、愤怒、委屈
// cc_group_freerider: 人际冲突、学业压力、愤怒
// cc_parents_grade: 家庭关系、焦虑、内疚
// cc_partner_space: 亲密关系、压力、孤独
// cc_friend_heartbreak: 亲密关系、悲伤、麻木
// cc_classmate_debt: 经济压力、人际冲突、压力
// cc_professor_extension: 学业压力、焦虑
// cc_roommate_chore: 人际冲突、愤怒、委屈
// cc_apology_repair: 人际冲突、内疚、羞耻
// cc_refuse_drinking: 生活节奏、人际冲突、压力
// cc_feedback_defense: 人际冲突、委屈、羞耻
// cc_long_distance_friend: 亲密关系、生活节奏、孤独
// cc_argument_cooling: 人际冲突、亲密关系、愤怒
// cc_study_buddy_invite: 学业压力、孤独、焦虑
// cc_interview_thanks: 就业压力、自我期待、焦虑
