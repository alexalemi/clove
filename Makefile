CC      ?= cc
# -falign-*: stabilizes performance across otherwise-neutral code changes
# (±5% swings on hot interpreter loops were pure layout luck without it)
CFLAGS  ?= -O2 -falign-functions=64 -falign-loops=32 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function
PREFIX  ?= /usr/local
DESTDIR ?=
SHAREDIR = $(PREFIX)/share/clove
BATTERIES = docs.clj claude.clj tasks.clj inspect.clj libc.clj json.clj fs.clj process.clj test.clj jit.clj bundle.clj clerk.clj judge.cljc csp.clj http.clj nrepl.clj peg.clj

clove: clove.c
	$(CC) $(CFLAGS) -DCLOVE_SHAREDIR='"$(SHAREDIR)"' -o $@ $< -lm -ldl

run: clove
	./clove

example: examples/host.c clove.c
	$(CC) $(CFLAGS) -o examples/host examples/host.c -lm -ldl
	./examples/host

# The verdict is clove's exit status plus the suite's summary line -- never a
# grep for "FAIL|error" over the output, which broke on any test that
# legitimately printed the word "error". tests.clj exits 1 if any assertion
# failed; a script-mode abort exits non-zero before the summary prints.
# $(1) = env prefix. On failure show everything except the PASS noise.
define run_suite
	@out=$$($(1) ./clove tests.clj 2>&1); rc=$$?; \
	  if [ $$rc -ne 0 ] || ! echo "$$out" | tail -1 | grep -q '^tests complete: [0-9]* passed, 0 failed$$'; then \
	    echo "$$out" | grep -v '^PASS$$' | tail -40; echo "suite FAILED (rc=$$rc)"; exit 1; fi; \
	  echo "$(1) $$(echo "$$out" | tail -1)"
endef

test: clove
	$(call run_suite,)
	@$(MAKE) --no-print-directory conformance
	@echo "all tests pass (normal + conformance)"

# Adds the GC-stress pass: collect every 512 allocations instead of the
# adaptive ~1M threshold, which is what shakes out missing GC roots. It is
# ~8x slower than the normal run (140s vs 18s here) and dominates everything
# else, so it lives in CI and pre-release rather than in every install.
test-full: test
	$(call run_suite,CLOVE_GC_STRESS=1)
	@echo "all tests pass (normal + GC stress + conformance)"

# Diff the conformance corpus against the checked-in JVM-Clojure golden file.
# With babashka on PATH the golden is re-derived live instead.
conformance: clove
	@./clove fuzz/runner_clove.clj fuzz/conformance.txt | diff - fuzz/conformance_expected.txt \
	  && echo "conformance: $$(grep -cv '^;' fuzz/conformance.txt | cat) expressions, no divergences"
	@if command -v bb > /dev/null; then \
	  bb -f fuzz/runner.clj -- fuzz/conformance.txt | diff - fuzz/conformance_expected.txt \
	    && echo "conformance: golden verified against babashka"; \
	else echo "conformance: bb not on PATH, golden not re-verified"; fi

# Differential fuzz against babashka (needs bb + python3): random typed
# expressions, BMP strings, string invariants. `make fuzz SEED=7 N=5000`
# reproduces a run; the nightly workflow (.github/workflows/fuzz.yml) runs it
# with fresh seeds.
fuzz: clove
	@fuzz/fuzz.sh "$(SEED)" "$(N)"

install: clove
	install -d $(DESTDIR)$(PREFIX)/bin
	install -m 755 clove $(DESTDIR)$(PREFIX)/bin/clove
	install -d $(DESTDIR)$(SHAREDIR)
	install -m 644 $(BATTERIES) $(DESTDIR)$(SHAREDIR)/
	install -m 644 clove.c $(DESTDIR)$(SHAREDIR)/   # `clove bundle` needs the source
	@# every vendored source, whatever directories exist -- a new vendor/ lib
	@# needs no Makefile edit (plain install -d/-m: BSD install has no -D)
	@find vendor -type f \( -name '*.clj' -o -name '*.cljc' -o -name '*.edn' \) | while read -r f; do \
	  install -d "$(DESTDIR)$(SHAREDIR)/$$(dirname "$$f")" && \
	  install -m 644 "$$f" "$(DESTDIR)$(SHAREDIR)/$$f" || exit 1; done
	@echo "installed $(DESTDIR)$(PREFIX)/bin/clove (batteries: $(SHAREDIR))"

uninstall:
	rm -f $(DESTDIR)$(PREFIX)/bin/clove
	rm -rf $(DESTDIR)$(SHAREDIR)

clean:
	rm -f examples/host
	rm -f clove

lint:
	clj-kondo --lint json.clj libc.clj test.clj examples 2>/dev/null || true
	@echo "(name files .cljc if they use #?(:clove ...) reader conditionals)"

.PHONY: run test test-full conformance fuzz clean example lint install uninstall
