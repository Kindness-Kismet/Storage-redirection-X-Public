#pragma once

#include <string_view>
#include <parallel_hashmap/phmap.h>
#include <vector>

#include "slicer/reader.h"

class DexHelper {
public:
    DexHelper(const std::vector<std::tuple<const void *, size_t, const void *, size_t>> &dexs);

    void CreateFullCache() const;

    std::vector<size_t> FindMethodUsingString(std::string_view str, bool match_prefix,
                                              size_t return_type, short parameter_count,
                                              std::string_view parameter_shorty,
                                              size_t declaring_class,
                                              const std::vector<size_t> &parameter_types,
                                              const std::vector<size_t> &contains_parameter_types,
                                              const std::vector<size_t> &dex_priority,
                                              bool find_first) const;

    std::vector<size_t> FindMethodInvoking(size_t method_idx, size_t return_type,
                                           short parameter_count, std::string_view parameter_shorty,
                                           size_t declaring_class,
                                           const std::vector<size_t> &parameter_types,
                                           const std::vector<size_t> &contains_parameter_types,
                                           const std::vector<size_t> &dex_priority,
                                           bool find_first) const;

    std::vector<size_t> FindMethodInvoked(size_t method_idx, size_t return_type,
                                          short parameter_count, std::string_view parameter_shorty,
                                          size_t declaring_class,
                                          const std::vector<size_t> &parameter_types,
                                          const std::vector<size_t> &contains_parameter_types,
                                          const std::vector<size_t> &dex_priority,
                                          bool find_first) const;

    std::vector<size_t> FindMethodGettingField(size_t field_idx, size_t return_type,
                                               short parameter_count,
                                               std::string_view parameter_shorty,
                                               size_t declaring_class,
                                               const std::vector<size_t> &parameter_types,
                                               const std::vector<size_t> &contains_parameter_types,
                                               const std::vector<size_t> &dex_priority,
                                               bool find_first) const;

    std::vector<size_t> FindMethodSettingField(size_t field_idx, size_t return_type,
                                               short parameter_count,
                                               std::string_view parameter_shorty,
                                               size_t declaring_class,
                                               const std::vector<size_t> &parameter_types,
                                               const std::vector<size_t> &contains_parameter_types,
                                               const std::vector<size_t> &dex_priority,
                                               bool find_first) const;

    std::vector<size_t> FindField(size_t type, const std::vector<size_t> &dex_priority,
                                  bool find_first) const;

    struct Class {
        const std::string_view name;
    };
    struct Field {
        const Class declaring_class;
        const Class type;
        const std::string_view name;
    };
    struct Method {
        const Class declaring_class;
        const std::string_view name;
        const std::vector<Class> parameters;
        const Class return_type;
    };

    size_t CreateClassIndex(std::string_view class_name) const;
    size_t CreateMethodIndex(std::string_view class_name, std::string_view method_name,
                             const std::vector<std::string_view> &params_name) const;
    size_t CreateFieldIndex(std::string_view class_name, std::string_view field_name) const;

    Class DecodeClass(size_t class_idx) const;
    Field DecodeField(size_t field_idx) const;
    Method DecodeMethod(size_t method_idx) const;

private:
    std::tuple<std::vector<std::vector<uint32_t>>, std::vector<std::vector<uint32_t>>>
    ConvertParameters(const std::vector<size_t> &parameter_types,
                      const std::vector<size_t> &contains_parameter_types) const;

    std::vector<size_t> GetPriority(const std::vector<size_t> &priority) const;

    bool ScanMethod(size_t dex_idx, uint32_t method_id, size_t str_lower = size_t(-1),
                    size_t str_upper = size_t(-1)) const;

    std::tuple<uint32_t, uint32_t> FindPrefixStringId(size_t dex_idx,
                                                      std::string_view to_find) const;

    uint32_t FindPrefixStringIdExact(size_t dex_idx, std::string_view to_find) const;

    bool IsMethodMatch(size_t dex_id, uint32_t method_id, uint32_t return_type,
                       short parameter_count, std::string_view parameter_shorty,
                       uint32_t declaring_class, const std::vector<uint32_t> &parameter_types,
                       const std::vector<uint32_t> &contains_parameter_types) const;

    size_t CreateMethodIndex(size_t dex_idx, uint32_t method_id) const;
    size_t CreateClassIndex(size_t dex_idx, uint32_t class_id) const;
    size_t CreateFieldIndex(size_t dex_idx, uint32_t field_id) const;

    std::vector<dex::Reader> readers_;

    // for interface
    // indices[method_index][dex] -> id
    mutable std::vector<std::vector<uint32_t>> method_indices_;
    mutable std::vector<std::vector<uint32_t>> class_indices_;
    mutable std::vector<std::vector<uint32_t>> field_indices_;
    // rev[dex][method_id] -> method_index
    mutable std::vector<std::vector<size_t>> rev_method_indices_;  // for each dex
    mutable std::vector<std::vector<size_t>> rev_class_indices_;
    mutable std::vector<std::vector<size_t>> rev_field_indices_;

    // for preprocess
    // strings[dex][str_id] -> str
    std::vector<std::vector<std::string_view>> strings_;
    // method_codes[dex][method_id] -> code
    std::vector<std::vector<const dex::CodeItem *>> method_codes_;

    // for cache
    // type_cache[dex][str_id] -> type_id
    std::vector<std::vector<uint32_t>> type_cache_;
    // field_cache[dex][type_id][str_id] -> method_ids
    std::vector<std::vector<phmap::flat_hash_map<uint32_t, std::vector<uint32_t>>>> method_cache_;
    // field_cache[dex][type_id][str_id] -> field_id
    std::vector<std::vector<phmap::flat_hash_map<uint32_t, uint32_t>>> field_cache_;
    // class_cache[dex][type_id] -> class_id
    std::vector<std::vector<uint32_t>> class_cache_;

    // search result cache
    // string_cache[dex][str_id] -> method_ids
    mutable std::vector<std::vector<std::vector<uint32_t>>> string_cache_;
    // invoking_cache[dex][method_id] -> method_ids
    mutable std::vector<std::vector<std::vector<uint32_t>>> invoking_cache_;
    // invoked_cache[dex][method_id] -> method_ids
    mutable std::vector<std::vector<std::vector<uint32_t>>> invoked_cache_;
    // getting/setting_cache[dex][field_id] -> method_ids
    mutable std::vector<std::vector<std::vector<uint32_t>>> getting_cache_;
    mutable std::vector<std::vector<std::vector<uint32_t>>> setting_cache_;
    mutable std::vector<std::vector<std::vector<uint32_t>>> declaring_cache_;
    // for method search
    mutable std::vector<std::vector<bool>> searched_methods_;
};
