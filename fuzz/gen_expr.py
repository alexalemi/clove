"""Random TYPED Clojure expressions for differential fuzzing (clove vs JVM/bb).

    python3 gen_expr.py SEED N > exprs.txt

One expression per line; every line must print the same value in both
runtimes (see fuzz.sh, which runs the two runners and diffs). The grammar is
typed so that almost every expression is well-formed, and deliberately leaves
room for the ones that throw (division by zero, index out of range, long
overflow, bad parses): the runners print :FUZZERR for a throw, so "both
throw" is a match and "one throws" is a finding.

Rules that keep the corpus free of the documented, deliberate divergences:
  - hash order never leaks: a set or map only becomes a seq through `sort`,
    and a set-/map-typed result is printed through a sorted view
  - strings are ASCII (JVM strings index UTF-16 units; see gen_str.py for the
    non-ASCII corpus)
  - sizes are bounded (ranges, repeats, depth) so nothing runs away
"""
import random
import sys

seed = int(sys.argv[1])
n = int(sys.argv[2])
random.seed(seed)

MAXD = 4
KWS = [":a", ":b", ":c", ":d", ":e"]
WORDS = ["", "a", "ab", "abc", "Hello", "foo bar", " pad ", "x,y,z", "42", "-7", "3.5", "zz"]
# (fn ..) rather than #(..): these get spliced inside other fn literals, and
# Clojure rejects nested #() (clove accepts it)
FNS = ["inc", "dec", "-", "abs", "(fn [x] (* x x))", "(fn [a] (+ a 3))", "(fn [a] (quot a 2))", "(partial * 2)"]
PREDS = ["even?", "odd?", "pos?", "neg?", "zero?", "(fn [p] (> p 2))", "(fn [p] (< p 0))"]
# No Long/MIN_VALUE: boxed JVM math computes (- x y) as (+ x (- y)), so
# (- -1 Long/MIN_VALUE) throws in bb yet is Long/MAX_VALUE in compiled Clojure
# (primitive path) and in clove. A typing artifact, not a finding.
EDGE_INTS = ["9223372036854775807", "-9223372036854775807", "4611686018427387904",
             "3037000500", "2147483647", "-2147483648", "1000000007"]


def pick(xs):
    return random.choice(xs)


def small():
    return str(random.randint(-9, 12))


def idx():
    return str(random.randint(0, 6))


def lit_int():
    r = random.random()
    if r < 0.06:
        return pick(EDGE_INTS)
    if r < 0.15:
        return str(random.randint(-1000000, 1000000))
    return small()


def lit_str():
    return '"' + pick(WORDS) + '"'


def lit_vec():
    return "[" + " ".join(small() for _ in range(random.randint(0, 6))) + "]"


def lit_set():
    return "#{" + " ".join(str(x) for x in random.sample(range(-5, 10), random.randint(0, 5))) + "}"


def lit_map():
    ks = random.sample(KWS, random.randint(0, 4))
    return "{" + " ".join(f"{k} {small()}" for k in ks) + "}"


def kwvec():
    return "[" + " ".join(random.sample(KWS, random.randint(0, 3))) + "]"


class Gen:
    def __init__(self):
        self.ints = []      # int-typed locals in scope
        self.vecs = []      # vector-typed locals in scope

    # ── int ──
    def int(self, d):
        if d >= MAXD or random.random() < 0.18:
            if self.ints and random.random() < 0.5:
                return pick(self.ints)
            return lit_int()
        d += 1
        r = random.randrange(34)
        if r < 3:
            return f"({pick(['+', '-', '*'])} {self.int(d)} {self.int(d)})"
        if r == 3:
            return f"({pick(['+', '*'])} {self.int(d)} {self.int(d)} {self.int(d)})"
        if r == 4:
            return f"({pick(['quot', 'rem', 'mod'])} {self.int(d)} {self.int(d)})"
        if r == 5:
            return f"({pick(['inc', 'dec', 'abs', '-'])} {self.int(d)})"
        if r == 6:
            return f"({pick(['max', 'min'])} {self.int(d)} {self.int(d)})"
        if r == 7:
            return f"({pick(['bit-and', 'bit-or', 'bit-xor'])} {self.int(d)} {self.int(d)})"
        if r == 8:
            return f"(count {pick([self.vec, self.str, self.set, self.map])(d)})"
        if r == 9:
            return f"(reduce + 0 {self.vec(d)})"
        if r == 10:
            return f"(reduce * 1 {self.vec(d)})"
        if r == 11:
            return f"(apply {pick(['+', '*', 'max', 'min'])} {self.int(d)} {self.vec(d)})"
        if r == 12:
            return f"(nth {self.vec(d)} {idx()})"
        if r == 13:
            return f"(nth {self.vec(d)} {idx()} {self.int(d)})"
        if r == 14:
            return f"(get {self.vec(d)} {idx()} {self.int(d)})"
        if r == 15:
            return f"(get {self.map(d)} {pick(KWS)} {self.int(d)})"
        if r == 16:
            return f"({pick(KWS)} {self.map(d)} {self.int(d)})"
        if r == 17:
            return f"(if {self.bool(d)} {self.int(d)} {self.int(d)})"
        if r == 18:
            return f"(or (first {self.vec(d)}) {self.int(d)})"
        if r == 19:
            return f"(or (peek {self.vec(d)}) (last {self.vec(d)}) 0)"
        if r == 20:
            return f"(count (filter {pick(PREDS)} {self.vec(d)}))"
        if r == 21:
            return f"(reduce + (sort {self.set(d)}))"
        if r == 22:
            return f"(reduce + (vals {self.map(d)}))"
        if r == 23:
            v = f"x{len(self.ints)}"
            bound = self.int(d)
            self.ints.append(v)
            body = self.int(d)
            self.ints.pop()
            return f"(let [{v} {bound}] {body})"
        if r == 24:
            return f"(loop [i 0 acc {self.int(d)}] (if (< i {random.randint(0, 6)}) (recur (inc i) (+ acc i)) acc))"
        if r == 25:
            return f"(Integer/parseInt {self.str(d)})"
        if r == 26:
            return f"(or (parse-long {self.str(d)}) {self.int(d)})"
        if r == 27:
            return f"(long ({pick(['+', '*'])} {self.num(d)} {self.int(d)}))"
        if r == 28:
            return f"(try {self.int(d)} (catch ArithmeticException e -1))"
        if r == 29:
            return f"(try {self.int(d)} (catch IndexOutOfBoundsException e -2) (catch Exception e -3))"
        if r == 30:
            return f"(unchecked-{pick(['add', 'multiply', 'subtract'])} {self.int(d)} {self.int(d)})"
        if r == 31:
            return f"({pick(['+', '*'])} {self.int(d)} {self.int(d)})".replace("(+ ", "(+' ", 1).replace("(* ", "(*' ", 1)
        if r == 32:
            return f"(case {self.int(d)} 0 10 1 11 (2 3) 12 {self.int(d)})"
        return f"(cond {self.bool(d)} {self.int(d)} {self.bool(d)} {self.int(d)} :else {self.int(d)})"

    # ── exact/float numbers ──
    def num(self, d):
        if d >= MAXD or random.random() < 0.25:
            return pick([small(), "0.5", "1.5", "2.0", "1/3", "-7/2", "0.1"])
        d += 1
        r = random.randrange(6)
        if r == 0:
            return f"(/ {self.int(d)} {self.int(d)})"
        if r == 1:
            return f"({pick(['+', '-', '*'])} {self.num(d)} {self.num(d)})"
        if r == 2:
            # literal non-zero divisor: float division by an exact zero is a
            # known divergence (clove: ##Inf; boxed JVM math and bb: throw)
            return f"(/ {self.num(d)} {pick(['2', '-3', '0.5', '7/2', '10'])})"
        if r == 3:
            return f"(double {self.int(d)})"
        if r == 4:
            return f"({pick(['max', 'min'])} {self.num(d)} {self.int(d)})"
        return f"(* 0.5 {self.int(d)})"

    # ── bool ──
    def bool(self, d):
        if d >= MAXD or random.random() < 0.15:
            return pick(["true", "false"])
        d += 1
        r = random.randrange(14)
        if r == 0:
            return f"({pick(['<', '<=', '>', '>=', '=', 'not='])} {self.int(d)} {self.int(d)})"
        if r == 1:
            return f"({pick(['even?', 'odd?', 'pos?', 'neg?', 'zero?'])} {self.int(d)})"
        if r == 2:
            return f"({pick(['and', 'or'])} {self.bool(d)} {self.bool(d)})"
        if r == 3:
            return f"(not {self.bool(d)})"
        if r == 4:
            return f"(empty? {pick([self.vec, self.str, self.set, self.map])(d)})"
        if r == 5:
            return f"(contains? {self.set(d)} {self.int(d)})"
        if r == 6:
            return f"(contains? {self.map(d)} {pick(KWS)})"
        if r == 7:
            return f"(every? {pick(PREDS)} {self.vec(d)})"
        if r == 8:
            return f"(boolean (some {pick(PREDS)} {self.vec(d)}))"
        if r == 9:
            return f"(= {self.vec(d)} {self.vec(d)})"
        if r == 10:
            return f"(clojure.string/{pick(['starts-with?', 'ends-with?', 'includes?'])} {self.str(d)} {self.str(d)})"
        if r == 11:
            return f"(clojure.string/blank? {self.str(d)})"
        if r == 12:
            return f"(< {self.num(d)} {self.num(d)})"
        return f"(= {self.set(d)} (set {self.vec(d)}))"

    # ── string ──
    def str(self, d):
        if d >= MAXD or random.random() < 0.2:
            return lit_str()
        d += 1
        r = random.randrange(19)
        if r == 0:
            return f"(str {self.str(d)} {self.str(d)})"
        if r == 1:
            return f"(str {pick([self.int, self.bool, self.num, self.vec])(d)})"
        if r == 2:
            return f"(str {self.str(d)} {self.int(d)} {pick(KWS)} {self.vec(d)})"
        if r == 3:
            return f"(clojure.string/{pick(['upper-case', 'lower-case', 'reverse', 'trim', 'capitalize', 'triml', 'trimr'])} {self.str(d)})"
        if r == 4:
            return f"(subs {self.str(d)} {random.randint(0, 3)})"
        if r == 5:
            return f"(subs {self.str(d)} {random.randint(0, 2)} {random.randint(2, 5)})"
        if r == 6:
            return f"(clojure.string/join {lit_str()} {self.vec(d)})"
        if r == 7:
            return f"(apply str {self.vec(d)})"
        if r == 8:
            return f"(name {pick(KWS)})"
        if r == 9:
            return f"(clojure.string/replace {self.str(d)} {lit_str()} {lit_str()})"
        if r == 10:
            return f"(pr-str {pick([self.vec, self.str, self.int, self.bool])(d)})"
        if r == 11:
            return f"(str (sort {self.set(d)}))"
        if r == 12:
            return f"(str (into (sorted-map) {self.map(d)}))"
        if r == 13:
            return f"(format \"%d|%s|%5d\" {self.int(d)} {self.str(d)} {self.int(d)})"
        if r == 14:
            return f"(if {self.bool(d)} {self.str(d)} {self.str(d)})"
        if r == 15:
            return f"(print-str {self.str(d)} {self.vec(d)} {self.int(d)})"
        if r == 16:
            return f"(with-out-str (print {self.str(d)} {self.int(d)} (double {self.int(d)})) (prn {self.str(d)}))"
        if r == 17:
            return f"(try (str {self.int(d)}) (catch Exception e \"caught\"))"
        return f"(str (vec (clojure.string/split {self.str(d)} #\"[ ,]\")))"

    # ── vector of ints ──
    def vec(self, d):
        if d >= MAXD or random.random() < 0.2:
            if self.vecs and random.random() < 0.4:
                return pick(self.vecs)
            return lit_vec()
        d += 1
        r = random.randrange(30)
        if r == 0:
            return f"(vec (range {random.randint(-3, 3)} {random.randint(0, 8)}))"
        if r == 1:
            return f"(conj {self.vec(d)} {self.int(d)})"
        if r == 2:
            return f"(mapv {pick(FNS)} {self.vec(d)})"
        if r == 3:
            return f"(mapv {pick(['+', '*', 'max', '-'])} {self.vec(d)} {self.vec(d)})"
        if r == 4:
            return f"(vec ({pick(['filter', 'remove', 'take-while', 'drop-while'])} {pick(PREDS)} {self.vec(d)}))"
        if r == 5:
            return f"(vec ({pick(['take', 'drop', 'take-last', 'drop-last', 'take-nth'])} {random.randint(1, 4)} {self.vec(d)}))"
        if r == 6:
            return f"(vec ({pick(['reverse', 'sort', 'distinct', 'rest', 'butlast', 'dedupe', 'next'])} {self.vec(d)}))"
        if r == 7:
            return f"(into {self.vec(d)} {self.vec(d)})"
        if r == 8:
            return f"(vec (concat {self.vec(d)} {self.vec(d)}))"
        if r == 9:
            return f"(subvec {self.vec(d)} {random.randint(0, 2)} {random.randint(2, 5)})"
        if r == 10:
            return f"(vec (sort {self.set(d)}))"
        if r == 11:
            return f"(vec (map-indexed + {self.vec(d)}))"
        if r == 12:
            return f"(vec (keep (fn [k] (when ({pick(PREDS)} k) ({pick(FNS)} k))) {self.vec(d)}))"
        if r == 13:
            return f"(vec (interpose {self.int(d)} {self.vec(d)}))"
        if r == 14:
            return f"(vec (map count (partition-all {random.randint(1, 3)} {self.vec(d)})))"
        if r == 15:
            return f"(vec (map #(reduce + %) (partition {random.randint(1, 3)} {random.randint(1, 3)} {self.vec(d)})))"
        if r == 16:
            return f"(assoc {self.vec(d)} {idx()} {self.int(d)})"
        if r == 17:
            return f"(pop {self.vec(d)})"
        if r == 18:
            return f"(vec (reductions + {self.vec(d)}))"
        if r == 19:
            return f"(vec (mapcat (fn [x] [x ({pick(FNS)} x)]) {self.vec(d)}))"
        if r == 20:
            return f"(update {self.vec(d)} {idx()} {pick(FNS)})"
        if r == 21:
            return f"(vec (repeat {random.randint(0, 4)} {self.int(d)}))"
        if r == 22:
            return f"(vec (interleave {self.vec(d)} {self.vec(d)}))"
        if r == 23:
            return f"(vec (cons {self.int(d)} {self.vec(d)}))"
        if r == 24:
            return f"(vec (sort > {self.vec(d)}))"
        if r == 25:
            return f"(vec (sort-by {pick(['abs', '-', '(fn [m] (mod m 3))'])} {self.vec(d)}))"
        if r == 26:
            return f"(into [] (comp (map {pick(FNS)}) (filter {pick(PREDS)})) {self.vec(d)})"
        if r == 27:
            return f"(vec (for [x {self.vec(d)} :when ({pick(PREDS)} x)] ({pick(FNS)} x)))"
        if r == 28:
            v = f"v{len(self.vecs)}"
            bound = self.vec(d)
            self.vecs.append(v)
            body = self.vec(d)
            self.vecs.pop()
            return f"(let [{v} {bound}] {body})"
        return f"(vec (vals (into (sorted-map) {self.map(d)})))"

    # ── set of ints ──
    def set(self, d):
        if d >= MAXD or random.random() < 0.3:
            return lit_set()
        d += 1
        r = random.randrange(6)
        if r == 0:
            return f"(set {self.vec(d)})"
        if r == 1:
            return f"({pick(['conj', 'disj'])} {self.set(d)} {self.int(d)})"
        if r == 2:
            return f"(clojure.set/{pick(['union', 'intersection', 'difference'])} {self.set(d)} {self.set(d)})"
        if r == 3:
            return f"(set (filter {pick(PREDS)} {self.set(d)}))"
        if r == 4:
            return f"(into #{{}} (map {pick(FNS)}) {self.vec(d)})"
        return f"(set (keys (frequencies {self.vec(d)})))"

    # ── map keyword → int ──
    def map(self, d):
        if d >= MAXD or random.random() < 0.3:
            return lit_map()
        d += 1
        r = random.randrange(9)
        if r == 0:
            return f"(assoc {self.map(d)} {pick(KWS)} {self.int(d)})"
        if r == 1:
            return f"(dissoc {self.map(d)} {pick(KWS)})"
        if r == 2:
            return f"(merge {self.map(d)} {self.map(d)})"
        if r == 3:
            return f"(merge-with {pick(['+', '*', 'max'])} {self.map(d)} {self.map(d)})"
        if r == 4:
            return f"(update {self.map(d)} {pick(KWS)} (fnil {pick(['inc', 'dec'])} 0))"
        if r == 5:
            return f"(select-keys {self.map(d)} {kwvec()})"
        if r == 6:
            return f"(zipmap {kwvec()} {self.vec(d)})"
        if r == 7:
            return f"(reduce-kv (fn [a k v] (assoc a k ({pick(FNS)} v))) {{}} {self.map(d)})"
        return f"(into {{}} (map (fn [[k v]] [k ({pick(FNS)} v)])) {self.map(d)})"

    def top(self):
        r = random.randrange(12)
        if r < 3:
            return self.int(0)
        if r < 5:
            return self.vec(0)
        if r < 7:
            return self.str(0)
        if r == 7:
            return self.bool(0)
        if r == 8:
            return self.num(0)
        if r == 9:
            return f"(sort {self.set(0)})"                    # sets print via a sorted view
        if r == 10:
            return f"(into (sorted-map) {self.map(0)})"       # so do maps
        return f"[{self.int(0)} {self.str(0)} {self.bool(0)}]"


g = Gen()
print(f"; gen_expr.py seed={seed} n={n}")
for _ in range(n):
    print(g.top())
