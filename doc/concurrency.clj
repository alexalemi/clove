;; # Concurrency
;;
;; clove runs on one OS thread. Concurrency comes from coroutines: a
;; `future`, a `go` block or an agent action runs as a fiber, and fibers
;; switch whenever one waits, whether on a channel, a `deref`, `Thread/sleep`,
;; or a socket. One scheduler runs all of them, so a `future`, a `go` block
;; and an agent can wait on each other. There's no parallelism: two fibers
;; never run at the same moment, and a loop that never waits holds up
;; everything else.
;;
;; None of this exists on Windows or in the browser; neither has the
;; coroutine support it needs. The examples on this page aren't live for
;; that reason.
;;
;; ## Futures and promises
;;
;; ```clojure
;; (def total (future (Thread/sleep 100) (+ 1 2)))
;; @total                                              ;=> 3
;;
;; (deref (future (Thread/sleep 1000) :slow) 50 :timeout)  ;=> :timeout
;; ```
;;
;; A future that throws throws again when you `deref` it, wrapped in a
;; `java.util.concurrent.ExecutionException` whose `ex-cause` is the
;; original, as on the JVM. `promise` and `deliver` work as in Clojure.
;;
;; ## core.async
;;
;; `clojure.core.async` is the real API: channels, buffers, transducers on
;; channels, `go`, `<!`, `>!`, `alts!` and `timeout`. It works like the
;; ClojureScript version, with all the go blocks on one thread, but `<!!`
;; and `>!!` work as well, because clove owns the main loop.
;;
;; ```clojure
;; (require '[clojure.core.async :as a])
;;
;; (let [done (a/chan 10)]
;;   (doseq [job ["fetch" "parse" "store"]]
;;     (a/go
;;       (a/<! (a/timeout (rand-int 50)))   ; pretend to work
;;       (a/>! done job)))
;;   (repeatedly 3 #(a/<!! done)))
;; ;=> ("fetch" "store" "parse"), in whatever order they finished
;; ```
;;
;; `csp.clj` is the same idea as a smaller library, and
;; `examples/csp-http-server.clj` serves HTTP from go blocks.
;;
;; ## Agents and refs
;;
;; Agents run their actions in order, on a fiber of their own. Refs give you
;; transactions: `dosync` retries when another fiber committed a change to a
;; ref it read, and holds any agent sends until it commits.
;;
;; ```clojure
;; (def balance (ref 100))
;; (def audit (agent []))
;;
;; (dosync
;;   (alter balance - 30)
;;   (send audit conj [:withdraw 30]))
;;
;; (await audit)
;; [@balance @audit]   ;=> [70 [[:withdraw 30]]]
;; ```
;;
;; ## Coroutines
;;
;; Everything above runs on three primitives, which you can use directly:
;;
;; ```clojure
;; (def gen (coro/new (fn [] (coro/yield 1) (coro/yield 2) 3)))
;; [(coro/resume gen) (coro/resume gen) (coro/resume gen)]   ;=> [1 2 3]
;; ```
