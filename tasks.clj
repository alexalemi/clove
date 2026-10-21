;; tasks.clj — bb-tasks-style task runner and project scaffolding.
;;
;;   clove run <task> [args]   run a task from the nearest clove.edn
;;   clove run                 list the tasks (same as `clove tasks`)
;;   clove tasks               list the tasks with their docs
;;   clove new <name> [dir]    scaffold a project (src/, test/, clove.edn)
;;
;; clove.edn is found in the cwd or the nearest parent directory:
;;
;;   {:paths    ["src" "test"]              ; added to the load path, see below
;;    :requires [[clojure.string :as s]]    ; required before Clojure tasks
;;    :tasks
;;    {build  "make all"                                 ; string → shell command
;;     hello  (println "hi" *command-line-args*)        ; list   → Clojure form
;;     test   {:doc "run the tests" :task "clove test"} ; map    → :task :doc :depends
;;     ci     {:doc "everything" :depends [build test]} ; deps run first, once each
;;     repl   {:task "clove repl" :interactive true}    ; see "interactive" below
;;     -setup "echo private"}}                           ; leading - : hidden from lists
;;
;; Working directory and load path:
;; - Shell tasks run in clove.edn's directory (the command is prefixed with a
;;   `cd`), with :paths exported in CLOVE_PATH so a nested `clove test` or
;;   `clove -m app.core` finds src/ and test/. A bare `clove` invocation outside
;;   a task does not read clove.edn's :paths (only deps.edn/bb.edn's), so
;;   `clove run test` is the way to get them.
;; - Clojure tasks run in-process, in the invoking cwd (clove has no portable
;;   chdir); :paths are added to *load-path* resolved against clove.edn's
;;   directory, which is also bound to tasks/*root* (a relative path).
;;
;; Arguments after the task name go to that task only, not its :depends:
;; appended shell-quoted to a shell command, or bound to *command-line-args*
;; for a Clojure form. A shell task's nonzero exit stops the run and becomes
;; clove's exit status. Output streams line by line through a pipe, so a
;; program that prompts without a newline (a REPL) would look hung:
;; :interactive true sends such a task's output straight to the terminal.
;;
;; Note `clove run x` runs the FILE x when one exists, never a task named x.

(require '[process])

(def ^:dynamic *root* ".")

(defn- windows? [] (= :windows (clove/os*)))

(defn- fail
  "Throw a task error: printed as a plain message (no stack) by the mains."
  [msg]
  (throw (ex-info msg {:tasks/error true})))

;; ── config ──────────────────────────────────────────────────────────────────

(defn normalize-task
  "A task value → {:task form-or-string-or-nil :doc :depends [names] ...}."
  [tname v]
  (let [m (cond
            (string? v) {:task v}
            (seq? v)    {:task v}
            (map? v)    v
            :else (fail (str "task " tname ": expected a string, a list, or a map, got "
                             (pr-str v))))
        t (:task m)]
    (when-not (or (nil? t) (string? t) (seq? t))
      (fail (str "task " tname ": :task must be a string or a list, got " (pr-str t))))
    (when (and (nil? t) (empty? (:depends m)))
      (fail (str "task " tname ": has neither :task nor :depends")))
    (assoc m :depends (mapv str (:depends m)))))

(defn parse-config
  "clove.edn data → {:paths [..] :requires [..] :tasks {\"name\" task}}.
  Task names become strings, the spelling they arrive in from the CLI."
  [edn]
  (when-not (map? edn) (fail "clove.edn: expected a map"))
  {:paths    (mapv str (:paths edn))
   :requires (vec (:requires edn))
   :tasks    (into (sorted-map)
                   (map (fn [[k v]] [(str k) (normalize-task (str k) v)]))
                   (:tasks edn))})

(defn find-root
  "Relative path (\".\", \"..\", \"../..\" ...) of the nearest dir with a
  clove.edn, or nil. Relative like clove/project-paths: clove has no
  portable cwd, and a path relative to an unchanged cwd stays correct."
  []
  (loop [pre "" n 0]
    (cond
      (try (slurp (str pre "clove.edn")) true (catch Exception e false))
      (if (= pre "") "." (subs pre 0 (dec (count pre))))
      (> n 20) nil
      :else (recur (str pre "../") (inc n)))))

(defn load-config [root]
  (let [f (str root "/clove.edn")]
    (parse-config (try (read-string (slurp f))
                       (catch Exception e
                         (fail (str f ": " (ex-message e))))))))

;; ── names ───────────────────────────────────────────────────────────────────

(defn levenshtein [a b]
  (let [b (vec b)]
    (last (reduce (fn [prev [i ca]]
                    (reduce (fn [row [j cb]]
                              (conj row (min (inc (peek row))
                                             (inc (nth prev (inc j)))
                                             (+ (nth prev j) (if (= ca cb) 0 1)))))
                            [(inc i)]
                            (map-indexed vector b)))
                  (vec (range (inc (count b))))
                  (map-indexed vector a)))))

(defn suggest
  "The closest of names to s within edit distance 2, else nil."
  [s names]
  (let [scored (sort-by first (map (fn [n] [(levenshtein s n) n]) names))
        [d n] (first scored)]
    (when (and d (<= d 2) (< d (count s))) n)))

(defn visible-names [tasks]
  (remove #(str/starts-with? % "-") (keys tasks)))

(defn unknown-task-message [tname tasks]
  (let [names (visible-names tasks)
        dym (suggest tname (keys tasks))]
    (str "no task or file named `" tname "`."
         (when dym (str " Did you mean `" dym "`?"))
         (if (seq names)
           (str "\nAvailable tasks: " (str/join " " names))
           "\nclove.edn defines no tasks."))))

;; ── dependency order ───────────────────────────────────────────────────────

(defn plan
  "Names to run, in order, for task tname: depth-first through :depends, each
  task at most once, tname last. Throws on an unknown name or a cycle."
  [tasks tname]
  (letfn [(visit [[order done] path n]
            (cond
              (some #{n} path)
              (fail (str "dependency cycle: "
                         (str/join " -> " (concat (drop-while #(not= % n) path) [n]))))
              (contains? done n) [order done]
              (not (contains? tasks n))
              (fail (if (seq path)
                      (str "task `" (peek path) "` depends on unknown task `" n "`")
                      (unknown-task-message n tasks)))
              :else
              (let [[order done] (reduce (fn [acc d] (visit acc (conj path n) d))
                                         [order done]
                                         (:depends (get tasks n)))]
                [(conj order n) (conj done n)])))]
    (first (visit [[] #{}] [] tname))))

;; ── listing ─────────────────────────────────────────────────────────────────

(defn listing-lines
  "`bb tasks`-style rows: visible names padded to one column, then the doc."
  [tasks]
  (let [names (visible-names tasks)
        w (apply max 0 (map count names))]
    (map (fn [n]
           (let [doc (:doc (get tasks n))]
             (if doc
               (str n (apply str (repeat (- (+ w 2) (count n)) " ")) doc)
               n)))
         names)))

;; ── shell ───────────────────────────────────────────────────────────────────

(defn shell-quote [win? s]
  (if win?
    (str "\"" (str/replace s "\"" "\\\"") "\"")   ; cmd.exe has no real quoting
    (process/escape s)))

(defn shell-command
  "The full command line for a shell task: cd to the project root, export
  :paths in CLOVE_PATH (absolute on POSIX, so they survive a `cd` inside the
  task), then cmd with args quoted onto the end."
  [cmd args root paths win?]
  (let [full (str/join " " (cons cmd (map #(shell-quote win? %) args)))]
    (if win?
      (str "cd /d " (shell-quote true (str/replace root "/" "\\"))
           (when (seq paths)
             (str " && set \"CLOVE_PATH=" (str/join ":" paths) ":%CLOVE_PATH%\""))
           " && " full)
      (str "cd " (shell-quote false root) " || exit 1; "
           (when (seq paths)
             (str "CLOVE_PATH=\"" (str/join ":" (map #(str "$PWD/" %) paths))
                  "${CLOVE_PATH:+:$CLOVE_PATH}\"; export CLOVE_PATH; "))
           full))))

(defn- run-shell [task args root paths]
  (let [win? (windows?)
        cmd (shell-command (:task task) args root paths win?)]
    (if (and (:interactive task) (clove/isatty*))
      ;; straight to the terminal, bypassing the line pipe (prompts show)
      (clove/sh-lines* (str cmd (if win? " >CON" " >/dev/tty")) println)
      (clove/sh-lines* cmd (fn [line] (println line) (flush))))))

;; ── running ─────────────────────────────────────────────────────────────────

(defn- add-paths! [root paths]
  (when (seq paths)
    (let [ps (map #(if (= root ".") % (str root "/" %)) paths)]
      (alter-var-root #'*load-path* (fn [lp] (vec (distinct (concat ps lp))))))))

(defn- run-clojure [task args]
  (binding [*command-line-args* (seq args)]
    (eval (:task task)))
  0)

(defn- report [e]
  (binding [*out* *err*] (println (str "clove run: " (ex-message e))))
  1)

(defn- task-error? [e] (:tasks/error (ex-data e)))

(defn- print-listing [cfg]
  (let [lines (listing-lines (:tasks cfg))]
    (if (seq lines)
      (do (println "The following tasks are available:\n")
          (doseq [l lines] (println l)))
      (println "clove.edn defines no tasks."))
    0))

(def ^:private no-config-message
  "no clove.edn in this directory or any parent (see `clove new` to start a project)")

(defn run-task
  "Run tname (after its :depends) from the config at root; exit status."
  [cfg root tname args]
  (let [order (plan (:tasks cfg) tname)
        required (atom false)]
    (add-paths! root (:paths cfg))
    (binding [*root* root]
      (loop [[n & more] order]
        (if-not n
          0
          (let [t (get (:tasks cfg) n)
                a (if (= n tname) args [])
                status (cond
                         (nil? (:task t)) 0
                         (string? (:task t)) (run-shell t a root (:paths cfg))
                         :else (do (when-not @required
                                     (reset! required true)
                                     (doseq [r (:requires cfg)]
                                       (eval (list 'require (list 'quote r)))))
                                   (run-clojure t a)))]
            (if (zero? status)
              (recur more)
              (do (binding [*out* *err*]
                    (println (str "clove run: task `" n "` failed (exit " status ")")))
                  status))))))))

(defn run-main [args]
  (try
    (let [root (find-root)]
      (cond
        (and (nil? root) (empty? args))
        (fail (str no-config-message "\nusage: clove run <file.clj | task> [args]"))
        (nil? root)
        (fail (str "no file named `" (first args) "`, and " no-config-message))
        :else
        (let [cfg (load-config root)]
          (if (empty? args)
            (print-listing cfg)
            (run-task cfg root (first args) (vec (rest args)))))))
    (catch Exception e
      (if (task-error? e) (report e) (throw e)))))

(defn list-main [args]
  (try
    (if-let [root (find-root)]
      (print-listing (load-config root))
      (fail no-config-message))
    (catch Exception e
      (if (task-error? e) (report e) (throw e)))))

;; ── clove new ───────────────────────────────────────────────────────────────

(defn valid-project-name? [s]
  (boolean (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*(\.[a-zA-Z][a-zA-Z0-9_-]*)*" s)))

(defn ns->path
  "Namespace → file path under a source root, as require resolves it:
  my-app.core → my_app/core.clj."
  [ns-str]
  (str (str/replace (str/replace ns-str "-" "_") "." "/") ".clj"))

(defn scaffold-files
  "Relative path → contents for a new project called pname."
  [pname]
  (let [main-ns (str pname ".core")
        test-ns (str pname ".core-test")]
    {"clove.edn"
     (str ";; Tasks: `clove run <task>`; list them with `clove tasks`.\n"
          ";; :paths are on the load path of every task (shell tasks get them\n"
          ";; through CLOVE_PATH), so src/ and test/ namespaces can require each other.\n"
          "{:paths [\"src\" \"test\"]\n"
          " :tasks\n"
          " {test {:doc \"Run the tests\" :task \"clove test\"}\n"
          "  run  {:doc \"Run " main-ns "/-main\" :task \"clove -m " main-ns "\"}\n"
          "  repl {:doc \"Start a REPL with src/ and test/ on the load path\"\n"
          "        :task \"clove repl\" :interactive true}}}\n")

     (str "src/" (ns->path main-ns))
     (str "(ns " main-ns ")\n\n"
          "(defn greet [who]\n"
          "  (str \"Hello, \" who \"!\"))\n\n"
          "(defn -main [& args]\n"
          "  (println (greet (or (first args) \"world\"))))\n")

     (str "test/" (ns->path test-ns))
     (str "(ns " test-ns "\n"
          "  (:require [clojure.test :refer [deftest is]]\n"
          "            [" main-ns " :as core]))\n\n"
          "(deftest greet-test\n"
          "  (is (= \"Hello, world!\" (core/greet \"world\"))))\n")

     "README.md"
     (str "# " pname "\n\n"
          "A [clove](https://github.com/alexalemi/clove) project.\n\n"
          "```sh\n"
          "clove run run [name]   # run " main-ns "/-main\n"
          "clove run test         # run the tests under test/\n"
          "clove run repl         # REPL with src/ and test/ on the load path\n"
          "clove tasks            # list the tasks in clove.edn\n"
          "```\n")

     ".gitignore"
     ".cpcache/\n.nrepl-port\ntarget/\n"}))

(defn- mkdirs [d]
  (if (windows?)
    (sh (str "mkdir " (shell-quote true (str/replace d "/" "\\"))))
    (sh (str "mkdir -p " (shell-quote false d))))
  (when-not (clove/dir?* d) (fail (str "cannot create directory " d))))

(defn new-main [args]
  (try
    (let [[pname dir] args
          dir (or dir pname)]
      (cond
        (nil? pname)
        (fail "usage: clove new <name> [dir]")
        (not (valid-project-name? pname))
        (fail (str "`" pname "` is not a valid project name: use letters, digits, - and _,"
                   " starting with a letter (dots make a multi-segment namespace)"))
        (seq (clove/list-dir* dir))
        (fail (str dir " exists and is not empty"))
        (and (not (clove/dir?* dir))
             (try (slurp dir) true (catch Exception e false)))
        (fail (str dir " exists and is not a directory"))
        :else
        (let [files (scaffold-files pname)]
          (doseq [[rel content] (sort files)]
            (let [p (str dir "/" rel)
                  parent (str/join "/" (butlast (str/split p "/")))]
              (mkdirs parent)
              (spit p content)))
          (println (str "Created " dir "/:"))
          (doseq [rel (sort (keys files))] (println (str "  " rel)))
          (println (str "\nNext:\n  cd " dir "\n  clove run test\n  clove run run"))
          0)))
    (catch Exception e
      (if (task-error? e)
        (do (binding [*out* *err*] (println (str "clove new: " (ex-message e)))) 1)
        (throw e)))))
