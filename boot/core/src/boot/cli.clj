(ns boot.cli
  (:require
   [boot.util :as util]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.pprint :as pprint]
   [boot.from.clojure.tools.cli :as cli]))

(def ^:private A-Z?
  (->> (range (int \A) (inc (int \Z))) (map char) set (partial contains?)))

(defn- depunc   [s] (string/replace s #"\.$" ""))
(defn- decap    [s] (apply str (string/lower-case (first s)) (rest s)))
(defn- indent   [n] #(string/replace % #"\n" (apply str "\n" (repeat n " "))))
(defn- rm-lines [n] #(->> (string/split % #"\n") (drop n) (string/join "\n")))

(defn- parse-atom [type]
  (case type
    int   read-string
    float read-string
    str   (fnil identity "")
    kw    keyword
    sym   symbol
    char  first
    bool  identity
    edn   (fnil read-string "nil")
    regex (fnil re-pattern "")
    code  (fnil (comp eval read-string) "nil")
    file  io/file))

(defn- assert-atom [type]
  (case type
    int   integer?
    float float?
    str   string?
    kw    keyword?
    sym   symbol?
    char  char?
    bool  #(contains? #{true false} %)
    edn   (constantly true)
    regex #(instance? java.util.regex.Pattern %)
    code  (constantly true)
    file  #(instance? java.io.File %)))

(defn- parse-fn [optarg]
  (fn [arg]
    (let [chars (->> optarg str (remove A-Z?))]
      (if-not (seq chars)
        arg
        (loop [ret [], arg arg, [c & chars] chars]
          (if-not c
            (conj ret arg)
            (let [splitter  (re-pattern (str "(?<!\\\\)" c))
                  cleaner   (re-pattern (str "\\\\" c))
                  [nxt arg] (string/split arg (re-pattern (str "(?<!\\\\)" c)) 2)
                  nxt       (string/replace nxt cleaner (str c))]
              (recur (conj ret nxt) arg chars))))))))

(defn- parse-type [type args]
  (if (symbol? type)
    ((parse-atom type) args)
    (mapv parse-type type args)))

(defn- assert-type [type args]
  (if (symbol? type)
    ((assert-atom type) args)
    (every? identity (mapv assert-type type args))))

(defn- assoc-fn [optarg type]
  (fn [m k v]
    (let [flag?      (not optarg)
          int-flag?  (and flag? (= 'int type))
          bool-flag? (and flag? (= 'bool type))]
      (cond
        bool-flag?     (assoc m k v)
        int-flag?      (update-in m [k] (fnil inc 0))
        (symbol? type) (assoc m k (parse-type type v))
        (coll? type)   (update-in m [k] (fnil conj (empty type)) (parse-type (first type) v))))))

(defn- format-doc [optarg type doc]
  (let [atom? (symbol? type)
        flag? (not optarg)
        incr? (and flag? (= 'int type))]
    (cond
      incr? (format "Increase %s" (decap doc))
      flag? doc
      atom? (format "Set %s to %s." (depunc (decap doc)) optarg)
      :else (let [f "Conj %s onto %s"
                  v ((parse-fn optarg) (str optarg))]
              (format f (if (string? v) v (pr-str (mapv symbol v))) (decap doc))))))

(defn- argspec->cli-argspec
  ([short long type doc]
     (argspec->cli-argspec short long nil type doc))
  ([short long optarg type doc]
     [:id           (keyword long)
      :short-opt    (str "-" short)
      :long-opt     (str "--" long)
      :required     (when optarg (str optarg))
      :desc         (format-doc optarg type doc)
      :parse-fn     `(#'parse-fn ~(when optarg (list 'quote optarg)))
      :assoc-fn     `(#'assoc-fn ~(when optarg (list 'quote optarg)) '~type)]))

(defn- argspec->assert
  ([short long type doc]
     (argspec->assert short long nil type doc))
  ([short long optarg type doc]
     (if (:! (meta type))
       nil
       `(when-not (or (nil? ~long) (#'assert-type '~type ~long))
          (throw (IllegalArgumentException.
                   ~(format "option :%s must be of type %s" long type)))))))

(defn- argspec->summary
  ([short long type doc]
     (argspec->summary short long nil type doc))
  ([short long optarg type doc]
     [(str ":" long) (str (when (:! (meta type)) "^:! ")) (str type) doc]))

(defn- argspec-seq [args]
  (when (seq args)
    (let [[ret [doc & more]] (split-with (complement string?) args)]
      (cons (conj (vec ret) doc) (when (seq more) (argspec-seq more))))))

(defn- cli-argspec->bindings [argspec]
  (->> (cli/compile-option-specs argspec)
    (mapv (comp symbol name :id))
    (assoc {:as '*opts*} :keys)))

(defn- format-lines [lens parts]
  (->> parts (mapv #(->> (interleave lens %)
                      (pprint/cl-format nil "~{  ~vA  ~vA~vA  ~vA~}")
                      string/trimr))))

(defn- clj-summary [argspecs]
  (let [parts (mapv (partial apply argspec->summary) argspecs)
        lens  (apply map #(apply max (map count %&)) parts)]
    (string/join "\n" (format-lines lens parts))))

(defn- cli-summary [argspecs]
  (let [cli-args (mapv (partial apply argspec->cli-argspec) argspecs)]
    (:summary (cli/parse-opts [] cli-args))))

(defn split-args [args]
  (loop [kw {} cli [] [arg & more] args]
    (if-not arg
      {:kw kw :cli cli}
      (if-not (keyword? arg)
        (recur kw (conj cli arg) more)
        (recur (assoc kw arg (first more)) cli (rest more))))))

(defn- assert-argspecs [argspecs]
  (let [opts (->> argspecs
                  (partition-by string?)
                  (mapcat (partial take 2))
                  (filter symbol?))]
    (when (seq opts)
      (assert (apply distinct? opts) "cli options must be unique")
      (assert (not-any? #{'h 'help} opts) "the -h/--help cli option is reserved"))))

(defmacro clifn [& forms]
  (let [[doc argspecs & body]
        (if (string? (first forms))
          forms
          (list* "No description provided." forms))]
    (assert-argspecs argspecs)
    (let [doc      (string/replace doc #"\n  " "\n")
          helpspec '[h help bool "Print this help info."]
          argspecs (cons helpspec (argspec-seq argspecs))
          cli-args (mapv (partial apply argspec->cli-argspec) argspecs)
          bindings (cli-argspec->bindings cli-args)
          arglists (list 'list (list 'quote ['& bindings]))
          cli-doc  (format "%s\n\nOptions:\n%s\n" doc (cli-summary argspecs))
          clj-doc  (format "%s\n\nKeyword Args:\n%s\n" doc (clj-summary argspecs))
          varmeta  {:doc clj-doc :arglists arglists :argspec cli-args}]
      `(-> (fn [& args#]
             (let [{kws# :kw clis# :cli} (split-args args#)
                   parsed#   (cli/parse-opts clis# ~cli-args)
                   ~bindings (merge kws# (:options parsed#))
                   ~'*args*  (:arguments parsed#)]
               ~@(mapv (partial apply argspec->assert) argspecs)
               (if-not ~'help (do ~@body) (print ~cli-doc))))
           (with-meta ~varmeta)))))

(defmacro defclifn [sym & forms]
  `(let [var#    (def ~sym (clifn ~@forms))
         fmtdoc# (comp string/trim (#'indent 2))
         meta#   (update-in (meta ~sym) [:doc] fmtdoc#)]
     (doto var# (alter-meta! (fnil merge {}) meta#))))

(comment

  (defclifn d
    "Docstring here."
    [S no-source              bool        "Exclude source files from jar."
     v verbose                int         "The verbosity level."
     p project      SYM       sym         "The project id (eg. foo/bar)."
     b bind         ADDR      str         "The address server will listen on."
     n init-ns      SYM       sym         "The initial REPL namespace."
     d description  DESC      str         "The project description."
     D dependencies ID:VER    [[sym str]] "The dependencies vector."
     l license      KEY=VAL   {kw str}    "The license map."
     m middleware   SYM       [sym]       "The vector of REPL middleware."
     M manifest     KEY=VAL   {str str}   "The jar manifest."
     s scopes       SCOPE     #{str}      "The set of excluded scopes."
     t thing        CODE      code        "Some computings."]

    [*opts* *args*])

  (d "-vvD" "foo/bar:0.1.0" "-p" "hello/world" "-l" "name=foop" "-l" "url=http://me.com" "-t" "(inc 41)" "zxcv" "qwer")
  [{:thing 42,
    :license {:url "http://me.com", :name "foop"},
    :project hello/world,
    :dependencies [[foo/bar "0.1.0"]],
    :verbose 2}
   ["zxcv" "qwer"]]
  
  (doc d)
  ; -------------------------
  ; boot.cli-util/d
  ; ([& {:keys [help no-source verbose project bind init-ns description dependencies license middleware manifest scopes thing], :as *opts*}])
  ;   Docstring here.
  ;   
  ;   Keyword Args:
  ;     :help          bool         Print this help info.
  ;     :no-source     bool         Exclude source files from jar.
  ;     :verbose       int          The verbosity level.
  ;     :project       sym          The project id (eg. foo/bar).
  ;     :bind          str          The address server will listen on.
  ;     :init-ns       sym          The initial REPL namespace.
  ;     :description   str          The project description.
  ;     :dependencies  [[sym str]]  The dependencies vector.
  ;     :license       {kw str}     The license map.
  ;     :middleware    [sym]        The vector of REPL middleware.
  ;     :manifest      {str str}    The jar manifest.
  ;     :scopes        #{str}       The set of excluded scopes.
  ;     :thing         code         Some computings.
  nil
          
  (d "-h")
  ; Docstring here.
  ; 
  ; Options:
  ;   -h, --help                 Print this help info.
  ;   -S, --no-source            Exclude source files from jar.
  ;   -v, --verbose              Increase the verbosity level.
  ;   -p, --project SYM          Set the project id (eg. foo/bar) to SYM.
  ;   -b, --bind ADDR            Set the address server will listen on to ADDR.
  ;   -n, --init-ns SYM          Set the initial REPL namespace to SYM.
  ;   -d, --description DESC     Set the project description to DESC.
  ;   -D, --dependencies ID:VER  Conj [ID VER] onto the dependencies vector.
  ;   -l, --license KEY=VAL      Conj [KEY VAL] onto the license map.
  ;   -m, --middleware SYM       Conj SYM onto the vector of REPL middleware.
  ;   -M, --manifest KEY=VAL     Conj [KEY VAL] onto the jar manifest.
  ;   -s, --scopes SCOPE         Conj SCOPE onto the set of excluded scopes.
  ;   -t, --thing CODE           Set some computings to CODE.
  nil

    )
