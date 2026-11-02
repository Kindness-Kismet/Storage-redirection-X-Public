---
name: update-dependencies
description: Check or upgrade Storage Redirect X Rust crates, Java/Kotlin/Android dependencies and build tools, and official LSPlant. Use for dependency audits or updates; normally exclude remote updates of the owned srx_hook and srx_inline_hook submodules.
---

# Dependency Updates

Follow the requested scope: an audit reports available updates without changing dependencies; an upgrade includes version changes, necessary compatibility work, and verification. Use authoritative registry metadata and upstream release notes, recording the lookup date and current/target versions. Do not infer that a locally cached version is the latest.

Default to the latest stable release. Preserve an existing prerelease channel when its stable successor is not available; never silently downgrade a newer prerelease to an older stable version. If the latest release is blocked by a real compatibility requirement, explain the blocker and report the retained version accurately. Do not hide warnings, suppress failures, or add speculative compatibility fallbacks to make an upgrade pass.

## Inventory and Boundaries

- Inspect the main worktree, staged changes, submodule gitlinks, manifests, lockfiles, version catalogs, and explicit dependency overrides before editing. Preserve unrelated work.
- Check the local state of `srx_core/third_party/srx_hook` and `srx_core/third_party/srx_inline_hook`, but normally skip their remote version and branch checks. Do not fetch or advance them unless explicitly requested or needed by the current task. Never run a blanket `git submodule update --remote --recursive`.
- Use the [commit skill](../commit/SKILL.md) for completed task-related local changes in either owned submodule, then record its commit in the parent. Unknown or unrelated edits must remain untouched.

## Rust

- Inventory workspace and package `Cargo.toml` files and the root `Cargo.lock`, including build and target-specific dependencies. Distinguish registry, Git, and path sources; do not convert a pinned local submodule back to a remote dependency.
- Check crates.io metadata for direct registry dependencies and changed transitive crates. Update manifest constraints when the latest release is outside them, and resolve the smallest coherent lockfile change. `cargo update` alone does not discover newer incompatible direct releases.
- Review minimum Rust versions, feature changes, target support, and release notes for breaking upgrades. Upgrade owned submodule registry dependencies only when included in the requested scope; this does not authorize checking their remote branches.
- Preserve the root workspace's `members = ["srx_*"]` and `exclude = ["srx_core/third_party"]`. Explicit member-directory prefixes can pull nested independent workspaces into the root despite exclusions. Confirm membership with Cargo metadata after integration changes.

## Java, Kotlin, and Android

- Inspect `android/gradle/libs.versions.toml`, Gradle plugins, the Compose BOM, wrapper properties, build scripts, and explicit version forces. Java Hook compilation also depends on the SDK/JDK selected by `scripts/build.py` and `srx_core/build.rs`.
- Query Google Maven, Maven Central, Gradle's release metadata, and official project release notes as appropriate. Check all artifacts sharing a version alias. BOM-managed artifacts should remain under BOM control unless a documented exception is still necessary.
- Coordinate AGP, Kotlin/Compose, Gradle, JDK, SDK, and NDK compatibility. Keep existing Android API and device support unless the upgrade requires a deliberate migration; do not raise app versions as part of a dependency update.
- Recheck temporary overrides against the resolved dependency graph. Remove them once the normal graph selects a fixed release; if a constraint remains necessary, make it narrow and explain the actual compatibility requirement.
- When upgrading the wrapper, regenerate its scripts and JAR with the wrapper task and verify the official distribution checksum. Updating only the URL leaves stale wrapper inputs.

## LSPlant

- Check `https://github.com/LSPosed/LSPlant.git`. This project tracks upstream source commits: inspect the latest official default-branch commit and relevant release notes, then pin the chosen exact gitlink. The commit belongs in the parent index, not in `.gitmodules`.
- Preserve upstream licenses and nested dependency pins. Initialize only the required build dependency, not the private upstream test submodules:

  ```bash
  git submodule update --init
  git -C srx_core/third_party/lsplant submodule update --init --recursive -- lsplant/src/main/jni/external/dex_builder
  ```

- Review native bridge API changes and `srx_core/native/cmake/cxx/cxxConfig.cmake`. Keep the official libcxx AAR, its SHA256, and the NDK/toolchain used locally and in CI compatible with the selected LSPlant revision. Update the related pins together when required.

## Verification and Commits

Use `scripts/build.py` so Android tools are discovered through the project workflow; do not commit machine-specific absolute paths. For Rust/native changes, build the supported `arm64-v8a` and `x86_64` modules and run strict clippy. For Android changes, build the APK and run `:app:lintDebug`. Inspect the resolved Cargo and Gradle graphs for incompatible duplicates and unexpected source changes. Follow the commit skill's clean-worktree Rust formatting phase.

Respect any user restriction on testing or device access. Distinguish metadata checks, compilation, static checks, and device results, and state omitted checks. Do not create new tests by default or install/flash anything merely to audit dependencies.

Commit each independent upgrade atomically using the commit skill. Keep a compatibility-coupled upgrade and its required manifest, lockfile, and integration changes together. Commit owned child repositories before parent gitlinks. Every push requires a separate one-use approval under `AGENTS.md`; creating or invoking this skill grants no publication permission.

Report the actual version changes, already-current dependencies, blocked upgrades, checks performed, and local commit IDs. Keep transient lookup data and logs under ignored `build/record/` paths.
