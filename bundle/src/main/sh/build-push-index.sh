#!/bin/sh

BUNDLE_IMAGE=
BUNDLE_IMAGE_DIGEST_FILE=''
INDEX_REGISTRY=
INDEX_GROUP=
INDEX_IMAGE=
BUILD_ENGINE='docker'

while [[ ${#} -gt 0 ]]; do
    key="${1}"
    case ${key} in
    "--bundle-image" )
        BUNDLE_IMAGE="${2:?${key} requires a valid bundle image reference}"
        shift
        shift
        ;;
    "--bundle-image-digest-file" )
        BUNDLE_IMAGE_DIGEST_FILE="${2:?${key} requires a valid bundle image digest file reference}"
        shift
        shift
        ;;
    "--index-registry" )
        INDEX_REGISTRY="${2:?${key} requires an index registry value}"
        shift
        shift
        ;;
    "--index-group" )
        INDEX_GROUP="${2:?${key} requires an index image group}"
        shift
        shift
        ;;
    "--index-image" )
        INDEX_IMAGE="${2:?${key} requires an index image}"
        shift
        shift
        ;;
    "--index-tag" )
        INDEX_TAG="${2:?${key} requires an index tag}"
        shift
        shift
        ;;
    "--build-engine" )
        BUILD_ENGINE="${2:?${key} requires a build engine value}"
        shift
        shift
        ;;
    *)
        echo "Unknown argument '${1}'";
        exit 1
        ;;
    esac
done

if [ -f "${BUNDLE_IMAGE_DIGEST_FILE}" ] ; then
    BUNDLE_IMAGE_BASE=$(echo "${BUNDLE_IMAGE}" | cut -d ':' -f 1)
    BUNDLE_IMAGE="${BUNDLE_IMAGE_BASE}@$(cat "${BUNDLE_IMAGE_DIGEST_FILE}")"
fi

opm index add --bundles "${BUNDLE_IMAGE}" --generate -d $(pwd)/index.Dockerfile

# set docker env variable to force it build images for linux/amd64 platform
export DOCKER_DEFAULT_PLATFORM=linux/amd64

IDX="${INDEX_REGISTRY}/${INDEX_GROUP}/${INDEX_IMAGE}:${INDEX_TAG}"
${BUILD_ENGINE} build -f $(pwd)/index.Dockerfile -t "${IDX}" $(pwd)

${BUILD_ENGINE} login -u ${KAS_INDEX_CREDENTIAL_USERNAME} -p ${KAS_INDEX_CREDENTIAL_PASSWORD} ${INDEX_REGISTRY}
${BUILD_ENGINE} push "${IDX}"
