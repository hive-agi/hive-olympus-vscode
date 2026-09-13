(ns hive-olympus-vscode.test-runner
  (:require [clojure.test :as test]
            [hive-olympus-vscode.manifest-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'hive-olympus-vscode.manifest-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
