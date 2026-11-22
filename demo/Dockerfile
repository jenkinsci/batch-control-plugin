# Jenkins for the Batch Control demo (see README.md next to this file).
#
# A pinned LTS tag rather than `lts`, so the demo does not move under its README.
# Batch Control needs Jenkins 2.568.3 or newer on Java 21 or 25.
ARG JENKINS_TAG=2.580.2-lts-jdk21
FROM jenkins/jenkins:${JENKINS_TAG}

# Plugins are downloaded from the Jenkins update center when the image is built,
# with every version pinned in plugins.txt; nothing is downloaded at startup.
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# The folder `batch` and its two jobs. Files under /usr/share/jenkins/ref are copied
# into JENKINS_HOME on the first start (an empty volume), before Jenkins loads its
# jobs, so the jobs exist when Batch Control starts for the first time.
COPY --chown=jenkins:jenkins jobs/ /usr/share/jenkins/ref/jobs/

# Users, permissions and the Batch Control settings, applied on every start.
COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc.yaml
