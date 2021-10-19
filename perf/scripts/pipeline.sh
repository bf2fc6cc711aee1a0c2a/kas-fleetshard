#!/usr/bin/env bash

# List of required env variables
# AWS_ACCOUNT_ID ...... AWS acount id
# AWS_ACCESS_KEY_ID ....AWS access key id
# AWS_SECRET_ACCESS_KEY ...... AWS secret key 
# OCM_TOKEN ........... ocm token for user
# FLAVOR .............. aws flavor for cluster node
# REGION .............. region of aws for osd cluster
# KAFKA_WORKER_COUNT .. count of osd worker nodes for kafka cluster
# OMB_WORKER_COUNT .... count of osd worker nodes for omb cluster
# TESTCASE ............ maven surefire testcase format (package.TestClass#testcase)

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="${DIR}/.."
OCM_TOKEN_FILE="${REPO_ROOT}/token-file"
KAFKA_KUBECONFIG_FILE="${REPO_ROOT}/kafka-config"
OMB_KUBECONFIG_FILE="${REPO_ROOT}/client-config"
OSD="${DIR}/osd-provision.sh"
KAFKA_CLUSTER_NAME_FILE="${REPO_ROOT}/kafka-cluster-name"
OMB_CLUSTER_NAME_FILE="${REPO_ROOT}/omb-cluster-name"
TESTCASE=${TESTCASE:-"org.bf2.performance.**"}
MULTI_AZ=${MULTI_AZ:-"false"}
trap "echo script failed; exit 1" ERR

function info() {
    MESSAGE="${1}"
    echo "[INFO]  [$(date +"%T")] - ${MESSAGE}"
}

function error() {
    MESSAGE="${1}"
    echo "[ERROR] [$(date +"%T")] - ${MESSAGE}"
    exit 1
}

function separator() {
    echo ""
    info "---------------------------------------------------------------------"
    echo ""
}

function check_env_variable() {
    X="${1}"
    info "Checking content of variable ${X}"
    if [[ -z "${!X}" ]]; then
        error "Variable ${X} is not defined, exit!!!"
    fi
}

function delete_cluster_if_exists() {
    CLUSTER_NAME_FILE="${1}"
    if [[ -f "${CLUSTER_NAME_FILE}" ]]; then
      CLUSTER_NAME=$(cat "${CLUSTER_NAME_FILE}")
      echo "Delete cluster ${CLUSTER_NAME}"
      $OSD --delete -n "${CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}"
      if [[ $? -eq 0 ]]; then
        rm -f "${CLUSTER_NAME_FILE}"
      fi
    else
      echo "No cluster to delete"
    fi
}

if [[ $1 == "cleanup" ]]; then
  if [[ ! -f "${OCM_TOKEN_FILE}" ]]; then
    error "No OCM token file found"
  fi
  echo "Clean up clusters"
  delete_cluster_if_exists "${KAFKA_CLUSTER_NAME_FILE}"
  delete_cluster_if_exists "${OMB_CLUSTER_NAME_FILE}"
  exit 0
fi

info "1. Check env variables"
check_env_variable "AWS_ACCOUNT_ID"
check_env_variable "AWS_ACCESS_KEY_ID"
check_env_variable "AWS_SECRET_ACCESS_KEY"
check_env_variable "OCM_TOKEN"
check_env_variable "FLAVOR"
check_env_variable "REGION"
check_env_variable "KAFKA_WORKER_COUNT"
check_env_variable "OMB_WORKER_COUNT"
check_env_variable "TESTCASE"
check_env_variable "MULTI_AZ"
separator

info "2. Prepare secrets"
separator

info "3. Storing ocm token"
echo -e "${OCM_TOKEN}" >"${OCM_TOKEN_FILE}"
separator

info "4. Install clusters"
separator
# NOTE: cluster names should not exceed 15 characters!
KAFKA_CLUSTER_NAME="kafka-$(echo -e $(uuidgen) | cut -d '-' -f 2)"
OMB_CLUSTER_NAME="omb-$(echo -e $(uuidgen) | cut -d '-' -f 2)"
$OSD --create --cloud-token-file "${OCM_TOKEN_FILE}" --aws-account-id "${AWS_ACCOUNT_ID}" --aws-access-key "${AWS_ACCESS_KEY_ID}" --aws-secret-access-key "${AWS_SECRET_ACCESS_KEY}" --name "${KAFKA_CLUSTER_NAME}" --region "${REGION}" --flavor "${FLAVOR}" --count "${KAFKA_WORKER_COUNT}" --multi-az "${MULTI_AZ}"
if [[ $? -eq 0 ]]; then
  echo ${KAFKA_CLUSTER_NAME} >"${KAFKA_CLUSTER_NAME_FILE}"
fi
$OSD --create --cloud-token-file "${OCM_TOKEN_FILE}" --aws-account-id "${AWS_ACCOUNT_ID}" --aws-access-key "${AWS_ACCESS_KEY_ID}" --aws-secret-access-key "${AWS_SECRET_ACCESS_KEY}" --name "${OMB_CLUSTER_NAME}" --region "${REGION}" --flavor "${FLAVOR}" --count "${OMB_WORKER_COUNT}" --multi-az "${MULTI_AZ}"
if [[ $? -eq 0 ]]; then
  echo ${OMB_CLUSTER_NAME} >"${OMB_CLUSTER_NAME_FILE}"
fi
$OSD --wait --name "${KAFKA_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}"
$OSD --wait --name "${OMB_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}"
sleep 600
$OSD --set-storageclass --name "${KAFKA_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}"
$OSD --set-storageclass --name "${OMB_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}"
sleep 300
$OSD --infra-pod-rebalance --name "${KAFKA_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}"
$OSD --infra-pod-rebalance --name "${OMB_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}"
separator

info "5. Generating kubeconfigs"
separator
$OSD --get kubeconfig --name "${KAFKA_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}" --output "${KAFKA_KUBECONFIG_FILE}"
$OSD --get kubeconfig --name "${OMB_CLUSTER_NAME}" --cloud-token-file "${OCM_TOKEN_FILE}" --output "${OMB_KUBECONFIG_FILE}"
separator

info "6. Running tests"
separator
pushd "${REPO_ROOT}"
mvn test -Dtest="${TESTCASE}" -Pci --no-transfer-progress
popd
separator

info "7. Deleting clusters"
separator
delete_cluster_if_exists "${KAFKA_CLUSTER_NAME_FILE}"
delete_cluster_if_exists "${OMB_CLUSTER_NAME_FILE}"
separator
