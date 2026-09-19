// SoulVoyage · Neo4j 知识图谱种子（与 backend/src/main/resources/kg/cognitive_distortions.json 同源）
// 用法（Neo4j 就绪后）: cypher-shell -u neo4j -p <pw> -f deploy/neo4j/seed.cypher
// 引入动机见手册 §5.3：真实模型上线后由 Neo4jKgService 实现 KgSearchService，替换内存版。

CREATE CONSTRAINT distortion_id IF NOT EXISTS FOR (d:CognitiveDistortion) REQUIRE d.kgNodeId IS UNIQUE;
CREATE CONSTRAINT stressor_name IF NOT EXISTS FOR (s:Stressor) REQUIRE s.name IS UNIQUE;
CREATE CONSTRAINT emotion_name IF NOT EXISTS FOR (e:Emotion) REQUIRE e.name IS UNIQUE;
CREATE CONSTRAINT eventtag_name IF NOT EXISTS FOR (t:EventTag) REQUIRE t.name IS UNIQUE;

// —— 情绪节点（与 16 情绪闭集一致，先行创建供 TRIGGERS 关联）——
UNWIND ['喜悦','愤怒','悲伤','恐惧','焦虑','惊讶','厌恶','羞耻','内疚','委屈','孤独','麻木','平静','压力','期待','其他'] AS name
MERGE (:Emotion {name: name});

// —— 认知误区卡（12）——
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_all_or_nothing'}) SET d.name='非黑即白', d.definition='以两个极端类别概括事物，忽视中间地带', d.typicalSignature='要么全好要么全坏 / 一次失败=彻底失败 / 我就是个废物', d.socraticTemplate='如果一位好朋友经历了完全相同的事，你也会用「彻底失败」来评价他吗？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_catastrophizing'}) SET d.name='灾难化思维', d.definition='把可能的负面结果放大为不可避免的灾难', d.typicalSignature='完了 / 撑不住了 / 一定会出大事', d.socraticTemplate='最坏、最好、最可能发生的结果分别是什么？各自的概率有多大？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_mind_reading'}) SET d.name='读心术', d.definition='没有证据便断定别人对自己有负面看法', d.typicalSignature='他肯定觉得我傻 / 大家都不喜欢我', d.socraticTemplate='除了你猜测的解释，对方的行为还有哪两种可能的原因？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_personalization'}) SET d.name='过度归己', d.definition='把不完全由自己造成的结果全部归责于自己', d.typicalSignature='都是我的错 / 要不是我就好了', d.socraticTemplate='这件事的结果里，有哪些因素是你无法控制的？如果责任是一百份，你实际拿几份？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_should_statements'}) SET d.name='「应该」句式', d.definition='用僵化的规则要求自己和他人，违反即愤怒或自责', d.typicalSignature='他应该知道 / 我必须做到完美', d.socraticTemplate='把「他应该」换成「我希望」，你的感受会有什么不同？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_labeling'}) SET d.name='贴标签', d.definition='用一个固化标签定义整个人，而非评价具体行为', d.typicalSignature='我就是个懒人 / 他是个小人', d.socraticTemplate='「这次没做好一件事」和「我这个人不行」，哪个更符合事实？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_emotional_reasoning'}) SET d.name='情绪推理', d.definition='把感觉当作事实证据：我这么觉得，所以一定如此', d.typicalSignature='我感觉很糟，所以一切都完了', d.socraticTemplate='如果睡个好觉之后再看这件事，你的判断会改变吗？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_discounting_positive'}) SET d.name='否定正面', d.definition='把好的反馈与成果排除在外，只保留负面证据', d.typicalSignature='这次只是运气 / 不算什么', d.socraticTemplate='如果这些成绩写在别人的简历上，你会承认它们的价值吗？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_overgeneralization'}) SET d.name='过度概括', d.definition='由单一事件得出普遍性、永久性结论', d.typicalSignature='总是 / 每次 / 永远都', d.socraticTemplate='「总是」是真的没有一次例外，还是今天特别难受？能想到一个反例吗？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_heavenly_reward'}) SET d.name='应该回报思维', d.definition='认为自己的付出必然应得到对等回报，落空即强烈不公感', d.typicalSignature='我付出这么多，凭什么', d.socraticTemplate='对方知道你的付出并把它计入账本了吗？你们对「公平」的定义一致吗？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_change_fallacy'}) SET d.name='改变谬误', d.definition='相信只要自己足够努力，就能改变别人的行为与选择', d.typicalSignature='只要我再劝几次他就改了', d.socraticTemplate='如果对方一年后仍然不变，你能接受并保护自己的方案是什么？';
MERGE (d:CognitiveDistortion {kgNodeId: 'cd_always_be_right'}) SET d.name='坚持到底谬误', d.definition='为避免认错，把争论的输赢置于关系与事实之上', d.typicalSignature='这次绝不能先低头', d.socraticTemplate='在这件事上，你更想赢这场争论，还是更想宿舍关系变好一些？';

// —— 情绪 → 误区（TRIGGERS）——
UNWIND [['cd_all_or_nothing','羞耻'],['cd_all_or_nothing','悲伤'],['cd_all_or_nothing','麻木'],['cd_all_or_nothing','焦虑'],
        ['cd_catastrophizing','焦虑'],['cd_catastrophizing','恐惧'],['cd_catastrophizing','压力'],
        ['cd_mind_reading','愤怒'],['cd_mind_reading','羞耻'],['cd_mind_reading','孤独'],['cd_mind_reading','焦虑'],
        ['cd_personalization','内疚'],['cd_personalization','悲伤'],['cd_personalization','羞耻'],
        ['cd_should_statements','愤怒'],['cd_should_statements','内疚'],['cd_should_statements','压力'],
        ['cd_labeling','羞耻'],['cd_labeling','愤怒'],['cd_labeling','麻木'],
        ['cd_emotional_reasoning','悲伤'],['cd_emotional_reasoning','麻木'],['cd_emotional_reasoning','孤独'],
        ['cd_discounting_positive','悲伤'],['cd_discounting_positive','羞耻'],['cd_discounting_positive','平静'],
        ['cd_overgeneralization','悲伤'],['cd_overgeneralization','愤怒'],['cd_overgeneralization','孤独'],['cd_overgeneralization','焦虑'],
        ['cd_heavenly_reward','愤怒'],['cd_heavenly_reward','委屈'],['cd_heavenly_reward','内疚'],
        ['cd_change_fallacy','愤怒'],['cd_change_fallacy','压力'],['cd_change_fallacy','悲伤'],
        ['cd_always_be_right','愤怒'],['cd_always_be_right','孤独']] AS pair
MATCH (d:CognitiveDistortion {kgNodeId: pair[0]}), (e:Emotion {name: pair[1]})
MERGE (e)-[:TRIGGERS]->(d);

// —— 事件标签 → 压力源（MAPS_TO，与 stressorMap 同源）——
UNWIND [['宿舍','人际冲突'],['小组项目','人际冲突'],['人际冲突','人际冲突'],['亲子沟通','家庭关系'],
        ['亲密关系','亲密关系'],['关系失落','亲密关系'],['学业压力','学业压力'],['就业焦虑','就业压力'],
        ['经济压力','经济压力'],['躯体健康','身体健康'],['成就事件','自我期待'],['日常','生活节奏'],['其他','其他']] AS pair
MERGE (:EventTag {name: pair[0]})
MERGE (:Stressor {name: pair[1]});

UNWIND [['宿舍','人际冲突'],['小组项目','人际冲突'],['人际冲突','人际冲突'],['亲子沟通','家庭关系'],
        ['亲密关系','亲密关系'],['关系失落','亲密关系'],['学业压力','学业压力'],['就业焦虑','就业压力'],
        ['经济压力','经济压力'],['躯体健康','身体健康'],['成就事件','自我期待'],['日常','生活节奏'],['其他','其他']] AS pair
MATCH (t:EventTag {name: pair[0]}), (s:Stressor {name: pair[1]})
MERGE (t)-[:MAPS_TO]->(s);
