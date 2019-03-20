(defproject profiling "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [com.clojure-goes-fast/clj-async-profiler "0.3.1"]
                 [org.clojure/core.async "0.4.490"]
                 [org.clojure/tools.logging "0.4.1"]
                 [com.taoensso/carmine "2.19.1"]]
  :jvm-opts ["-Djdk.attach.allowAttachSelf" "-XX:+UnlockDiagnosticVMOptions" "-XX:+DebugNonSafepoints"]
  :repl-options {:init-ns profiling.core})
