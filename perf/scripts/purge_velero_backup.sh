#!/usr/bin/env bash
# Usage: purge_velero_backup.sh

# Install AWS CLI
if ! [ -x "$(command -v aws)" ]; then
  echo "Installing the AWS CLI..."
  curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
  unzip -qq awscliv2.zip
  sudo ./aws/install
  sudo rm -rf ./aws
  echo "AWS CLI successfully installed"
fi

# Configure the AWS CLI
aws configure set aws_access_key_id "$AWS_ACCESS_KEY_ID" --profile default
aws configure set secret_access_key "$AWS_SECRET_ACCESS_KEY" --profile default
aws configure set region "$AWS_REGION" --profile default

for i in $(aws s3api list-buckets | jq -r '.Buckets[].Name' | grep velero-backups )
do
aws s3 rb --force s3://$i
done
