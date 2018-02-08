#!/usr/bin/env bash

# Setup everything to be able to push to github pages
mkdir -p ~/.ssh
echo -e "Host github.com\n\tStrictHostKeyChecking no\n" > ~/.ssh/config
echo -e "${BLOOPOID_PRIVATE_KEY}\n" > ~/.ssh/id_rsa
chmod 0600 ~/.ssh/id_rsa
