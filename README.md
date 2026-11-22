# BaoInstall

接管 Android 系统安装入口的 LSPosed 模块。点 APK 不再走厂商安装器，改为直接通过 Root 通道调用系统包管理器安装。

## 特性

- **接管安装入口**：浏览器、文件管理器、聊天软件里点 APK，直接进本应用的安装确认页
- **跳过厂商拦截**：放行 ColorOS「登录 OPPO 账号才能继续安装」，其他机型无影响
- **Root 通道安装**：`su` + `pm install-write/commit`，不经过厂商安装器，无广告、无二次确认
- **分包支持**：`.apk` / `.apks` / `.xapk` / `.apkm`，按设备 ABI 与屏幕密度自动筛选分包
- **降级安装**：可选允许覆盖安装更低版本
- **自定义 Root 命令**：自动探测 `su` 等入口，也支持 `suu -c` 这类自定义前缀
- **明文错误提示**：把 `INSTALL_FAILED_*` 翻译成「原因 + 怎么办」

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android 12（API 31）及以上 |
| Root | Magisk / KernelSU / APatch 任一 |
| 框架 | LSPosed，作用域需勾选「系统框架」 |

## 使用

1. 安装 APK，在 LSPosed 中启用模块，作用域勾选「系统框架」
2. 重启手机
3. 打开应用，主页状态卡显示「运行正常」即生效
4. 之后点任意 APK 都会进入本应用

## 构建

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 仓库未包含 `gradle/wrapper/gradle-wrapper.jar`，若 `./gradlew` 无法执行，先在本机执行 `gradle wrapper` 生成。
> `app/src/main/res/drawable/dev_avatar.jpg` 为开发者头像，缺失时会导致资源编译失败，请自行放置任意图片或删除相关引用。

## 目录结构

```
app/src/main/java/bao/omk/
├── MainActivity.kt          主页 / 设置（Compose）
├── InstallActivity.kt       安装确认对话框
├── installer/               安装引擎：解析、筛选、执行、错误翻译
├── xposed/                  system_server 侧 Hook 与配置 Provider
└── config/                  开关配置
```

## 免责声明

本模块会修改系统 `system_server` 的行为，并需要 Root 权限。使用前请确认你理解风险，作者不对任何设备损坏或数据丢失负责。

## 关于创作方式

本项目由 **AI 辅助创作**：从代码结构、Compose 界面、`system_server` 侧 Hook 到这份文档，均在 WorkBuddy（DeepSeek-V4.1-Flash）的对话式协助下完成；需求、方案取舍与真机验证由开发者负责。

AI 生成的代码可能存在缺陷，尤其涉及 Root 与系统框架的部分，请在使用前自行评估。

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

## 开发者

芝士🧀雪豹 · [github.com/MeiYueKing](https://github.com/MeiYueKing)
