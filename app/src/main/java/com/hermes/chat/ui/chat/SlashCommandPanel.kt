package com.hermes.chat.ui.chat

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hermes.chat.databinding.DialogSlashCommandsBinding
import com.hermes.chat.databinding.ItemSlashCommandBinding

/** 一条斜杠命令（数据源，可后续从后端接口动态拉取替换 [SlashCommands.all]）。 */
data class SlashCommand(val command: String, val description: String, val group: String)

/** 官方聊天斜杠命令数据源（取自 Hermes Agent 消息平台命令参考）。 */
object SlashCommands {
    val all: List<SlashCommand> = listOf(
        // —— 会话 ——
        SlashCommand("/new", "开始新会话（可加名称：/new my-exp）", "会话"),
        SlashCommand("/reset", "重置对话历史", "会话"),
        SlashCommand("/status", "显示会话信息与摘要", "会话"),
        SlashCommand("/compress", "压缩对话上下文（可加主题）", "会话"),
        SlashCommand("/retry", "重试最后一条消息", "会话"),
        SlashCommand("/undo", "移除最后一轮对话", "会话"),
        SlashCommand("/clear", "清屏并开始新会话", "会话"),
        SlashCommand("/history", "显示对话历史", "会话"),
        SlashCommand("/save", "保存当前对话", "会话"),
        SlashCommand("/title", "设置会话标题", "会话"),
        SlashCommand("/stop", "终止所有后台进程", "会话"),
        // —— 配置 ——
        SlashCommand("/model", "显示或更改模型", "配置"),
        SlashCommand("/fast", "切换快速模式 normal|fast", "配置"),
        SlashCommand("/personality", "设置人格", "配置"),
        SlashCommand("/reasoning", "管理推理力度与显示", "配置"),
        SlashCommand("/verbose", "切换工具进度显示", "配置"),
        SlashCommand("/voice", "切换语音模式", "配置"),
        SlashCommand("/codex-runtime", "切换 Codex 运行时", "配置"),
        // —— 工具 / Skill ——
        SlashCommand("/tools", "列出/禁用/启用工具", "工具"),
        SlashCommand("/toolsets", "列出可用工具集", "工具"),
        SlashCommand("/skills", "搜索/安装/管理 skill", "工具"),
        SlashCommand("/cron", "管理定时任务", "工具"),
        SlashCommand("/browser", "连接本地浏览器 CDP", "工具"),
        SlashCommand("/memory", "审核待处理 memory 写入", "工具"),
        SlashCommand("/suggestions", "审核建议的自动化", "工具"),
        SlashCommand("/blueprint", "通过模板设置自动化", "工具"),
        SlashCommand("/curator", "后台 skill 维护", "工具"),
        SlashCommand("/kanban", "看板视图", "工具"),
        // —— 平台 ——
        SlashCommand("/sethome", "标记 home 频道", "平台"),
        SlashCommand("/update", "更新 Hermes", "平台"),
        SlashCommand("/commands", "列出可用命令", "平台"),
        // —— 信息 ——
        SlashCommand("/help", "显示帮助", "信息"),
        SlashCommand("/version", "显示版本/构建信息", "信息"),
        SlashCommand("/usage", "显示 token 用量", "信息"),
        SlashCommand("/platforms", "显示 gateway 状态", "信息"),
        SlashCommand("/profile", "显示活动 profile", "信息"),
        SlashCommand("/whoami", "显示当前用户", "信息"),
        SlashCommand("/debug", "上传调试报告", "信息")
    )
}

/**
 * 斜杠命令面板：底部抽屉列出官方聊天命令，支持按名称/描述/分组实时过滤，
 * 点选后把命令文本插入输入框（由 [onInsert] 处理）。
 */
class SlashCommandPanel(
    private val activity: Activity,
    private val onInsert: (String) -> Unit
) {
    fun show() {
        val binding = DialogSlashCommandsBinding.inflate(LayoutInflater.from(activity))
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(binding.root)

        val adapter = SlashAdapter(SlashCommands.all) { cmd ->
            onInsert(cmd)
            sheet.dismiss()
        }
        binding.listCommands.layoutManager = LinearLayoutManager(activity)
        binding.listCommands.adapter = adapter

        binding.editFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        sheet.show()
    }

    private class SlashAdapter(
        private val source: List<SlashCommand>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SlashAdapter.VH>() {

        private var items = source

        fun filter(q: String) {
            val ql = q.lowercase().trim()
            items = if (ql.isBlank()) source else source.filter {
                it.command.lowercase().contains(ql) ||
                    it.description.lowercase().contains(ql) ||
                    it.group.lowercase().contains(ql)
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSlashCommandBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            holder.b.textCommand.text = c.command
            holder.b.textDesc.text = c.description
            holder.b.textGroup.text = c.group
            holder.b.root.setOnClickListener { onClick(c.command) }
        }

        class VH(val b: ItemSlashCommandBinding) : RecyclerView.ViewHolder(b.root)
    }
}
