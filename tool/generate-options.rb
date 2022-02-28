#!/usr/bin/env ruby

# Copyright (c) 2016, 2021 Oracle and/or its affiliates. All rights reserved.
# This code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

require 'ostruct'
require 'yaml'
require 'erb'

def camelize(string)
  string.sub(/^([a-z\d]*)/) { $1.capitalize }.gsub(/_([a-z\d]*)/) { $1.capitalize }
end

def parse_reference_defaults(default)
  match = /^!?[A-Z_]+(\s*\|\|\s*!?[A-Z_]+)*$/.match(default)
  if match
    match[0].split('||').map(&:strip)
  else
    nil
  end
end

options_data = YAML.load_file('src/options.yml')

options = []

language_options_keys = options_data.delete('LANGUAGE_OPTIONS').to_h { |name| [name, true] }
options_data.delete('LANGUAGE_OPTIONS')

options_data.each do |category, stabilities|
  stabilities.each do |stability, category_options|
    category_options.each do |constant, values|
      raise "More or less than 4 arguments in #{values} for #{constant}" unless values.size == 4
      (name, *mri_names), type, default, description = values

      case type
      when 'boolean'
        type       = 'boolean'
        boxed_type = 'Boolean'
        if default.is_a?(Array)
          env_condition, default = default
        end
        default    = default.to_s
      when 'integer'
        type       = 'int'
        boxed_type = 'Integer'
        if default.is_a?(Array)
          env_condition, default = default
        end
        default    = default.to_s
      when /^enum\/(\w*)/
        type       = camelize $1
        boxed_type  = type
        default     = "#{type}.#{default.to_s.upcase}"
        raise if parse_reference_defaults(default)
      when 'string'
        type       = 'String'
        boxed_type = type
        default    = default.nil? ? 'null' : "\"#{default.to_s}\""
      when 'string-array'
        raise unless default.empty?
        type             = 'String[]'
        boxed_type       = type
        default          = "StringArrayOptionType.EMPTY_STRING_ARRAY"
        option_type      = 'StringArrayOptionType.INSTANCE'
      else
        raise type.to_s
      end

      options.push OpenStruct.new(
          category:          category,
          stability:         stability,
          constant:          constant,
          key_constant:      "#{constant}_KEY",
          name:              name,
          type:              type,
          boxed_type:        boxed_type,
          default:           default,
          reference_default: parse_reference_defaults(default),
          env_condition:     env_condition,
          description:       description + (mri_names.empty? ?
                                                '' : " (configured by the #{mri_names.join(', ')} Ruby option#{'s' if mri_names.size > 1})"),
          option_type:       option_type,
          language_option:   language_options_keys.has_key?(constant),
      )
    end
  end
end

options_map = {}

options.each do |option|
  options_map[option.constant] = option
  if option.reference_default
    option.default_value = option.reference_default.map { |r|
      raise r unless r =~ /^(!?)(.+)$/
      prefix, name = $1, $2
      "#{prefix}#{options_map[name].constant}_KEY.getDefaultValue()"
    }.join(' || ')
  else
    option.default_value = option.default
  end
end

language_options, context_options = options.partition { |op| op.language_option }

file = __FILE__

TEMPLATE = <<'JAVA'
/*
 * Copyright (c) 2016, 2022 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.options;

// GENERATED BY <%= file %>
// This file is automatically generated from options.yml with 'jt build options'

import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionValues;
import org.truffleruby.shared.options.OptionsCatalog;
<% if class_prefix == '' -%>
import org.truffleruby.shared.options.Verbosity;
import org.truffleruby.shared.options.Profile;
<% else -%>
import com.oracle.truffle.api.TruffleLogger;
<% end -%>

import com.oracle.truffle.api.TruffleLanguage.Env;

// @formatter:off
public class <%= class_prefix %>Options {
<% options.each do |o| %>
    /** --<%= o.name %>=<%= o.env_condition %><%= o.default %> */
    public final <%= o.type %> <%= o.constant %>;<% end %>

    public <%= class_prefix %>Options(Env env, OptionValues options<%= class_prefix == "" ? ", LanguageOptions languageOptions" : ", boolean singleContext" %>) {
    <% options.each do |o| %>    <%= o.constant %> = <%= o.env_condition %><%=
      key = "OptionsCatalog.#{o.constant}_KEY"
      value = if o.reference_default
        "options.hasBeenSet(#{key}) ? options.get(#{key}) : #{class_prefix == '' && language_keys.has_key?(o.default) ? "languageOptions." + o.default : o.default}"
      else
        "options.get(#{key})"
      end
      o.env_condition ? "(#{value});" : "#{value};"
      %>
    <% end %>}

    public Object fromDescriptor(OptionDescriptor descriptor) {
        switch (descriptor.getName()) {
            <% options.each do |o| %>case "ruby.<%= o.name %>":
                return <%= o.constant %>;
            <% end %>default:
                return null;
        }
    }

<% if class_prefix == 'Language' -%>
    public static boolean areOptionsCompatible(OptionValues one, OptionValues two) {
        return <%= options.map { |o| "one.get(OptionsCatalog.#{o.constant}_KEY).equals(two.get(OptionsCatalog.#{o.constant}_KEY))"  }.join(" &&\n               ") %>;
    }

    public static boolean areOptionsCompatibleOrLog(TruffleLogger logger, LanguageOptions oldOptions, LanguageOptions newOptions) {
        Object oldValue;
        Object newValue;
<%= options.map { |o| "
        oldValue = oldOptions.#{o.constant};
        newValue = newOptions.#{o.constant};
        if (!newValue.equals(oldValue)) {
            logger.fine(\"not reusing pre-initialized context: --#{o.name} differs, was: \" + oldValue + \" and is now: \" + newValue);
            return false;
        }" }.join("\n") %>

        return true;
    }
<% end -%>
}
// @formatter:on
JAVA

File.write('src/main/java/org/truffleruby/options/Options.java', ERB.new(TEMPLATE, nil, '-').result_with_hash(class_prefix: '', options: context_options, language_keys: language_options_keys))
File.write('src/main/java/org/truffleruby/options/LanguageOptions.java', ERB.new(TEMPLATE, nil, '-').result_with_hash(class_prefix: 'Language', options: language_options))

File.write('src/shared/java/org/truffleruby/shared/options/OptionsCatalog.java', ERB.new(<<JAVA).result)
/*
 * Copyright (c) 2016, 2022 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.shared.options;

// GENERATED BY <%= file %>
// This file is automatically generated by options.yml with 'jt build options'

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;

// @formatter:off
public class OptionsCatalog {
<% options.each do |o| %>
    public static final OptionKey<<%= o.boxed_type %>> <%= o.key_constant %> = new OptionKey<>(<%= o.default_value %><%= o.option_type ? ', ' + o.option_type : '' %>);<% end %>
<% options.each do |o| %>
    public static final OptionDescriptor <%= o.constant %> = OptionDescriptor
            .newBuilder(<%= o.key_constant %>, "ruby.<%= o.name %>")
            .help("<%= o.description %>")
            .category(OptionCategory.<%= o.category %>)
            .stability(OptionStability.<%= o.stability %>)
            .build();
<% end %>
    public static OptionDescriptor fromName(String name) {
        switch (name) {
            <% options.each do |o| %>case "ruby.<%= o.name %>":
                return <%= o.constant %>;
            <% end %>default:
                return null;
        }
    }

    public static OptionDescriptor[] allDescriptors() {
        return new OptionDescriptor[] {<% options.each do |o| %>
            <%= o.constant %>,<% end %>
        };
    }
}
// @formatter:on
JAVA
