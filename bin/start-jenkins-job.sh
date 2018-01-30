#!/usr/bin/env bash

JENKINS_AUTH=$(echo -n "$1" | base64)
LENGTH=$(echo -n "$1" | wc -c)
BROKEN=$(echo -n "bloop:" | wc -c)

if [ $LENGTH = $BROKEN ]; then
    echo "No token..."
    exit 1
fi

curl -X POST \
    http://lamppc23.epfl.ch:8080/job/bloop/buildWithParameters \
    -F BUILD_REF=$DRONE_COMMIT_REF \
    -H "authorization: Basic $JENKINS_AUTH"
