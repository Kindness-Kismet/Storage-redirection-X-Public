#include <jni.h>
#include <srx_inline_hook.h>
#include <sys/mman.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>

#include "lsplant.hpp"

extern "C" void *srx_art_symbol_resolve(const char *name);
extern "C" void *srx_art_symbol_resolve_prefix(const char *prefix);

namespace {

std::mutex g_hook_mutex;
std::unordered_map<void *, void *> g_hook_stubs;

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
