# Advanced Guide

OLM deployment

Refining operands

## Configure fleetshard operator and synchronizer logging

If you wish to change the logging behavior of a component at runtime, you should create a configmap with an application.properties file in the operator namespace named *component*_logging_config_override.  The application.properties file should contain logging properties of the form quarkus.log.category.*something*.level.

For example, a configmap.yml file to change the logging level of org.bf2 to DEBUG for the operator would look like

```
apiVersion: v1
data:
  application.properties: |
    quarkus.log.category."org.bf2".level=DEBUG
kind: ConfigMap
metadata:
  name: operator-logging-config-override
```

You can create the configmap with:

```shell
kubectl apply -f configmap.yml -n kas-fleetshard
```

To restore logging defaults, you can delete the configmap and restart the pod, or you can update the configmap with the appropriate defaults, in this case:

```
apiVersion: v1
data:
  application.properties: |
    quarkus.log.category."org.bf2".level=INFO
kind: ConfigMap
metadata:
  name: operator-logging-config-override
```

See more on [Quarkus logging](https://quarkus.io/guides/logging)

## Configure canary logging

The canary tool, deployed by the fleetshard operator, provides logging at two different levels:

* canary application itself
* Sarama Apache Kafka client, used by the canary to interact with the Apache Kafka cluster

The canary application has a verbosity logging level to be configurable through the `VERBOSITY_LOG_LEVEL` environment variable that defaults to 0 (INFO); other allowed values are 1 (DEBUG) and 2 (TRACE).
Warnings and errors are always logged and not driven by such a configuration.

The Sarama Apache Kafka client just allows to enable or disable (default) the logging, without any kind of verbosity level support.
It is possible to enable it by using the `SARAMA_LOG_ENABLED` environment variable.

If you wish to change the above logging configuration, you should create a ConfigMap containing the corresponding environment variables in the following way.

```
apiVersion: v1
kind: ConfigMap
metadata:
  name: canary-config
data:
  sarama.log.enabled: "true"
  verbosity.log.level: "1"
```

You can create the ConfigMap with:

```shell
kubectl apply -f canary-config.yml -n kas-fleetshard
```

Because the configuration is set using environment variables, you need to restart the canary application in order to allow it to get and use the new values; it is enough to scale the corresponding Deployment down because the fleetshard operator will scale it up again during the reconcile.