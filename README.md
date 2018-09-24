# capital-example

Pedestal application that shows how [capital-file](https://github.com/doubleelbow/capital-file) and [capital-http](https://github.com/doubleelbow/capital-file) can be used. In particular it shows that by using the capital-file, changes to configuration files take effect without a need to restart the application.

It also shows that HTML creation can be done inline with string concatenation. While this is good enough for this application it's probably not good enough for anything more production oriented.

## Getting started

The app can be started by `lein run`. After the app is started go to the [home page](http://localhost:8080/) that shows currently available external web services and a form that enables sending a batch of get requests to those services. Responses are generated based on `instructions` map defined in `resources/services.esc`. The `instructions` map states the probability of given HTTP status code in a response. If the probabilities don't add up to 1, the remaining probability is used to simulate connection problems by throwing `IOException`.

Changes to `instructions` map in  `services.esc` and configs in `cb-conf.edn` and `retry-conf.edn` take effect immediately (on browser refresh), without the need to restart pedestal app. The new service can also be added without the need to restart pedestal. With a bit of code reorganization even changes to `circuit-breaker-config` path and `retry-config` path in `services.esc` could take effect immediately.
