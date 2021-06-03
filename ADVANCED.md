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

The canary application has a verbosity logging level configuration using the `VERBOSITY_LOG_LEVEL` environment variable that defaults to `0` (INFO); other allowed values are `1` (DEBUG) and `2` (TRACE).
Warnings and errors are always logged and are not affected by the value of `VERBOSITY_LOG_LEVEL`.

The Sarama Apache Kafka client only allows logging to be enabled or disabled (default), without any kind of verbosity level support.
It is possible to enable it by using the `SARAMA_LOG_ENABLED` environment variable.

If you wish to change the above logging configuration, you should create a ConfigMap named `canary-config` in the namespace of the Kafka instance where the canary is running.
The ConfigMap should contain the corresponding environment variables in the following way.

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
kubectl apply -f canary-config.yml -n my-kafka-cluster-namespace
```

Because the configuration is set using environment variables, you need to restart the canary application in order to allow it to get and use the new values; it is enough to scale the corresponding Deployment down because the fleetshard operator will scale it up again during the reconcile.

## Configure admin server logging

The Kafka admin server, deployed by the fleetshard operator, utilizes the Log4J2 logging framework and provides a way to configure an additional configuration properties file at runtime using a ConfigMap. The ConfigMap must reside in the same namespace as the admin server where additional logging will be enabled and the name of the ConfigMap must match the name of the admin server deployment, e.g. `my-kafka-cluster-admin-server`. The ConfigMap should contain an entry named `log4j2.properties` with any content supported by Log4J2 properties configuration. [See more on Log4J2 properties format and options](https://logging.apache.org/log4j/2.x/manual/configuration.html#Properties).

Given the following `admin-server-config.yml` file:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: my-kafka-cluster-admin-server
data:
  log4j2.properties: |
    rootLogger.level = DEBUG
    logger.adminserver.name = org.bf2.admin
    logger.adminserver.level = DEBUG
```

You can create the ConfigMap with:

```shell
kubectl apply -f admin-server-config.yml -n my-kafka-cluster-namespace
```
The added configuration will be merged with the default logging configuration already present in the admin server. There is no need to restart the application. Configuration properties present in the ConfigMap will override properties with the same key in the default configuration. To reset logging configuration to the defaults, simply delete the ConfigMap.

## Configure Strimzi Components logging

To change the Strimzi operator logging, find the namespace in which the Strimzi Operator is installed then edit the config map with name `strimzi-cluster-operator` that has the log4j configuration for the operator. The contents of the key `log4j.properties` needs to be modified to suit the needs.

```shell
kubectl edit cm strimzi-cluster-operator -n <namespace>
```

## Configure Kafka Components logging

The fleetshard operator when it installs Kafka cluster, it configures Kafka cluster with custom logging configuration that be changed by the user at runtime. The logging configuration for brokers, zookeeper and exporter are configured individually in separate ConfigMaps in the namespace where the Kafka cluster is installed.

### Kafka Broker logging configuration

To change the logging configuration of the Kafka broker component execute the following and the contents of the key `log4j.properties` needs to be modified to suit the needs.

```shell
oc edit cm <kafka-cluster-name>-kafka-logging -n <tenant-kafka-cluster-namespace>
```

### Kafka Zookeeper logging configuration

To change the logging configuration of the Zookeeper component execute the following and the contents of the key `log4j.properties` needs to be modified to suit the needs.

```shell
oc edit cm <kafka-cluster-name>-zookeeper-logging -n <tenant-kafka-cluster-namespace>
```

### Kafka Exporter logging configuration

To change the logging configuration of the Kafka Exporter component execute the following

```shell
oc edit cm <kafka-cluster-name>-kafka-exporter-logging -n <tenant-kafka-cluster-namespace>
```

A sample configmap looks as below, make necessary edits as required


```yaml
  apiVersion: v1
  kind: ConfigMap
  metadata:
    name: kafka-exporter-logging
  data:
  enableSaramaLogging: 'false'
  logLevel: info
```

supported log levels: [debug, info, warn, error, fatal]