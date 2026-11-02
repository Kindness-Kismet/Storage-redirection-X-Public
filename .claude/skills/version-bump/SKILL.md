---
name: version-bump
description: Bump the app version number and generate changelog. Trigger when user says "更新版本号", "提高版本号", "版本号提升", "version bump", "bump version", or "update version".
user_invocable: true
---

## 流程

1. **检查未提交改动**：先使用 `commit` 技能，按独立目的逐项审查、验证并原子提交已获授权的业务改动；不得把多个业务目的合并，也不得夹带版本号与 changelog。格式整理遵守提交技能的独立提交顺序
2. 读取 `android/gradle.properties` 中的 `appVersionName` 和 `appVersionCode`
3. 确定新版本号：
   - 默认：patch +1（如 1.1.33 → 1.1.34），`appVersionCode` +1
   - 用户指定了具体版本号时，使用用户指定的版本
4. 通过 `git log` 查找上一次版本号提升的 commit，收集此后所有变更
5. 归纳为面向用户的功能描述，忽略纯重构、CI 修复、代码风格等不影响用户体验的改动
6. **合并同一功能的多次改动**：如果某功能在两个版本之间经历了多次修改，以最终状态为准。例如功能 A 先加入后被移除，则更新日志中不应有任何关于功能 A 的描述
7. 识别本次版本涉及的 GitHub issue：用 `gh` 读取 issue 原文，逐条核对本次改动是否完整满足 issue 要求；只有完整满足时，提交信息才允许追加 `close #xxxx`，否则不要追加
8. 修改 `android/gradle.properties` 的 `appVersionName` 和 `appVersionCode`
9. 修改 `srx_core/Cargo.toml` 的 `version` 字段，保持与 app 版本号同步
10. 覆盖写入 `.github/CHANGELOG.md`，不保留旧日志，仅保留本次新版本条目
11. 使用 `commit` 技能创建仅包含版本号、必要锁文件与本次 changelog 的原子提交，提交信息格式：`chore: bump version to {version}`；仅当第 7 步确认完整满足某个 issue 时，才在提交信息末尾追加 `close #xxxx`

## 推送规则

- 完成版本号提升后，**禁止主动询问用户是否 `git push`**，也**禁止擅自 `git push`**
- 仅当用户在本次会话明确要求"推送"、"push"时，允许且**仅允许本次** `git push`
- 本次推送完成后，立即恢复默认行为：**禁止再次主动询问或擅自推送**

## 写入模式

固定使用覆写模式。

完全覆盖 `.github/CHANGELOG.md`，不保留任何旧日志，仅保留本次新版本条目。

## 更新日志格式

使用中英文双语，英文在前、中文在后，中间用单独一行 `---` 分隔。正文不写版本号或分类标题；GitHub Release 标题负责展示版本。

固定结构如下：

```markdown
- English change 1
- English change 2

---

- 中文改动描述 1
- 中文改动描述 2
```

规则：
- 英文列表在前，中文列表在后，中间只有一条分隔线，中英文条目一一对应
- 不写任何 markdown 标题行，包括 `#`、`##`、`###`
- 每条以动词或清晰动作开头，简洁描述用户可感知的变化
- 条目平铺，不折叠，不使用 emoji，条目末尾不加标点
- 文件末尾无空行
