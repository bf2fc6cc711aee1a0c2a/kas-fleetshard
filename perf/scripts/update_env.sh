#!/usr/bin/env bash
# Usage: . update_env.sh <AWS credentials CSV> <region>

set -e

SED=sed
GREP=grep
if [[ "$OSTYPE" == "darwin"* ]]; then
    SED=gsed
fi
export AWS_SEC_CREDENTIALS_FILE=$1

cred=$($SED '2q;d' "${AWS_SEC_CREDENTIALS_FILE}")
export AWS_ACCESS_KEY_ID="$(cut -d',' -f1 <<<"${cred//[$'\t\r\n']/}")"
export AWS_SECRET_ACCESS_KEY="$(cut -d',' -f2 <<<"${cred//[$'\t\r\n']/}")"
export AWS_REGION=$2
export AWS_ID="$(grep -Eo '[0-9]*' <<< $(basename -- ${AWS_SEC_CREDENTIALS_FILE}))"
