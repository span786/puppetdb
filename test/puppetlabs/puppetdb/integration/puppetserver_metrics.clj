(ns puppetlabs.puppetdb.integration.puppetserver-metrics
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(deftest ^:integration puppetserver-http-client-metrics
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (testing "Run puppet using exported resources and puppetdb_query function"
      (int/run-puppet-as "exporter" ps pdb
                         (str
                          "$counts = puppetdb_query(['from', 'catalogs',"
                          "                            ['extract', [['function', 'count']]]])"
                          "@@notify { 'hello world': }"))

      ;; Collecting resources triggers a `facts find` and `resource search`
      (int/run-puppet-as "collector" ps pdb "Notify <<| |>>"))

    (testing "Puppet Server status endpoint contains expected puppetdb metrics"

      (let [status-endpoint (str "https://localhost:" (-> ps int/server-info :port) "/status/v1")
            all-svcs-status (svc-utils/get-ssl (str status-endpoint "/services"))]
        (is (= 200 (:status all-svcs-status)))
        ;; in older versions of Puppet Server (pre-5.0), the `master` status
        ;; didn't exist. Since these tests are run against multiple versions
        ;; of Puppet Server, this ensures that we're only testing that the
        ;; `master` status has appropriate content on the right versions.
        (when (some? (get-in all-svcs-status [:body :master]))
          (let [resp (svc-utils/get-ssl (str status-endpoint "/services/master?level=debug"))]
            (is (= 200 (:status resp)))

            (let [metrics (get-in resp [:body :status :experimental :http-client-metrics])]
              (is (= #{["puppetdb" "command" "replace_catalog"]
                       ["puppetdb" "command" "replace_facts"]
                       ["puppetdb" "command" "store_report"]
                       ["puppetdb" "facts" "find"]
                       ["puppetdb" "query"]
                       ["puppetdb" "resource" "search"]}
                     (set (map :metric-id metrics)))))))))

    (testing "PuppetDB metrics are updated for compressed commands"
      ;; the terminus is configured to send gzipped commands without a
      ;; 'Content-Length' header this test checks that the custom
      ;; 'X-Uncompressed-Length' header updates the PDB size metric
      (let [size-metrics-url (str "https://localhost:"
                                  (-> pdb int/server-info :base-url :port)
                                  "/metrics/v2/read/puppetlabs.puppetdb.mq:name=global.size")
            metrics-resp (svc-utils/get-ssl size-metrics-url)]
        (is (= 200 (:status metrics-resp)))
        ;; assert that the size metric has been updated and no values are 0.0
        (is (->> metrics-resp
                 :body
                 :value
                 vals
                 (map #(not= 0.0 %))
                 (every? true?)))))

    (testing "PuppetDB doesn't create duplicate command metrics"
      (let [bulk-metrics-req (fn [metrics]
                               (let [{:keys [status body]}
                                     (svc-utils/post-ssl (str "https://localhost:"
                                                              (-> pdb int/server-info :base-url :port)
                                                              "/metrics/v1/mbeans")
                                                         metrics)]
                                 {:status status
                                  :body (-> body slurp json/parse-string)}))

            ;; these metrics use the normalized command name and should be populated
            normalized-metrics (bulk-metrics-req ["puppetlabs.puppetdb.mq:name=replace facts.5.seen"
                                                  "puppetlabs.puppetdb.mq:name=store report.8.seen"
                                                  "puppetlabs.puppetdb.mq:name=replace catalog.9.seen"])

            ;; these metrics were the duplicates being seen in PDB-3417 they use
            ;; the command name from the query param before normalization and shouldn't be populated
            improper-metrics (bulk-metrics-req ["puppetlabs.puppetdb.mq:name=replace_facts.5.seen"
                                                "puppetlabs.puppetdb.mq:name=store_report.8.seen"
                                                "puppetlabs.puppetdb.mq:name=replace_catalog.9.seen"])]

        ;; check that normalized metrics are populated
        (let [{:keys [status body]} normalized-metrics]
          (is (= 200 status))
          (is (= 3 (count body)))
          (is (every? some? body)))

        ;; check that the improperly formated metrics are no longer being created
        (let [{:keys [status body]} improper-metrics]
          (is (= 200 status))
          (is (= 3 (count body)))
          (is (every? nil? body)))))))
