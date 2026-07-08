#include <jni.h>
#include <srx_inline_hook.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <sys/mman.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>

#include "lsplant.hpp"

extern "C" void *srx_art_symbol_resolve(const char *name);
extern "C" void *srx_art_symbol_resolve_prefix(const char *prefix);
extern "C" bool
srx_should_force_fuse_userspace_private_owner_sqlite(const char *path);
extern "C" bool srx_prepare_fuse_private_owner_sqlite_sidecar(const char *path);

namespace {

std::mutex g_hook_mutex;
std::unordered_map<void *, void *> g_hook_stubs;

struct SrxFuseFileInfo {
  int flags;
  uint32_t bit_flags;
  uint32_t padding2;
  uint32_t align_fh;
  uint64_t fh;
  uint32_t passthrough_fh;
  uint32_t align_lock_owner;
  uint64_t lock_owner;
  uint32_t poll_events;
  int32_t backing_id;
};
static_assert(offsetof(SrxFuseFileInfo, fh) == 16);
static_assert(offsetof(SrxFuseFileInfo, passthrough_fh) == 24);
static_assert(offsetof(SrxFuseFileInfo, backing_id) == 44);

struct SrxMediaProviderHandle {
  int fd;
};

using FuseReplyOpenFn = int (*)(void *, const SrxFuseFileInfo *);
using FuseReplyCreateFn = int (*)(void *, const void *, const SrxFuseFileInfo *);
using FusePassthroughEnableFn = int (*)(void *, unsigned int);
using FusePassthroughOpenFn = int (*)(void *, int);

FuseReplyOpenFn g_orig_fuse_reply_open = nullptr;
FuseReplyCreateFn g_orig_fuse_reply_create = nullptr;
FusePassthroughEnableFn g_orig_fuse_passthrough_enable = nullptr;
FusePassthroughOpenFn g_orig_fuse_passthrough_open = nullptr;
void *g_fuse_fix_reply_open_stub = nullptr;
void *g_fuse_fix_reply_create_stub = nullptr;
void *g_fuse_fix_passthrough_enable_stub = nullptr;
void *g_fuse_fix_passthrough_open_stub = nullptr;

std::string ResolveFdPath(int fd) {
  if (fd < 0)
    return {};
  char link_path[64];
  snprintf(link_path, sizeof(link_path), "/proc/self/fd/%d", fd);
  char path[PATH_MAX + 1];
  ssize_t len = readlink(link_path, path, PATH_MAX);
  if (len <= 0)
    return {};
  path[len] = '\0';
  return std::string(path);
}

bool ShouldForceUserspacePrivateOwnerSqlite(const std::string &path) {
  if (path.empty())
    return false;
  return srx_should_force_fuse_userspace_private_owner_sqlite(path.c_str());
}

void PreparePrivateOwnerSqliteSidecar(const std::string &path) {
  if (path.empty())
    return;
  srx_prepare_fuse_private_owner_sqlite_sidecar(path.c_str());
}

bool ShouldDisableFusePassthroughForFd(int fd, const char *source) {
  std::string path = ResolveFdPath(fd);
  if (!ShouldForceUserspacePrivateOwnerSqlite(path))
    return false;
  PreparePrivateOwnerSqliteSidecar(path);
  __android_log_print(ANDROID_LOG_INFO, "StorageRedirect",
                      "[RsInfo] disable fuse passthrough source=%s fd=%d path=%s",
                      source == nullptr ? "unknown" : source, fd, path.c_str());
  return true;
}

int FdFromFuseFileInfo(const SrxFuseFileInfo *fi) {
  if (fi == nullptr || fi->fh == 0)
    return -1;
  auto handle = reinterpret_cast<const SrxMediaProviderHandle *>(
      static_cast<uintptr_t>(fi->fh));
  int fd = handle->fd;
  if (fd < 0)
    return -1;
  return fd;
}

bool ShouldClearFusePassthroughReply(const SrxFuseFileInfo *fi,
                                     const char *source) {
  if (fi == nullptr)
    return false;
  if (fi->passthrough_fh == 0 && fi->backing_id <= 0)
    return false;
  return ShouldDisableFusePassthroughForFd(FdFromFuseFileInfo(fi), source);
}

int SrxFuseFixReplyOpen(void *req, const SrxFuseFileInfo *fi) {
  if (g_orig_fuse_reply_open == nullptr)
    return -ENOSYS;
  if (ShouldClearFusePassthroughReply(fi, "reply_open")) {
    SrxFuseFileInfo clean = *fi;
    clean.passthrough_fh = 0;
    clean.backing_id = 0;
    return g_orig_fuse_reply_open(req, &clean);
  }
  return g_orig_fuse_reply_open(req, fi);
}

int SrxFuseFixReplyCreate(void *req, const void *entry,
                          const SrxFuseFileInfo *fi) {
  if (g_orig_fuse_reply_create == nullptr)
    return -ENOSYS;
  if (ShouldClearFusePassthroughReply(fi, "reply_create")) {
    SrxFuseFileInfo clean = *fi;
    clean.passthrough_fh = 0;
    clean.backing_id = 0;
    return g_orig_fuse_reply_create(req, entry, &clean);
  }
  return g_orig_fuse_reply_create(req, entry, fi);
}

int SrxFuseFixPassthroughEnable(void *req, unsigned int fd) {
  if (g_orig_fuse_passthrough_enable == nullptr)
    return -ENOSYS;
  if ((g_fuse_fix_reply_open_stub != nullptr ||
       g_fuse_fix_reply_create_stub != nullptr) &&
      ShouldDisableFusePassthroughForFd(static_cast<int>(fd),
                                        "passthrough_enable")) {
    return 1;
  }
  return g_orig_fuse_passthrough_enable(req, fd);
}

int SrxFuseFixPassthroughOpen(void *req, int fd) {
  if (g_orig_fuse_passthrough_open == nullptr)
    return -ENOSYS;
  if ((g_fuse_fix_reply_open_stub != nullptr ||
       g_fuse_fix_reply_create_stub != nullptr) &&
      ShouldDisableFusePassthroughForFd(fd, "passthrough_open")) {
    return 1;
  }
  return g_orig_fuse_passthrough_open(req, fd);
}

bool InstallFuseFixHook(void *target, void *replacement, void **orig,
                        void **stub_slot) {
  if (target == nullptr || replacement == nullptr || orig == nullptr ||
      stub_slot == nullptr)
    return false;
  if (*stub_slot != nullptr)
    return true;
  *stub_slot = srx_inline_hook_hook_func_addr(target, replacement, orig);
  return *stub_slot != nullptr && *orig != nullptr;
}

bool PatchFuseFixPltSlot(void *slot, void *replacement, void **orig,
                         void **stub_slot) {
  if (slot == nullptr || replacement == nullptr || orig == nullptr ||
      stub_slot == nullptr) {
    return false;
  }
  if (*stub_slot != nullptr) {
    return true;
  }

  auto slot_ptr = reinterpret_cast<void **>(slot);
  void *current = *slot_ptr;
  if (current == nullptr || current == replacement) {
    return false;
  }

  long page_size = sysconf(_SC_PAGESIZE);
  if (page_size <= 0) {
    return false;
  }
  uintptr_t page_start = reinterpret_cast<uintptr_t>(slot_ptr) &
                         ~(static_cast<uintptr_t>(page_size) - 1);
  if (mprotect(reinterpret_cast<void *>(page_start),
               static_cast<size_t>(page_size), PROT_READ | PROT_WRITE) != 0) {
    __android_log_print(
        ANDROID_LOG_WARN, "StorageRedirect",
        "[RsWarn] fuse fix plt mprotect rw failed slot=%p errno=%d", slot,
        errno);
    return false;
  }

  *orig = current;
  *slot_ptr = replacement;
  if (mprotect(reinterpret_cast<void *>(page_start),
               static_cast<size_t>(page_size), PROT_READ) != 0) {
    __android_log_print(
        ANDROID_LOG_WARN, "StorageRedirect",
        "[RsWarn] fuse fix plt mprotect ro failed slot=%p errno=%d", slot,
        errno);
  }
  *stub_slot = slot;
  return true;
}

void *ResolveDefaultSymbol(const char *name) {
  if (name == nullptr || name[0] == '\0')
    return nullptr;
  return dlsym(RTLD_DEFAULT, name);
}

int SrxFuseFixInstall(void *reply_open_slot, void *reply_create_slot,
                      void *passthrough_enable_slot,
                      void *passthrough_open_slot) {
  if (srx_inline_hook_init(SRX_INLINE_HOOK_MODE_UNIQUE, false) != 0)
    return -1;

  std::lock_guard lock(g_hook_mutex);
  int installed = 0;
  if (InstallFuseFixHook(ResolveDefaultSymbol("fuse_reply_open"),
                         reinterpret_cast<void *>(SrxFuseFixReplyOpen),
                         reinterpret_cast<void **>(&g_orig_fuse_reply_open),
                         &g_fuse_fix_reply_open_stub) ||
      PatchFuseFixPltSlot(reply_open_slot,
                          reinterpret_cast<void *>(SrxFuseFixReplyOpen),
                          reinterpret_cast<void **>(&g_orig_fuse_reply_open),
                          &g_fuse_fix_reply_open_stub)) {
    installed++;
  }
  if (InstallFuseFixHook(ResolveDefaultSymbol("fuse_reply_create"),
                         reinterpret_cast<void *>(SrxFuseFixReplyCreate),
                         reinterpret_cast<void **>(&g_orig_fuse_reply_create),
                         &g_fuse_fix_reply_create_stub) ||
      PatchFuseFixPltSlot(reply_create_slot,
                          reinterpret_cast<void *>(SrxFuseFixReplyCreate),
                          reinterpret_cast<void **>(&g_orig_fuse_reply_create),
                          &g_fuse_fix_reply_create_stub)) {
    installed++;
  }
  if (InstallFuseFixHook(
          ResolveDefaultSymbol("fuse_passthrough_enable"),
          reinterpret_cast<void *>(SrxFuseFixPassthroughEnable),
          reinterpret_cast<void **>(&g_orig_fuse_passthrough_enable),
          &g_fuse_fix_passthrough_enable_stub) ||
      PatchFuseFixPltSlot(
          passthrough_enable_slot,
          reinterpret_cast<void *>(SrxFuseFixPassthroughEnable),
          reinterpret_cast<void **>(&g_orig_fuse_passthrough_enable),
          &g_fuse_fix_passthrough_enable_stub)) {
    installed++;
  }
  if (InstallFuseFixHook(
          ResolveDefaultSymbol("fuse_passthrough_open"),
          reinterpret_cast<void *>(SrxFuseFixPassthroughOpen),
          reinterpret_cast<void **>(&g_orig_fuse_passthrough_open),
          &g_fuse_fix_passthrough_open_stub) ||
      PatchFuseFixPltSlot(
          passthrough_open_slot,
          reinterpret_cast<void *>(SrxFuseFixPassthroughOpen),
          reinterpret_cast<void **>(&g_orig_fuse_passthrough_open),
          &g_fuse_fix_passthrough_open_stub)) {
    installed++;
  }
  __android_log_print(
      ANDROID_LOG_INFO, "StorageRedirect",
      "[RsInfo] fuse fix native hooks installed=%d reply_open=%d reply_create=%d passthrough_enable=%d passthrough_open=%d",
      installed, g_fuse_fix_reply_open_stub != nullptr,
      g_fuse_fix_reply_create_stub != nullptr,
      g_fuse_fix_passthrough_enable_stub != nullptr,
      g_fuse_fix_passthrough_open_stub != nullptr);
  return installed;
}

void *InlineHooker(void *target, void *hooker) {
  void *origin = nullptr;
  void *stub = srx_inline_hook_hook_func_addr(target, hooker, &origin);
  if (stub == nullptr || origin == nullptr)
    return nullptr;
  std::lock_guard lock(g_hook_mutex);
  g_hook_stubs[target] = stub;
  return origin;
}

bool InlineUnhooker(void *func) {
  if (func == nullptr)
    return false;
  void *stub = nullptr;
  {
    std::lock_guard lock(g_hook_mutex);
    auto it = g_hook_stubs.find(func);
    if (it == g_hook_stubs.end())
      return false;
    stub = it->second;
    g_hook_stubs.erase(it);
  }
  return srx_inline_hook_unhook(stub) == 0;
}

void *ResolveArtSymbol(std::string_view name) {
  if (name.empty())
    return nullptr;
  return srx_art_symbol_resolve(std::string{name}.c_str());
}

void *ResolveArtSymbolPrefix(std::string_view prefix) {
  if (prefix.empty())
    return nullptr;
  return srx_art_symbol_resolve_prefix(std::string{prefix}.c_str());
}

} // namespace

extern "C" int srx_fuse_fix_install(void *reply_open_slot,
                                      void *reply_create_slot,
                                      void *passthrough_enable_slot,
                                      void *passthrough_open_slot) {
  return SrxFuseFixInstall(reply_open_slot, reply_create_slot,
                           passthrough_enable_slot, passthrough_open_slot);
}

extern "C" bool srx_lsplant_init(JNIEnv *env) {
  if (env == nullptr)
    return false;
  if (srx_inline_hook_init(SRX_INLINE_HOOK_MODE_UNIQUE, false) != 0)
    return false;

  lsplant::InitInfo info{
      .inline_hooker = InlineHooker,
      .inline_unhooker = InlineUnhooker,
      .art_symbol_resolver = ResolveArtSymbol,
      .art_symbol_prefix_resolver = ResolveArtSymbolPrefix,
      .generated_class_name = "SrxHooker_",
      .generated_source_name = "SRX",
      .generated_field_name = "hooker",
      .generated_method_name = "{target}",
  };
  return lsplant::Init(env, info);
}

extern "C" jobject srx_lsplant_hook(JNIEnv *env, jobject target_method,
                                    jobject hooker_object,
                                    jobject callback_method) {
  if (env == nullptr || target_method == nullptr || hooker_object == nullptr ||
      callback_method == nullptr) {
    return nullptr;
  }
  return lsplant::Hook(env, target_method, hooker_object, callback_method);
}

extern "C" bool srx_lsplant_unhook(JNIEnv *env, jobject target_method) {
  if (env == nullptr || target_method == nullptr)
    return false;
  return lsplant::UnHook(env, target_method);
}
