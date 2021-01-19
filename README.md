# mk-agent

![Build](https://github.com/bf2fc6cc711aee1a0c2a/mk-agent/workflows/Build%20and%20Unit%20tests/badge.svg?branch=main)

## Running

> **WARNING** : currently the agent operator needs a Strimzi operator already running on your Kubernetes/OpenShift cluster.

The first step is to package the operator allowing the `dekorate` plugin to generate the `ManagedKafka` CRD.

```shell
mvn package
```

After that, apply the generated CRD to the Kubernetes/OpenShift cluster by running the following commands.

```shell
cd agent-operator
kubectl apply -f target/classes/META-INF/dekorate/kubernetes.yml
```

Finally, you can start the operator from your IDE running the `Main` application (for a step by step debugging purposes), 
or you can run it from the command line by running the following command (with Quarkus in "dev" mode).

```shell
./mvnw quarkus:dev
```

> NOTE: Quarkus will start debugger listener on port 5005 to which you can attach from your IDE.
