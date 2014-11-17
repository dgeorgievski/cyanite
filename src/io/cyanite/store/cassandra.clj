(ns io.cyanite.store.cassandra
  (:require [qbits.alia            :as alia]
            [clojure.core.async    :as a]
            [io.cyanite.store      :as store]
            [io.cyanite.precision  :as p]
            [clojure.tools.logging :refer [error info debug]])
  (:import [com.datastax.driver.core
            BatchStatement
            PreparedStatement]))

;;
;; The following contains necessary cassandra queries. Since
;; cyanite relies on very few queries, I decided against using
;; hayt

(defn insertq
  "Yields a cassandra prepared statement of 6 arguments:

* `ttl`: how long to keep the point around
* `metric`: the data point
* `rollup`: interval between points at this resolution
* `period`: rollup multiplier which determines the time to keep points for
* `path`: name of the metric
* `time`: timestamp of the metric, should be divisible by rollup"
  [session]
  (alia/prepare
   session
   (str
    "UPDATE metric USING TTL ? SET data = data + ? "
    "WHERE tenant = ? AND rollup = ? AND period = ? AND path = ? AND time = ?;")))

(defn fetchq
  "Yields a cassandra prepared statement of 6 arguments:

* `paths`: list of paths
* `rollup`: interval between points at this resolution
* `period`: rollup multiplier which determines the time to keep points for
* `min`: return points starting from this timestamp
* `max`: return points up to this timestamp
* `limit`: maximum number of points to return"
  [session]
  (alia/prepare
   session
   (str
    "SELECT path,data,time FROM metric WHERE "
    "path IN ? AND tenant = '' AND rollup = ? AND period = ? "
    "AND time >= ? AND time <= ? ORDER BY time ASC;")))

(defn cassandra-store
  "Connect to cassandra and start a path fetching thread.
   The interval is fixed for now, at 1minute"
  [{:keys [keyspace cluster chan_size]}]
  (info "creating cassandra metric store")
  (let [cluster (if (sequential? cluster) cluster [cluster])
        session (-> (alia/cluster {:contact-points cluster})
                    (alia/connect keyspace))
        insert! (insertq session)
        fetch!  (fetchq session)]
    (reify
      store/Metricstore
      (insert! [this tenant precision metric]
        (alia/execute-chan
         session
         insert!
         {:values [(int (p/ttl precision))
                   [(:point metric)]
                   tenant
                   (:rollup precision)
                   (:period precision)
                   (:path metric)
                   (:time metric)]}))
      (fetch [this tenant precision spec]
        (debug "will fetch values: " (pr-str           {:values [(:paths spec)
                   (int (:rollup precision))
                   (int (:period precision))
                   (:from spec)
                   (:to spec)]
          :fetch-size Integer/MAX_VALUE}
                                      ))
        (alia/execute
         session fetch!
         {:values [(:paths spec)
                   (int (:rollup precision))
                   (int (:period precision))
                   (:from spec)
                   (:to spec)]
          :fetch-size Integer/MAX_VALUE})))))


(comment
  (def s (store/wrapped-store (cassandra-store {:cluster "localhost"
                                                :keyspace "metric"})
                              [(p/->Precision 10 60480)]
                              ))

  (store/fetch s "" (p/->Precision 10 60480) (p/->FetchSpec :mean
                                                            ["foo.bar"]
                                                            1416213125
                                                            1416214690))
  )