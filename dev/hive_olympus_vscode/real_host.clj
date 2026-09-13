(ns hive-olympus-vscode.real-host
  "The brick against the REAL hive.vscode (published jar) and the REAL
   hive.olympus core, in an isolated JVM: mount all three from their shipped
   manifests, subscribe to the bridge's SSE stream the way the extension host
   does, and optionally drive a headless VS Code with the hive-vscode extension.

   Never load this into a live hive: it starts its own bridge and core.

     clojure -Sdeps \"$(cat local.deps.edn)\" -M:real-host          ; wire capture
     clojure -Sdeps \"$(cat local.deps.edn)\" -M:real-host vscode   ; + real VS Code"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [clojure.pprint :as pp])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

;; SPDX-License-Identifier: MIT

(defn stub-roster
  "N agents cycling through the statuses the grid renders."
  [n]
  (mapv (fn [i]
          (cond-> {:agent/id (str "demo-" i)
                   :agent/name (str "demo-" i)
                   :agent/status (nth [:working :blocked :error :idle] (mod i 4))}
            (even? i) (assoc :agent/task (str "task " i))))
        (range 1 (inc n))))

(defonce agent-count (atom 6))

(defn roster
  "The core's :olympus/roster-fn: (stub-roster @agent-count)."
  []
  (stub-roster @agent-count))

(defn- manifest [file]
  (or (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string)
      (throw (ex-info (str "manifest not on the classpath: " file) {:file file}))))

(defn specs
  "The three shipped manifests; only runtime knobs are layered onto :addon/config."
  [{:keys [discovery-path refresh-ms]}]
  [(update (manifest "hive-vscode.edn") :addon/config assoc :vscode/discovery-path discovery-path)
   (update (manifest "hive-olympus.edn") :addon/config assoc
           :olympus/refresh-ms refresh-ms
           :olympus/roster-fn `roster)
   (manifest "hive-olympus-vscode.edn")])

(defn mount-real!
  "Mount hive.vscode, hive.olympus and the brick. {:host :report :order}."
  [opts]
  (let [host (mount/atom-mount-host)
        report (mount/mount! (mount/solve (specs opts)) host)]
    {:host host :report report :order (:order report)}))

(defn health
  "Health of every mounted addon, by id."
  [{:keys [host order]}]
  (into {} (for [id order :let [a (mount-port/registered host id)] :when a]
             [id (addon/health a)])))

(defn teardown! [{:keys [host order]}]
  (mount/teardown! host order))

(defn temp-root []
  (str (Files/createTempDirectory "hive-olympus-vscode" (make-array FileAttribute 0))))

(defn subscribe!
  "Open GET /vessel/events with the token from DISCOVERY-PATH, exactly as the
   extension client does. {:lines atom-of-raw-sse-lines :cancel fn}; a failure
   of the reader lands in :lines as an \":error ...\" line."
  [discovery-path]
  (let [{:strs [url token]} (json/read-str (slurp discovery-path))
        lines (atom [])
        client (HttpClient/newHttpClient)
        req (.build (.GET (HttpRequest/newBuilder (URI/create (str url "/vessel/events?token=" token)))))
        fut (.sendAsync client req (HttpResponse$BodyHandlers/ofLines))
        reader (future
                 (try
                   (let [^java.net.http.HttpResponse resp (.get fut 10 TimeUnit/SECONDS)
                         ^java.util.stream.Stream body (.body resp)]
                     (swap! lines conj (str ":status " (.statusCode resp)))
                     (.forEach body
                               (reify java.util.function.Consumer
                                 (accept [_ line] (swap! lines conj line)))))
                   (catch Throwable t
                     (swap! lines conj (str ":error " (.getName (class t)) " " (ex-message t))))))]
    {:lines lines :cancel (fn [] (future-cancel reader) (.cancel fut true))}))

(defn frames
  "Split raw SSE LINES into frames at blank lines."
  [lines]
  (->> lines (partition-by str/blank?) (remove #(every? str/blank? %))))

(defn events
  "Parsed `data:` frames from raw SSE LINES: [{:id :op :panel :title :keys}]."
  [lines]
  (->> (frames lines)
       (keep (fn [frame]
               (when-let [data (some #(when (str/starts-with? % "data: ") (subs % 6)) frame)]
                 (let [m (json/read-str data)]
                   {:id (some #(when (str/starts-with? % "id: ") (subs % 4)) frame)
                    :op (get m "op")
                    :panel (get m "panel/id")
                    :title (get-in m ["doc" "doc/title"])
                    :keys (vec (sort (keys m)))}))))
       vec))

(defn wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 100) (recur))))))

(defn- panel-event? [op panel lines]
  (some #(and (= op (:op %)) (= panel (:panel %))) (events lines)))

(defn vscode-process!
  "Start xvfb-run node dev/hive_olympus_vscode/vscode_run.js against the
   hive-vscode checkout at VSCODE-REPO (extension + cached VS Code, read-only)."
  [{:keys [root runtime vscode-repo]}]
  (let [pb (doto (ProcessBuilder. ["xvfb-run" "-a" "node" "dev/hive_olympus_vscode/vscode_run.js"])
             (.redirectErrorStream true)
             (.redirectOutput (io/file (str root "/vscode.log"))))
        env (.environment pb)]
    (doseq [[k v] {"XDG_RUNTIME_DIR" runtime
                   "HIVE_VSCODE_REPO" vscode-repo
                   "HIVE_OLYMPUS_OPENED" (str root "/opened.json")
                   "HIVE_OLYMPUS_RESULT" (str root "/result.json")
                   "HIVE_OLYMPUS_WORKDIR" root}]
      (.put env k v))
    (.start pb)))

(defn scenario!
  "The whole real-host scenario: six agents (two tabs), then three (one tab).
   Writes the raw SSE capture to <root>/events.sse. Returns the evidence map."
  [{:keys [vscode? vscode-repo] :or {vscode-repo "../hive-vscode"}}]
  (reset! agent-count 6)
  (let [root (temp-root)
        runtime (str root "/runtime")
        discovery (str runtime "/hive-vessel/vscode.json")
        vscode-repo (.getCanonicalPath (io/file vscode-repo))
        sys (mount-real! {:discovery-path discovery :refresh-ms 250})]
    (try
      (let [sub (subscribe! discovery)
            proc (when vscode? (vscode-process! {:root root :runtime runtime :vscode-repo vscode-repo}))
            lines (:lines sub)
            opened-both (wait-until #(and (panel-event? "ui/show-panel" "olympus/tab-1" @lines)
                                          (panel-event? "ui/show-panel" "olympus/tab-2" @lines))
                                    10000)
            vscode-opened (when proc (wait-until #(.exists (io/file root "opened.json")) 300000))
            health-open (health sys)
            _ (reset! agent-count 3)
            closed (wait-until #(panel-event? "ui/close-panel" "olympus/tab-2" @lines) 10000)
            exited (when proc (.waitFor ^Process proc 120 TimeUnit/SECONDS))
            _ (Thread/sleep 300)
            health-closed (health sys)
            read-json (fn [name] (let [f (io/file root name)] (when (.exists f) (json/read-str (slurp f)))))]
        ((:cancel sub))
        (spit (str root "/events.sse") (str/join "\n" @lines))
        {:root root
         :mounted (:ok? (:report sys))
         :order (:order sys)
         :wire {:opened-both? opened-both :closed-tab-2? closed
                :head (vec (take 2 @lines))
                :events (events @lines)}
         :brick-route (get-in health-open ["hive.olympus.vscode" :details :route])
         :presenters (get-in health-closed ["hive.olympus" :details :presenters])
         :bridge-panels {:open (get-in health-open ["hive.vscode" :details :panels])
                         :after-shrink (get-in health-closed ["hive.vscode" :details :panels])
                         :windows (get-in health-open ["hive.vscode" :details :windows])}
         :vscode (when proc
                   {:opened? vscode-opened
                    :exited? exited
                    :exit (when exited (.exitValue ^Process proc))
                    :opened (read-json "opened.json")
                    :result (read-json "result.json")})})
      (finally (teardown! sys)))))

(defn -main [& args]
  (let [evidence (scenario! {:vscode? (boolean (some #{"vscode"} args))})]
    (spit (str (:root evidence) "/evidence.edn") (with-out-str (pp/pprint evidence)))
    (pp/pprint evidence)
    (shutdown-agents)
    (System/exit (if (and (:mounted evidence)
                          (get-in evidence [:wire :opened-both?])
                          (get-in evidence [:wire :closed-tab-2?])
                          (or (nil? (:vscode evidence)) (= 0 (get-in evidence [:vscode :exit]))))
                   0 1))))

(comment
  (def sys (mount-real! {:discovery-path (str (temp-root) "/hive-vessel/vscode.json") :refresh-ms 250}))
  (health sys)
  (reset! agent-count 3)
  (teardown! sys)
  (scenario! {})
  (scenario! {:vscode? true}))
