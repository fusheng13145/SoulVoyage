// 一次性生成工具：从 scenes.json 产出 deploy/sql/seed_scenes.sql（保持两处分源一致）
const fs = require('fs');
const scenes = JSON.parse(fs.readFileSync('backend/src/main/resources/scenes/scenes.json', 'utf8'));
const esc = (s) => s.replace(/'/g, "''");
let out = `-- 场景卡种子数据：与 backend/src/main/resources/scenes/scenes.json 同源。
-- 运行时应用读 classpath JSON；本文件用于 MySQL 侧登记（后续管理端 CRUD 切表时启用）。
-- 重复导入前先执行：DELETE FROM scene_card;
`;
for (const s of scenes) {
  const persona = JSON.stringify({ npcName: s.npcName, relation: s.relation, persona: s.persona, openingLines: s.openingLines });
  out += `\nINSERT INTO scene_card (code, title, description, difficulties, persona_json, goal_dimensions, max_turns) VALUES\n  ('${s.code}', '${esc(s.title)}', '${esc(s.description)}', '${s.difficulties.join(',')}', '${esc(persona)}', '${JSON.stringify(s.goalDimensions)}', ${s.maxTurns});\n`;
}
fs.writeFileSync('deploy/sql/seed_scenes.sql', out);
console.log('written', scenes.length, 'rows');
