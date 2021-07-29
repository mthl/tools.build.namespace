(ns tools.build.namespace.alpha-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [tools.build.namespace.alpha :as sut]))

(defn- delete-file
  [file]
  (let [f (io/file file)]
    (when (.exists f)
      (.delete f))))

(defn- touch
  [file]
  (let [f (io/file file)
        lm (.lastModified f)]
    (.setLastModified f (+ lm 42))))

(defn- untouch
  [file]
  (let [f (io/file file)
        lm (.lastModified f)]
    (.setLastModified f (- lm 42))))

(def ^:private files
  {:state "target/.cache/test.edn"
   :src "src/tools/build/namespace/alpha.clj"
   :test "test/tools/build/namespace/alpha_test.clj"})

(defn- scan-files []
  (sut/scan {:state-file (:state files)
             :lang :clj
             :dirs ["test"]
             :extra-dirs ["src"]}))

(deftest scan-test
  (delete-file (:state files))
  (let [s0 (scan-files)]
    (is (contains? (-> s0 sut/fresh-namespaces set)
                   'tools.build.namespace.alpha-test))
    (sut/commit! s0))

  (let [s1 (scan-files)]
    (is (-> s1 sut/fresh-namespaces empty?)))

  (touch (:test files))
  (let [s2 (scan-files)]
    (is (contains? (-> s2 sut/fresh-namespaces set)
                   'tools.build.namespace.alpha-test)))


  (untouch (:test files))
  (touch (:src files))
  (let [s3 (scan-files)]
    (is (contains? (-> s3 sut/fresh-namespaces set)
                   'tools.build.namespace.alpha-test))
    (is (not (contains? (-> s3 sut/fresh-namespaces set)
                        'tools.build.namespace.alpha))))

  (untouch (:src files)))
