#!/usr/bin/env bash

DEFAULT_REMOTE="origin"
EXPECTED_CHANGES=("homebrew" "bin/install.py")

TAG=$1
REMOTE=${2:-$DEFAULT_REMOTE}

if [ "$TAG" = "" ]; then
    echo "No tag specified."
    exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ ! "$CURRENT_BRANCH" = "topic/script-templates" ]; then
    echo "Releases should come from the 'master' branch."
    exit 1
fi

git diff --quiet --exit-code
if [ ! $? -eq 0 ]; then
    echo "Repository is dirty."
    exit 1
fi

git diff --cached --quiet --exit-code
if [ ! $? -eq 0 ]; then
    echo "Repository is dirty (staged but not commited changes)."
    exit 1
fi

sbt "set latestTag in Global := \"$TAG\"" \
    "frontend/makeTemplates"
if [ ! $? -eq 0 ]; then
    echo "sbt couldn't update the scripts."
    exit 1
fi

for expected_change in "${EXPECTED_CHANGES[@]}"; do
    git status --porcelain | grep "$expected_change"
    if [ ! $? -eq 0 ]; then
        echo "No changes found in '$expected_change', something's wrong."
        exit 1
    fi
done

git diff --submodule=diff
echo "Do the changes look good?"
select yn in "Yes" "No"; do
    case $yn in
        No ) exit 1; break;;
        Yes ) break;;
    esac
done

pushd homebrew
git add .
git commit -m "Releasing Bloop $TAG"
git tag -s "$TAG" -m "Bloop $TAG"
popd

git add .
git commit -m "Releasing Bloop $TAG"
git tag -s "$TAG" -m "Bloop $TAG"

sbt "frontend/makeTemplates"
if [ ! $? -eq 0]; then
    echo "sbt couldn't update the scripts."
    exit 1
fi

git diff --quiet --exit-code
if [ $? -eq 0 ]; then
    pushd homebrew
    git push "$REMOTE" master
    git push "$REMOTE" "$TAG"
    popd

    git push "$REMOTE" master
    git push "$REMOTE" "$TAG"

    echo "Pushed $TAG :)"
else
    echo "Repository is dirty, but it should be clean. Reverting changes."

    pushd homebrew
    git tag -d "$TAG"
    git reset --hard HEAD~
    popd

    git tag -d "$TAG"
    git reset --hard HEAD~
    exit 1
fi
