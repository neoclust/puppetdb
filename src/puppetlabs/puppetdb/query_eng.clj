(ns puppetlabs.puppetdb.query-eng
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.query.catalogs :as catalogs]
            [puppetlabs.puppetdb.query.edges :as edges]
            [puppetlabs.puppetdb.query.environments :as environments]
            [puppetlabs.puppetdb.query.events :as events]
            [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.query.factsets :as factsets]
            [puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [puppetlabs.puppetdb.query.nodes :as nodes]
            [puppetlabs.puppetdb.query.reports :as reports]
            [puppetlabs.puppetdb.query.resources :as resources]))

(defn ignore-engine-params
  "Query engine munge functions should take two arguments, a version
   and a list of columns to project. Some of the munge funcitons don't
   currently adhere to that contract, so this function will wrap the
   given function `f` and ignore those arguments"
  [f]
  (fn [_ _ _ _]
    f))

(defn stream-query-result
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [entity version query paging-options db url-prefix output-fn]
  (let [[query->sql munge-fn]
        (case entity
          :facts [facts/query->sql facts/munge-result-rows]
          :event-counts [event-counts/query->sql (ignore-engine-params (event-counts/munge-result-rows (first paging-options)))]
          :aggregate-event-counts [aggregate-event-counts/query->sql (ignore-engine-params (comp (partial kitchensink/mapvals #(if (nil? %) 0 %)) first))]
          :fact-contents [fact-contents/query->sql fact-contents/munge-result-rows]
          :fact-paths [facts/fact-paths-query->sql facts/munge-path-result-rows]
          :events [events/query->sql events/munge-result-rows]
          :nodes [nodes/query->sql nodes/munge-result-rows]
          :environments [environments/query->sql (ignore-engine-params identity)]
          :reports [reports/query->sql reports/munge-result-rows]
          :factsets [factsets/query->sql factsets/munge-result-rows]
          :resources [resources/query->sql resources/munge-result-rows]
          :edges [edges/query->sql edges/munge-result-rows]
          :catalogs [catalogs/query->sql catalogs/munge-result-rows])]
    (jdbc/with-transacted-connection db
      (let [{[sql & params] :results-query
             count-query :count-query
             projected-fields :projected-fields} (query->sql version query
                                                             paging-options)
             query-error (promise)
             resp (output-fn
                   (fn [f]
                     (try
                       (jdbc/with-transacted-connection db
                         (query/streamed-query-result version sql params
                           (comp
                             f
                             #(do
                                (first %)
                                (deliver query-error nil)
                                %)
                             (munge-fn version projected-fields paging-options url-prefix))))
                       (catch java.sql.SQLException e
                         (deliver query-error e)
                         nil))))]
        (when @query-error
          (throw @query-error))
        (if count-query
          (http/add-headers resp {:count (jdbc/get-result-count count-query)})
          resp)))))

(defn produce-streaming-body
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [entity version query paging-options db url-prefix]
  (try
    (let [parsed-query (json/parse-strict-string query true)]
      (stream-query-result entity version parsed-query paging-options db url-prefix http/stream-json-response))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (http/error-response e))
    (catch IllegalArgumentException e
      (http/error-response e))
    (catch org.postgresql.util.PSQLException e
      (if (= (.getSQLState e) "2201B")
        (do
          (log/debug e "Caught PSQL processing exception")
          (http/error-response (.getMessage e)))
        (throw e)))))
