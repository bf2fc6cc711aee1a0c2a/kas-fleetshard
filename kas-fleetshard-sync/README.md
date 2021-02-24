# kas-fleetshard-sync

Responsible for communications between the operator and the control plane.

There are two main processing activities:

- Poll the control plane and sync to the remote state

- Process local events and push those updates to the control plane, currently in the Informer package.

## build/test

```shell
mvn clean install
```

## running

Follow the operator instructions to at least build / install the CRDs, then create the kas-fleetshard namespace and create necessary authorizations:

```shell
kubectl create namespace kas-fleetshard
kubectl config set-context --current --namespace=kas-fleetshard
kubectl create -f ../kas-fleetshard-operator/src/main/kubernetes
```

> NOTE: ../kas-fleetshard-operator/src/main/kubernetes contains a deployment of the operator, which is not required for the sync to function.

### local

If crc or minikube is running, Quarkus should be smart enough to know how to connect to the instance and create a local process to run the sync.  

> NOTE: Quarkus will start debugger listener on port 5005 to which you can attach from your IDE.

Simply run:

```shell
mvn quarkus:dev
```

### in container

To directly use the minikube registry, run:

```shell
eval $(minikube docker-env)

mvn package -DskipTests -Dquarkus.kubernetes.deploy=true -Dquarkus.container-image.build=true -Dquarkus.kubernetes.image-pull-policy=IfNotPresent -Dquarkus.kubernetes.namespace=kas-fleetshard
```

Be default the image will run in prod mode.  Add -Dquarkus.profile=dev to run in dev mode - which does not expect sso nor the addon secret

### In OpenShift (Code Ready Container)

To get access to the OpenShift image registry run below commands

```shell
oc extract secret/router-ca --keys=tls.crt -n openshift-ingress-operator
sudo mkdir -p /etc/docker/certs.d/default-route-openshift-image-registry.apps-crc.testing/ 
sudo cp tls.crt /etc/docker/certs.d/default-route-openshift-image-registry.apps-crc.testing/
sudo chmod 644 /etc/docker/certs.d/default-route-openshift-image-registry.apps-crc.testing/tls.crt
docker login -u developer -p $(oc whoami -t) default-route-openshift-image-registry.apps-crc.testing
```

now to deploy the agent-sync run

```shell
mvn package -DskipTests -Dquarkus.kubernetes.deploy=true -Dquarkus.container-image.build=true -Dquarkus.kubernetes.image-pull-policy=IfNotPresent -Dquarkus.kubernetes.namespace=kas-fleetshard -Dquarkus.kubernetes-client.trust-certs=true
```