# kas-fleetshard

[![Build and Unit tests](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/build.yml/badge.svg)](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/build.yml)
[![Smoke tests](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/smoke.yaml/badge.svg)](https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/actions/workflows/smoke.yaml)

## Running

> **WARNING** : currently the kas fleetshard operator needs a Strimzi operator already running on your Kubernetes/OpenShift cluster.

```shell
kubectl create namespace kafka
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
```

The first step is to install the operator allowing fabric8 to generate the `ManagedKafka` CRDs.

```shell
mvn install
```

After that, apply the generated CRD to the Kubernetes/OpenShift cluster by running the following commands.

```shell
kubectl apply -f operator/target/kubernetes/managedkafkas.managedkafka.bf2.org-v1.yml
kubectl apply -f operator/target/kubernetes/managedkafkaagents.managedkafka.bf2.org-v1.yml
```

Finally, you can start the operator from your IDE running the `Main` application (for a step by step debugging purposes),
or you can run it from the command line by running the following command (with Quarkus in "dev" mode). If you're running
against a vanilla Kubernetes, you'll need to add `-Dkafka=dev` so that it doesn't assume that OLM, etc, are available.

```shell
# OpenShift
mvn -pl operator quarkus:dev

# OR
# Vanilla Kubernetes
mvn -pl operator quarkus:dev -Dkafka=dev
```

> NOTE: Quarkus will start debugger listener on port 5005 to which you can attach from your IDE.

## Testing

Read [Testing guide](TESTING.md)

## Releasing

### Milestones
Each release requires an open milestone that includes the issues/pull requests that are part of the release. All issues in the release milestone must be closed. The name of the milestone must match the version number to be released.

### Configuration
The release action flow requires that the following secrets are configured in the repository:
* `IMAGE_REPO_HOSTNAME` - the host (optionally including a port number) of the image repository where images will be pushed
* `IMAGE_REPO_NAMESPACE` - namespace/library/user where the image will be pushed
* `IMAGE_REPO_USERNAME` - user name for authentication to server `IMAGE_REPO_HOSTNAME`
* `IMAGE_REPO_PASSWORD` - password for authentication to server `IMAGE_REPO_HOSTNAME`
These credentials will be used to push the release image to the repository configured in the `.github/workflows/release.yml` workflow.

### Performing the Release

#### Release Branch
*Optional - only required when a new release branch is needed -- for patch releases, skip this branch creation, and instead re-use the existing minor release branch*.

If you are starting on main branch, create a new branch from the main. For example `0.3.x`.

```shell
git checkout -b 0.3.x main
git push upstream 0.3.x
```
Now release from the `0.3.x` branch a release of `0.3.0`, if you are already releasing from a branch skip the above
step of creating a new branch and simply checkout that branch.

#### Pull Request
Releases are performed by modifying the `.github/project.yml` file, setting `current-version` to the release version and `next-version` to the next SNAPSHOT. Open a pull request with the changed `project.yml` to initiate the pre-release workflows. The target of the pull request should be either `main` or a release branch (described above).
At this phase, the project milestone will be checked and it will be verified that no issues for the release milestone are still open. Additionally, the project's integration tests will be run.
Once approved and the pull request is merged, the release action will execute. This action will execute the Maven release plugin to tag the release commit, build the application artifacts, create the build image, and push the image to the repository identified by the secret `IMAGE_REPO_HOSTNAME`. If successful, the action will push the new tag to the Github repository and generate release notes listing all of the closed issues included in the milestone. Finally, the milestone will be closed.

## Contributing

Use mvn clean process-sources or almost any mvn command to automatically format your code contribution prior to creating a pull request.
