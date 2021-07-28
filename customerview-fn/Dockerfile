# Copyright (c) 2021, Oracle and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
FROM fnproject/fn-java-fdk-build:jdk11-1.0.110 as build-stage
WORKDIR /function
ENV MAVEN_OPTS -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort= -Dhttp.nonProxyHosts= -Dmaven.repo.local=/usr/share/maven/ref/repository
ADD pom.xml /function/pom.xml
ADD lib/orajsoda-1.1.7.jar /function/lib/orajsoda-1.1.7.jar 
RUN ["mvn", "install:install-file", "-Dfile=/function/lib/orajsoda-1.1.7.jar", "-DgroupId=com.oracle.database.soda", "-DartifactId=orajsoda", "-Dversion=1.1.7", "-Dpackaging=jar"]
RUN ["mvn", "package", "dependency:copy-dependencies", "-DincludeScope=runtime", "-DskipTests=true", "-Dmdep.prependGroupId=true", "-DoutputDirectory=target", "--fail-never"]
ADD xslt/Xslt2Xml.xsl /function/Xslt2Xml.xsl
ADD src /function/src
RUN ["mvn", "package"]
FROM fnproject/fn-java-fdk:jre11-1.0.110
WORKDIR /function
ADD xslt/Xslt2Xml.xsl /function/Xslt2Xml.xsl
COPY --from=build-stage /function/target/*.jar /function/app/
CMD ["com.oracle.ateam.customerview.fn.CustomerViewFn::handleRequest"]