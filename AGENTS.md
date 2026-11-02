# Storage Redirect X — 项目规范与架构

Storage Redirect X 由 Android 配置应用和 Rust Zygisk 模块组成，通过挂载、文件操作 Hook 与系统代写处理实现共享存储重定向。本文面向协作者和 AI 助手，以当前分支的源码与构建配置为准。

## 项目结构

| 路径 | 职责 |
|---|---|
| `android/app/src/main/` | Kotlin 应用、Compose 界面与资源 |
| `android/app/src/debug/` | 调试包专用命令 Provider |
| `android/gradle/` | 依赖版本与 Gradle wrapper |
| `srx_core/src/` | Rust 核心与日志守护进程 |
| `srx_core/java_src/` | Java Hook 源码，构建时生成内嵌 DEX |
| `srx_core/native/` | LSPlant C++ 桥接与 CMake 入口 |
| `srx_core/third_party/` | LSPlant、`srx_hook` 与 `srx_inline_hook` Git 子模块，保留上游依赖、许可证及独立提交历史 |
| `assets/zygisk_module/` | 模块安装、启动、卸载脚本与 SELinux 规则 |
| `scripts/build.py` | Android 构建与 Zygisk 构建、打包入口 |
| `.github/` | CI、设备验证脚本与发布更新日志 |
| `.claude/skills/`、`.codex/skills/` | 共享技能，两个客户端的同名文件保持一致 |

### Rust 分层

| 模块 | 职责 |
|---|---|
| `config/` | 配置扫描、解析、合并、一致性检查与热重载 |
| `redirect/` | 路由匹配、重定向决策与系统代写策略 |
| `hook/` | 文件操作、调用方识别、FUSE 与 CursorWindow Hook |
| `mount/` | 存储别名、bind mount 与路径映射 |
| `lifecycle/` | specialize 前后处理、companion 请求与挂载 |
| `monitor/` | 文件访问审计、Downloads 归属与组件来源识别 |
| `java_hook/` | 内嵌 DEX、Hooker 与 LSPlant 绑定 |
| `platform/`、`zygisk/` | 文件系统、链接器、ABI 与 JNI 边界 |
| `bin/srx_logd/` | 私有 socket 日志接收与滚动保存 |

改动应放在负责该行为的模块，不把策略、平台操作和界面状态混在一个函数内。第三方源码只在任务确实需要时修改，保留原有许可证与署名。

## 编码规范

### 职责与命名

- 自行维护的 `.rs` 文件不超过 2400 行，含注释与空行；超限按职责拆分，不用编号分片规避上限。模块入口使用同名 `.rs`，禁止 `mod.rs`。
- 函数只做一件事，超过 60 行时考虑拆分；重复逻辑出现两次以上时提取函数或宏。
- Kotlin 类使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`；Rust/C++ 函数和变量使用 `snake_case`，类型使用 `UpperCamelCase`；常量统一使用 `SCREAMING_SNAKE_CASE`。
- 布尔量使用 `is/has/should/can` 前缀，集合用复数，映射用 `ByXxx/ToXxx`；事件回调用 `onXxx`，内部处理用 `handleXxx`。
- 变量靠近使用处声明，锁、borrow 与 `RefCell::borrow_mut` 的持有范围最小化。
- 无语义变更需求时禁止全量重排代码，不夹带无关清理。

### 卫语句

- 先处理错误和边界条件并提前返回；循环先跳过无关项，正常流程保持靠左。对称的二选一逻辑可保留 `if/else`。
- `EEXIST` 等可预期系统结果不作为 warning；有条件的副作用仅在条件成立时执行。

### 禁止过度防御

- 只在真实可能失败的边界校验；前置条件由调用方保证时，被调方不重复判空或校验，不做双向检查。
- 仅在确实会抛异常、且本层能处理的边界捕获异常，不逐行或逐层包裹 `try/catch`、`try/except`，不用异常代替正常分支。
- 写兜底分支前必须确认它现实中可达，且有明确的处理语义；禁止无依据的默认值、重试和兼容分支。
- 本层无法处理的错误交给上层，不静默忽略，不用默认值把失败伪装成成功；协程取消必须继续传播。

### Rust / Zygisk

- `unsafe` 仅用于 Zygisk ABI、Hook、JNI、libc 等必要边界；已在 `unsafe extern "C" fn` 内时不嵌套多余 `unsafe` 块。
- 生产路径禁止 `unwrap()/expect()`，用 `Result` 或错误码表达失败。
- 共享状态使用原子或锁，锁内禁止阻塞 I/O；热路径先用原子门控再进入锁。
- 日志使用 `log` crate 门面，经私有 socket 发送至 `srx_logd`；高频日志必须采样。

### Kotlin / Android

- 禁止滥用 `!!`，优先显式判空和卫语句。
- 禁止 `GlobalScope`，使用 `viewModelScope` 或 `rememberCoroutineScope`。
- 文件读取/写入与 Root 命令在 `Dispatchers.IO` 执行，界面状态在适当的生命周期内维护。
- 调试命令只放在 `src/debug/`，正式包不得包含调试 Provider。

### 注释与语言

- 回复、项目规范与自行维护的代码注释使用简体中文，中英文之间空一格；提交信息、PR 标题与正文、新增 skill 正文使用英文。
- 注释只写意图、约束和边界，同一段最多两行；不复述代码或记录修改历史。
- 使用目标语言原生单行注释：Rust/Kotlin/C++ 用 `//`，Python/Shell/YAML 用 `#`，XML 用 `<!-- -->`；保留第三方许可证原文。

## 本地方案与过程记录

- 按需记录，普通修改用提交说明和最终回复即可，不把写文档作为固定步骤。
- 用户要求方案，或需要保留取舍、迁移步骤和跨阶段依赖时，保存到 `build/docs/<任务标识>.md`；同一任务只维护一份方案。
- 复现依据、验收结果与交接进度保存在 `build/record/<任务标识>-<阶段号>.md`，不另建重复总结。
- 任务标识为 `YYYYMMDD-主题`，日期取任务开始日，跨天或跨会话沿用；阶段号从 `1` 连续递增。
- 一个阶段对应一个可独立验收的目标和至少一项可复核条件。同一目标的重试、修正和暂停后继续更新原阶段，进入下一个目标才增加阶段号。
- 记录顶部写明目标、状态（进行中 / 受阻 / 已完成 / 已终止）、更新时间、分支与提交号、未提交改动摘要和下一步；没有的项写“无”。暂停、交接或结论变化前更新。
- 验收项逐项标记未执行、通过或失败，附证据路径；失败或未完成不得写成通过。设备临时设置、备份和恢复要求必须记录。
- 接手先读取同一任务阶段号最大的记录，再核对实际分支、提交和工作区差异，不凭编号推断完成状态。
- 每份阶段记录最多 200 行，含空行；先合并重复内容，不按时间、消息轮数或文档长度拆阶段。
- 临时脚本与附件放在 `build/record/<任务标识>-<阶段号>/`；工具固定位置的日志、截图只引用路径，不复制完整输出或对话。
- `build/` 下的方案、记录和附件仅供本地使用，不纳入 Git。

## 构建与验证

依赖版本以 `android/gradle/libs.versions.toml` 和 `srx_core/Cargo.toml` 为准，版本号以 `android/gradle.properties` 与 Rust package version 为准。

首次检出或更新依赖后，按构建说明初始化子模块及 LSPlant 的 DexBuilder 依赖；不初始化上游测试工程的私有依赖。自有底层库修改在子模块内提交，再由主仓库记录对应提交号。

```bash
python scripts/build.py build-zygisk --abi arm64-v8a --debug
python scripts/build.py build-zygisk --abi x86_64 --debug
python scripts/build.py build-apk
./android/gradlew -p ./android :app:assembleDebug
cargo clippy --manifest-path srx_core/Cargo.toml --target aarch64-linux-android -- -D warnings
./android/gradlew -p ./android :app:lintDebug
```

- 本地模块与 AVD 验证优先 `--debug`，保留符号并关闭 LTO、跳过 strip；`build-apk` 产出 release 包，本地应用调试使用 `assembleDebug`。
- 修改后必须保证可编译且无 warning；改动 `srx_core/` 必跑 clippy，改动 `android/` 必跑 `lintDebug`。执行结果与源码检查必须区分，不把未执行的验证写成通过。
- **默认不写测试**：除非用户明确要求，不新建测试文件、不添加测试依赖，也不把验收脚本放进产品源码。
- 不写测试不等于跳过验证；按改动范围使用现有构建、静态检查和调试工具，必要时执行已有测试或实机检查。
- 修改构建白名单时核对整个索引，确保源码、资源、Java Hook 输入、原生依赖和 Gradle wrapper 均可跟踪；编译成功不代表缺失 DEX 时的 Java Hook 可用。

## 设备与文件安全

- 破坏性操作前确认完整路径和影响范围；删除优先使用回收站，不擅自永久删除。取消 Git 跟踪使用精确路径的 `git rm --cached`，保留本机副本。
- 在 Git Bash 中调用 `adb` 默认前置 `MSYS_NO_PATHCONV=1`；参数中含 Android/Linux 绝对路径时必须前置，包含引号内、`shell`、`push/pull` 的远端路径。
- 截图必须先查询 display id，再指定 id：

```bash
MSYS_NO_PATHCONV=1 adb -s <device_id> shell dumpsys SurfaceFlinger --display-id
MSYS_NO_PATHCONV=1 adb -s <device_id> exec-out screencap -d <display_id> -p > <output.png>
```

- Android 文件删除前先通过 `adb pull` 备份到本机并确认；验证不得擅自重置设备、卸载应用或覆盖用户配置。

## Git 白名单与协作

- `.gitignore` 默认忽略所有文件，只放行产品源码、测试代码、构建/安装/CI 必需配置与资源、许可证、共享 skills 和本规范。
- 新增文件先用 `git check-ignore --no-index -v <路径>` 核对；只有真实项目输入才补充最小白名单，不用 `git add -f` 绕过规则。
- 共享 skills 包括 `commit`、`pull-request`、`version-bump`、`update-dependencies`，在 `.claude/skills/` 与 `.codex/skills/` 同步维护；其他现有设备调试技能仅供本地使用。
- 检查或升级 Rust、Java/Kotlin/Android 依赖与官方 LSPlant 时使用 `update-dependencies` skill，按请求区分检查与实际升级。
- `srx_hook` 与 `srx_inline_hook` 通常不检查远端更新；审查任务时仍须检查这两个子模块的本地状态。本次任务相关改动完成并验证后，及时按 `commit` skill 在子模块内原子提交，再由主仓库登记提交号；保留来源不明、无关或未完成的改动。
- `CLAUDE.md` 仅保留 `@AGENTS.md`，规范只维护本文件一份。
- 按当前任务授权修改分支；未经要求不切换、合并或删除分支。新功能分支使用 `feature/<name>` 或 `fix/<name>`。
- 所有仓库的每次提交都必须是原子提交，包括主仓库、子模块、版本提升和格式整理：一个提交只处理一个可独立审查的目的，不把多个独立需求混在一起；同一目的必需的源码、配置和锁文件一起提交。
- 每完成一项可正常工作的需求并通过必要验证后，及时按 `commit` skill 单独提交，不等待用户再次催促；按目的暂存具体路径或代码块，执行 `git commit`（含 `--amend`）前复核 `git diff --cached`。不为落实新规范擅自改写已有历史。
- 提交标题和正文使用英文，标题为 `type: imperative summary`，最多 70 字符；正文说明原因、最终行为与验证结果。
- 未经用户明确允许，不得执行任何 `git push`，包括主仓库、子模块和标签推送；提交或创建 PR 的授权不等于推送授权。
- 每次推送前说明仓库、远端、分支或标签及待推送提交，等待用户针对本次操作明确允许。每次允许只授权当前这一次推送尝试，执行后即失效；重试、增加提交、改变目标或后续推送都必须重新询问，不沿用先前会话或其他仓库的授权。
- 不主动追加无关推送，不得擅自强推公开历史。
- 准备、审查或创建 PR 时使用 `pull-request` skill；一个 PR 围绕一个主题，按授权发布，不把创建 skill 当作创建 PR 的许可。

## 审查清单

- [ ] 改动位于负责该行为的模块，未夹带其他任务？
- [ ] 文件大小、命名、卫语句与最小锁作用域符合规范？
- [ ] 没有重复防御、无依据兜底或掩盖失败？
- [ ] 没有擅自添加测试源码或依赖，必要验证已有真实结果？
- [ ] 调试实现仅在调试包生效，Root 与文件 I/O 不在主线程？
- [ ] 新文件可通过白名单，许可证与编译输入没有被排除？
- [ ] 临时方案与记录位于忽略目录，两个客户端的共享 skills 一致？
- [ ] 提交范围和英文信息已复核，推送得到明确确认？
