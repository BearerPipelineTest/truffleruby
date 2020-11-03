#include <truffleruby-impl.h>

// Array, rb_ary_*

long rb_array_len(VALUE array) {
  return polyglot_get_array_size(rb_tr_unwrap(array));
}

int RARRAY_LENINT(VALUE array) {
  return polyglot_get_array_size(rb_tr_unwrap(array));
}

VALUE RARRAY_AREF(VALUE array, long index) {
  return rb_tr_wrap(polyglot_get_array_element(rb_tr_unwrap(array), (int) index));
}

VALUE rb_Array(VALUE array) {
  return RUBY_CEXT_INVOKE("rb_Array", array);
}

VALUE *RARRAY_PTR_IMPL(VALUE array) {
  return (VALUE *) polyglot_as_i64_array(RUBY_CEXT_INVOKE_NO_WRAP("RARRAY_PTR", array));
}

VALUE rb_ary_new() {
  return RUBY_CEXT_INVOKE("rb_ary_new");
}

VALUE rb_ary_new_capa(long capacity) {
  return rb_tr_wrap(polyglot_invoke(RUBY_CEXT, "rb_ary_new_capa", capacity));
}

VALUE rb_ary_new_from_args(long n, ...) {
  VALUE array = rb_ary_new_capa(n);
  va_list args;
  va_start(args, n);
  for (int i = 0; i < n; i++) {
    rb_ary_store(array, i, va_arg(args, VALUE));
  }
  va_end(args);
  return array;
}

VALUE rb_ary_new_from_values(long n, const VALUE *values) {
  VALUE array = rb_ary_new_capa(n);
  for (int i = 0; i < n; i++) {
    rb_ary_store(array, i, values[i]);
  }
  return array;
}

VALUE rb_assoc_new(VALUE a, VALUE b) {
  return rb_ary_new3(2, a, b);
}

VALUE rb_ary_push(VALUE array, VALUE value) {
  RUBY_INVOKE_NO_WRAP(array, "push", value);
  return array;
}

VALUE rb_ary_pop(VALUE array) {
  return RUBY_INVOKE(array, "pop");
}

VALUE rb_ary_sort(VALUE array) {
  return RUBY_INVOKE(array, "sort");
}

VALUE rb_ary_sort_bang(VALUE array) {
  return RUBY_INVOKE(array, "sort!");
}

void rb_ary_store(VALUE array, long index, VALUE value) {
  RUBY_INVOKE_NO_WRAP(array, "[]=", LONG2FIX(index), value);
}

VALUE rb_ary_entry(VALUE array, long index) {
  return rb_tr_wrap(polyglot_invoke(rb_tr_unwrap(array), "[]", index));
}

VALUE rb_ary_unshift(VALUE array, VALUE value) {
  return RUBY_INVOKE(array, "unshift", value);
}

VALUE rb_ary_aref(int n, const VALUE* values, VALUE array) {
  return RUBY_CEXT_INVOKE("send_splatted", array, rb_str_new_cstr("[]"), rb_ary_new4(n, values));
}

VALUE rb_ary_clear(VALUE array) {
  return RUBY_INVOKE(array, "clear");
}

VALUE rb_ary_delete(VALUE array, VALUE value) {
  return RUBY_INVOKE(array, "delete", value);
}

VALUE rb_ary_delete_at(VALUE array, long n) {
  return rb_tr_wrap(polyglot_invoke(rb_tr_unwrap(array), "delete_at", n));
}

VALUE rb_ary_includes(VALUE array, VALUE value) {
  return RUBY_INVOKE(array, "include?", value);
}

VALUE rb_ary_join(VALUE array, VALUE sep) {
  return RUBY_INVOKE(array, "join", sep);
}

VALUE rb_ary_to_s(VALUE array) {
  return RUBY_INVOKE(array, "to_s");
}

VALUE rb_ary_reverse(VALUE array) {
  return RUBY_INVOKE(array, "reverse!");
}

VALUE rb_ary_shift(VALUE array) {
  return RUBY_INVOKE(array, "shift");
}

VALUE rb_ary_concat(VALUE a, VALUE b) {
  return RUBY_INVOKE(a, "concat", b);
}

VALUE rb_ary_plus(VALUE a, VALUE b) {
  return RUBY_INVOKE(a, "+", b);
}

VALUE rb_ary_to_ary(VALUE array) {
  VALUE tmp = rb_check_array_type(array);

  if (!NIL_P(tmp)) return tmp;
  return rb_ary_new_from_args(1, array);
}

VALUE rb_ary_subseq(VALUE array, long start, long length) {
  return rb_tr_wrap(polyglot_invoke(rb_tr_unwrap(array), "[]", start, length));
}

VALUE rb_ary_cat(VALUE array, const VALUE *cat, long n) {
  return RUBY_INVOKE(array, "concat", rb_ary_new4(n, cat));
}

VALUE rb_ary_rotate(VALUE array, long n) {
  if (n != 0) {
    return rb_tr_wrap(polyglot_invoke(rb_tr_unwrap(array), "rotate!", n));
  }
  return Qnil;
}

VALUE rb_ary_tmp_new(long capa) {
  return rb_ary_new_capa(capa);
}

VALUE rb_ary_freeze(VALUE array) {
  return rb_obj_freeze(array);
}

VALUE rb_ary_dup(VALUE array) {
  return rb_obj_dup(array);
}
