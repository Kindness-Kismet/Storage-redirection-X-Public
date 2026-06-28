#pragma once

#include <type_traits>

namespace lsplant {
template <class, template <class, class...> class>
struct is_instance : public std::false_type {};

template <class... Ts, template <class, class...> class U>
struct is_instance<U<Ts...>, U> : public std::true_type {};

template <class T, template <class, class...> class U>
inline constexpr bool is_instance_v = is_instance<T, U>::value;

enum class Arch {
    kAArch64,
    kArm,
    kX86,
    kAmd64,
    kRiscv64,

    kLP64 = -64,
    kLP32 = -32,

    kUnknown = -1,

#if defined(__aarch64__)
    kCurrent = kAArch64,
#elif defined(__arm__)
    kCurrent = kArm,
#elif defined(__i386__)
    kCurrent = kX86,
#elif defined(__x86_64__)
    kCurrent = kAmd64,
#elif defined(__riscv) && __riscv_xlen == 64
    kCurrent = kRiscv64,
#else
    kCurrent = kUnknown,
#endif

#if defined(__LP64__)
    kLPCurrent = kLP64,
#else
    kLPCurrent = kLP32,
#endif
};

template <Arch...>
struct is_arch;

template <Arch kArch>
struct is_arch<kArch> : std::bool_constant<kArch == Arch::kCurrent || kArch == Arch::kLPCurrent> {};

template <Arch kFirst, Arch... kRest>
struct is_arch<kFirst, kRest...>
    : std::bool_constant<is_arch<kFirst>::value || is_arch<kRest...>::value> {};

template <Arch... kArchs>
inline constexpr bool is_arch_v = is_arch<kArchs...>::value;

static_assert(!is_arch_v<Arch::kUnknown>, "Unsupported architecture");
}  // namespace lsplant
