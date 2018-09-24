(ns capital-example.simulation
  (:require [com.doubleelbow.capital.alpha :as capital]
            [com.doubleelbow.capital.file.alpha :as capital.file]
            [com.doubleelbow.capital.http.alpha :as capital.http]
            [com.doubleelbow.capital.interceptor.impl.alpha.circuit-breaker :as intc.cb]
            [com.doubleelbow.capital.interceptor.impl.alpha.retry :as intc.retry]
            [clj-http.fake :as http-fake]
            [clj-http.client :as http-client]
            [io.pedestal.log :as log]))

(defn- transform-instructions [instructions]
  (:new-instructions (reduce (fn [r [k v]]
                               (let [new-total (+ (:total r 0) v)]
                                 (-> r
                                     (assoc :total new-total)
                                     (assoc-in [:new-instructions new-total] k))))
                             {}
                             instructions)))

(defn- edn->config-map [v]
  (into {} (map #(conj [] (:name %) (assoc % :transformed (transform-instructions (:instructions %)))) v)))

(defn- text->config-map [v]
  (-> v
      (clojure.edn/read-string)
      (edn->config-map)))


(def ^:private file-ctx
  (capital.file/initial-context {::capital.file/base-path "resources"
                                 ::capital.file/read-opts {::capital.file/cache {::capital.file/use-cache? true
                                                                                 ::capital.file/duration 1200
                                                                                 ::capital.file/check-if-newer? true}
                                                           ::capital.file/format-config {"esc" text->config-map}}}))

(def ^:private services (atom {}))

(defn- resource! [r]
  (capital.file/read! r file-ctx))

(defn- convert-to-clj-http-request [request async?]
  (-> request
      (assoc :url (::capital.http/url request)
             :async? async?)
      (dissoc ::capital.http/url ::fake-defs)))

(defn- fake-sync-request [request]
  (http-fake/with-fake-routes-in-isolation
    (::fake-defs request)
    (http-client/request (convert-to-clj-http-request request false))))

(defn- fake-async-request [request on-success on-error]
  (http-fake/with-fake-routes-in-isolation
    (::fake-defs request)
    (http-client/request (convert-to-clj-http-request request true)
                         #(on-success %)
                         #(on-error %))))

(defn- declare-retriable [retriable-statuses]
  (fn [context ex]
    (let [e (ex-data ex)]
      (not= -1 (.indexOf (get retriable-statuses (:status e 503) [])
                         (get-in context [::capital/request :method]))))))

(defn- counters [statuses]
  (log/debug :msg "creating counters" :statuses statuses)
  (into {"IOException" 0 "CBOpened" 0} (vec (map #(vec %) (partition 2 (interleave statuses (repeat 0)))))))

(defn- create-service! [config]
  (log/debug :msg "creating service" :config config)
  (let [srv-ctx (capital.http/initial-context {::capital.http/base-url (:url config)
                                               ::intc.cb/config #(resource! (:circuit-breaker-config config))
                                               ::intc.retry/config #(resource! (:retry-config config))
                                               ::capital.http/declare-retriable (declare-retriable {429 [:get :put :delete] 503 [:get :put :delete]})
                                               ::capital.http/request-fns {::capital.http/sync-fn fake-sync-request
                                                                           ::capital.http/async-fn fake-async-request}})
        srv-ctx (assoc srv-ctx ::counters (atom (counters (keys (:instructions config)))))]
    (swap! services assoc (:name config) srv-ctx)
    srv-ctx))

(defn- read-config! []
  (resource! "services.esc"))

(defn- service-config! [name]
  (log/debug :msg "reading service config" :name name)
  (get (read-config!) name))

(defn- service [name]
  (log/debug :msg "get or create service" :services (keys @services))
  (if-let [srv (get @services name)]
    srv
    (create-service! (service-config! name))))

(defn- status [transformed p]
  (if-let [k (some #(and (>= % p) %) (keys transformed))]
    (get transformed k)
    "IOException"))

(defn- cb-state [service]
  (deref (get-in service [::intc.cb/circuit-breaker-stats ::intc.cb/state])))

(defn- cb-state-type [state]
  (case (::intc.cb/state-type state)
    ::intc.cb/closed "closed"
    ::intc.cb/opened "opened"
    ::intc.cb/half-opened "half-opened"
    "unknown"))

(defn- service-details [name]
  (let [srv (service name)
        stats (deref (::counters srv))
        cb-st (cb-state srv)]
    {:name name
     :stats (assoc stats
                   "cb state" (cb-state-type cb-st)
                   "cb state change" (::intc.cb/last-change cb-st))}))

(defn stats []
  (let [names (keys (read-config!))]
    (map #(service-details %) names)))

(defn- fake-request-fn [context config]
  (fn [request]
    (let [p (rand)
          s (status (:transformed config) p)]
      (log/debug :msg "preparing response" :p p :status s :counters (deref (::counters context)))
      (swap! (::counters context) update s inc)
      (log/debug :msg "counters changed" :counters (deref (::counters context)))
      (if (= "IOException" s)
        (throw (java.io.IOException. "fake connection problems"))
        {:status s :headers {} :body (str "Randomly generated response for p = " p)}))))

(defn- simulate-request [service-name]
  (let [srv-ctx (service service-name)
        srv-conf (service-config! service-name)
        response (capital.http/send-sync-request! "/"
                                                  {:method :get
                                                   ::fake-defs {(:url srv-conf) {:get (fake-request-fn srv-ctx srv-conf)}}}
                                                  srv-ctx)]
    (if (and (::capital.http/error? response)
             (= ::intc.cb/circuit-breaker (get-in response [::capital.http/data :type]))
             (= ::intc.cb/opened (get-in response [::capital.http/data :cause])))
      (swap! (::counters srv-ctx) update "CBOpened" inc))
    response))

(defn simulate-requests [service-name n]
  (log/debug :msg "simulation start" :service service-name :size n)
  (dotimes [i n]
    (do
      (log/debug :msg "sending request" :no i)
      (let [response (simulate-request service-name)]
        (log/debug :msg "response" :no i :response response))))
  (log/debug :msg "simulation end"))


