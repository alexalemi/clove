;; claude.clj — ask Claude about your REPL session.
;;
;; At the REPL, start a line with `? ` (question mark, space):
;;
;;   clove[3]> ? why did that throw
;;
;; The question goes to Claude together with this session's transcript (inputs,
;; results, errors, earlier Q&A) and a standing prompt about clove. The answer
;; streams in as it is written, and is a result like any other: *1 holds it.
;;
;; Claude has two tools:
;;   clove_lookup   read-only: doc / source / dir / apropos / find-doc / a var's
;;                 value. Runs without asking — it evaluates no code.
;;   propose_form  Claude writes a line into your prompt; edit it, Enter runs
;;                 it, ^C (an empty line) declines. Claude sees what you ran and
;;                 its output. Nothing Claude writes runs without your Enter.
;;
;; Programmatic use: (require '[claude]) (claude/ask "what does juxt do?")
;; Tuning: (swap! claude/config assoc :effort "low")
;;
;; Needs curl and ANTHROPIC_API_KEY. The key is expanded by the shell inside the
;; curl command, so it never passes through clove values.

(require '[json] '[process])

(def config
  (atom {:model      "claude-opus-5-5"
         :max-tokens 16000
         :effort     "medium"   ; low | medium | high | xhigh | max
         :max-rounds 8          ; tool-call rounds per question
         :max-result 8000}))    ; chars of tool output sent back

(def system-prompt
  "You are embedded in the REPL of clove, a Clojure interpreter written in a single C file (no JVM). The user typed a question at the REPL prompt; you see their session transcript and answer as a knowledgeable pair programmer.

About clove:
- Clojure semantics: persistent collections, lazy seqs, macros, destructuring, protocols/records, multimethods, ratios, BigInt/BigDecimal, namespaces, transients, regex (own engine, Java-ish syntax), try/catch with typed catch.
- No JVM: Java interop is limited to a shimmed subset (common String/Math/System/Integer statics, StringBuilder, StringWriter, ...). JVM-only libraries do not work.
- Concurrency is cooperative: future/promise/agents/STM run on a fiber scheduler, core.async runs on coroutines. There is a single global environment.
- Batteries loaded with (require '[name]): json, fs, process, http (async HTTP/1.1, no TLS), csp, peg, test, nrepl, clerk, bundle, jit, libc (FFI). Vendored libs include clojure.set/string/walk/zip/edn, core.async, core.logic, datascript, emmy, specter, meander, instaparse, hiccup, medley.
- REPL niceties: (doc x) (source x) (dir ns) (apropos \"s\") (find-doc \"s\") (dbg x) / #p x, *1 *2 *3, (*results* n) by prompt number, *e and (pst) after an error, !cmd shells out.
- Behavior can diverge from JVM Clojure in edge cases; when it matters, check rather than assume.

Tools:
- clove_lookup is read-only and runs immediately: use it freely for docs, source, namespace contents, and the current value of the user's vars.
- propose_form puts ONE line of code in the user's prompt. They decide: run it as is, edit it first, or decline. Use it when running code would settle the question (testing a claim, computing something from their data) or when the answer is a line they will want to run. You get back the line they actually ran and its output. If they decline, do not propose it again; answer from what you know. Propose side-effecting forms (def, spit, sh) only when the user asked for that effect.

Answering: the reply streams into a terminal REPL, so be brief and concrete — a few sentences and maybe a snippet. Plain text with light markdown (backticks, fenced blocks for multi-line code); no headings or tables. Refer to transcript entries by prompt number, e.g. [3]. After a proposed form has run, add at most a sentence; say nothing more if the output speaks for itself.")

(def tools
  [{"name" "clove_lookup"
    "description" "Read-only lookup in the user's live clove session. kind: doc (docstring+arglists), source (definition), dir (a namespace's public vars), apropos (vars whose name contains the string), find-doc (vars whose doc mentions the string), value (the current value of a var, printed with length limits)."
    "input_schema" {"type" "object"
                    "properties" {"kind" {"type" "string"
                                          "enum" ["doc" "source" "dir" "apropos" "find-doc" "value"]}
                                  "name" {"type" "string"
                                          "description" "Symbol (e.g. clojure.string/join, xs), namespace, or search string"}}
                    "required" ["kind" "name"]
                    "additionalProperties" false}
    "strict" true
    "eager_input_streaming" true}
   {"name" "propose_form"
    "description" "Place one line of Clojure in the user's REPL prompt. The user may run it (Enter), edit it first, or decline. Returns the line actually run and its printed output and value, or that the user declined. Write a single line with no comments."
    "input_schema" {"type" "object"
                    "properties" {"code" {"type" "string"
                                          "description" "One line of Clojure, no comments"}}
                    "required" ["code"]
                    "additionalProperties" false}
    "strict" true
    "eager_input_streaming" true}])

(defn- dim [s] (str "\u001b[2m" s "\u001b[0m"))

(defn- clip [s n]
  (if (> (count s) n) (str (subs s 0 n) "\n... [truncated]") s))

;; ── request ──────────────────────────────────────────────────────────────
(defn request
  "The Messages API body for a conversation so far (no network)."
  [messages]
  (let [{:keys [model max-tokens effort]} @config]
    {"model"         model
     "max_tokens"    max-tokens
     "stream"        true
     "system"        [{"type" "text" "text" system-prompt
                       "cache_control" {"type" "ephemeral"}}]
     "thinking"      {"type" "adaptive"}
     "output_config" {"effort" effort}
     "fallbacks"     "default"
     "tools"         tools
     "messages"      messages}))

(defn first-message
  "The opening user turn: transcript (if any) then the question."
  [question transcript]
  {"role" "user"
   "content" (str (when (seq transcript)
                    (str "<repl-transcript>\n" transcript "</repl-transcript>\n\n"))
                  question)})

;; ── streaming ────────────────────────────────────────────────────────────
;; The response is server-sent events; each `data: {...}` line is one event.
;; Content blocks are rebuilt by index so the assistant turn can be sent back
;; verbatim (thinking blocks and their signatures included).

(defn- finish-block [b]
  (case (get b "type")
    "tool_use" (let [raw (get b "partial" "")]
                 (-> b (dissoc "partial")
                     (assoc "input" (if (str/blank? raw) {}
                                        (try (json/parse raw)
                                             (catch Exception _ {}))))))   ; run-tool rejects it
    b))

(defn sse-step
  "Fold one SSE line into the stream state. on-text sees each text delta."
  [st line on-text]
  (cond
    (str/starts-with? line "data: ")
    (let [ev (json/parse (subs line 6))]
      (case (get ev "type")
        "content_block_start"
        (assoc-in st [:blocks (get ev "index")] (get ev "content_block"))

        "content_block_delta"
        (let [i (get ev "index") d (get ev "delta")]
          (case (get d "type")
            "text_delta"      (do (on-text (get d "text"))
                                  (update-in st [:blocks i "text"] str (get d "text")))
            "input_json_delta" (update-in st [:blocks i "partial"] str (get d "partial_json"))
            "thinking_delta"  (update-in st [:blocks i "thinking"] str (get d "thinking"))
            "signature_delta" (assoc-in st [:blocks i "signature"] (get d "signature"))
            st))

        "content_block_stop"
        (update-in st [:blocks (get ev "index")] finish-block)

        "message_delta"
        (cond-> st
          (get-in ev ["delta" "stop_reason"])  (assoc :stop (get-in ev ["delta" "stop_reason"]))
          (get-in ev ["delta" "stop_details"]) (assoc :stop-details (get-in ev ["delta" "stop_details"])))

        "error" (assoc st :error (get ev "error"))
        st))

    ;; a non-SSE body: an HTTP error comes back as plain JSON, curl as text
    (or (str/starts-with? line "event:") (str/blank? line)) st
    :else (update st :raw str line "\n")))

(defn- stream! [body on-text]
  (let [tmp (str/trim (:out (sh "mktemp")))
        _   (spit tmp (json/write body))
        st  (atom {:blocks (sorted-map)})
        ex  (try
              (clove/sh-lines*
               (str "curl -sS -N https://api.anthropic.com/v1/messages"
                    " -H 'content-type: application/json'"
                    " -H \"x-api-key: $ANTHROPIC_API_KEY\""
                    " -H 'anthropic-version: 2023-06-01'"
                    " -H 'anthropic-beta: server-side-fallback-2026-07-01'"
                    " --data-binary @" (process/escape tmp))
               (fn [line] (swap! st sse-step line on-text)))
              (finally (sh (str "rm -f " (process/escape tmp)))))
        {:keys [raw error] :as s} @st]
    (when-let [err (or error (when raw (get (try (json/parse raw) (catch Exception _ nil)) "error")))]
      (throw (ex-info (str "claude: " (get err "type") ": " (get err "message")) {:error err})))
    (when (and raw (not (zero? ex)))
      (throw (ex-info (str "claude: curl failed: " (str/trim raw)) {:exit ex})))
    (assoc s :content (vec (vals (:blocks s))))))

;; ── tools ────────────────────────────────────────────────────────────────
(defn- capped [f]
  (binding [*print-length* 50 *print-level* 8]
    (clip (try (f) (catch Exception e (str "!! " (or (ex-message e) (pr-str e)))))
          (:max-result @config))))

(defn lookup [kind nm]
  (capped
   #(let [sym (symbol nm)]
      (case kind
        "doc"      (with-out-str (eval (list 'doc sym)))
        "source"   (with-out-str (eval (list 'source sym)))
        "dir"      (with-out-str (eval (list 'dir sym)))
        "apropos"  (pr-str (apropos nm))
        "find-doc" (with-out-str (find-doc nm))
        "value"    (if-let [v (resolve sym)] (pr-str @v) (str nm " does not resolve"))
        (str "unknown kind " kind)))))

(defn- eval-code
  "Evaluate a line in the live session: printed output, then => value."
  [code]
  (let [res (atom nil)
        out (with-out-str
              (reset! res (capped #(let [forms (read-string (str "[" code "\n]"))]
                                     (str "=> " (pr-str (reduce (fn [_ f] (eval f)) nil forms)))))))]
    (str out @res)))

(defn- one-line [code]
  (str/trim (str/replace code #"\s*\n\s*" " ")))

(defn- propose [code]
  (if-not (clove/isatty*)
    "Not an interactive terminal; the form was not shown or run."
    (if-let [line (clove/repl-edit* (dim "claude⟩ ") (one-line code))]
      (let [r (eval-code line)]
        (println r)
        (clove/repl-log* (str "claude> " line "\n" r))
        (str "The user ran: " line "\n" r))
      (do (println (dim "  (declined)"))
          "The user declined to run it."))))

(defn- run-tool [block]
  (let [in   (get block "input")
        res  (fn [content & [err?]]
               (cond-> {"type" "tool_result" "tool_use_id" (get block "id") "content" content}
                 err? (assoc "is_error" true)))]
    (cond
      (not (or (contains? in "code") (contains? in "kind")))
      (res "Invalid or incomplete tool input; send the call again." true)
      (= "clove_lookup" (get block "name"))
      (do (println (dim (str "  ⟩ " (get in "kind") " " (get in "name"))))
          (res (lookup (get in "kind") (get in "name" ""))))
      (= "propose_form" (get block "name"))
      (res (propose (get in "code" "")))
      :else (res (str "No tool named " (get block "name")) true))))

;; ── the loop ─────────────────────────────────────────────────────────────
(defn- text-of [content]
  (str/trim (str/join "\n\n" (for [b content :when (= "text" (get b "type"))]
                               (get b "text")))))

(defn ask
  "Ask Claude a question, optionally with a REPL transcript for context.
  Streams the answer to *out*, runs the tool loop, returns the answer text."
  ([question] (ask question nil))
  ([question transcript]
   (when-not (seq (System/getenv "ANTHROPIC_API_KEY"))
     (throw (ex-info "claude: ANTHROPIC_API_KEY is not set (export it, then ask again)" {})))
   (loop [messages [(first-message question transcript)]
          answer   []
          round    0]
     (let [waiting (atom true)
           bol     (atom true)          ; cursor at the start of a line?
           on-text (fn [t]
                     (when @waiting (reset! waiting false) (print "\r\u001b[K"))
                     (print t) (flush)
                     (when (seq t) (reset! bol (str/ends-with? t "\n"))))
           _       (do (print (dim "  thinking…")) (flush))
           {:keys [content stop stop-details]}
           (try (stream! (request messages) on-text)
                (finally (when @waiting (print "\r\u001b[K") (flush))))
           _       (when-not @bol (println))
           answer  (conj answer (text-of content))]
       (cond
         (= stop "refusal")
         (let [m (str "[claude declined to answer"
                      (when-let [c (get stop-details "category")] (str ": " c)) "]")]
           (println m)
           (str/join "\n\n" (conj answer m)))

         (and (= stop "tool_use") (< round (:max-rounds @config)))
         (let [results (vec (for [b content :when (= "tool_use" (get b "type"))]
                              (run-tool b)))]
           (recur (conj messages
                        {"role" "assistant" "content" content}
                        {"role" "user" "content" results})
                  answer
                  (inc round)))

         :else
         (let [note (case stop
                      "max_tokens" "[answer cut off at max_tokens]"
                      "tool_use"   (str "[stopped after " (:max-rounds @config) " tool rounds]")
                      nil)]
           (when note (println note))
           (str/join "\n\n" (remove str/blank? (conj answer note)))))))))
