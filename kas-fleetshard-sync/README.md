# kas-fleetshard-sync

Responsible for communications between the operator and the control plane.

There are two main processing activities:

- Poll the control plane and sync to the remote state

- Process local events and push those updates to the control plane, currently in the Informer package.

## build/test

```shell
mvn clean install
```

## running locally

```shell
mvn quarkus:dev
```

> NOTE: Quarkus will start debugger listener on port 5005 to which you can attach from your IDE.

## deployment

Follow the operator instructions to at least build / install the CRDs.  

The operator is optional for the sync to function.

To directly use the minikube registry, run:

```shell
eval $(minikube docker-env)

mvn package -DskipTests -Dquarkus.kubernetes.deploy=true -Dquarkus.container-image.build=true -Dquarkus.kubernetes.image-pull-policy=IfNotPresent
```