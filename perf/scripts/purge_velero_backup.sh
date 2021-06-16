#!/usr/bin/env bash
# Usage: purge_velero_backup.sh <AWS credentials CSV>

set -e

SED=sed
GREP=grep
if [[ "$OSTYPE" == "darwin"* ]]; then
    SED=gsed
fi
AWS_CSV_PATH=$1

cred=$($SED '2q;d' "${AWS_CSV_PATH}")
AWS_ACCESS_KEY="$(cut -d',' -f1 <<<"${cred//[$'\t\r\n']/}")"
AWS_SECRET_ACCESS_KEY="$(cut -d',' -f2 <<<"${cred//[$'\t\r\n']/}")"

trap 'rm -rf "$TMPFILE"' EXIT
TMPFILE=$(mktemp -d) || exit 1
chmod og-r ${TMPFILE}

AWS_CONFIG_FILE=$TMPFILE
aws configure --profile default set aws_access_key_id ${AWS_ACCESS_KEY}
aws configure --profile default set aws_secret_access_key ${AWS_SECRET_ACCESS_KEY}

for i in $(aws s3api list-buckets | jq -r '.Buckets[].Name' | grep velero-backups )
do
aws s3 rb --force s3://$i
done

