;; # Libraries
;;
;; `require` looks for namespaces on the load path: the current directory,
;; `./vendor`, the directory holding the `clove` binary, and the install's
;; share directory (`~/.local/share/clove`). Add more roots with
;; `CLOVE_PATH`, colon-separated. `my.lib-name` loads from
;; `my/lib_name.clj`, or `.cljc`.
;;
;; ## What comes with clove
;;
;; These load with a plain `require`:
;;
;; | namespace | |
;; |---|---|
;; | `clojure.string` `clojure.set` `clojure.walk` `clojure.zip` `clojure.edn` | |
;; | `clojure.data` `clojure.datafy` `clojure.math` `clojure.pprint` `clojure.stacktrace` | |
;; | `clojure.test` | `deftest`, `is`, `testing`, `are` |
;; | `clojure.core.async` | channels and `go` blocks, on coroutines |
;; | `clojure.core.match` | pattern matching |
;; | `clojure.data.json`, `cheshire.core` | JSON |
;; | `clj-yaml.core` | YAML, a pure-Clojure subset |
;; | `nextjournal.markdown` | markdown to hiccup |
;; | `clojure.tools.logging`, `taoensso.timbre` | logging to stderr |
;; | `babashka.fs` `babashka.process` `babashka.cli` `bencode.core` | |
;; | `medley.core`, `camel-snake-kebab.core` | |
;;
;; clove also ships batteries of its own, which load with `load-file` or
;; `require`: `json`, `fs`, `process`, `http` (a client and a server), `peg`
;; (Janet's parsing expression grammars), `csp`, and `libc`.
;;
;; ## Vendoring a library
;;
;; `clove vendor` copies a library's source into `./vendor/`, then tries
;; loading each of its namespaces and tells you which ones work.
;;
;; ```sh
;; clove vendor weavejester/medley                  # Clojars, Maven Central, then GitHub
;; clove vendor https://github.com/clojure/data.csv # any git URL
;; clove vendor                                     # everything in ./deps.edn
;; ```
;;
;; With no argument it reads `deps.edn` and resolves `:deps` transitively.
;; Maven coordinates are fetched as jars and followed through their poms,
;; `:git/url` deps are cloned at their `:git/sha` or `:git/tag`, and
;; `:local/root` deps are copied. Java-only jars are skipped. There's no JVM
;; and no `~/.m2` involved.
;;
;; ## What runs
;;
;; Pure-Clojure libraries usually work, including big ones built on
;; `deftype`, protocols and multimethods. We've run these from their own
;; source, unmodified:
;;
;; | library | what we tried |
;; |---|---|
;; | [DataScript](https://github.com/tonsky/datascript) | `q`, `pull`, `transact!` |
;; | [core.logic](https://github.com/clojure/core.logic) | `run*`, `conde`, `membero` |
;; | [Emmy](https://github.com/mentat-collective/emmy) | `simplify`, `D`, Lagrangian mechanics |
;; | [instaparse](https://github.com/Engelberg/instaparse) | grammars to parse trees |
;; | [hiccup](https://github.com/weavejester/hiccup) | HTML generation |
;; | [specter](https://github.com/redplanetlabs/specter) | its own test suite passes |
;; | [meander](https://github.com/noprompt/meander) | `match`, `search`, `find` (not `rewrite`) |
;; | [test.check](https://github.com/clojure/test.check) | generative tests |
;; | [SCI](https://github.com/babashka/sci) | a Clojure interpreter inside clove |
;; | data.csv, tools.cli | |
;;
;; Libraries that wrap a Java library are another story. `clojure.data.xml`
;; needs a real XML parser, and transit needs Java's serializers. Those won't
;; load until someone writes the Java side in clove or C.
;;
;; When a library almost works, the missing piece is usually one Java
;; method. Please [open an issue](https://github.com/alexalemi/clove/issues)
;; with the error.
