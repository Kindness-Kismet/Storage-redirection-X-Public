/* DexBuilder
 * Copyright (C) 2021 LSPosed
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * Copyright (C) 2018 The Android Open Source Project
 * Modifications copyright (C) 2021 LSPosed Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef DEX_BUILDER_H_
#define DEX_BUILDER_H_

#include <array>
#include <forward_list>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <parallel_hashmap/phmap.h>
#include <vector>

#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "slicer/writer.h"

namespace startop {
namespace dex {

//////////////////////////
// Forward declarations //
//////////////////////////
class DexBuilder;

// Our custom allocator for dex::Writer
//
// This keeps track of all allocations and ensures they are freed when
// TrackingAllocator is destroyed. Pointers to memory allocated by this
// allocator must not outlive the allocator.
class TrackingAllocator : public ::dex::Writer::Allocator {
public:
  virtual void *Allocate(size_t size);
  virtual void Free(void *ptr);

private:
  phmap::flat_hash_map<void *, std::unique_ptr<uint8_t[]>> allocations_;
};

// Represents a DEX type descriptor.
//
// TODO: add a way to create a descriptor for a reference of a class type.
class TypeDescriptor {
public:
  // Well known class
  static const TypeDescriptor Int;
  static const TypeDescriptor Void;
  static const TypeDescriptor Boolean;
  static const TypeDescriptor Byte;
  static const TypeDescriptor Char;
  static const TypeDescriptor Double;
  static const TypeDescriptor Float;
  static const TypeDescriptor Long;
  static const TypeDescriptor Short;

  static const TypeDescriptor Object;
  static const TypeDescriptor String;
  static const TypeDescriptor ObjectInt;
  static const TypeDescriptor ObjectBoolean;
  static const TypeDescriptor ObjectByte;
  static const TypeDescriptor ObjectChar;
  static const TypeDescriptor ObjectDouble;
  static const TypeDescriptor ObjectFloat;
  static const TypeDescriptor ObjectLong;
  static const TypeDescriptor ObjectShort;

  // Creates a type descriptor from a fully-qualified class name. For example,
  // it turns the class name java.lang.Object into the descriptor
  // Ljava/lang/Object.
  static TypeDescriptor FromClassname(const std::string &name);

  static TypeDescriptor FromDescriptor(const std::string &descriptor);

  static TypeDescriptor FromDescriptor(const char descriptor);

  TypeDescriptor ToArray() const { return TypeDescriptor{"[" + descriptor_}; }

  TypeDescriptor ToBoxType() const;

  TypeDescriptor ToUnBoxType() const;

  // Return the full descriptor, such as I or Ljava/lang/Object
  const std::string &descriptor() const { return descriptor_; }
  // Return the shorty descriptor, such as I or L
  char short_descriptor() const;

  bool is_object() const { return descriptor_[0] == 'L'; }

  bool is_array() const { return descriptor_[0] == '['; }

  bool is_primitive() const { return !is_object() && !is_array(); }

  bool is_wide() const { return wide_; }

  bool operator<(const TypeDescriptor &rhs) const {
    return descriptor_ < rhs.descriptor_;
  }

  bool operator==(const TypeDescriptor &rhs) const {
    return descriptor_ == rhs.descriptor_;
  }

  friend struct std::hash<TypeDescriptor>;

private:
  static const phmap::flat_hash_map<TypeDescriptor, TypeDescriptor> unbox_map;

  explicit TypeDescriptor(std::string descriptor, bool wide = false)
      : descriptor_{descriptor}, wide_(wide) {}

  const std::string descriptor_;
  const bool wide_;
};

// Defines a function signature. For example, Prototype{TypeDescriptor::VOID,
// TypeDescriptor::Int} represents the function type (Int) -> Void.
class Prototype {
public:
  template <typename... TypeDescriptors>
  explicit Prototype(const TypeDescriptor &return_type,
                     TypeDescriptors &&...param_types)
      : return_type_{return_type}, param_types_{std::forward<TypeDescriptors>(
                                       param_types)...} {}

  explicit Prototype(const TypeDescriptor &return_type,
                     const std::vector<TypeDescriptor> &param_types)
      : return_type_{return_type}, param_types_{param_types} {}

  // Encode this prototype into the dex file.
  ir::Proto *Encode(DexBuilder *dex) const;

  // Get the shorty descriptor, such as VII for (Int, Int) -> Void
  std::string Shorty() const;

  const TypeDescriptor &ArgType(size_t index) const;

  const TypeDescriptor &ReturnType() const { return return_type_; }

  bool operator<(const Prototype &rhs) const {
    return std::make_tuple(return_type_, param_types_) <
           std::make_tuple(rhs.return_type_, rhs.param_types_);
  }

private:
  const TypeDescriptor return_type_;
  const std::vector<TypeDescriptor> param_types_;
};

// Represents a DEX register or constant. We separate regular registers and
// parameters because we will not know the real parameter id until after all
// instructions have been generated.
class Value {
public:
  static constexpr Value Local(size_t id) {
    return Value{id, Kind::kLocalRegister};
  }
  static constexpr Value Parameter(size_t id) {
    return Value{id, Kind::kParameter};
  }
  static constexpr Value Immediate(size_t value) {
    return Value{value, Kind::kImmediate};
  }
  static constexpr Value String(size_t value) {
    return Value{value, Kind::kString};
  }
  static constexpr Value Label(size_t id) { return Value{id, Kind::kLabel}; }
  static constexpr Value Type(size_t id) { return Value{id, Kind::kType}; }

  bool is_register() const { return kind_ == Kind::kLocalRegister; }
  bool is_parameter() const { return kind_ == Kind::kParameter; }
  bool is_variable() const { return is_register() || is_parameter(); }
  bool is_immediate() const { return kind_ == Kind::kImmediate; }
  bool is_string() const { return kind_ == Kind::kString; }
  bool is_label() const { return kind_ == Kind::kLabel; }
  bool is_type() const { return kind_ == Kind::kType; }

  size_t value() const { return value_; }

  constexpr Value() : value_{0}, kind_{Kind::kInvalid} {}

  bool operator==(const Value &rhs) const {
    return value_ == rhs.value_ && kind_ == rhs.kind_;
  }
  bool operator!=(const Value &rhs) const { return !(*this == rhs); }

  Value WidePair() const {
    assert(kind_ == Kind::kLocalRegister || kind_ == Kind::kParameter);
    return Value{value_ + 1, kind_};
  }

private:
  enum class Kind {
    kInvalid,
    kLocalRegister,
    kParameter,
    kImmediate,
    kString,
    kLabel,
    kType
  };

  size_t value_;
  Kind kind_;

  constexpr Value(size_t value, Kind kind) : value_{value}, kind_{kind} {}
};

// Represents an allocated register returned by MethodBuilder::AllocRegister
class LiveRegister {
  friend class MethodBuilder;

public:
  LiveRegister(LiveRegister &&other)
      : liveness_{other.liveness_}, index_{other.index_} {
    other.index_ = {};
  };
  ~LiveRegister() {
    if (index_.has_value()) {
      (*liveness_)[*index_] = false;
    }
  };

  operator const Value() const { return Value::Local(*index_); }

private:
  LiveRegister(std::vector<bool> *liveness, size_t index)
      : liveness_{liveness}, index_{index} {}

  std::vector<bool> *const liveness_;
  std::optional<size_t> index_;
};

// A virtual instruction. We convert these to real instructions in
// MethodBuilder::Encode. Virtual instructions are needed to keep track of
// information that is not known until all of the code is generated. This
// information includes things like how many local registers are created and
// branch target locations.
class Instruction {
public:
  // The operation performed by this instruction. These are virtual instructions
  // that do not correspond exactly to DEX instructions.
  enum class Op {
    kBindLabel,
    kBranchEqz,
    kBranchNEqz,
    kCheckCast,
    kGetInstanceField,
    kGetStaticField,
    kGetStaticObjectField,
    kInvokeDirect,
    kInvokeInterface,
    kInvokeStatic,
    kInvokeVirtual,
    kInvokeDirectRange,
    kInvokeInterfaceRange,
    kInvokeStaticRange,
    kInvokeVirtualRange,
    kMove,
    kMoveObject,
    kMoveWide,
    kNew,
    kNewArray,
    kReturn,
    kReturnObject,
    kReturnWide,
    kSetInstanceField,
    kSetStaticField,
    kSetStaticObjectField,
    kAputObject,
  };

  ////////////////////////
  // Named Constructors //
  ////////////////////////

  // For instructions with no return value and no arguments.
  static inline Instruction OpNoArgs(Op opcode) {
    return Instruction{opcode, /*index_argument*/ 0, /*dest*/ {}};
  }
  // For most instructions, which take some number of arguments and have an
  // optional return value.
  template <typename... T>
  static inline Instruction
  OpWithArgs(Op opcode, std::optional<const Value> dest, const T &...args) {
    return Instruction{
        opcode, /*index_argument=*/0, /*result_is_object=*/false, false, dest,
        args...};
  }

  template <typename... T>
  static inline Instruction
  OpWithArgsWide(Op opcode, std::optional<const Value> dest, const T &...args) {
    return Instruction{
        opcode, /*index_argument=*/0, /*result_is_object=*/false, true, dest,
        args...};
  }

  // A cast instruction. Basically, `(type)val`
  static inline Instruction Cast(Value val, Value type) {
    assert(type.is_type());
    return OpWithArgs(Op::kCheckCast, val, type);
  }

  // For method calls.
  template <typename... T>
  static inline Instruction InvokeVirtual(size_t index_argument,
                                          std::optional<const Value> dest,
                                          Value this_arg, T... args) {
    return Instruction{
        Op::kInvokeVirtual,         index_argument,
        /*result_is_object=*/false, false,          dest, this_arg, args...};
  }
  template <typename... T>
  static inline Instruction InvokeVirtualWide(size_t index_argument,
                                              std::optional<const Value> dest,
                                              Value this_arg, T... args) {
    return Instruction{
        Op::kInvokeVirtual,         index_argument,
        /*result_is_object=*/false, true,           dest, this_arg, args...};
  }
  // Returns an object
  template <typename... T>
  static inline Instruction
  InvokeVirtualObject(size_t index_argument, std::optional<const Value> dest,
                      Value this_arg, const T &...args) {
    return Instruction{
        Op::kInvokeVirtual,        index_argument,
        /*result_is_object=*/true, false,          dest, this_arg, args...};
  }
  // For direct calls (basically, constructors).
  template <typename... T>
  static inline Instruction InvokeDirect(size_t index_argument,
                                         std::optional<const Value> dest,
                                         Value this_arg, const T &...args) {
    return Instruction{
        Op::kInvokeDirect,          index_argument,
        /*result_is_object=*/false, false,          dest, this_arg, args...};
  }
  // Returns an object
  template <typename... T>
  static inline Instruction
  InvokeDirectObject(size_t index_argument, std::optional<const Value> dest,
                     Value this_arg, const T &...args) {
    return Instruction{
        Op::kInvokeDirect,         index_argument,
        /*result_is_object=*/true, false,          dest, this_arg, args...};
  }
  // For static calls.
  template <typename... T>
  static inline Instruction InvokeStatic(size_t index_argument,
                                         std::optional<const Value> dest,
                                         const T &...args) {
    return Instruction{
        Op::kInvokeStatic,          index_argument,
        /*result_is_object=*/false, false,          dest, args...};
  }
  template <typename... T>
  static inline Instruction InvokeStaticWide(size_t index_argument,
                                             std::optional<const Value> dest,
                                             const T &...args) {
    return Instruction{
        Op::kInvokeStatic,          index_argument,
        /*result_is_object=*/false, true,           dest, args...};
  }
  static inline Instruction InvokeStaticRange(size_t index_argument,
                                              std::optional<const Value> dest,
                                              const Value &first,
                                              size_t length) {
    return Instruction{Op::kInvokeStaticRange,     index_argument,
                       /*result_is_object=*/false, false,          dest, first,
                       Value::Immediate(length)};
  }
  static inline Instruction
  InvokeStaticRangeWide(size_t index_argument, std::optional<const Value> dest,
                        const Value &first, size_t length) {
    return Instruction{Op::kInvokeStaticRange,     index_argument,
                       /*result_is_object=*/false, true,           dest, first,
                       Value::Immediate(length)};
  }
  // Returns an object
  template <typename... T>
  static inline Instruction InvokeStaticObject(size_t index_argument,
                                               std::optional<const Value> dest,
                                               const T &...args) {
    return Instruction{
        Op::kInvokeStatic,         index_argument,
        /*result_is_object=*/true, false,          dest, args...};
  }
  // Returns an object
  template <typename... T>
  static inline Instruction
  InvokeStaticObjectRange(size_t index_argument,
                          std::optional<const Value> dest, const Value &first, size_t length) {
    return Instruction{
        Op::kInvokeStaticRange,    index_argument,
        /*result_is_object=*/true, false,          dest, first, Value::Immediate(length)};
  }
  // For static calls.
  template <typename... T>
  static inline Instruction InvokeInterface(size_t index_argument,
                                            std::optional<const Value> dest,
                                            const T &...args) {
    return Instruction{Op::kInvokeInterface, index_argument,
                       /*result_is_object=*/false, dest, args...};
  }

  static inline Instruction GetStaticField(size_t field_id, const Value &dest) {
    return Instruction{Op::kGetStaticField, field_id, dest};
  }

  static inline Instruction GetStaticObjectField(size_t field_id,
                                                 const Value &dest) {
    return Instruction{Op::kGetStaticObjectField, field_id, dest};
  }

  static inline Instruction GetStaticField(size_t field_id, const Value &dest,
                                           bool result_is_wide) {
    return Instruction{Op::kGetStaticField, field_id, false, result_is_wide,
                       dest};
  }

  static inline Instruction SetStaticField(size_t field_id,
                                           const Value &value) {
    return Instruction{
        Op::kSetStaticField,        field_id,
        /*result_is_object=*/false, false,    /*dest=*/{}, value};
  }

  static inline Instruction SetStaticObjectField(size_t field_id,
                                                 const Value &value) {
    return Instruction{
        Op::kSetStaticObjectField,  field_id,
        /*result_is_object=*/false, false,    /*dest=*/{}, value};
  }

  static inline Instruction SetStaticField(size_t field_id, const Value &value,
                                           bool result_is_wide) {
    return Instruction{
        Op::kSetStaticField,        field_id,
        /*result_is_object=*/false, result_is_wide, /*dest=*/{}, value};
  }

  static inline Instruction GetField(size_t field_id, const Value &dest,
                                     const Value &object) {
    return Instruction{Op::kGetInstanceField,      field_id,
                       /*result_is_object=*/false, false,    dest, object};
  }

  static inline Instruction GetField(size_t field_id, const Value &dest,
                                     const Value &object, bool result_is_wide) {
    return Instruction{
        Op::kGetInstanceField,      field_id,
        /*result_is_object=*/false, result_is_wide, dest, object};
  }

  static inline Instruction SetField(size_t field_id, const Value &object,
                                     const Value &value) {
    return Instruction{Op::kSetInstanceField,
                       field_id,
                       /*result_is_object=*/false,
                       false,
                       /*dest=*/{},
                       object,
                       value};
  }

  static inline Instruction SetField(size_t field_id, const Value &object,
                                     const Value &value, bool result_is_wide) {
    return Instruction{
        Op::kSetInstanceField, field_id, /*result_is_object=*/false,
        result_is_wide,
        /*dest=*/{},           object,   value};
  }

  ///////////////
  // Accessors //
  ///////////////

  Op opcode() const { return opcode_; }
  size_t index_argument() const { return index_argument_; }
  bool result_is_object() const { return result_is_object_; }
  bool result_is_wide() const { return result_is_wide_; }
  const std::optional<const Value> &dest() const { return dest_; }
  const std::vector<Value> &args() const { return args_; }

private:
  inline Instruction(Op opcode, size_t index_argument,
                     std::optional<const Value> dest)
      : opcode_{opcode}, index_argument_{index_argument},
        result_is_object_{false}, result_is_wide_(false), dest_{dest}, args_{} {
  }

  template <typename... T>
  inline Instruction(Op opcode, size_t index_argument, bool result_is_object,
                     bool result_is_wide, std::optional<const Value> dest,
                     const T &...args)
      : opcode_{opcode}, index_argument_{index_argument},
        result_is_object_{result_is_object},
        result_is_wide_(result_is_wide), dest_{dest}, args_{args...} {}

  const Op opcode_;
  // The index of the method to invoke, for kInvokeVirtual and similar opcodes.
  const size_t index_argument_{0};
  const bool result_is_object_;
  const bool result_is_wide_;
  const std::optional<const Value> dest_;
  const std::vector<Value> args_;
};

// Needed for CHECK_EQ, DCHECK_EQ, etc.
std::ostream &operator<<(std::ostream &out, const Instruction::Op &opcode);

// Keeps track of information needed to manipulate or call a method.
struct MethodDeclData {
  size_t id;
  ir::MethodDecl *decl;
};

class MethodBuilder;
class FieldBuilder;
// A helper to build class definitions.
class ClassBuilder {
public:
  ClassBuilder(DexBuilder *parent, const std::string &name,
               ir::Class *class_def);

  void set_source_file(const std::string &source);

  // Create a method with the given name and prototype. The returned
  // MethodBuilder can be used to fill in the method body.
  MethodBuilder CreateMethod(const std::string &name,
                             const Prototype &prototype);

  FieldBuilder CreateField(const std::string &name, const TypeDescriptor &type);

  ClassBuilder setSuperClass(const TypeDescriptor &type);

  DexBuilder *parent() const { return parent_; }

  const TypeDescriptor &descriptor() const { return type_descriptor_; }

private:
  static const phmap::flat_hash_map<TypeDescriptor, std::string> value_method_map;

  DexBuilder *const parent_;
  const TypeDescriptor type_descriptor_;
  ir::Class *const class_;
};

class FieldBuilder {
public:
  FieldBuilder(ClassBuilder *parent, ir::Class *class_def, ir::FieldDecl *decl);
  ir::EncodedField *Encode();

  ClassBuilder *parent() const { return parent_; }
  DexBuilder *dex_file() const { return parent_->parent(); }

  ::dex::u4 access_flags() const { return access_flags_; }
  FieldBuilder &access_flags(const ::dex::u4 &access_flags) {
    access_flags_ = access_flags;
    return *this;
  }

private:
  ClassBuilder *parent_;
  ir::Class *class_;
  ir::FieldDecl *decl_;
  ::dex::u4 access_flags_ = ::dex::kAccPublic | ::dex::kAccStatic;
  ;
};

// Tools to help build methods and their bodies.
class MethodBuilder {
public:
  MethodBuilder(ClassBuilder *parent, ir::Class *class_def,
                ir::MethodDecl *decl);

  // Encode the method into DEX format.
  ir::EncodedMethod *Encode();

  // Create a new register to be used to storing values.
  LiveRegister AllocRegister();

  Value MakeLabel();

  /////////////////////////////////
  // Instruction builder methods //
  /////////////////////////////////

  MethodBuilder &AddInstruction(Instruction instruction);

  // return-void
  MethodBuilder &BuildReturn();
  MethodBuilder &BuildReturn(const Value &src, bool is_object = false,
                             bool is_wide = false);
  // const/4
  MethodBuilder &BuildConst(const Value &target, int value);
  MethodBuilder &BuildConstWide(const Value &target, int value);
  MethodBuilder &BuildConstString(const Value &target,
                                  const std::string &value);
  template <typename... T>
  MethodBuilder &BuildNew(const Value &target, const TypeDescriptor &type,
                          const Prototype &constructor, const T &...args);
  MethodBuilder &BuildNewArray(const Value &target,
                               const TypeDescriptor &base_type,
                               const Value &size);
  MethodBuilder &BuildAput(Instruction::Op opcode, const Value &target_array,
                           const Value &value, const Value &index);
  MethodBuilder &BuildBoxIfPrimitive(const Value &target,
                                     const TypeDescriptor &type,
                                     const Value &src);
  MethodBuilder &BuildUnBoxIfPrimitive(const Value &target,
                                       const TypeDescriptor &type,
                                       const Value &src);

  // TODO: add builders for more instructions

  DexBuilder *dex_file() const { return parent_->parent(); }
  ClassBuilder *parent() const { return parent_; }

  ::dex::u4 access_flags() const { return access_flags_; }
  MethodBuilder &access_flags(const ::dex::u4 &access_flags) {
    access_flags_ = access_flags;
    return *this;
  }

  // Converts a register or parameter to its DEX register number.
  size_t RegisterValue(const Value &value) const;

private:
  using Op = Instruction::Op;

  void EncodeInstructions();
  void EncodeInstruction(const Instruction &instruction);

  // Encodes a return instruction. For instructions with no return value, the
  // opcode field is ignored. Otherwise, this specifies which return instruction
  // will be used (return, return-object, etc.)
  void EncodeReturn(const Instruction &instruction, ::dex::Opcode opcode);

  void EncodeMove(const Instruction &instruction);
  void EncodeInvoke(const Instruction &instruction, ::dex::Opcode opcode);
  void EncodeInvokeRange(const Instruction &instruction, ::dex::Opcode opcode);
  void EncodeBranch(::dex::Opcode op, const Instruction &instruction);
  void EncodeNew(const Instruction &instruction);
  void EncodeCast(const Instruction &instruction);
  void EncodeFieldOp(const Instruction &instruction);
  void EncodeNewArray(const Instruction &instruction);
  void EncodeAput(const Instruction &instruction);

  // Low-level instruction format encoding. See
  // https://source.android.com/devices/tech/dalvik/instruction-formats for
  // documentation of formats.

  inline uint8_t ToBits(::dex::Opcode opcode) {
    static_assert(sizeof(uint8_t) == sizeof(::dex::Opcode));
    return static_cast<uint8_t>(opcode);
  }

  inline void Encode10x(::dex::Opcode opcode) {
    // 00|op
    static_assert(sizeof(uint8_t) == sizeof(::dex::Opcode));
    buffer_.push_back(ToBits(opcode));
  }

  inline void Encode11x(::dex::Opcode opcode, uint8_t a) {
    // aa|op
    buffer_.push_back((a << 8) | ToBits(opcode));
  }

  inline void Encode11n(::dex::Opcode opcode, uint8_t a, int8_t b) {
    // b|a|op

    // Make sure the fields are in bounds (4 bits for a, 4 bits for b).
    assert(a < 16);
    assert(-8 < b);
    assert(b < 8);

    buffer_.push_back(((b & 0xf) << 12) | (a << 8) | ToBits(opcode));
  }

  inline void Encode21c(::dex::Opcode opcode, uint8_t a, uint16_t b) {
    // aa|op|bbbb
    buffer_.push_back((a << 8) | ToBits(opcode));
    buffer_.push_back(b);
  }

  inline void Encode22c(::dex::Opcode opcode, uint8_t a, uint8_t b,
                        uint16_t c) {
    // b|a|op|bbbb
    assert(IsShortRegister(a));
    assert(IsShortRegister(b));
    buffer_.push_back((b << 12) | (a << 8) | ToBits(opcode));
    buffer_.push_back(c);
  }

  inline void Encode21s(::dex::Opcode opcode, uint8_t a, uint16_t b) {
    assert(b < 32768);
    Encode21c(opcode, a, b);
  }

  inline void Encode23x(::dex::Opcode opcode, uint8_t a, uint8_t b, uint8_t c) {
    // AA|op|CC|BB
    buffer_.push_back((a << 8) | ToBits(opcode));
    buffer_.push_back((c << 8) | b);
  }

  inline void Encode32x(::dex::Opcode opcode, uint16_t a, uint16_t b) {
    // ØØ|op|AAAA|BBBB
    buffer_.push_back(ToBits(opcode));
    buffer_.push_back(a);
    buffer_.push_back(b);
  }

  inline void Encode31i(::dex::Opcode opcode, uint8_t a, uint32_t b) {
    // AA|op|BBBBlo|BBBBhi
    buffer_.push_back((a << 8) | ToBits(opcode));
    // FIXME: it may be wrong
    buffer_.push_back((uint16_t)b);
    buffer_.push_back(b >> 16);
  }

  inline void Encode35c(::dex::Opcode opcode, size_t a, uint16_t b, uint8_t c,
                        uint8_t d, uint8_t e, uint8_t f, uint8_t g) {
    // a|g|op|bbbb|f|e|d|c

    assert(a < 5);
    assert(IsShortRegister(c));
    assert(IsShortRegister(d));
    assert(IsShortRegister(e));
    assert(IsShortRegister(f));
    assert(IsShortRegister(g));
    buffer_.push_back((a << 12) | (g << 8) | ToBits(opcode));
    buffer_.push_back(b);
    buffer_.push_back((f << 12) | (e << 8) | (d << 4) | c);
  }

  inline void Encode3rc(::dex::Opcode opcode, size_t a, uint16_t b,
                        uint16_t c) {
    assert(a < 255);
    buffer_.push_back((a << 8) | ToBits(opcode));
    buffer_.push_back(b);
    buffer_.push_back(c);
  }

  static constexpr bool IsShortRegister(size_t register_value) {
    return register_value < 16;
  }

  // Returns an array of num_regs scratch registers. These are guaranteed to be
  // contiguous, so they are suitable for the invoke-*/range instructions.
  template <int num_regs>
  std::array<Value, num_regs> GetScratchRegisters() const {
    // static_assert(num_regs <= kMaxScratchRegisters);
    std::array<Value, num_regs> regs;
    for (size_t i = 0; i < num_regs; ++i) {
      regs[i] = std::move(Value::Local(NumRegisters() + i));
    }
    return regs;
  }

  // Sets a label's address to the current position in the instruction buffer.
  // If there are any forward references to the label, this function will
  // back-patch them.
  void BindLabel(const Value &label);

  // Returns the offset of the label relative to the given instruction offset.
  // If the label is not bound, a reference will be saved and it will
  // automatically be patched when the label is bound.
  ::dex::u2 LabelValue(const Value &label, size_t instruction_offset,
                       size_t field_offset);

  ClassBuilder *parent_;
  ir::Class *class_;
  ir::MethodDecl *decl_;

  // A list of the instructions we will eventually encode.
  std::vector<Instruction> instructions_;

  // A buffer to hold instructions that have been encoded.
  std::vector<::dex::u2> buffer_;

  // We create some scratch registers for when we have to shuffle registers
  // around to make legal DEX code.
  // TODO: calculate it dynamically?
  static constexpr size_t kMaxScratchRegisters = 0;

  size_t NumRegisters() const { return register_liveness_.size(); }

  // Stores information needed to back-patch a label once it is bound. We need
  // to know the start of the instruction that refers to the label, and the
  // offset to where the actual label value should go.
  struct LabelReference {
    size_t instruction_offset;
    size_t field_offset;
  };

  struct LabelData {
    std::optional<size_t> bound_address;
    std::forward_list<LabelReference> references;
  };

  std::vector<LabelData> labels_;

  // During encoding, keep track of the largest number of arguments needed, so
  // we can use it for our outs count
  size_t max_args_{0};

  std::vector<bool> register_liveness_;

  ::dex::u4 access_flags_ = ::dex::kAccPublic | ::dex::kAccStatic;
  ;
};

// Builds Dex files from scratch.
class DexBuilder {
public:
  DexBuilder();

  // Create an in-memory image of the DEX file that can either be loaded
  // directly or written to a file.
  slicer::MemView CreateImage(bool checksum=false);

  template <typename T> T *Alloc() { return dex_file_->Alloc<T>(); }

  // Find the ir::String that matches the given string, creating it if it does
  // not exist.
  ir::String *GetOrAddString(const std::string &string);
  // Create a new class of the given name.
  ClassBuilder MakeClass(const std::string &name);

  // Add a type for the given descriptor, or return the existing one if it
  // already exists. See the TypeDescriptor class for help generating these.
  // GetOrAddType can be used to declare imported classes.
  ir::Type *GetOrAddType(const std::string &descriptor);
  inline ir::Type *GetOrAddType(TypeDescriptor descriptor) {
    return GetOrAddType(descriptor.descriptor());
  }

  ir::FieldDecl *GetOrAddField(TypeDescriptor parent, const std::string &name,
                               TypeDescriptor type);

  // Returns the method id for the method, creating it if it has not been
  // created yet.
  const MethodDeclData &GetOrDeclareMethod(TypeDescriptor type,
                                           const std::string &name,
                                           Prototype prototype);

  std::optional<const Prototype> GetPrototypeByMethodId(size_t method_id) const;

private:
  // Looks up the ir::Proto* corresponding to this given prototype, or creates
  // one if it does not exist.
  ir::Proto *GetOrEncodeProto(Prototype prototype);

  std::shared_ptr<ir::DexFile> dex_file_;

  // allocator_ is needed to be able to encode the image.
  TrackingAllocator allocator_;

  // We'll need to allocate buffers for all of the encoded strings we create.
  // This is where we store all of them.
  std::vector<std::unique_ptr<uint8_t[]>> string_data_;

  // Keep track of what types we've defined so we can look them up later.
  phmap::flat_hash_map<std::string_view, ir::Type *> types_by_descriptor_;

  struct MethodDescriptor {
    TypeDescriptor type;
    std::string name;
    Prototype prototype;

    inline bool operator<(const MethodDescriptor &rhs) const {
      return std::make_tuple(type, name, prototype) <
             std::make_tuple(rhs.type, rhs.name, rhs.prototype);
    }
  };

  // Maps method declarations to their method index. This is needed to encode
  // references to them. When we go to actually write the DEX file, slicer will
  // re-assign these after correctly sorting the methods list.
  std::map<MethodDescriptor, MethodDeclData> method_id_map_;

  // Keep track of what strings we've defined so we can look them up later.
  phmap::flat_hash_map<std::string_view, ir::String *> strings_;

  // Keep track of already-encoded protos.
  std::map<Prototype, ir::Proto *> proto_map_;

  // Keep track of fields that have been declared
  std::map<std::tuple<TypeDescriptor, std::string>, ir::FieldDecl *>
      field_decls_by_key_;
};

template <typename... T>
MethodBuilder &
MethodBuilder::BuildNew(const Value &target, const TypeDescriptor &type,
                        const Prototype &constructor, const T &...args) {
  MethodDeclData constructor_data{
      dex_file()->GetOrDeclareMethod(type, "<init>", constructor)};
  // allocate the object
  ir::Type *type_def = dex_file()->GetOrAddType(type.descriptor());
  AddInstruction(Instruction::OpWithArgs(Op::kNew, target,
                                         Value::Type(type_def->orig_index)));
  // call the constructor
  AddInstruction(Instruction::InvokeDirect(constructor_data.id, /*dest=*/{},
                                           target, args...));
  return *this;
};

inline MethodBuilder &MethodBuilder::BuildNewArray(const Value &target,
                                                   const TypeDescriptor &type,
                                                   const Value &size) {
  ir::Type *type_def = dex_file()->GetOrAddType(type.ToArray());
  AddInstruction(Instruction::OpWithArgs(Op::kNewArray, target, size,
                                         Value::Type(type_def->orig_index)));
  return *this;
};

inline MethodBuilder &MethodBuilder::BuildAput(Op opcode,
                                               const Value &target_array,
                                               const Value &value,
                                               const Value &index) {
  AddInstruction(Instruction::OpWithArgs(opcode, value, target_array, index));
  return *this;
}

inline MethodBuilder &MethodBuilder::BuildReturn() {
  AddInstruction(Instruction::OpNoArgs(Op::kReturn));
  return *this;
}

inline MethodBuilder &MethodBuilder::BuildReturn(const Value &src,
                                                 bool is_object, bool is_wide) {
  if (is_wide) {
    AddInstruction(
        Instruction::OpWithArgsWide(Op::kReturnWide, /*destination=*/{}, src));
  } else {
    AddInstruction(Instruction::OpWithArgs(
        is_object ? Op::kReturnObject : Op::kReturn, /*destination=*/{}, src));
  }
  return *this;
}

inline MethodBuilder &MethodBuilder::BuildConst(const Value &target,
                                                int value) {
  assert(value <= 65535);
  AddInstruction(
      Instruction::OpWithArgs(Op::kMove, target, Value::Immediate(value)));
  return *this;
}

inline MethodBuilder &MethodBuilder::BuildConstWide(const Value &target,
                                                    int value) {
  AddInstruction(
      Instruction::OpWithArgs(Op::kMoveWide, target, Value::Immediate(value)));
  return *this;
}

inline MethodBuilder &
MethodBuilder::BuildConstString(const Value &target, const std::string &value) {
  const ir::String *const dex_string = dex_file()->GetOrAddString(value);
  AddInstruction(Instruction::OpWithArgs(
      Op::kMove, target, Value::String(dex_string->orig_index)));
  return *this;
}

inline void MethodBuilder::EncodeInstructions() {
  buffer_.clear();
  for (const auto &instruction : instructions_) {
    EncodeInstruction(instruction);
  }
}
} // namespace dex
} // namespace startop

namespace std {
template <> struct hash<startop::dex::TypeDescriptor> {
  std::size_t operator()(const startop::dex::TypeDescriptor &s) const {
    return std::hash<std::string_view>{}(s.descriptor_);
  }
};
} // namespace std

#endif // DEX_BUILDER_H_
