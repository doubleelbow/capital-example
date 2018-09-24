(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh refresh-all]]
            [capital-example.simulation :as sim]
            [capital-example.server :as srv]
            [io.pedestal.http :as http-server]))

(comment
  (def serv (srv/dev-run))

  (http-server/stop serv))
