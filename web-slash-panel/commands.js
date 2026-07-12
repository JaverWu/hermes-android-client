// commands.js
// 斜杠命令面板的数据源：静态示例 + 后端 API 占位。
// 这是「可配置的数据源数组」，后续把 getStaticCommands() 换成 fetchCommands() 即可动态拉取。

/**
 * @typedef {'skill' | 'tool' | 'command' | 'template'} CommandGroup
 *
 * @typedef {Object} SlashCommand
 * @property {string}   id          唯一 id
 * @property {CommandGroup} group    分组类型
 * @property {string}   groupLabel  分组标题（如 "Skills"）
 * @property {string}   title       显示名（也是默认过滤主键）
 * @property {string}   description  一句话说明（参与「描述即搜索」）
 * @property {string}   icon         emoji 或图标标识（轻量、零依赖）
 * @property {string}   [token]      插入输入框的文本，如 "/skill:english-frame"
 * @property {string[]} [keywords]   额外搜索关键词
 * @property {() => void} [action]   选中即触发的动作（与 token 二选一）
 */

/* ---------- 1. Skills：用户已安装的 skill（对应 ~/.hermes/skills/） ---------- */
const INSTALLED_SKILLS = [
  { id: 'skill-en',   group: 'skill', groupLabel: 'Skills',  title: 'english-frame', description: '把任意内容改写成地道英文',          icon: '🌐', token: '/skill:english-frame' },
  { id: 'skill-code', group: 'skill', groupLabel: 'Skills',  title: 'code-review',   description: '对代码做结构化审查并给建议',        icon: '🧪', token: '/skill:code-review' },
  { id: 'skill-sum',  group: 'skill', groupLabel: 'Skills',  title: 'summarize',     description: '长文本摘要与要点提取',              icon: '📝', token: '/skill:summarize' },
  { id: 'skill-sql',  group: 'skill', groupLabel: 'Skills',  title: 'sql-gen',       description: '根据自然语言生成 SQL 查询',         icon: '🗄️', token: '/skill:sql-gen' },
];

/* ---------- 2. 工具/能力：当前会话加载的 tools ---------- */
const SESSION_TOOLS = [
  { id: 'tool-web',   group: 'tool', groupLabel: '工具 / 能力', title: 'Web 搜索',   description: '联网检索网页与实时信息',  icon: '🔍', token: '/tool:web' },
  { id: 'tool-term',  group: 'tool', groupLabel: '工具 / 能力', title: '终端',       description: '在沙箱中执行 shell 命令', icon: '💻', token: '/tool:terminal' },
  { id: 'tool-file',  group: 'tool', groupLabel: '工具 / 能力', title: '文件读写',   description: '读取与写入工作区文件',    icon: '📄', token: '/tool:file' },
  { id: 'tool-draw',  group: 'tool', groupLabel: '工具 / 能力', title: '画图',       description: '生成或编辑图片',          icon: '🎨', token: '/tool:draw' },
  { id: 'tool-cron',  group: 'tool', groupLabel: '工具 / 能力', title: '定时任务',   description: '创建周期性定时任务',      icon: '⏰', token: '/tool:cron' },
];

/* ---------- 3. 内置命令 ---------- */
const BUILTIN_COMMANDS = [
  { id: 'cmd-cron',   group: 'command', groupLabel: '内置命令', title: '创建定时任务', description: '用自然语言新建一个定时任务', icon: '⏰', token: '/command:create-cron' },
  { id: 'cmd-memory', group: 'command', groupLabel: '内置命令', title: '保存记忆',     description: '把当前内容存入长期记忆', icon: '🧠', token: '/command:save-memory' },
  { id: 'cmd-runskill',group: 'command',groupLabel: '内置命令', title: '调用 Skill',   description: '手动触发某个已安装 skill', icon: '🚀', token: '/command:run-skill' },
  { id: 'cmd-clear',  group: 'command', groupLabel: '内置命令', title: '清空对话',     description: '清掉当前会话的全部消息', icon: '🗑️',
    action: () => { if (window.confirm('确定清空对话？')) console.log('[hermes] clear conversation'); } },
];

/* ---------- 4. 用户收藏的提示词模板 ---------- */
const PROMPT_TEMPLATES = [
  { id: 'tpl-meeting', group: 'template', groupLabel: '提示词模板', title: '会议纪要', description: '把对话整理成会议纪要模板', icon: '🗒️', token: '/tpl:meeting' },
  { id: 'tpl-email',   group: 'template', groupLabel: '提示词模板', title: '邮件草稿', description: '按要点生成一封邮件',     icon: '✉️', token: '/tpl:email' },
];

/** 静态合并后的命令源（可配置数组，方便后续替换为后端数据） */
export function getStaticCommands() {
  return [
    ...INSTALLED_SKILLS,
    ...SESSION_TOOLS,
    ...BUILTIN_COMMANDS,
    ...PROMPT_TEMPLATES,
  ];
}

/**
 * 从后端动态拉取命令源（占位）。
 * Hermes 侧建议提供以下端点：
 *   GET /v1/skills        -> [{ name, description }]           （~/.hermes/skills）
 *   GET /v1/session/tools -> [{ id, name, description, icon }] （当前会话加载的 tools）
 *   GET /v1/commands      -> [{ id, name, description, icon }] （内置命令）
 *   GET /v1/prompts       -> [{ id, name, description }]       （收藏模板）
 * 真实接入时把 setTimeout 那段换成 fetch 并 normalize 成 SlashCommand[] 即可。
 *
 * @returns {Promise<SlashCommand[]>}
 */
export async function fetchCommands() {
  // TODO: 替换为真实接口
  // const [skills, tools, commands, prompts] = await Promise.all([
  //   fetch('/v1/skills').then((r) => r.json()),
  //   fetch('/v1/session/tools').then((r) => r.json()),
  //   fetch('/v1/commands').then((r) => r.json()),
  //   fetch('/v1/prompts').then((r) => r.json()),
  // ]);
  // return normalizeToSlashCommands(skills, tools, commands, prompts);
  return new Promise((resolve) => {
    setTimeout(() => resolve(getStaticCommands()), 200);
  });
}
