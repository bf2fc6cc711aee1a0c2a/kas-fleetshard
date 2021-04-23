# Advanced Guide

OLM deployment

Refining operands

## Logging Adjustments At Runtime

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