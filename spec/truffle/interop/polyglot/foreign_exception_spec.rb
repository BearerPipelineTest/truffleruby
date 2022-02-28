# Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

require_relative '../../../ruby/spec_helper'

describe "Polyglot::ForeignException" do
  before :each do
    @foreign = Truffle::Debug.foreign_exception("exception message")
  end

  it "supports #message" do
    @foreign.message.should == "exception message"
  end

  it "supports #cause" do
    @foreign.cause.should == nil
  end

  it "supports #to_s" do
    @foreign.to_s.should == "exception message"
  end

  it "supports #inspect" do
    @foreign.inspect.should == "#<Polyglot::ForeignExceptionClass: exception message>"
  end

  it "supports rescue Polyglot::ForeignException" do
    begin
      raise @foreign
    rescue Polyglot::ForeignException => e
      e.should.equal?(@foreign)
    end
  end

  it "supports rescue Object" do
    begin
      raise @foreign
    rescue Object => e
      e.should.equal?(@foreign)
    end
  end

  it "supports rescue class" do
    begin
      raise @foreign
    rescue @foreign.class => e
      e.should.equal?(@foreign)
    end
  end

  it "supports #raise" do
    -> { raise @foreign }.should raise_error(Polyglot::ForeignException) { |e|
      e.should.equal?(@foreign)
    }
  end

  it "supports #backtrace" do
    @foreign.backtrace.should.is_a?(Array)
    @foreign.backtrace.should_not.empty?
    @foreign.backtrace.each { |entry| entry.should.is_a?(String) }
  end

  it "supports #backtrace_locations" do
    @foreign.backtrace_locations.should.is_a?(Array)
    @foreign.backtrace_locations.should_not.empty?
    @foreign.backtrace_locations.each do |entry|
      entry.should.respond_to?(:absolute_path)
      entry.path.should.is_a?(String)
      entry.lineno.should.is_a?(Integer)
      entry.label.should.is_a?(String)
    end
  end
end
