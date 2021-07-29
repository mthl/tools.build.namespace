(ns tools.build.namespace.alpha
  "Scan “fresh” namespaces to perform a build task (compilation, tests, ...).

  A namespace is considered “fresh” when its source file has been
  modified or when it requires another “fresh” namespace."
  (:require
   [clojure.set :as set]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.namespace.file :as file]
   [clojure.tools.namespace.find :as find]
   [clojure.tools.namespace.parse :as parse]
   [clojure.tools.namespace.dependency :as dep])
  (:import
   java.io.File))

(defn- ns-files
  ([dirs platform]
   (ns-files dirs platform {}))
  ([dirs platform read-opts]
   (into #{}
         (comp
          (map #(-> % io/file .getCanonicalFile))
          (filter #(.exists ^File %))
          (mapcat #(find/find-sources-in-dir % platform))
          (map #(.getCanonicalFile ^File %))
          (map (fn [f]
                 (let [decl (file/read-file-ns-decl f read-opts)]
                   {:ns (parse/name-from-ns-decl decl)
                    :file-name (str f)
                    :last-modified (.lastModified f)
                    :ns-deps (parse/deps-from-ns-decl decl)}))))
         dirs)))

(defn- index-by
  [keyfn xrel]
  (reduce (fn [idx x]
            (assoc idx (keyfn x) x))
          {}
          xrel))

(defn- ns-files-graph
  "Construct a directed acyclic graph (DAG) representing the namespace
  dependency relationship between a collection of local namespace files.

  `ns-files` can contain dependencies on external namespaces that are
  considered immutable. As a consequence those dependencies are not
  tracked as dependencies when constructing the dependency graph."
  [ns-files]
  (let [ns->ns-file (index-by :ns ns-files)
        local-ns? #(contains? ns->ns-file %)]
    (->> ns-files
         (mapcat (fn [ns-file]
                   (let [local-deps (filter local-ns? (:ns-deps ns-file))]
                     (for [dep local-deps]
                       [ns-file (ns->ns-file dep)]))))
         (reduce (fn [g [node dep]]
                   (dep/depend g node dep))
                 (dep/graph)))))

(defn- mark-fresh-1
  [old-ns-files new-ns-files]
  (let [created? (into #{} (map :ns) old-ns-files)
        modified? (complement created?)
        prev-modified (into {} (map (juxt :ns :last-modified)) old-ns-files)]
    (into #{}
          (map (fn [ns-file]
                 (let [{:keys [ns last-modified]} ns-file
                       fresh? (or (not (contains? prev-modified ns))
                                  (> last-modified (prev-modified ns)))]
                   (assoc ns-file :fresh? fresh?))))
          new-ns-files)))

(defn- mark-fresh
  [old-ns-files new-ns-files]
  (let [ns-files (mark-fresh-1 old-ns-files new-ns-files)
        dag (ns-files-graph ns-files)]
    (into #{}
          (map (fn [ns-file]
                 (let [deps (dep/transitive-dependencies dag ns-file)
                       fresh? (or (:fresh? ns-file)
                                  (boolean (some :fresh? deps)))]
                   (assoc ns-file :fresh? fresh?))))
          ns-files)))

(defn- diff
  [old-nfs new-nfs]
  (into #{}
        (comp
         (filter :fresh?)
         (map #(dissoc % :fresh?)))
        (mark-fresh old-nfs new-nfs)))

(defn- lang->platform
  [lang]
  (case lang
    :clj find/clj
    :cljs find/cljs
    (throw (ex-info "Lang must be either :clj or :cljs" {:lang lang}))))

(defn- write-file
  [^File f content]
  (let [dir (-> f .getCanonicalFile .getParent io/file)]
    (if (or (.exists dir) (.mkdirs dir))
      (spit f content)
      (throw (ex-info (str "Can't create directory " dir) {})))))

(defprotocol Scan
  (fresh-namespaces [this])
  (commit! [this]))

(defn scan
  "Scan a collection of namespaces from `dirs` that have changed or are
  dependent on files from `extra-dirs`."
  [{:keys [state-file lang dirs extra-dirs] :as opts
    :or {lang :clj
         dirs ["."]
         extra-dirs []}}]
  (let [platform (lang->platform lang)
        main-ns-files (ns-files dirs platform)
        extra-ns-files (ns-files extra-dirs platform)
        tf (io/file state-file)
        old-ns-files (set (when (.exists ^File tf)
                            (-> tf slurp edn/read-string)))
        new-ns-files (set/union main-ns-files extra-ns-files)
        ns-candidates (into #{} (map :ns) main-ns-files)]
    (reify Scan
      (fresh-namespaces [_]
        (->> (diff old-ns-files new-ns-files)
             (map :ns)
             (filter ns-candidates)))
      (commit! [_]
        (write-file tf new-ns-files)))))
