ROOT_DIR = $(dir $(lastword $(MAKEFILE_LIST)))

include ./Makefile.os

TESTCASE ?= org.bf2.**
OMB_DIR ?= $(ROOT_DIR)/openmessaging-benchmark
DOCKER ?= docker

ifdef PROFILE
	PROFILE_ARGS = "-P$(PROFILE)"
endif

all: clean submodule-init rw-install omb-install test

clean: clean_java

clean_java:
	mvn clean

submodule-init:
	git submodule update --init --recursive --force

submodule-update:
	git submodule update --remote --merge

omb-install:
	pushd openmessaging-benchmark; mvn install -DskipTests -Dlicense.failIfMissing=false -Dlicense.failIfUnknown=false; popd

omb-install-ci:
	pushd openmessaging-benchmark; mvn install -DskipTests -Dlicense.failIfMissing=false -Dlicense.failIfUnknown=false --no-transfer-progress --quiet; popd

rw-install:
	pushd remote-write; make remote-write; popd

test:
	OMB_DIR=$(OMB_DIR) mvn test -Dtest=$(TESTCASE) $(PROFILE_ARGS)

pipeline: rw-install omb-install-ci
	./scripts/pipeline.sh

pipeline/cleanup:
	./scripts/pipeline.sh cleanup

ci/pipeline: submodule-init
	$(DOCKER) pull quay.io/app-sre/mk-ci-tools:latest
	$(DOCKER) run -v $(ROOT_DIR):/opt/mk-performance-tests \
	            -w /opt/mk-performance-tests \
				-e HOME=/tmp \
				-e AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID} \
				-e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
				-e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
				-e OCM_TOKEN=${OCM_TOKEN} \
				-e FLAVOR=${FLAVOR} \
				-e REGION=${REGION} \
				-e KAFKA_WORKER_COUNT=${KAFKA_WORKER_COUNT} \
				-e OMB_WORKER_COUNT=${OMB_WORKER_COUNT} \
				-e OBSERVATORIUM_ROUTE=${OBSERVATORIUM_ROUTE} \
				-e THANOS_URL=${THANOS_URL} \
				-e THANOS_TOKEN=${THANOS_TOKEN} \
				-e GOPATH=/tmp \
				-e TESTCASE=${TESTCASE} \
				-e OMB_COLLECT_LOG=${OMB_COLLECT_LOG} \
				-e OMB_TEST_DURATION=${OMB_TEST_DURATION} \
				-e OMB_WARMUP_DURATION=${OMB_WARMUP_DURATION} \
				-u $(shell id -u) \
				quay.io/app-sre/mk-ci-tools:latest make pipeline

ci/pipeline/cleanup:
	$(DOCKER) pull quay.io/app-sre/mk-ci-tools:latest
	$(DOCKER) run -v $(ROOT_DIR):/opt/mk-performance-tests \
	            -w /opt/mk-performance-tests \
				-e HOME=/tmp \
				-u $(shell id -u) \
				quay.io/app-sre/mk-ci-tools:latest make pipeline/cleanup

.PHONY: clean rw-install omb-install test pipeline pipeline/cleanup ci/pipeline ci/pipeline/cleanup
