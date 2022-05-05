#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="${DIR}/../../"
OCM=ocm
OC=oc
DOCKER=docker

if [ -z "$1" ]
  then
    echo "No cluster name provided. ex: ./enable_os_registry.sh my-kafka"
    exit 1
fi

CLUSTER_NAME=$1
CLUSTER_ID=$($OCM list clusters | grep ${CLUSTER_NAME} | awk '{print $1;}')
KUSER=$($OCM get /api/clusters_mgmt/v1/clusters/${CLUSTER_ID}/credentials | jq -r .admin.user)
KPASSWORD=$($OCM get /api/clusters_mgmt/v1/clusters/${CLUSTER_ID}/credentials | jq -r .admin.password)
API_URL=$($OCM get cluster  ${CLUSTER_ID}| jq -r .api.url)

if [[ "${CLUSTER_ID}" == "" || "${KUSER}" == "" || "${KPASSWORD}" == "" || "${API_URL}" == "" ]]; then
    echo "Failed to grab the cluster login details, failed to proceed"
    exit 2
fi

echo "$OC login -u ${KUSER} -p ${KPASSWORD} ${API_URL}"
for i in {1..20}; do
    if KUBECONFIG="${OUTPUT}" $OC login -u ${KUSER} -p ${KPASSWORD} ${API_URL} --insecure-skip-tls-verify=true; then
        break
    else
        echo "[WARN] Login attempt $i/20 failed, waiting 1 minute before next try"
        sleep 60
    fi
done

# extract the tls cert for the route to the OSD internal image registry
rm -f tls.crt
$OC extract secret/router-ca --keys=tls.crt -n openshift-ingress-operator
HOST=$($OC get route default-route -n openshift-image-registry -o json | jq -r .status.ingress[0].host)

if [[ ! -e "tls.crt" ]]; then
    echo "Failed to retrieve the tls certificate for registry route"
    exit 3
fi

if [[ "${HOST}" != "" ]]; then
        
    sudo mkdir -p /etc/docker/certs.d/$HOST
    sudo mv tls.crt /etc/docker/certs.d/$HOST
    sudo chmod 644 /etc/docker/certs.d/$HOST
    
    echo "$DOCKER login -u ${KUSER} -p $($OC whoami -t) $HOST"
    $DOCKER login -u ${KUSER} -p $($OC whoami -t) $HOST
    
    cd $REPO_ROOT
     
    mvn clean -Pquickly -pl !perf package \
      -Dquarkus.container-image.registry=$HOST \
      -Dquarkus.container-image.group=openshift \
      -Dquarkus.container-image.tag=latest \
      -Dquarkus.container-image.build=true \
      -Dquarkus.container-image.push=true \
      -Dquarkus.kubernetes-client.trust-certs=true
    
    cd $DIR
else
    echo "Failed to find the OSD's internal image registry"
    exit 4
fi 


