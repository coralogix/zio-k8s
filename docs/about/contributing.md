---
id: about_contributing
title:  "ZIO-K8s Contributor Guidelines"
---

Thank you for your interest in contributing to ZIO-Actors, which is a small, zero-dependency library for doing type-safe, composable concurrent and asynchronous programming!

We welcome contributions from all people! You will learn about functional programming, and you will add your own unique touch to the ZIO-Actors project. We are happy to help you to get started and to hear your suggestions and answer your questions.

_You too can contribute to ZIO-Actors, we believe in you!_

# Contributing

## Getting Started

To begin contributing, please follow these steps:

### Get The Project

If you don't already have one, sign up for a free [GitHub Account](https://github.com/join?source=header-home).

After you [log into](https://github.com/login) GitHub using your account, go to the [ZIO-Actors Project Page](https://github.com/zio/zio-actors), and click on [Fork](https://github.com/zio/zio-actors/fork) to fork the ZIO-Actors repository into your own account.

You will make _all_ contributions from your own account. No one contributes _directly_ to the main repository. Contributors only ever merge code from other people's forks into the main repository.

Once you have forked the repository, you can now clone your forked repository to your own machine, so you have a complete copy of the project and can begin safely making your modifications (even without an Internet connection).

To clone your forked repository, first make sure you have installed [Git](https://git-scm.com/downloads), the version control system used by GitHub. Then open a Terminal and type the following commands:

```bash
git clone git@github.com:your-user-name/zio-actors.git .
```

If these steps were successful, then congratulations, you now have a complete copy of the ZIO Actors project!

The next step is to build the project on your machine, to ensure you know how to compile the project and run tests.

### Build the Project

The official way to build the project is with sbt. An sbt build file is included in the project, so if you choose to build the project this way, you won't have to do any additional configuration or setup (others choose to build the project using IntelliJ IDEA, Gradle, Maven, Mill, or Fury).

We use a custom sbt script, which is included in the repository, in order to ensure settings are uniform across all development machines, and the continuous integration service (Circle CI).

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

You may have your own idea about what contributions to make to ZIO-Actors, which is great! If you want to make sure the ZIO contributors are open to your idea, you can [open an issue](https://github.com/zio/zio-actors/issues/new) first on the ZIO project site.

Otherwise, if you have no ideas about what to contribute, you can find a large supply of feature requests and bugs on the project's [issue tracker](https://github.com/zio/zio-actors/issues).

Issues are tagged with various labels, such as `good first issue`, which help you find issues that are a fit for you.

If some issue is confusing or you think you might need help, then just post a comment on the issue asking for help. Typically, the author of the issue will provide as much help as you need, and if the issue is critical, leading ZIO-Actors contributors will probably step in to mentor you and give you a hand, making sure you understand the issue thoroughly.

Once you've decided on an issue and understand what is necessary to complete the issue, then it's a good idea to post a comment on the issue saying that you intend to work on it. Otherwise, someone else might work on it too!

### Fix an Issue

Once you have an issue, the next step is to fix the bug or implement the feature. Since ZIO-Actors is an open source project, there are no deadlines. Take your time!

The only thing you have to worry about is if you take too long, especially for a critical issue, eventually someone else will come along and work on the issue.

If you shoot for 2-3 weeks for most issues, this should give you plenty of time without having to worry about having your issue stolen.

If you get stuck, please consider [opening a pull request](https://github.com/zio/zio-actors/compare) for your incomplete work, and asking for help (just prefix the pull request by _WIP_). In addition, you can comment on the original issue, pointing people to your own fork. Both of these are great ways to get outside help from people more familiar with the project.

### Prepare Your Code

If you've gotten this far, congratulations! You've implemented a new feature or fixed a bug. Now you're in the last mile, and the next step is submitting your code for review, so that other contributors can spot issues and help improve the quality of the code.

To do this, you need to commit your changes locally. A good way to find out what you did locally is to use the `git status` command:

```bash
git status
```

If you see new files, you will have to tell `git` to add them to the repository using `git add`:

```bash
git add src/main/zio/zmx/NewFile.scala
```

Then you can commit all your changes at once with the following command:

```bash
git commit -am "Fixed #94211 - Optimized race for lists of effects"
```

At this point, you have saved your work locally, to your machine, but you still need to push your changes to your fork of the repository. To do that, use the `git push` command:

```bash
git push
```

Now while you were working on this great improvement, it's quite likely that other ZIO-Actors contributors were making their own improvements. You need to pull all those improvements into your own code base to resolve any conflicts and make sure the changes all work well together.

To do that, use the `git pull` command:

```bash
git pull git@github.com:zio/zio-actors.git master
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

If all the tests are passing, then you can format your code:

```bash
# If you are already in a SBT session you can type only 'fmt'

sbt fmt
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

Next, [open a new pull request](https://github.com/zio/zio-actors/compare) on GitHub, and select _Compare Across Forks_. On the right hand side, choose your own fork of the ZIO-Actors repository, in which you've been making your contribution.

Provide a description for the pull request, which details the issue it is fixing, and has other information that may be helpful to developers reviewing the pull request.

Finally, click _Create Pull Request_!

### Get Your Pull Request Merged

Once you have a pull request open, it's still your job to get it merged! To get it merged, you need at least one core ZIO-Actors contributor to approve the code.

If you know someone who would be qualified to review your code, you can request that person, either in the comments of the pull request, or on the right-hand side, if you have appropriate permissions.

Code reviews can sometimes take a few days, because open source projects are largely done outside of work, in people's leisure time. Be patient, but don't wait forever. If you haven't gotten a review within a few days, then consider gently reminding people that you need a review.

Once you receive a review, you will probably have to go back and make minor changes that improve your contribution and make it follow existing conventions in the code base. This is normal, even for experienced contributors, and the rigorous reviews help ensure ZIO-Actors' code base stays high quality.

After you make changes, you may need to remind reviewers to check out the code again. If they give a final approval, it means your code is ready for merge! Usually this will happen at the same time, though for controversial changes, a contributor may wait for someone more senior to merge.

If you don't get a merge in a day after your review is successful, then please gently remind folks that your code is ready to be merged.

Sit back, relax, and enjoy being a ZIO-Actors contributor!

