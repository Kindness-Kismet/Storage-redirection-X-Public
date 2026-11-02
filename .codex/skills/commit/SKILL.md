---
name: commit
description: Prepare, review, and create Git commits for Storage Redirect X. Use when the user requests a commit, a commit message, or an amendment, and before any assistant-initiated git commit, including --amend. Pushing requires separate explicit approval.
---

# Local Commits

Create an atomic commit promptly after each independently working requirement passes its necessary checks. Include only changes belonging to that requirement and follow `AGENTS.md`.

## Atomic Scope

Every commit must be atomic in the main repository and in submodules, including release, formatting, and amended commits. One commit serves one independently reviewable purpose; a request containing several unrelated changes requires separate commits.

Keep the source, configuration, lockfile, and integration changes required for one purpose together. Separate independent fixes, dependency upgrades, version bumps, and formatting. When a file contains multiple purposes, stage the relevant hunks rather than bundling the whole file. Do not rewrite existing history to enforce this rule without an explicit request.

## Workflow

1. Confirm the repository, current branch, upstream, and worktree with `git status --short --branch`. Inspect staged and unstaged changes separately. Do not switch branches or absorb unrelated changes.
2. Verify the final behavior using the checks appropriate to the changed paths and repository. In the main repository, run clippy for `srx_core/` changes and `lintDebug` for `android/` changes; in a submodule, use its own manifest and supported targets. Do not create test files by default; run existing checks where relevant.
3. Check each new path with `git check-ignore --no-index -v <path>`. A final negated pattern permits tracking; a positive pattern excludes it. Keep build artifacts, credentials, and local plans or records out of the index. Do not bypass the allowlist with `git add -f`.
4. Stage explicit paths with `git add -- <path>`. To stop tracking an authorized file, confirm its absolute path and use `git rm --cached -- <path>` so its local copy remains intact. Never use broad staging commands.
5. Review `git diff --cached --check` and the complete `git diff --cached`. Confirm scope, required build inputs, license notices, and actual verification results before committing. Do not commit incomplete work or claim failed checks passed.
6. Review the message against the rules below, then execute `git commit`. Apply the same review before `--amend`.
7. Verify the saved message with `git log -1 --format=%B` and inspect `git status --short --branch`.
8. Complete the Rust formatting phase below before finishing. If the worktree was already clean at entry, skip creating a task commit and start that phase directly. Report all commit IDs, scopes, verification results, and remaining worktree changes.

## Owned Submodules

Inspect the local worktrees of `srx_core/third_party/srx_hook` and `srx_core/third_party/srx_inline_hook` during task review. Normally do not fetch, compare remote branches, or check for upstream updates in these repositories; do so only when the user requests it or the task explicitly requires remote synchronization.

Review local changes before deciding ownership. Promptly verify and atomically commit completed, task-related changes inside each affected submodule using this skill. Preserve unrelated, unknown, or unfinished edits; a dirty submodule is not permission to commit everything.

Commit the submodule first, then record its exact gitlink together with the necessary main-repository integration changes. A source-identical submodule import needs no empty child commit. Inspect both the child diff and the parent's gitlink diff. Local commits do not authorize pushing either repository; report child commits that must be published before others can reproduce the parent revision.

## Rust Formatting

1. Start only with a clean index and worktree. If uncommitted changes exist, verify and commit the authorized changes first, following the workflow above; do not run even a formatting check before those commits. Preserve unrelated or unfinished changes and defer formatting until they are resolved. Do not stash, reset, or absorb them merely to obtain a clean worktree.
2. Run `cargo fmt --all --check`. If it passes, no formatting commit is needed. If it reports formatting differences, run `cargo fmt --all`. Diagnose tool or parsing failures instead of treating them as formatting differences.
3. Review the complete diff and confirm it contains only formatter-generated Rust changes. Run `cargo fmt --all --check` again and `cargo clippy --manifest-path srx_core/Cargo.toml --target aarch64-linux-android -- -D warnings` in the main repository; use the submodule's manifest and supported target when committing there.
4. Stage only the explicit formatted paths, review `git diff --cached --check` and `git diff --cached`, and create one separate commit such as `chore: Format Rust sources`. Never mix formatting with functional changes or amend it into the preceding task commit.
5. Verify the saved commit and clean worktree. This formatting-only commit completes the phase; do not recursively start another formatting phase for it.

## Commit Message

The subject and body must be entirely in English. Chinese response and source-comment rules do not apply to Git messages.

Use `type: imperative summary`, at most 70 characters. Supported types include `feat`, `fix`, `build`, `docs`, `refactor`, `chore`, and `test`.

Explain why the change is needed, its final behavior, and the checks actually performed. State relevant limitations without copying raw logs. Add an issue-closing reference only after reading the issue and verifying its complete acceptance criteria.

## Push and Amend

Create the local commit first. Before any push, identify the repository, remote, target branches or tags, and outgoing commits; obtain explicit approval for that exact operation. A commit or PR request is not push permission.

Each approval authorizes one current push attempt and expires when that attempt is made. Ask again before retrying, changing the target or outgoing commits, pushing a submodule, or making any later push. Never reuse approval from an earlier turn, session, or repository. Do not initiate an unrelated push; retain the version-bump skill's restriction on proactively offering to push.

Amend only unpublished task-owned commits when authorized. Do not rewrite public history or force-push without separate explicit approval.

If ownership is unclear or a command fails, preserve the worktree and index, report the concrete blocker, and do not reset or delete user changes.
