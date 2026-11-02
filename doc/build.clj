;; Builds the docs site into _site/. Run it from the repo root:
;;
;;   ./clove doc/build.clj
;;   python3 -m http.server -d _site      # then open localhost:8000
;;
;; Every page is a notebook in doc/, rendered in its own clove process so one
;; page's defs can't leak into the next. The site is served from a subpath
;; (alexalemi.github.io/clove/), so all links are relative and the output is
;; flat: no page lives in a subdirectory.

(load-file "clerk.clj")

(def pages
  [{:group "Start"     :src "doc/index.clj"        :out "index.html"        :nav "Home" :repl true}
   {:group "Start"     :src "doc/install.clj"      :out "install.html"      :nav "Install"}
   {:group "Start"     :gen :playground            :out "playground.html"   :nav "Playground"}
   {:group "Start"     :src "doc/tutorial.clj"     :out "tutorial.html"     :nav "Tutorial"}
   {:group "Start"     :src "doc/from-clojure.clj" :out "from-clojure.html" :nav "Coming from Clojure"}
   {:group "Guides"    :src "doc/scripting.clj"    :out "scripting.html"    :nav "Scripts and the REPL"}
   {:group "Guides"    :src "doc/judge.clj"        :out "judge.html"        :nav "Snapshot tests"}
   {:group "Guides"    :src "doc/libraries.clj"    :out "libraries.html"    :nav "Libraries"}
   {:group "Guides"    :src "doc/notebooks.clj"    :out "notebooks.html"    :nav "Notebooks"}
   {:group "Guides"    :src "doc/binaries.clj"     :out "binaries.html"     :nav "Standalone binaries"}
   {:group "Guides"    :src "doc/embedding.clj"    :out "embedding.html"    :nav "Embedding in C"}
   {:group "Guides"    :src "doc/ffi.clj"          :out "ffi.html"          :nav "FFI and the JIT"}
   {:group "Guides"    :src "doc/concurrency.clj"  :out "concurrency.html"  :nav "Concurrency"}
   {:group "Reference" :gen :cheatsheet            :out "cheatsheet.html"   :nav "Cheat sheet"}
   {:group "Reference" :gen :reference             :out "reference.html"    :nav "Core functions"}
   {:group "Project"   :src "doc/how-clove-is-made.clj" :out "how-clove-is-made.html" :nav "How clove is made"}
   {:group "Project"   :gen :changelog             :out "changelog.html"    :nav "Changelog"}])

(def site-dir "_site")
(def repo "https://github.com/alexalemi/clove")

;; ── reference, generated from docs.clj ──

(defn ref-category
  "\";; ── strings (flat str/ aliases) ──\" => \"strings\""
  [line]
  (when-let [[_ name] (re-find #";; ── ([^(─]+)" line)]
    (str/trim name)))

(defn ref-entries
  "[[category [[name doc arglists] ...]] ...] in file order. Each entry sits
  on one line of docs.clj; the categories are its section comments."
  []
  (loop [lines (str/split-lines (slurp "docs.clj")), cat nil, acc []]
    (if-let [line (first lines)]
      (let [c (ref-category line)
            start (str/index-of line "[\"")
            end (str/last-index-of line "\"]")]
        (cond
          c (recur (rest lines) c (conj acc [c []]))
          (and start end) (recur (rest lines) cat
                                 (update-in acc [(dec (count acc)) 1] conj
                                            (read-string (subs line start (+ end 2)))))
          :else (recur (rest lines) cat acc)))
      acc)))

(defn anchor [s]
  (apply str (map (fn [ch] (if (re-matches #"[A-Za-z0-9-]" (str ch)) ch (str "_" (int ch)))) s)))

(defn usage-forms
  "Arglists \"([f] [f coll])\" become (name f) (name f coll); the special
  forms' third slot already holds whole usage forms."
  [name args]
  (let [forms (read-string args)]
    (str/join "\n"
              (for [f forms]
                (if (vector? f)
                  (str "(" name (when (seq f) " ") (str/join " " (map pr-str f)) ")")
                  (pr-str f))))))

(defn reference-html []
  (let [cats (ref-entries)
        n (reduce + (map (comp count second) cats))]
    (str "<h1>Core functions</h1>"
         "<p>The " n " names that <code>(doc ...)</code> knows about, grouped as in "
         "<a href=\"" repo "/blob/main/docs.clj\">docs.clj</a>. The REPL prints the same "
         "text: <code>(doc map)</code>. Names that clove shares with Clojure behave as in "
         "Clojure; the <a href=\"https://clojure.org/api/cheatsheet\">Clojure cheatsheet</a> "
         "covers them in more depth.</p>"
         "<input id=\"ref-filter\" type=\"search\" placeholder=\"filter, e.g. str/ or lazy\" "
         "aria-label=\"filter the reference\">"
         (apply str
                (for [[cat es] cats]
                  (str "<section class=\"ref-cat\"><h2 id=\"" (anchor cat) "\">" (clerk/escape (str/capitalize cat)) "</h2>"
                       (apply str
                              (for [[name doc args] es]
                                (str "<div class=\"ref\" id=\"" (anchor name) "\" data-k=\""
                                     (clerk/escape (str/lower-case (str name " " doc))) "\">"
                                     "<h3><a href=\"#" (anchor name) "\"><code>" (clerk/escape name) "</code></a></h3>"
                                     (when (seq args)
                                       (str "<pre class=\"src\">" (clerk/hl (usage-forms name args)) "</pre>"))
                                     "<p>" (clerk/escape doc) "</p></div>")))
                       "</section>"))))))

(def reference-js "
(function(){var f=document.getElementById('ref-filter');if(!f)return;
var items=[].slice.call(document.querySelectorAll('.ref')),cats=[].slice.call(document.querySelectorAll('.ref-cat'));
f.oninput=function(){var q=f.value.trim().toLowerCase();
  items.forEach(function(e){e.hidden=q&&e.getAttribute('data-k').indexOf(q)<0});
  cats.forEach(function(c){c.hidden=!c.querySelector('.ref:not([hidden])')})}})();
")

;; ── cheat sheet, from doc/cheatsheet.edn ──

(defn usage-of
  "Usage lines for a name from its doc-info arglists: vectors are fn
  arglists, lists are the special forms' whole usage forms."
  [n {:keys [arglists]}]
  (str/join "\n" (for [a arglists]
                   (if (vector? a)
                     (str "(" n (when (seq a) " ") (str/join " " (map pr-str a)) ")")
                     (pr-str a)))))

(defn attr [s] (str/replace (clerk/escape s) "\"" "&quot;"))

(defn cheatsheet-html []
  (clove/ensure-docs!)
  (require '[clojure.set :as set] 'clojure.walk 'clojure.data)
  (let [sheet (read-string (slurp "doc/cheatsheet.edn"))
        in-ref (set (for [[_ es] (ref-entries), [n] es] n))
        on-sheet (set (for [[_ & bs] sheet, [_ & rs] bs, [_ & ns] rs, n ns :when (symbol? n)] (str n)))
        item (fn [n]
               (if (string? n)
                 (str "<code>" (clerk/escape n) "</code>")
                 (let [k (str n), info (clove/doc-info* n)]
                   (str "<a class=\"cs\" href=\"" (if (in-ref k) (str "reference.html#" (anchor k)) "#")
                        "\" data-u=\"" (attr (usage-of k info))
                        "\" data-d=\"" (attr (or (:doc info) "")) "\">" (clerk/escape k) "</a>"))))]
    (when-let [left (seq (remove on-sheet (sort in-ref)))]
      (println "site: documented but not on the cheat sheet:" (str/join " " left)))
    (str "<h1>Cheat sheet</h1>"
         "<p class=\"cs-intro\">The core of clove, grouped by what you're trying to do. Point at a name to see "
         "how to call it and what it does; click to keep it open. <a href=\"cheatsheet.pdf\">Printable PDF</a>.</p>"
         "<div id=\"cs-pop\" hidden></div>"
         "<div class=\"cs-grid\">"
         (apply str
                (for [[sec & boxes] sheet]
                  (str "<h2>" (clerk/escape sec) "</h2>"
                       (apply str
                              (for [[box & rows] boxes]
                                (str "<div class=\"cs-box\"><h3>" (clerk/escape box) "</h3><table>"
                                     (apply str
                                            (for [[label & names] rows]
                                              (str "<tr><th>" (clerk/escape label) "</th><td>"
                                                   (str/join " " (map item names)) "</td></tr>")))
                                     "</table></div>"))))))
         "</div>"
         "<p class=\"cs-foot\">" (str/trim (:out (sh (str (clove/exe-dir*) "/clove version")))) " · " repo "</p>")))

(def cheatsheet-css "
.cs-grid{columns:310px;column-gap:1.4rem}
.cs-grid h2{break-after:avoid;margin:1.2rem 0 .6rem;column-span:all}
body.wide .site{grid-template-columns:200px minmax(0,1fr);max-width:1500px}
.cs-grid h2:first-child{margin-top:0}
.cs-box{break-inside:avoid;margin:0 0 1.1rem;padding:.55rem .75rem;background:var(--surface);border-radius:8px}
.cs-box h3{margin:0 0 .35rem;font-size:.95rem}
.cs-box table{font:13px/1.55 var(--font-mono);margin:0;border:0;font-variant-ligatures:none;font-feature-settings:'liga' 0,'calt' 0}
.cs-box td a,.cs-box td code{white-space:nowrap;font:inherit}
.cs-box th,.cs-box td{border:0;padding:.1rem .5rem .1rem 0;vertical-align:top;background:none}
.cs-box th{font:600 12px/1.9 var(--font-head);color:var(--ink-soft);white-space:nowrap}
.cs-box td a,.cs-box td code{margin-right:.15rem}
.cs-box td code{background:none;padding:0}
a.cs{text-decoration:none;color:var(--link)}
a.cs:hover{text-decoration:underline}
.cs-foot{display:none}
#cs-pop{position:fixed;z-index:5;max-width:420px;background:var(--paper);border:1px solid var(--accent);
  border-radius:8px;padding:.6rem .8rem;box-shadow:0 6px 24px rgba(0,0,0,.18);font-size:14px}
#cs-pop pre{margin:0 0 .35rem;white-space:pre-wrap;font-weight:600;color:var(--link)}
#cs-pop .cs-more{font-size:12.5px;margin-top:.35rem}
#cs-pop p{margin:.2rem 0}
@media print{
  @page{size:letter landscape;margin:.9cm}
  header.site,aside.side,nav.pn,footer.clove,#clerk-bar,.cs-intro,#cs-pop{display:none!important}
  body{background:#fff;font-size:9pt}
  .site{display:block;max-width:none;padding:0}.site main{margin:0}
  h1{font-size:15pt;margin:0 0 .2rem}
  .cs-grid h2{font-size:10pt;margin:.35rem 0 .2rem;column-span:none}
  .cs-grid{columns:4;column-gap:.5cm}
  .cs-box{padding:.15rem .3rem;margin-bottom:.25rem;background:none;border:1px solid #d6d3c2;border-radius:4px}
  .cs-box h3{font-size:8.5pt;margin:0}
  .cs-box table{font-size:7pt;line-height:1.35}
  .cs-box th{font-size:6.5pt;line-height:1.5}
  a.cs{color:#364c3b}
  .cs-foot{display:block;font:7pt var(--font-head);color:#6b6650;margin-top:.3rem}}
")

(def cheatsheet-js "
(function(){var pop=document.getElementById('cs-pop');if(!pop)return;
var cur=null,pinned=false,timer=null;
function esc(s){var d=document.createElement('div');d.textContent=s;return d.innerHTML}
function hide(){pop.hidden=true;cur=null;pinned=false}
function show(a){
  cur=a;var u=a.getAttribute('data-u'),d=a.getAttribute('data-d'),h=a.getAttribute('href');
  pop.innerHTML=(u?'<pre>'+esc(u)+'</pre>':'<pre>'+esc(a.textContent)+'</pre>')+
    (d?'<p>'+esc(d)+'</p>':'<p><em>no docstring yet</em></p>')+
    (h!=='#'?'<p class=cs-more><a href='+JSON.stringify(h)+'>in the reference</a></p>':'');
  pop.hidden=false;var r=a.getBoundingClientRect(),w=pop.offsetWidth;
  pop.style.left=Math.max(8,Math.min(r.left,innerWidth-w-8))+'px';
  var top=r.bottom+6;if(top+pop.offsetHeight>innerHeight-8)top=Math.max(8,r.top-pop.offsetHeight-6);
  pop.style.top=top+'px'}
function link(e){return e.target.closest&&e.target.closest('a.cs')}
document.addEventListener('mouseover',function(e){var a=link(e);
  if(pinned)return;clearTimeout(timer);
  if(a){timer=setTimeout(function(){show(a)},120)}
  else if(!pop.contains(e.target)){timer=setTimeout(function(){if(!pinned)hide()},200)}});
document.addEventListener('click',function(e){var a=link(e);
  if(!a){if(!pop.contains(e.target))hide();return}
  e.preventDefault();clearTimeout(timer);
  if(pinned&&cur===a){hide();return}
  show(a);pinned=true});
document.addEventListener('keydown',function(e){if(e.key==='Escape')hide()});
addEventListener('scroll',function(){if(!pinned)hide()},{passive:true});})();
")

;; ── playground ──

(def playground-examples
  [["Hello" "(defn greet [name]\n  (str \"Hello, \" name \"!\"))\n\n(println (greet \"clove\"))\n(map greet [\"Ada\" \"Grace\"])\n"]
   ["FizzBuzz" "(defn fizzbuzz [n]\n  (cond\n    (zero? (mod n 15)) \"FizzBuzz\"\n    (zero? (mod n 3))  \"Fizz\"\n    (zero? (mod n 5))  \"Buzz\"\n    :else n))\n\n(doseq [n (range 1 16)]\n  (println (fizzbuzz n)))\n"]
   ["Lazy primes" ";; an infinite, lazy sequence of primes\n(defn prime? [n]\n  (and (> n 1)\n       (not-any? #(zero? (mod n %))\n                 (range 2 (inc (int (Math/sqrt n)))))))\n\n(def primes (filter prime? (range)))\n\n(take 20 primes)\n"]
   ["Word counts" "(def text \"the quick brown fox jumps over the lazy dog the end\")\n\n(->> (str/split text #\" \")\n     frequencies\n     (sort-by val >)\n     (take 3))\n"]
   ["Records" "(defprotocol Shape\n  (area [s]))\n\n(defrecord Circle [r]\n  Shape\n  (area [_] (* Math/PI r r)))\n\n(defrecord Rect [w h]\n  Shape\n  (area [_] (* w h)))\n\n(map area [(->Circle 1) (->Rect 3 4)])\n"]
   ["A macro" ";; unless: the opposite of when\n(defmacro unless [test & body]\n  `(if ~test nil (do ~@body)))\n\n(println (macroexpand '(unless done? (cleanup))))\n\n(unless false :ran)\n"]
   ["Game of Life" "(defn neighbors [[x y]]\n  (for [dx [-1 0 1] dy [-1 0 1] :when (not= 0 dx dy)]\n    [(+ x dx) (+ y dy)]))\n\n(defn step [cells]\n  (set (for [[loc n] (frequencies (mapcat neighbors cells))\n             :when (or (= n 3) (and (= n 2) (cells loc)))]\n         loc)))\n\n(defn show [cells]\n  (doseq [y (range 5)]\n    (println (apply str (for [x (range 5)] (if (cells [x y]) \"#\" \".\"))))))\n\n(def glider #{[1 0] [2 1] [0 2] [1 2] [2 2]})\n\n(doseq [g (take 3 (iterate step glider))]\n  (show g)\n  (println))\n"]])

(defn playground-html []
  (str "<h1>Playground</h1>"
       "<p>Write some clove and run it. It runs in your browser, so nothing is sent anywhere, "
       "and <em>Share</em> puts the code in the link. The browser build has no files, shell, "
       "FFI or coroutines; see <a href=\"notebooks.html\">Notebooks</a> for the details.</p>"
       "<div id=\"pg\"><div class=\"pg-bar\"><button id=\"pg-run\" title=\"ctrl+enter\">Run</button>"
       "<select id=\"pg-ex\" aria-label=\"examples\"><option value=\"\">Examples…</option>"
       (apply str (map-indexed (fn [i [t _]] (str "<option value=\"" i "\">" (clerk/escape t) "</option>")) playground-examples))
       "</select><button id=\"pg-share\">Share</button><button id=\"pg-reset\" title=\"start a fresh interpreter\">Reset</button>"
       "<span id=\"pg-msg\"></span></div>"
       "<textarea id=\"pg-src\" spellcheck=\"false\" aria-label=\"code\"></textarea>"
       "<pre id=\"pg-out\" aria-live=\"polite\"></pre>"
       "<div class=\"repl-in\"><span>user=&gt;</span><textarea id=\"pg-repl\" rows=\"1\" spellcheck=\"false\" "
       "aria-label=\"REPL\" placeholder=\"evaluate in the same environment\"></textarea></div></div>"
       (apply str (map-indexed (fn [i [_ code]] (str "<textarea hidden class=\"pg-example\" data-i=\"" i "\">\n" (clerk/escape code) "</textarea>")) playground-examples))))

(def playground-css "
#pg .pg-bar{display:flex;gap:.5rem;align-items:center;margin-bottom:.5rem;font:13px var(--font-head)}
#pg button,#pg select{font:13px var(--font-head);cursor:pointer;color:var(--link);background:var(--paper);
  border:1px solid var(--rule);border-radius:5px;padding:.3rem .8rem}
#pg #pg-run{background:var(--forest);color:var(--paper);border-color:var(--forest);font-weight:700}
#pg-msg{color:var(--ink-soft);margin-left:auto}
#pg-src{display:block;width:100%;box-sizing:border-box;min-height:22rem;resize:vertical;tab-size:2;
  background:var(--surface);color:var(--ink);border:1px solid var(--rule);border-radius:8px;padding:.7rem .9rem}
#pg-src:focus,#pg-repl:focus{outline:none;border-color:var(--accent)}
#pg-out{min-height:4rem;max-height:26rem;overflow:auto;white-space:pre-wrap;margin:.6rem 0;
  padding:.6rem .9rem;border-left:3px solid var(--accent)}
#pg-out .bad{color:var(--err)}#pg-out .val{font-weight:600}#pg-out .in{color:var(--ink-soft)}
")

(def playground-js "
(function(){var src=document.getElementById('pg-src');if(!src)return;
var out=document.getElementById('pg-out'),msg=document.getElementById('pg-msg'),repl=document.getElementById('pg-repl');
var L=window.clerkLive;
function enc(s){var b=new TextEncoder().encode(s),t='';for(var i=0;i<b.length;i++)t+=String.fromCharCode(b[i]);
  return btoa(t).replace(/[+]/g,'-').replace(/[/]/g,'_').replace(/=+$/,'')}
function dec(s){var t=atob(s.replace(/-/g,'+').replace(/_/g,'/')),b=new Uint8Array(t.length);
  for(var i=0;i<t.length;i++)b[i]=t.charCodeAt(i);return new TextDecoder().decode(b)}
function example(i){return document.querySelector('.pg-example[data-i=\"'+i+'\"]').value}
var m=location.hash.match(/^#code=(.*)$/);
try{src.value=m?dec(m[1]):(localStorage.getItem('clove-playground')||example(0))}catch(e){src.value=example(0)}
function put(cls,t){var s=document.createElement('span');s.className=cls;s.textContent=t;out.appendChild(s)}
function ev(code){
  return L.boot().then(function(){msg.textContent='';return L.call('(clerk/wasm-repl '+JSON.stringify(code)+')')})
    .then(function(v){var i=v.indexOf('error: ')===0?0:-1;
      if(i===0)put('bad',v+'\\n');else put('val',v+'\\n')},
      function(e){put('bad',String(e&&e.message||e)+'\\n')})}
var busy=false;
function run(){if(busy)return;busy=true;out.textContent='';msg.textContent='running...';
  try{localStorage.setItem('clove-playground',src.value)}catch(e){}
  ev(src.value).then(function(){busy=false;msg.textContent=''})}
document.getElementById('pg-run').onclick=run;
document.getElementById('pg-reset').onclick=function(){
  try{localStorage.setItem('clove-playground',src.value)}catch(e){}location.reload()};
document.getElementById('pg-share').onclick=function(){
  history.replaceState(null,'','#code='+enc(src.value));
  var u=location.href;(navigator.clipboard?navigator.clipboard.writeText(u):Promise.reject())
    .then(function(){msg.textContent='link copied'},function(){msg.textContent='link is in the address bar'})};
document.getElementById('pg-ex').onchange=function(e){if(e.target.value!==''){src.value=example(e.target.value);e.target.value='';run()}};
src.addEventListener('keydown',function(e){
  if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();run();return}
  if(e.key==='Tab'&&!e.shiftKey){e.preventDefault();var a=src.selectionStart,b=src.selectionEnd;
    src.value=src.value.slice(0,a)+'  '+src.value.slice(b);src.selectionStart=src.selectionEnd=a+2}});
repl.addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();
  var c=repl.value;if(!c.trim())return;repl.value='';put('in','user=> '+c+'\\n');ev(c)}});
})();
")

;; ── the home page REPL ──

(def repl-html "
<div id=\"repl\"><pre class=\"repl-log\" aria-live=\"polite\"></pre>
<div class=\"repl-in\"><span>user=&gt;</span><textarea rows=\"1\" spellcheck=\"false\"
 aria-label=\"clove expression\">(map inc [1 2 3])</textarea><button>run</button></div>
<p class=\"repl-note\">This is clove compiled to WebAssembly, running in your browser
(300 KB gzipped, fetched on the first run). Enter evaluates, shift+enter adds a line.</p></div>")

(def repl-js "
(function(){var r=document.getElementById('repl');if(!r)return;
var log=r.querySelector('.repl-log'),inp=r.querySelector('textarea');
function put(cls,t){var s=document.createElement('span');s.className=cls;s.textContent=t+'\\n';
  log.appendChild(s);log.scrollTop=log.scrollHeight}
function go(){var src=inp.value;if(!src.trim())return;inp.value='';put('in','user=> '+src);
  var wait=window.clerkLive.booted?null:setTimeout(function(){put('note','(loading clove...)')},150);
  window.clerkLive.boot().then(function(){window.clerkLive.booted=true;
      return window.clerkLive.call('(clerk/wasm-repl '+JSON.stringify(src)+')')})
    .then(function(v){clearTimeout(wait);put(/^error: /.test(v)?'bad':'val',v)},
          function(e){clearTimeout(wait);put('bad',String(e&&e.message||e))})}
r.querySelector('button').onclick=go;
inp.onkeydown=function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();go()}};})();
")

;; ── layout ──

(def site-css "
body{font-size:16.5px}
header.site{display:flex;align-items:center;gap:1.5rem;padding:.7rem 1.4rem;border-bottom:1px solid var(--rule)}
header.site img{height:30px;display:block}
header.site nav{display:flex;gap:1.1rem;margin-left:auto;font:14px var(--font-head)}
header.site nav a{text-decoration:none}
.site{display:grid;grid-template-columns:200px minmax(0,760px);gap:3rem;max-width:1060px;margin:0 auto;padding:0 1.2rem}
.site main{margin:2rem 0;padding:0;max-width:none}
aside.side{position:sticky;top:0;align-self:start;max-height:100vh;overflow-y:auto;padding:2rem 0;font:14px/1.5 var(--font-head)}
aside.side h4{font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:var(--ink-soft);margin:1.3rem 0 .4rem}
aside.side a{display:block;padding:.15rem 0;text-decoration:none;color:var(--ink)}
aside.side a[aria-current]{color:var(--link);font-weight:700}
nav.pn{display:flex;justify-content:space-between;gap:1rem;margin-top:3rem;padding-top:1rem;border-top:1px solid var(--rule);font:14px var(--font-head)}
nav.pn a{text-decoration:none}
.hero{display:flex;gap:2rem;align-items:center;margin:1rem 0 2rem}
.hero img{width:150px;flex:none}
.hero p{font-size:1.2rem;margin:.3rem 0}
pre.install{background:var(--surface);padding:.7rem .95rem;border-radius:8px;overflow-x:auto}
#repl{border:1px solid var(--rule);border-radius:8px;padding:.6rem .8rem;margin:1.2rem 0}
.repl-log{max-height:16rem;overflow-y:auto;white-space:pre-wrap;margin:0}
.repl-log:empty{display:none}
.repl-log .in{color:var(--ink-soft)}.repl-log .bad{color:var(--err)}.repl-log .note{color:var(--ink-soft);font-style:italic}
.repl-in{display:flex;gap:.5rem;align-items:flex-start;font:13.5px var(--font-mono);font-variant-ligatures:none}
.repl-in span{color:var(--ink-soft);padding-top:.3rem}
.repl-in textarea{flex:1;background:var(--surface);color:var(--ink);border:1px solid var(--rule);border-radius:6px;padding:.25rem .5rem;resize:vertical}
.repl-in button{font:13px var(--font-head);cursor:pointer;color:var(--link);background:var(--paper);border:1px solid var(--rule);border-radius:5px;padding:.25rem .7rem}
.repl-note{font-size:13px;color:var(--ink-soft);margin:.4rem 0 0}
#ref-filter{width:100%;box-sizing:border-box;font:15px var(--font-mono);padding:.45rem .7rem;border:1px solid var(--rule);border-radius:6px;background:var(--surface);color:var(--ink);margin:.5rem 0 1rem}
.ref{margin:.9rem 0;padding-left:.9rem;border-left:2px solid var(--rule)}
.ref h3{margin:0;font-size:.95rem}
.ref h3 a{text-decoration:none}
.ref pre.src{background:none;padding:.1rem 0;color:var(--ink-soft)}
.ref p{margin:.15rem 0;font-size:.95rem}
@media (max-width:820px){.site{grid-template-columns:1fr;gap:0}
  aside.side{position:static;max-height:none;padding:1rem 0 0;border-bottom:1px solid var(--rule)}
  aside.side div{display:flex;flex-wrap:wrap;gap:0 1rem}
  .hero{flex-direction:column;align-items:flex-start}.hero img{width:100px}
  header.site nav{display:none}}
")

(defn page-title [html]
  (when-let [[_ t] (re-find #"<h1>(.*?)</h1>" html)]
    (str/replace t #"<[^>]+>" "")))

(defn sidebar [current]
  (str "<aside class=\"side\">"
       (apply str
              (for [ps (partition-by :group pages)]
                (str "<h4>" (:group (first ps)) "</h4><div>"
                     (apply str (for [p ps]
                                  (str "<a href=\"" (:out p) "\""
                                       (when (= p current) " aria-current=\"page\"")
                                       ">" (:nav p) "</a>")))
                     "</div>")))
       "</aside>"))

(defn prev-next [p]
  (let [i (.indexOf pages p)
        pv (when (pos? i) (nth pages (dec i)))
        nx (get pages (inc i))]
    (str "<nav class=\"pn\">"
         (if pv (str "<a href=\"" (:out pv) "\">← " (:nav pv) "</a>") "<span></span>")
         (when nx (str "<a href=\"" (:out nx) "\">" (:nav nx) " →</a>"))
         "</nav>")))

(def logo
  (str "<picture><source srcset=\"logo-horizontal-dark.svg\" media=\"(prefers-color-scheme: dark)\">"
       "<img src=\"logo-horizontal.svg\" alt=\"clove\"></picture>"))

(def hero
  (str "<div class=\"hero\"><picture><source srcset=\"logo-dark.svg\" media=\"(prefers-color-scheme: dark)\">"
       "<img src=\"logo.svg\" alt=\"clove logo\"></picture><div>"
       "<p>A Clojure interpreter in one C file.</p>"
       "<p style=\"font-size:1rem;color:var(--ink-soft)\">Embed it with an "
       "<code>#include</code>, or bundle a script into a 500 KB binary with no dependencies.</p></div></div>"))

(defn layout [p body live?]
  (let [title (or (page-title body) (:nav p))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>" (if (= (:out p) "index.html") "clove: Clojure in one C file" (str title " · clove")) "</title>"
         "<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">"
         "<style>" clove/clerk-css clove/clerk-live-css site-css
         (case (:gen p) :cheatsheet cheatsheet-css :playground playground-css "") "</style>"
         "</head><body" (when (= (:gen p) :cheatsheet) " class=\"wide\"")
         (when live? " data-clerk-lazy data-clerk-lib=\"clerk.clj\"") ">"
         "<header class=\"site\"><a href=\"index.html\">" logo "</a><nav>"
         "<a href=\"install.html\">Install</a><a href=\"tutorial.html\">Tutorial</a>"
         "<a href=\"reference.html\">Reference</a><a href=\"" repo "\">GitHub</a></nav></header>"
         "<div class=\"site\">" (sidebar p) "<main>"
         (when (:repl p) (str hero repl-html))
         body
         (prev-next p) "</main></div>" clove/clerk-footer
         (when live? (str "<script src=\"clove-wasm.js\"></script><script>" clove/clerk-live-js "</script>"))
         (when (:repl p) (str "<script>" repl-js "</script>"))
         (when (= (:gen p) :reference) (str "<script>" reference-js "</script>"))
         (when (= (:gen p) :cheatsheet) (str "<script>" cheatsheet-js "</script>"))
         (when (= (:gen p) :playground) (str "<script>" playground-js "</script>"))
         "</body></html>")))

;; ── build ──

(defn render-page
  "A notebook's cells as HTML, rendered by a fresh clove process."
  [src]
  (let [tmp (str (clove/user-tmp-dir*) "/clove-site-" (Math/abs (hash src)) ".html")
        r (sh (str (clove/exe-dir*) "/clove doc/build.clj --cells " src " " tmp " 2>&1"))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "rendering " src " failed:\n" (:out r)) {})))
    (slurp tmp)))

(defn copy! [from to]
  (let [r (sh (str "cp " from " " to))]
    (when-not (zero? (:exit r)) (throw (ex-info (str "cp " from ": " (:out r)) {})))))

(defn print-pdf!
  "Prints a built page to PDF with headless Chrome, when there is one."
  [html pdf]
  (if-let [chrome (some (fn [c] (when (zero? (:exit (sh (str "command -v " c " >/dev/null")))) c))
                        ["google-chrome" "chromium" "chromium-browser"])]
    ;; its own profile dir, or a running Chrome may swallow the request;
    ;; stdin closed and a timeout, or a stuck Chrome holds up the build
    (let [r (sh (str "timeout 90 " chrome " --headless=new --disable-gpu --no-pdf-header-footer"
                     " --user-data-dir=" (clove/user-tmp-dir*) "/clove-site-chrome"
                     " --print-to-pdf=" (System/getProperty "user.dir") "/" site-dir "/" pdf
                     " file://" (System/getProperty "user.dir") "/" site-dir "/" html " </dev/null 2>&1"))]
      (println (if (zero? (:exit r)) (str "site: wrote " pdf) (str "site: " pdf " failed:\n" (:out r)))))
    (println "site: no Chrome found, skipping" pdf)))

(defn build! []
  (sh (str "mkdir -p " site-dir))
  (let [wasm? (try (spit (str site-dir "/clove-wasm.js") (clerk/wasm-js)) true
                   (catch Exception e
                     (println "site: no wasm interpreter, pages will be static:" (ex-message e))
                     false))]
    (doseq [f ["logo.svg" "logo-dark.svg" "logo-horizontal.svg" "logo-horizontal-dark.svg" "favicon.svg" "mark.svg"]]
      (copy! (str "brand/" f) site-dir))
    (copy! "clerk.clj" site-dir)
    (copy! "llms.txt" site-dir)          ; the agent-facing summary, at the site root
    (doseq [p pages]
      (let [body (case (:gen p)
                   :reference (reference-html)
                   :changelog (clerk/md->html (slurp "CHANGELOG.md"))
                   :cheatsheet (cheatsheet-html)
                   :playground (playground-html)
                   (render-page (:src p)))
            live? (and wasm? (or (:repl p) (= (:gen p) :playground) (str/includes? body "clerk-live")))]
        (spit (str site-dir "/" (:out p)) (layout p body live?))
        (println "site: wrote" (:out p))))
    (print-pdf! "cheatsheet.html" "cheatsheet.pdf")))

(if (= (first *args*) "--cells")
  (let [[_ src out] *args*]
    (spit out (clerk/render-cells src {:standalone true})))
  (build!))
