# mk-agent

![Build and Unit tests](https://github.com/bf2fc6cc711aee1a0c2a/mk-agent/workflows/Build%20and%20Unit%20tests/badge.svg)
![Integration tests](https://github.com/bf2fc6cc711aee1a0c2a/mk-agent/workflows/Integration%20tests/badge.svg)

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

## Running on OpenShift

If you want run the operator on a OpenShift instance please follow below steps. If you do not have access to a Openshift instance, you can install (Code Ready Container) CRC locally.

Once OpenShift instance is available, then using `oc` login into OpenShift and create project (namespace) then

- Install the crd

```
cd agent-operator
oc apply -f target/classes/META-INF/dekorate/kubernetes.yml
```

Then run 

```
mvn package -DskipTests -Dquarkus.kubernetes.deploy=true \
-Dquarkus.container-image.push=true \
-Dquarkus.container-image.registry=docker.io \
-Dquarkus.container-image.username=xxxx \
-Dquarkus.container-image.password=xxxx
```

replace above `xxx` with your `docker` or `quay.io` credentials. If the Strimzi Operator is installed in the cluster 
scope then also run

```
./cluster-scope.sh
```

this should create permissions such that Agent will be able to watch Strimzi resources at cluster level.