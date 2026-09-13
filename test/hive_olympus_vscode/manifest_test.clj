(ns hive-olympus-vscode.manifest-test
  "The shipped manifest through the real mounter against a stub hive.olympus
   (a presenter seat) and a stub hive.vscode exposing ONLY :vessel/target (a
   recording :json vessel), then against the real hive.olympus core manifest."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]))

(defrecord StubAddon [id hook-map]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _] {:success? true})
  (shutdown! [_] nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok})
  (excluded-tools [_] #{})
  (hooks [_] hook-map))

(def seat (atom {}))
(def natives (atom []))

(def panel
  {:op :ui/show-panel :panel/id "olympus/tab-1"
   :doc {:doc/title "Olympus  tab 1/1  (0 agents: 0 working, 0 blocked, 0 error, 0 idle)"
         :doc/blocks [{:block/type :para :text "No active agents" :tone :muted}]}})

(def vessel
  {:vessel/id :vscode-stub
   :vessel/dialect :json
   :vessel/features #{}
   :vessel/execute! (fn [native] (swap! natives conj native) {:delivered 1})})

(defn olympus-stub-ctor [_]
  (->StubAddon "hive.olympus"
               {:olympus/register-presenter! (fn [id target] (swap! seat assoc id target) (target [panel]) id)
                :olympus/unregister-presenter! (fn [id] (swap! seat dissoc id) id)}))

(defn vscode-stub-ctor
  "hive.vscode's hook surface as shipped: :vessel/target and nothing else."
  [_]
  (->StubAddon "hive.vscode" {:vessel/target (fn [] vessel)}))

(defn six-agents []
  (mapv #(hash-map :agent/id (str "ling-" %) :agent/name (str "worker-" %) :agent/status :idle)
        (range 1 7)))

(defn- manifest [file]
  (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string))

(def vscode-stub-spec
  {:addon/id "hive.vscode" :addon/type :native
   :addon/init-ns "hive-olympus-vscode.manifest-test" :addon/init-fn "vscode-stub-ctor"
   :addon/capabilities #{:vessel}})

(defn- mount-all [specs]
  (let [host (mount/atom-mount-host)
        report (mount/mount! (mount/solve specs) host)]
    [host report]))

(defn- wire
  "What reached the vessel: [dialect op panel-id] per native."
  []
  (mapv (fn [{:native/keys [dialect payload]}]
          [dialect (get payload "op") (get payload "panel/id")])
        @natives))

(deftest the-manifest-is-data-only
  (let [spec (manifest "hive-olympus-vscode.edn")]
    (is (= "hive.olympus.vscode" (:addon/id spec)))
    (is (= "hive-olympus.harness" (:addon/init-ns spec)))
    (is (= "addon-ctor" (:addon/init-fn spec)))
    (is (= {:olympus/host "hive.vscode"} (:addon/config spec)))
    (is (= #{"hive.olympus" "hive.vscode"} (:addon/dependencies spec)))
    (is (= :foss (:addon/trust-class spec)))
    (is (some #(= "hive.olympus.vscode" (:addon/id %)) (:specs (mount/discover-specs))))))

(deftest the-stub-host-offers-only-a-target
  (is (= #{:vessel/target} (set (keys (addon/hooks (vscode-stub-ctor {})))))
      "no :vessel/dispatch!, so :host-dispatch cannot be what delivers"))

(deftest mounts-against-stubs-and-delivers-through-host-target
  (reset! seat {})
  (reset! natives [])
  (let [[host report] (mount-all [(manifest "hive-olympus-vscode.edn")
                                  {:addon/id "hive.olympus" :addon/type :native
                                   :addon/init-ns "hive-olympus-vscode.manifest-test"
                                   :addon/init-fn "olympus-stub-ctor" :addon/capabilities #{}}
                                  vscode-stub-spec])
        brick (mount-port/registered host "hive.olympus.vscode")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (= "hive.olympus.vscode" (last (:order report))))
      (is (contains? @seat "hive.vscode") "registered under the host id")
      (is (= [[:json "ui/show-panel" "olympus/tab-1"]] (wire))
          "the seat's op is lowered by hive-vessel into one :json native on the target")
      (is (= "Olympus  tab 1/1  (0 agents: 0 working, 0 blocked, 0 error, 0 idle)"
             (get-in (first @natives) [:native/payload "doc" "doc/title"])))
      (is (= :host-target (get-in (addon/health brick) [:details :route])))
      (is (empty? (:errors (mount/teardown! host (:order report)))))
      (is (empty? @seat) "teardown unregisters the presenter")
      (finally (when brick (addon/shutdown! brick))))))

(deftest mounts-against-the-real-core
  (reset! natives [])
  (let [core-spec (update (manifest "hive-olympus.edn") :addon/config assoc
                          :olympus/refresh-ms 0
                          :olympus/roster-fn 'hive-olympus-vscode.manifest-test/six-agents)
        [host report] (mount-all [(manifest "hive-olympus-vscode.edn") core-spec vscode-stub-spec])
        core (mount-port/registered host "hive.olympus")
        brick (mount-port/registered host "hive.olympus.vscode")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (testing "the real core renders six agents on two tabs and the brick lowers both"
        (is (= [[:json "ui/show-panel" "olympus/tab-1"] [:json "ui/show-panel" "olympus/tab-2"]]
               (wire)))
        (is (= {:status :live :deliveries 1}
               (get-in (addon/health core) [:details :presenters "hive.vscode"])))
        (is (= :host-target (get-in (addon/health brick) [:details :route]))))
      (mount/teardown! host (:order report))
      (finally (doseq [id ["hive.olympus.vscode" "hive.olympus"]]
                 (when-let [a (mount-port/registered host id)]
                   (try (addon/shutdown! a) (catch Throwable _ nil))))))))
