/* host.c — embedding example for clove.
 *
 * The embedding model is stb-style: define CLOVE_NO_MAIN and #include
 * clove.c directly into one translation unit. This gives your native
 * functions access to the interpreter's internals (value constructors
 * like mk_int/mk_str, the args-list accessors, NIL, ...) which is
 * exactly what you need to exchange values across the boundary.
 *
 * Build:  cc -O2 -Wall -o host examples/host.c -lm   (from the repo root)
 */

#include <unistd.h>   /* getpid, for the demo native */

#define CLOVE_NO_MAIN
#include "../clove.c"

/* C-side state that the script mutates through a native function. */
static int total_score = 0;

/* (add-score! n) — adds n to the C-side total, returns the new total. */
static Clove *native_add_score(CloveEnv *env, Clove *args) {
    (void)env;
    total_score += (int)as_int(args->as.cons.head, "add-score!");
    return mk_int(total_score);
}

/* (host-info) — returns a clove map built from C. */
static Clove *native_host_info(CloveEnv *env, Clove *args) {
    (void)env; (void)args;
    Clove *m = mk_map();
    m = map_assoc(m, mk_kw(intern("pid", 3)), mk_int((int64_t)getpid()));
    m = map_assoc(m, mk_kw(intern("lang", 4)), mk_str("C", 1));
    return m;
}

int main(int argc, char **argv) {
    (void)argv;
    clove_set_stack_base(&argc);   /* required: GC scans the C stack for roots */
    CloveEnv *env = clove_new_env();

    clove_define_native(env, "add-score!", native_add_score);
    clove_define_native(env, "host-info",  native_host_info);

    /* Script calls into C ... */
    clove_eval_string(env,
        "(doseq [s [10 25 7]] (add-score! s))"
        "(println \"script sees total:\" (add-score! 0))"
        "(println \"host info:\" (host-info))");

    /* ... and C reads the result back. */
    printf("C sees total: %d\n", total_score);

    /* Evaluate an expression and consume the value in C. */
    Clove *result = clove_eval_string(env, "(reduce + (map (fn [x] (* x x)) (range 10)))");
    printf("sum of squares, computed in clove, printed from C: ");
    clove_print(result);
    printf("\n");
    return 0;
}
