#!/usr/bin/env bash

CONTINUE="yes"
START=0
BASE_URL="http://lamppc23.epfl.ch:8080/job/bloop/lastBuild/logText/progressiveText"
HEADERS_FILE=$(mktemp)

# Sleep 10s to make sure Jenkins registered the job
sleep 10

while [[ ! $CONTINUE = "" ]]; do
    # We need to remove the cariage returns, otherwise the rest won't work
    CONTENT=$(curl "$BASE_URL?start=$START" --verbose 2> "$HEADERS_FILE" | tr -d '\r')
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
