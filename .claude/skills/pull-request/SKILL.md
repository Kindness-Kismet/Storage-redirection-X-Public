---
name: pull-request
description: Prepare, review, describe, or create a pull request for Storage Redirect X. Use when the user requests a PR, pull request, PR description, or PR review. Creating or publishing a PR and pushing its branch require explicit authorization.
---

# Pull Requests

Keep one pull request focused on one independently reviewable topic. Follow `AGENTS.md` and apply the commit skill before creating or amending commits.

Every constituent commit must remain atomic under the commit skill. Do not combine independent purposes merely because they share a pull request, release, or user request.

## Scope and Branches

1. Confirm the repository, remote, current branch, base branch, and clean or task-owned worktree state. Default the base to `main` unless the user specifies another branch.
2. Compare `git log <base>..<head>` and `git diff <base>...<head>` to identify the complete outgoing scope, including existing commits. Do not describe only the latest commit when the branch contains earlier work.
3. Use `feature/<name>` or `fix/<name>` for a new topic branch when authorized. Never switch, merge, rebase, or delete branches solely to prepare a description.
4. If work was explicitly requested on `main`, report that state and ask for an authorized head branch before publishing a PR targeting `main`.

## Review

- Changes belong to the requested topic and live in the responsible modules.
- Maintained Rust files stay within 2400 lines; required source and resource paths are permitted by the Git allowlist.
- Rust ABI and Hook boundaries, locking, sampled logging, and error propagation follow project constraints.
- Kotlin file I/O and Root commands run on `Dispatchers.IO`; debug providers remain in `src/debug/`.
- There is no excessive defensive code, unsupported fallback, or silent failure. Do not add test files by default.
- Appropriate existing builds, clippy, lint, and runtime checks have actual recorded results. Distinguish execution evidence from source inspection and disclose unperformed checks.
- UI changes have screenshots when visual verification is part of acceptance; local evidence stays outside Git.
- License notices and necessary build, packaging, and CI inputs remain available. Local plans, progress records, logs, credentials, and generated artifacts are excluded.
- Issue-closing references are justified by the complete issue requirements, not by matching a title.

## Title and Description

Write the title and entire description in English. Use a concise imperative title describing the final outcome.

Start with the concrete problem and resulting behavior, then explain necessary implementation decisions, verification, and limitations. A small change needs only a short paragraph or two; use `Summary`, `Validation`, and `Limitations` sections for broader work. Explain why cross-layer changes are necessary.

Do not claim a check passed unless it ran successfully. Do not copy raw logs or include secrets, private device paths, or unrelated branch history.

## Publication

Preparing text does not authorize remote changes. Describe the exact remote, head, base, outgoing commits, and PR text before publishing under the user's authorization. Pushing still requires explicit approval under the commit skill.

After creation, report the PR URL, actual head and base, and any outstanding verification. Never force-push or merge the PR without separate explicit approval.
