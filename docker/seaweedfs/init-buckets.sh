#!/bin/sh
# One-shot: create the lake buckets if they do not exist yet. Safe to run again.
set -eu
MASTER="${WEED_MASTER:-lake:9333}"
for bucket in lv-raw lv-lake lv-marts; do
  if echo "s3.bucket.list" | weed shell -master="$MASTER" 2>/dev/null | grep -qw "$bucket"; then
    echo "bucket $bucket exists"
  else
    echo "s3.bucket.create -name $bucket" | weed shell -master="$MASTER"
    echo "bucket $bucket created"
  fi
done
echo "s3.bucket.list" | weed shell -master="$MASTER"
