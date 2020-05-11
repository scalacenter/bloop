#!/usr/bin/env bash

# Add git metadata for the push to succeed
git config --global user.name "Bloopoid"
git config --global user.email "bloopoid@trashmail.ws"

# Setup everything to be able to push to github pages
mkdir -p ~/.ssh
echo -e "Host github.com\\n\\tStrictHostKeyChecking no\\n" > ~/.ssh/config
echo -e "${BLOOPOID_SSH_PRIVATE_KEY}\\n" > ~/.ssh/id_rsa
echo -e "${BLOOPOID_SSH_PUBLIC_KEY}\\n" > ~/.ssh/id_rsa.pub
chmod 0600 ~/.ssh/id_rsa
chmod 0600 ~/.ssh/id_rsa.pub
export BLOOPOID_PRIVATE_KEY_PATH="/root/.ssh/id_rsa"
