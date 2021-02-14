---
id: about_contributing
title:  "zio-k8s Contributor Guidelines"
---

Thank you for your interest in contributing to zio-k8s!

# Contributing

## Getting Started

To begin contributing, please follow these steps:

### Get The Project

If you don't already have one, sign up for a free [GitHub Account](https://github.com/join?source=header-home).

After you [log into](https://github.com/login) GitHub using your account, go to the [zio-k8s Project Page](https://github.com/coralogix/zio-k8s), and click on [Fork](https://github.com/coralogix/zio-k8s/fork) to fork the zio-k8s repository into your own account.

You will make _all_ contributions from your own account. No one contributes _directly_ to the main repository. Contributors only ever merge code from other people's forks into the main repository.

Once you have forked the repository, you can now clone your forked repository to your own machine, so you have a complete copy of the project and can begin safely making your modifications (even without an Internet connection).

To clone your forked repository, first make sure you have installed [Git](https://git-scm.com/downloads), the version control system used by GitHub. Then open a Terminal and type the following commands:

```bash
git clone git@github.com:your-user-name/zio-k8s.git .
```

If these steps were successful, then congratulations, you now have a complete copy of the ZIO K8s project!

The next step is to build the project on your machine, to ensure you know how to compile the project and run tests.

### Build the Project

The official way to build the project is with sbt. An sbt build file is included in the project, so if you choose to build the project this way, you won't have to do any additional configuration or setup (others choose to build the project using IntelliJ IDEA, Gradle, Maven, Mill, or Fury).

We use a custom sbt script, which is included in the repository, in order to ensure settings are uniform across all development machines, and the continuous integration service (GitHub Actions).

The sbt script is in the root of the repository. To launch this script from your Terminal window, simply type:

```bash
./sbt
```

Sbt will launch, read the project build file, and download dependencies as required.

You can now compile the production source code with the following sbt command:

```bash
compile
```

You can compile the test source code with the following sbt command:

```bash
test:compile
```

[Learn more](https://www.scala-sbt.org) about sbt to understand how you can list projects, switch projects, and otherwise manage an sbt project.

### Find an Issue

You may have your own idea about what contributions to make to zio-k8s, which is great! If you want to make sure the ZIO contributors are open to your idea, you can [open an issue](https://github.com/zio/zio-k8s/issues/new) first on the ZIO project site.

Otherwise, if you have no ideas about what to contribute, you can find a large supply of feature requests and bugs on the project's [issue tracker](https://github.com/zio/zio-k8s/issues).

Issues are tagged with various labels, such as `good first issue`, which help you find issues that are a fit for you.

If some issue is confusing or you think you might need help, then just post a comment on the issue asking for help. Typically, the author of the issue will provide as much help as you need, and if the issue is critical, leading zio-k8s contributors will probably step in to mentor you and give you a hand, making sure you understand the issue thoroughly.

Once you've decided on an issue and understand what is necessary to complete the issue, then it's a good idea to post a comment on the issue saying that you intend to work on it. Otherwise, someone else might work on it too!

### Fix an Issue

Once you have an issue, the next step is to fix the bug or implement the feature. Since zio-k8s is an open source project, there are no deadlines. Take your time!

The only thing you have to worry about is if you take too long, especially for a critical issue, eventually someone else will come along and work on the issue.

If you shoot for 2-3 weeks for most issues, this should give you plenty of time without having to worry about having your issue stolen.

If you get stuck, please consider [opening a pull request](https://github.com/coralogix/zio-k8s/compare) for your incomplete work, and asking for help (just prefix the pull request by _WIP_). In addition, you can comment on the original issue, pointing people to your own fork. Both of these are great ways to get outside help from people more familiar with the project.

### Prepare Your Code

If you've gotten this far, congratulations! You've implemented a new feature or fixed a bug. Now you're in the last mile, and the next step is submitting your code for review, so that other contributors can spot issues and help improve the quality of the code.

To do this, you need to commit your changes locally. A good way to find out what you did locally is to use the `git status` command:

```bash
git status
```

If you see new files, you will have to tell `git` to add them to the repository using `git add`:

```bash
git add zio-k8s-client/src/main/com/coralogix/zio/k8s/NewFile.scala
```

Then you can commit all your changes at once with the following command:

```bash
git commit -am "Fixed #94211 - Optimized race for lists of effects"
```

At this point, you have saved your work locally, to your machine, but you still need to push your changes to your fork of the repository. To do that, use the `git push` command:

```bash
git push
```

Now while you were working on this great improvement, it's quite likely that other zio-k8s contributors were making their own improvements. You need to pull all those improvements into your own code base to resolve any conflicts and make sure the changes all work well together.

To do that, use the `git pull` command:

```bash
git pull git@github.com:coralogix/zio-k8s.git master
```

You may get a warning from Git that some files conflicted. Don't worry! That just means you and another contributor edited the same parts of the same files.

Using a text editor, open up the conflicted files, and try to merge them together, preserving your changes and the other changes (both are important!).

Once you are done, you can commit again:

```bash
git commit -am "merged upstream changes"
```

At this point, you should re-run all tests to make sure everything is passing:

```bash
# If you are already in a SBT session you can type only 'test'

sbt test
```

Also test that the CRD support is not broken:

```bash
sbt zio-k8s-crd/scripted
```

If all the tests are passing, then you can format your code:

```bash
# If you are already in a SBT session you can type only 'scalafmtAll'

sbt scalafmtAll
```

If you also changed the `zio-k8s-codegen` subproject, format that separately:

```bash
cd zio-k8s-codegen
sbt scalafmtAll
```

If your changes altered an API, then you may need to rebuild the microsite to make sure none of the (compiled) documentation breaks:

```bash
# If you are already in a SBT session you can type only 'docs/docusaurusCreateSite'

sbt docs/docusaurusCreateSite
```

Finally, if you are up-to-date with master, all your tests are passing, you have properly formatted your code, and the microsite builds properly, then it's time to submit your work for review!

### Create a Pull Request

To create a pull request, first push all your changes to your fork of the project repository:

```bash
git push
```

Next, [open a new pull request](https://github.com/coralogix/zio-k8s/compare) on GitHub, and select _Compare Across Forks_. On the right hand side, choose your own fork of the zio-k8s repository, in which you've been making your contribution.

Provide a description for the pull request, which details the issue it is fixing, and has other information that may be helpful to developers reviewing the pull request.

Finally, click _Create Pull Request_!

### Get Your Pull Request Merged

Once you have a pull request open, it's still your job to get it merged! To get it merged, you need at least one core zio-k8s contributor to approve the code.

If you know someone who would be qualified to review your code, you can request that person, either in the comments of the pull request, or on the right-hand side, if you have appropriate permissions.

Code reviews can sometimes take a few days, because open source projects are largely done outside of work, in people's leisure time. Be patient, but don't wait forever. If you haven't gotten a review within a few days, then consider gently reminding people that you need a review.

Once you receive a review, you will probably have to go back and make minor changes that improve your contribution and make it follow existing conventions in the code base. This is normal, even for experienced contributors, and the rigorous reviews help ensure zio-k8s' code base stays high quality.

After you make changes, you may need to remind reviewers to check out the code again. If they give a final approval, it means your code is ready for merge! Usually this will happen at the same time, though for controversial changes, a contributor may wait for someone more senior to merge.

If you don't get a merge in a day after your review is successful, then please gently remind folks that your code is ready to be merged.

Sit back, relax, and enjoy being a zio-k8s contributor!

## Tips for working on zio-k8s

### minikube
The library can be tested locally using [minikube](https://minikube.sigs.k8s.io/docs/). Once it has been installed, start it:

```bash
minikube start
```

Once it is running, figure out the _host_ and _token_ to be able to connect to it with `zio-k8s`:

```bash
export K8S_HOST=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
export K8S_TOKEN=$(kubectl get secret $(kubectl get serviceaccount default -o jsonpath='{.secrets[0].name}') -o jsonpath='{.data.token}' | base64 -D )
```

You can pass these manually or with zio-config to the zio-k8s layer as described [on the getting started page](../overview/gettingstarted.md). Additionally you have to set `insecure` to true.

If you are using the _Typesafe Config_ based configuration, here is a good override:

```hocon
k8s-client {
  insecure = true
  debug = true
}

cluster {
  host = ${K8S_HOST}
  token = ${K8S_TOKEN}
}
```

The `debug` option enables additional logging. Make sure the zio-logger is configured to output debug logs.

### kubectl

If you can express the new feature you are working on with a `kubectl` command line operation, you can check the HTTP communication it performs by passing the `-v=9` (or `-v=6` if you are not interested in the content just the URLs) to `kubectl`. This is a very useful technique to verify that your code is doing the thing that Kubernetes expects.
