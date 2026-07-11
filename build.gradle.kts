// Hermes 对话 App —— 顶层构建脚本
// 插件版本集中管理，避免子模块重复声明。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
