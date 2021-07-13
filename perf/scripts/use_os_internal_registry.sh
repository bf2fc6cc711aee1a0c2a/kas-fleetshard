#!/usr/bin/env bash

if [ -z "$1" ]
  then
    echo "No cluster name provided. ex: ./enable_os_registry.sh my-kafka"
    exit
fi


CLUSTER_NAME=$1

CLUSTER_ID=`ocm list clusters | grep ${CLUSTER_NAME} | awk '{print $1;}'`
KUSER=`ocm get /api/clusters_mgmt/v1/clusters/${CLUSTER_ID}/credentials | jq -r .admin.user`
KPASSWORD=`ocm get /api/clusters_mgmt/v1/clusters/${CLUSTER_ID}/credentials | jq -r .admin.password`

oc login -u ${KUSER} -p ${KPASSWORD} ${API_URL}

oc extract secret/router-ca --keys=tls.crt -n openshift-ingress-operator

HOST=`oc get route default-route -n openshift-image-registry -o json | jq -r .status.ingress[0].host`
sudo mkdir -p /etc/docker/certs.d/$HOST
sudo mv tls.crt /etc/docker/certs.d/$HOST
sudo chmod 644 /etc/docker/certs.d/$HOST
docker login -u developer -p $(oc whoami -t) $HOST

mvn clean -Pquickly package -pl operator,sync \
-Dquarkus.container-image.registry=$HOST \
-Dquarkus.container-image.group=openshift \
-Dquarkus.container-image.tag=latest \
-Dquarkus.container-image.build=true \
-Dquarkus.container-image.push=true \
-Dquarkus.kubernetes-client.trust-certs=true 
