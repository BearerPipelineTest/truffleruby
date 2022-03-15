require_relative '../spec_helper'

ruby_version_is "3.0" do
  describe "Keyword arguments" do
    def target(*args, **kwargs)
      [args, kwargs]
    end

    it "are separated from positional arguments" do
      def m(*args, **kwargs)
        [args, kwargs]
      end

      empty = {}
      m(**empty).should == [[], {}]
      m(empty).should == [[{}], {}]

      m(a: 1).should == [[], {a: 1}]
      m({a: 1}).should == [[{a: 1}], {}]
    end

    it "when the receiving method has not keyword parameters it treats kwargs as positional" do
      def m(*a)
        a
      end

      m(a: 1).should == [{a: 1}]
      m({a: 1}).should == [{a: 1}]
    end

    it "empty kwargs are treated as if they were not passed" do
      def m(*a)
        a
      end

      empty = {}
      m(**empty).should == []
      m(empty).should == [{}]
    end

    it "extra keywords are not allowed without **kwrest" do
      def m(*a, kw:)
        a
      end

      m(kw: 1).should == []
      -> { m(kw: 1, kw2: 2) }.should raise_error(ArgumentError, 'unknown keyword: :kw2')
      -> { m(kw: 1, true => false) }.should raise_error(ArgumentError, 'unknown keyword: true')
    end

    it "handle * and ** at the same call site" do
      def m(*a)
        a
      end

      m(*[], **{}).should == []
      m(*[], 42, **{}).should == [42]
    end

    context "**" do
      it "does not copy a non-empty Hash for a method taking (*args)" do
        def m(*args)
          args[0]
        end

        h = {a: 1}
        m(**h).should.equal?(h)
      end

      it "copies the given Hash for a method taking (**kwargs)" do
        def m(**kw)
          kw
        end

        empty = {}
        m(**empty).should == empty
        m(**empty).should_not.equal?(empty)

        h = {a: 1}
        m(**h).should == h
        m(**h).should_not.equal?(h)
      end
    end

    context "delegation" do
      it "works with (*args, **kwargs)" do
        def m(*args, **kwargs)
          target(*args, **kwargs)
        end

        empty = {}
        m(**empty).should == [[], {}]
        m(empty).should == [[{}], {}]

        m(a: 1).should == [[], {a: 1}]
        m({a: 1}).should == [[{a: 1}], {}]
      end

      it "works with (...)" do
        def m(...)
          target(...)
        end

        empty = {}
        m(**empty).should == [[], {}]
        m(empty).should == [[{}], {}]

        m(a: 1).should == [[], {a: 1}]
        m({a: 1}).should == [[{a: 1}], {}]
      end

      it "works with a ruby2_keyword method (*args)" do
        class << self
          ruby2_keywords def m(*args)
            target(*args)
          end
        end

        empty = {}
        m(**empty).should == [[], {}]
        Hash.ruby2_keywords_hash?(empty).should == false
        m(empty).should == [[{}], {}]
        Hash.ruby2_keywords_hash?(empty).should == false

        m(a: 1).should == [[], {a: 1}]
        m({a: 1}).should == [[{a: 1}], {}]

        kw = {a: 1}

        m(**kw).should == [[], {a: 1}]
        m(**kw)[1].should == kw
        m(**kw)[1].should_not.equal?(kw)
        Hash.ruby2_keywords_hash?(kw).should == false
        Hash.ruby2_keywords_hash?(m(**kw)[1]).should == false

        m(kw).should == [[{a: 1}], {}]
        m(kw)[0][0].should.equal?(kw)
        Hash.ruby2_keywords_hash?(kw).should == false
      end

      it "does not work with (*args)" do
        class << self
          def m(*args)
            target(*args)
          end
        end

        empty = {}
        m(**empty).should == [[], {}]
        m(empty).should == [[{}], {}]

        m(a: 1).should == [[{a: 1}], {}]
        m({a: 1}).should == [[{a: 1}], {}]
      end
    end
  end
end
