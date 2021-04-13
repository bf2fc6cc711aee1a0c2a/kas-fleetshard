# kas-fleetshard

[![Build and Unit tests](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/build.yml/badge.svg)](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/build.yml)
[![Smoke tests](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/smoke.yaml/badge.svg)](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/smoke.yaml)

## Running

> **WARNING** : currently the kas fleetshard operator needs a Strimzi operator already running on your Kubernetes/OpenShift cluster.

```shell
kubectl create namespace kafka
kubectl apply -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
```

The first step is to install the operator allowing the `dekorate` plugin to generate the `ManagedKafka` CRD.

```shell
mvn install
```

After that, apply the generated CRD to the Kubernetes/OpenShift cluster by running the following commands.

```shell
kubectl apply -f api/target/classes/META-INF/dekorate/kubernetes.yml
```

Finally, you can start the operator from your IDE running the `Main` application (for a step by step debugging purposes), 
or you can run it from the command line by running the following command (with Quarkus in "dev" mode).

```shell
mvn -pl operator quarkus:dev
```

> NOTE: Quarkus will start debugger listener on port 5005 to which you can attach from your IDE.

## Testing

Read [Testing guide](TESTING.md)

## Contributing

Use mvn clean process-sources or almost any mvn command to automatically format your code contribution prior to creating a pull request.