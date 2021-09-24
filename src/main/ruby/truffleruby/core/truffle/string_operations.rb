# frozen_string_literal: true

# Copyright (c) 2017, 2020 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

module Truffle
  module StringOperations

    # Similar to MRI's Warning::buffer class
    class SimpleStringIO
      attr_reader :string

      def initialize(str)
        @string = str
      end

      def write(*args)
        @string.concat(*args)
      end
    end

    def self.gsub_match_and_replace(orig, matches, replacement, &block)
      if Primitive.undefined?(replacement)
        duped = orig.dup
        gsub_internal_yield_matches(orig, matches) do |_ret, m|
          Primitive.regexp_last_match_set(Primitive.proc_special_variables(block), m)
          val = yield m.to_s
          if duped != orig.dup
            raise RuntimeError, 'string modified'
          end
          val
        end
      else
        unless Primitive.object_kind_of?(replacement, String)
          hash = Truffle::Type.rb_check_convert_type(replacement, Hash, :to_hash)
          replacement = StringValue(replacement) unless hash
        end

        if hash
          gsub_internal_hash(orig, matches, hash)
        else
          gsub_internal_replacement(orig, matches, replacement)
        end
      end
    end

    def self.gsub_internal_hash(orig, matches, replacement)
      gsub_internal_yield_matches(orig, matches) do |_ret, m|
        replacement[m.to_s]
      end
    end

    def self.gsub_internal_replacement(orig, matches, replacement)
      gsub_internal_yield_matches(orig, matches) do |ret, m|
        Truffle::StringOperations.to_sub_replacement(replacement, ret, m)
      end
    end

    def self.gsub_internal_core_check_encoding(orig)
      unless orig.valid_encoding?
        raise ArgumentError, "invalid byte sequence in #{orig.encoding}"
      end
    end

    def self.gsub_internal_matches(global, orig, pattern)
      if Primitive.object_kind_of?(pattern, Regexp)
        gsub_regexp_matches(global, orig, pattern)
      elsif Primitive.object_kind_of?(pattern, String)
        gsub_string_matches(global, orig, pattern)
      else
        gsub_other_matches(global, orig, pattern)
      end
    end

    def self.gsub_new_offset(orig, match)
      start_pos = Primitive.match_data_byte_begin(match, 0)
      end_pos = Primitive.match_data_byte_end(match, 0)
      if start_pos == end_pos
        if char = Primitive.string_find_character(orig, start_pos)
          start_pos + char.bytesize
        else
          start_pos + 1
        end
      else
        end_pos
      end
    end

    def self.gsub_regexp_matches(global, orig, pattern)
      res = []
      offset = 0
      while match = Truffle::RegexpOperations.match_in_region(pattern, orig, offset, orig.bytesize, false, 0)
        res << match
        break unless global
        offset = gsub_new_offset(orig, match)
      end
      res
    end

    def self.gsub_string_matches(global, orig, pattern)
      res = []
      offset = 0
      while index = byte_index(orig, pattern, offset)
        match = Primitive.matchdata_create_single_group(pattern, orig.dup, index, index + pattern.bytesize)
        res << match
        break unless global
        offset = gsub_new_offset(orig, match)
      end
      res
    end

    def self.gsub_other_matches(global, orig, pattern)
      pattern = Truffle::Type.coerce_to_regexp(pattern, true)
      res = []
      offset = 0
      while match = Truffle::RegexpOperations.match_in_region(pattern, orig, offset, orig.bytesize, false, 0)
        res << match
        break unless global
        offset = gsub_new_offset(orig, match)
      end
      res
    end

    def self.gsub_internal_yield_matches(orig, matches)
      return nil if matches.empty?

      last_end = 0
      ret = orig.byteslice(0, 0) # Empty string and string subclass

      matches.each do |match|
        str = Truffle::RegexpOperations.pre_match_from(match, last_end)
        Primitive.string_append(ret, str) if str

        val = yield ret, match
        val = val.to_s unless Primitive.object_kind_of?(val, String)
        val = Truffle::Type.rb_any_to_s(val) unless Primitive.object_kind_of?(val, String)

        Primitive.string_append(ret, val)
        last_end = Primitive.match_data_byte_end(match, 0)
      end

      str = orig.byteslice(last_end, orig.bytesize-last_end+1)
      Primitive.string_append(ret, str) if str

      ret
    end

    def self.concat_internal(string, other)
      Primitive.check_frozen string

      unless Primitive.object_kind_of?(other, String)
        if Primitive.object_kind_of?(other, Integer)
          if string.encoding == Encoding::US_ASCII and other >= 128 and other < 256
            string.force_encoding(Encoding::ASCII_8BIT)
          end

          other = other.chr(string.encoding)
        else
          other = StringValue(other)
        end
      end

      Primitive.string_append(string, other)
    end

    def self.copy_from(string, other, other_offset, byte_count_to_copy, dest_offset)
      sz = string.bytesize
      osz = other.bytesize

      other_offset = 0 if other_offset < 0
      dest_offset = 0 if dest_offset < 0
      byte_count_to_copy = osz - other_offset if byte_count_to_copy > osz - other_offset
      byte_count_to_copy = sz - dest_offset if byte_count_to_copy > sz - dest_offset

      replacement = other.byteslice(other_offset, byte_count_to_copy)
      Primitive.string_splice(string, replacement, dest_offset, byte_count_to_copy, string.encoding)
    end

    def self.case_mapping_option_to_int(option, downcasing=false)
      case option
      when :ascii
        1 << 22 # CASE_ASCII_ONLY
      when :turkic
        1 << 20 # CASE_FOLD_TURKISH_AZERI
      when :lithuanian
        1 << 21 # CASE_FOLD_LITHUANIAN
      when :fold then
        if downcasing
          1 << 19 # CASE_FOLD
        else
          raise ArgumentError, 'option :fold only allowed for downcasing'
        end
      else
        raise ArgumentError, 'invalid option'
      end
    end

    def self.shorten!(string, size)
      return if string.empty?
      Truffle::StringOperations.truncate(string, string.bytesize - size)
    end

    def self.to_sub_replacement(string, result, match)
      index = 0
      while index < string.bytesize
        current = Primitive.find_string(string, '\\', index)
        current = string.bytesize if Primitive.nil? current


        Primitive.string_append(result, string.byteslice(index, current - index))
        break if current == string.bytesize

        # found backslash escape, looking next
        if current == string.bytesize - 1
          Primitive.string_append(result, '\\') # backslash at end of string
          break
        end
        index = current + 1

        cap = string.getbyte(index)

        additional = case cap
                     when 38   # ?&
                       match[0]
                     when 96   # ?`
                       match.pre_match
                     when 39   # ?'
                       match.post_match
                     when 43   # ?+
                       match.captures.compact[-1].to_s
                     when 48..57   # ?0..?9
                       match[cap - 48].to_s
                     when 92 # ?\\ escaped backslash
                       '\\'
                     when 107 # \k named capture
                       if string.getbyte(index + 1) == 60
                         name = +''
                         i = index + 2
                         data = string.bytes
                         while i < string.bytesize && data[i] != 62
                           name << data[i]
                           i += 1
                         end
                         if i >= string.bytesize
                           name << '\\'
                           name << cap.chr
                           index += 1
                           next
                         end
                         index = i
                         name.force_encoding result.encoding
                         match[name]
                       else
                         '\\' + cap.chr
                       end
                     else     # unknown escape
                       '\\' + cap.chr
                     end
        Primitive.string_append(result, additional)
        index += 1
      end
    end

    def self.validate_case_mapping_options(options, downcasing)
      if options.size > 2
        raise ArgumentError, 'too many options'
      end

      if options.size == 0
        0
      elsif options.size == 1
        case_mapping_option_to_int(options.first, downcasing)
      elsif options.size == 2
        first = options[0]
        second = options[1]

        case first
        when :turkic then
          if second == :lithuanian
            case_mapping_option_to_int(:turkic) | case_mapping_option_to_int(:lithuanian)
          else
            raise ArgumentError, 'invalid second option'
          end
        when :lithuanian
          if second == :turkic
            case_mapping_option_to_int(:lithuanian) | case_mapping_option_to_int(:turkic)
          else
            raise ArgumentError, 'invalid second option'
          end
        else raise ArgumentError, 'too many options'
        end
      end
    end

    def self.byte_index(src, str, start=0)
      start += src.bytesize if start < 0
      if start < 0 or start > src.bytesize
        Primitive.regexp_last_match_set(Primitive.caller_special_variables, nil) if Primitive.object_kind_of?(str, Regexp)
        return nil
      end

      return start if str == ''

      Primitive.encoding_ensure_compatible src, str

      Primitive.string_byte_index(src, str, start)
    end
  end
end
