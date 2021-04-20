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

## Releasing

If you are starting on main branch. create a new branch from the main for example `0.3.x`

```shell
git branch -b 0.3.x main
git push upstream 0.3.x
```
Now release from the `0.3.x` branch a release of `0.3.0`, if you are already releasing from a branch skip the above 
step of creating a new branch and simply checkout that branch.

```shell
git checkout 0.3.x
mvn -B -P release clean release:prepare -DreleaseVersion=0.3.0 -DdevelopmentVersion=0.3.1-SNAPSHOT -DignoreSnapshots=true
```

This will create the release commit, add a tag, and another commit updating the poms to the next development version. We have to specify the `releaseVersion` and `developmentVersion`, because we're starting off with the synthetic 999-SNAPSHOT version on main. 

Set environment variables or use -Drelease.properties="" to pass Quarkus related image properties to the release perform:

```shell
export QUARKUS_CONTAINER_IMAGE_REGISTRY=...
export QUARKUS_CONTAINER_IMAGE_GROUP=...
export QUARKUS_CONTAINER_IMAGE_USERNAME=...
export QUARKUS_CONTAINER_IMAGE_PASSWORD=...

mvn -P release release:perform
```

This will checkout against the tag created in the previous step and then runs the release. This will build the modules, but will not push any of the maven artifacts. This will also build container images for operator and sync modules and will push those images into the container registry defined in the above environment properties. The images will be also tagged with `releaseVersion`


## Contributing

Use mvn clean process-sources or almost any mvn command to automatically format your code contribution prior to creating a pull request.