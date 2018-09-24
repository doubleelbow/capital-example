(ns capital-example.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [capital-example.simulation :as sim]))

(defn- stats->html [stats]
  (reduce (fn [r [k v]]
            (str r "<div class=\"stat\"><span class=\"name\">" k "</span><span class=\"value\">" v "</span></div>"))
          ""
          stats))

(defn- service->html [service]
  (str "<div class=\"service \"><h3>" (:name service) "</h3>" (stats->html (:stats service)) "</div>"))

(defn- services->html [services]
  (str "<div class=\"services\">"
       (apply str (map #(service->html %) services))
       "</div>"))

(defn- service-select [options]
  (str "<select name=\"service\" id=\"service\">"
       (apply str (map #(str "<option>" % "</option>") options))
       "</select>"))

(defn- simulation-form [services]
  (let [names (map #(:name %) services)]
    (str "<div class=\"simulation\"><form action=\"/\" method=\"post\">"
         "<div class=\"line\"><label for=\"service\">Service:</label>" (service-select names) "</div>"
         "<div class=\"line\"><label for=\"size\">Number of requests:</label><input type=\"number\" name=\"size\" id=\"size\" /></div>"
         "<div class=\"line\"><input type=\"submit\" value=\"Simulate\" /></div></form></div>")))

(defn- html [& parts]
  (str "<html><head><style type=\"text/css\">" (slurp "resources/style.css") "</style></head>"
       (apply str parts)
       "</html>"))

(defn- stats-response [context]
  (let [stats (sim/stats)]
    (assoc context :response {:status 200 :body (html (services->html stats) (simulation-form stats))})))

(def list-services-intc
  {:name ::list-services
   :enter stats-response})

(defn- simulate-requests [service size]
  (let [n (Integer/parseInt size)]
    (if (> n 0)
      (sim/simulate-requests service n))))

(def simulate-requests-intc
  {:name ::simulate-requests
   :enter (fn [context]
            (let [service (get-in context [:request :params "service"])
                  size (get-in context [:request :params "size"])]
              (simulate-requests service size)
              (stats-response context)))})

(def common-interceptors [(body-params/body-params) http/html-body])

(def routes #{["/" :get (conj common-interceptors list-services-intc)]
              ["/" :post (conj common-interceptors simulate-requests-intc)]})

(def service {:env :prod
              ::http/routes routes
              ::http/resource-path "/public"
              ::http/type :jetty
              ::http/port 8080
              ::http/container-options {:h2c? true
                                        :h2? false
                                        :ssl? false}})

