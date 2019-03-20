(ns profiling.core
  (:require [clj-async-profiler.core :as prof]
            [clojure.core.async :as async :refer [>! <! >!! <!! go go-loop thread chan close! alts!!]]
            [clojure.tools.logging :as log]
            [taoensso.carmine :as car]))

;; The resulting flamegraph will be stored in /tmp/clj-async-profiler/results/
;; You can view the SVG directly from there or start a local webserver:


(declare add-to-pipeline)
(defmacro wcar* [& body] `(car/wcar server-conn ~@body))

(def occupancies (atom []))
(def pipeline (atom []))
(def server-conn {:pool {} :spec {:host "127.0.0.1" :port 6379}})
(def pipeline-max-size 100)
(def expire-time 3600)
(def sync-c (chan))
(def shutdown-c (chan))
(def pipeline-c (chan 1000))

(defn do-cmd [c]
  (let [cmd (:cmd c)
        params (:params c)]
    (cond
      (= cmd :set) (car/set (:key params) (:val params) :EX expire-time)
      (= cmd :del) (car/del (:key params))
      (= cmd :add-to-list) (apply car/rpush (:key params) (:vals params))
      (= cmd :expire) (car/expire (:key params) (:expire params))
      (= cmd :drop-from-list) (car/ltrim (:key params) (:count params) -1)
      :default nil)))

(defn force-sync []
  (>!! sync-c :do-sync))

(defn re-enable-pipeline []
  (>!! sync-c :continue))

(defn notify-sync-done []
  (>!! sync-c :continue))

(defn await-sync-ready []
  (<!! sync-c))

(defn await-pipeline-re-enabled []
  (<!! sync-c))



(defn sync-pipeline!
  "Synchonizes the pipeline, and sets up a new one afterwards"
  [pipeline]
  (when (> (count @pipeline) 0)
    (let [pipe @pipeline]
       ;(log/info (str "Redis pipeline sync: batch size was " (count pipe) " out of " pipeline-max-size))
      (swap! occupancies conj (count pipe))
      (try
        (wcar*
         (doseq [x pipe]
           (do-cmd x)))
        (catch java.lang.Exception e
          (ex-info (str "Failed to sync Redis pipeline: " (.getMessage e)) {:exception e}))
        (finally
          (reset! pipeline [])))
      nil)))



(defn sync-process []
  (let [continue (atom true)]
    (while @continue
      (let [[v ch] (alts!! [shutdown-c sync-c pipeline-c (async/timeout 1)] :priority true)]
        (cond
          (= ch shutdown-c) (do
                              (reset! continue false)
                              (println "Sync worker exiting"))
          (= ch sync-c) (do
                          (sync-pipeline! pipeline)
                          (notify-sync-done)
                          (await-pipeline-re-enabled))
          (= ch pipeline-c) (add-to-pipeline v (:allow-deferred? v)))))))

(defn start-sync-worker []
  (thread (sync-process)))

(defn add-to-pipeline
  "Auxiliary function to add a command to the pipeline"
  [cmd allow-deferred?]
  (dosync
   (swap! pipeline conj cmd)
   (when (or (>= (count @pipeline) pipeline-max-size)
             (not allow-deferred?))
     (sync-pipeline! pipeline))))

(defn -set
  "Sets key `k` to value `v`.
  If `allow-deferred` is true, it stores the command in the pipeline.
  The 2-arity version always acts deferred"
  ([k v] (-set k v true))
  ([k v allow-deferred?] (>!! pipeline-c {:cmd :set :params {:key k :val v} :allow-deferred? allow-deferred?})))

(defn -get
  "Gets key `k` from the store"
  [k]
  (do (force-sync)
      (await-sync-ready)
      (try (let [res (wcar* (car/get k))]
             (if (some? res)
               res
               nil))
           (finally (re-enable-pipeline)))))

(defn -drop
  "Drops key `k` from the store
  The 2-arity version always acts deferred"
  ([k] (-drop k true))
  ([k allow-deferred?]  (>!! pipeline-c {:cmd :del :params {:key k} :allow-deferred? allow-deferred?})))

(defn -add-to-list
  "Adds `v` to the list keyed by `k`.
  The 2-arity version always acts deferred"
  ([k v] (-add-to-list k v true))
  ([k v allow-deferred?] (let [values (cond
                                        (or (instance? java.util.List v)
                                            (seq? v))    (into [] v)
                                        (vector? v) v
                                        :else       [v])]
                           (when (> (count values) 0)
                             (>!! pipeline-c {:cmd :add-to-list :params {:key k :vals values} :allow-deferred? true})
                             (>!! pipeline-c {:cmd :expire :params {:key k :expire expire-time} :allow-deferred? allow-deferred?})))))

(defn -get-list
  "Gets the list keyed by `k`"
  [k]
  (do
    (force-sync)
    (await-sync-ready)
    (try (let [data (wcar* (car/lrange k 0 -1))
               result (java.util.LinkedList.)]
           (doseq [x data] (.addLast result x))
           result)
         (finally (re-enable-pipeline)))))

(defn -drop-from-list
  "Drops the first `drop-count` elements from the list keyed by `k`
  The 2-arity version always acts deferred"
  ([k drop-count] (-drop-from-list k drop-count true))
  ([k drop-count allow-deferred?] (>!! pipeline-c {:cmd :drop-from-list :params {:key k :count count} :allow-deferred? allow-deferred?})))

(comment

  (defn test2 []
    (do
      (wcar* (car/flushall))
      (reset! occupancies [])
      (doseq [n (range 10000)]
        (if (< n 7500)
          (wcar* (car/rpush n (range (rand 100))))
          (wcar* (car/lrange (- n 5000) 0 -1))))))

  (defn test []
    (do
      (wcar* (car/flushall))
      (reset! occupancies [])
      (doseq [n (range 10000)]
        (let [x (rand)]
      ;    (Thread/sleep (rand 3))
          (cond
            (< x 0.75) (-add-to-list n (range (rand 100)))
            (< x 0.80) (-add-to-list n :event false)
            (< x 0.82) (-set n :event true)
            :default (-get-list (rand n)))))))

  (time  (prof/profile (test)))
  (sort (frequencies @occupancies))

  (reduce + (map (fn[[f s]] (* f s)) (frequencies @occupancies)))
  (reset! occupancies [])

  (time (prof/profile (test2)))

  (prof/serve-files 8080) ; Serve on port 8080

  (let [t (java.lang.Thread)
        id (.getId t)]
    id))


(start-sync-worker)

(comment
  (thread (>!! shutdown-c :stop))

  )
