#!/usr/bin/env bash

JENKINS_AUTH=$(echo -n "$1" | base64)
LENGTH=$(echo -n "$1" | wc -c)
BROKEN=$(echo -n "bloop:" | wc -c)

if [ $LENGTH = $BROKEN ]; then
    echo "No token..."
    exit 1
fi

QUEUE_RESPONSE=$(curl -i -X POST http://lamppc23.epfl.ch:8080/job/bloop/buildWithParameters -F BUILD_REF="$DRONE_COMMIT_REF" -H "authorization: Basic $JENKINS_AUTH" | tr -d '\r')
QUEUE_ENDPOINT=$(echo "$QUEUE_RESPONSE" | grep "Location:" | cut -d' ' -f2)

# Sleep 10s to make sure Jenkins registered the job
sleep 10

POLL="yes"
while [[ ! "$POLL" = "" ]]; do
  BUILD_URL=$(curl --silent -X GET "${QUEUE_ENDPOINT}api/json\?pretty\=true" | jq '.executable.url')
  echo "Querying ${QUEUE_ENDPOINT}api/json\?pretty\=true..."
  echo "Result: $BUILD_URL"
  if [[ "$BUILD_URL" && "$BUILD_URL" != "null" ]]; then
    LOG_ENDPOINT="${BUILD_URL}logText/progressiveText"
    POLL=""
  else
    sleep 10
  fi
done

echo "The build started to run in Jenkins..."

CONTINUE="yes"
START=0
HEADERS_FILE=$(mktemp)

while [[ ! $CONTINUE = "" ]]; do
    # We need to remove the cariage returns, otherwise the rest won't work
    CONTENT=$(curl "$LOG_ENDPOINT?start=$START" --verbose 2> "$HEADERS_FILE" | tr -d '\r')
    HEADERS=$(cat "$HEADERS_FILE" | tr -d '\r')

    START=$(echo "$HEADERS" | grep "< X-Text-Size:" | sed 's/^.\{15\}//')

    # This header will no longer be set once the build has finished
    CONTINUE=$(echo "$HEADERS" | grep "< X-More-Data: true")

    if [ -n "$CONTENT" ]; then
        echo "$CONTENT"
    fi

    sleep 2
done

rm "$HEADERS_FILE"

echo "$CONTENT" | tail -n1 | grep "Finished: SUCCESS" > /dev/null
