#!/bin/bash
# Differential fuzz: random expressions through clove and babashka, diffed.
#
#   fuzz/fuzz.sh [SEED] [N]        (defaults: random seed, 2000 expressions)
#
# Runs three corpora from the same seed:
#   expr  gen_expr.py        typed random core expressions   -- must match bb
#   bmp   gen_str.py bmp     BMP string/regex ops            -- must match bb
#   inv   gen_str.py inv     string invariants (astral too)  -- must print true
#
# Exit 0 = no divergences. On a finding it prints the expression with both
# answers, and the SEED reproduces the whole run. CLOVE and BB override the
# binaries (default ./clove next to this directory, and bb on PATH).
set -u
here=$(cd "$(dirname "$0")" && pwd)
CLOVE=${CLOVE:-$here/../clove}
BB=${BB:-bb}
seed=${1:-$RANDOM$RANDOM}
n=${2:-2000}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "fuzz: seed=$seed n=$n"
fail=0

# $1 = label, $2 = corpus file: run both runtimes, report lines that differ
differential() {
  "$CLOVE" "$here/runner_clove.clj" "$2" > "$tmp/$1.cljc" 2> "$tmp/$1.cljc.err"
  local rc=$?
  "$BB" -f "$here/runner.clj" -- "$2" > "$tmp/$1.bb" 2> "$tmp/$1.bb.err"
  if [ $rc -ne 0 ]; then
    echo "$1: clove exited $rc (crash or uncaught error):"; tail -15 "$tmp/$1.cljc.err"; fail=1
  fi
  # A BigInt that fits a long prints without its N in clove (in-range bigints
  # demote to longs -- a documented divergence), so "-1N" vs "-1" is not news.
  sed -i -E 's/([0-9])N/\1/g' "$tmp/$1.cljc" "$tmp/$1.bb"
  grep -v '^;' "$2" | grep -v '^[[:space:]]*$' > "$tmp/$1.exprs"
  local found
  found=$(paste -d '\n' "$tmp/$1.exprs" "$tmp/$1.cljc" "$tmp/$1.bb" | awk '
    NR % 3 == 1 { e = $0 } NR % 3 == 2 { a = $0 }
    NR % 3 == 0 && a != $0 { print "  expr: " e "\n  clove: " a "\n  bb:   " $0 "\n"; c++ }
    END { exit c ? 1 : 0 }') || fail=1
  if [ -n "$found" ]; then
    echo "$1: DIVERGENCES"; echo "$found" | head -120
  else
    echo "$1: $(wc -l < "$tmp/$1.exprs") expressions, no divergences"
  fi
}

python3 "$here/gen_expr.py" "$seed" "$n" > "$tmp/expr.txt"
differential expr "$tmp/expr.txt"

python3 "$here/gen_str.py" "$seed" $((n / 5)) bmp > "$tmp/bmp.txt"
differential bmp "$tmp/bmp.txt"

python3 "$here/gen_str.py" "$seed" $((n / 5)) inv > "$tmp/inv.txt"
for stress in "" 1; do
  bad=$(env ${stress:+CLOVE_GC_STRESS=1} "$CLOVE" "$here/runner_clove.clj" "$tmp/inv.txt" | grep -vc '^true$')
  if [ "$bad" -ne 0 ]; then
    echo "inv${stress:+ (GC stress)}: $bad invariants violated"; fail=1
  else
    echo "inv${stress:+ (GC stress)}: all invariants hold"
  fi
done

[ $fail -eq 0 ] && echo "fuzz: clean (seed=$seed)" || echo "fuzz: FINDINGS (reproduce with: fuzz/fuzz.sh $seed $n)"
exit $fail
