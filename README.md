# tools.build.namespace

Tool to process only fresh namespaces when performing a build task (compilation, tests, ...).

This is an extension to [tools.namespace](https://github.com/clojure/tools.namespace) which track transitive namespace modifications across multiple independent process executions instead of a single REPL session.

The main objective of such extension is to speed-up *Continuous Integration* (CI) pipelines.

## Usage

In order to use it you must add the following line in your `deps.edn`:

```clojure
io.github.mthl/tools.build.namespace {:git/sha ",,,"}
```

This library is experimental so there is no tag release yet. Please be prepared to future API breakage.

## Incremental test runner example

Here is snippet of code wrapping [Cognitect test-runner](https://github.com/cognitect-labs/test-runner) with basic incremental functionality. Please notice that this code relies on an implementation detail of Cognitect test-runner which might change in the future.

```clojure
(ns my.custom.test-runner
  (:refer-clojure :exclude [test])
  (:require
   [cognitect.test-runner.api :as tr]
   [tools.build.namespace.alpha :as bn]))

(defn test
  [{:keys [src-dirs test-dirs] :as opts}]
  (let [s (bn/scan {:state-file "target/.cache/test.edn"
                    :lang :clj
                    :dirs test-dirs
                    :extra-dirs src-dirs})]
    (try
      (let [nses (bn/fresh-namespaces s)
            {:keys [fail error]} (#'tr/do-test
                                  (merge {:dirs test-dirs
                                          :nses (if (empty? nses) #{'user} nses)}
                                         opts))]
        (if (pos-int? (+ fail error))
          (System/exit 1)
          (do
            (bn/commit! s)
            (System/exit 0))))
      (finally
        (shutdown-agents)))))
```

You can then invoke the test runner from the command line by defining an alias in your `deps.edn` file.

```clojure
{
  :test
  {:extra-paths ["test"]
   :extra-deps {io.github.cognitect-labs/test-runner {:git/tag "v0.4.0" :git/sha "334f2e2"}
                io.github.mthl/tools.build.namespace {:git/sha ",,,"}}
   :exec-fn my.custom.test-runner/test
   :exec-args {:test-dirs ["test"] :src-dirs ["src"]}}
,,,}
```

## Copyright and License

Copyright © 2021 Mathieu Lirzin

All rights reserved. The use and
distribution terms for this software are covered by the
[Eclipse Public License 1.0] which can be found in the file
LICENSE at the root of this distribution. By using this software
in any fashion, you are agreeing to be bound by the terms of this
license. You must not remove this notice, or any other, from this
software.

[Eclipse Public License 1.0]: http://opensource.org/licenses/eclipse-1.0.php
