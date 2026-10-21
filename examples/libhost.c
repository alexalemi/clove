/* libhost.c — call a clove script compiled to a shared library from C.
 *
 *   clove bundle --library greet.clj libgreet.so     # + libgreet.so.h
 *   cc -O2 libhost.c ./libgreet.so -o libhost
 *   LD_LIBRARY_PATH=. ./libhost
 *
 * The library exports three functions (see the generated header):
 *   clove_lib_init()        runs the embedded script (idempotent; 0 = ok)
 *   clove_lib_eval(src)     evals a string, returns pr-str of the last form
 *                          (owned by the library, valid until the next call),
 *                          or NULL on error
 *   clove_lib_last_error()  message for the last failed call
 *
 * One interpreter per process, single-threaded — same rules as embedding
 * clove.c directly. Works the same from C++, Rust (extern "C"), Zig, etc.
 */
#include <stdio.h>

int clove_lib_init(void);
const char *clove_lib_eval(const char *src);
const char *clove_lib_last_error(void);

int main(void) {
    if (clove_lib_init() != 0) {
        fprintf(stderr, "init failed: %s\n", clove_lib_last_error());
        return 1;
    }
    printf("greet => %s\n", clove_lib_eval("(greet \"world\")"));
    printf("data  => %s\n", clove_lib_eval("(into (sorted-map) {:b [1 2] :a \"x\"})"));

    const char *bad = clove_lib_eval("(nosuchfn 1)");
    if (!bad) printf("error => %s\n", clove_lib_last_error());

    printf("still => %s\n", clove_lib_eval("(greet \"again\")"));  /* errors don't kill it */
    return 0;
}
