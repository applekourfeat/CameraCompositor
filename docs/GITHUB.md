# GitHub, in practical terms

Your project folder is the working copy. A **commit** saves a named snapshot locally. **Push** uploads those commits to your GitHub repository. A **branch** lets us make a change separately before combining it with the main version.

GitHub Desktop is already installed on this computer and provides buttons for this workflow.

## Save later changes

1. Open this repository in GitHub Desktop.
2. Review the changed files under Changes.
3. Write a short summary, such as `Add tap-to-select subject`.
4. Click **Commit to main** or the current branch.
5. Click **Push origin**.

For larger changes, use Branch > New branch, make the change, then publish the branch and open a pull request. A pull request is a reviewable proposal to combine that change with main.

## Initial publication if still pending

In GitHub Desktop, choose File > Add local repository and select this folder. If it has no local Git history yet, initialize it first. Click Publish repository, use `CameraCompositor` as the working repository name, and keep it private during development unless you decide to make the source public.

The connector can identify the connected GitHub user and work with permitted repositories. An authenticated connector and GitHub Desktop have separate access arrangements, so a new repository may also need to be enabled for the GitHub app in your account.

## What stays local

`.gitignore` excludes tool downloads, SDK files, Gradle caches, build outputs, private signing keys, and the reference videos. The original videos stay in Downloads. APKs can be shared separately for testing. The Gradle wrapper belongs in Git because it lets another machine build the project with the same Gradle version.

Making the app free to download and publishing its source are separate choices. Choose a source license deliberately before a public open-source release.
