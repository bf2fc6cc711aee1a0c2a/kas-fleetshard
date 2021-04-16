## Releasing

Following the temporary manual steps to build fleetshard operator and synchronizer.

1. Create a new release branch out of the `main` branch considering to include the future possible patch releases. So, for example, something like `0.1.x`.
2. Export an environment variable `RELEASE_VERSION` containing the new version to release. For example, `RELEASE_VERSION=0.1.0`.
3. Export an environment variable `NEW_SNAPSHOT_VERSION` containing the future development version. For example, `NEW_SNAPSHOT_VERSION=0.1.1-SNAPSHOT`.
3. Update the current SNAPSHOT version in all pom files with the release version in the `RELEASE_VERSION` environment variables.
4. Do a "prepare release" commit as `git commit -m "Releasing ${RELEASE_VERSION}`.
5. Tag the commit as `git tag ${RELEASE_VERSION}`.
6. Export the environment variables related to the container registry `IMAGE_REGISTRY` and the repository `IMAGE_REPO`.
For example something like `IMAGE_REGISTRY=quay.io`, `IMAGE_REPO=mk-ci-cd`.
7. Run the package and build image command for the fleetshard operator and synchronizer:

```shell
mvn clean package -am -DskipTests --no-transfer-progress \
          -Dquarkus.container-image.build=true \
          -Dquarkus.container-image.registry=${IMAGE_REGISTRY} \
          -Dquarkus.container-image.group=${IMAGE_REPO} \
          -Dquarkus.container-image.tag=${RELEASE_VERSION} \
          -Dquarkus.container-image.push=true
```

8. Do a "prepare for next development commit" as `git commit -m "Prepare development release ${NEW_SNAPSHOT_VERSION}"`
9. Push upstream `git push --tags upstream`