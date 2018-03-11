FROM openjdk:8
MAINTAINER Tristan Everitt

RUN apt-get update 
RUN apt-get -y upgrade
RUN apt-get install -y less curl ca-certificates unzip python-pip

RUN apt-get clean
RUN rm -rf /var/lib/apt/lists/* && rm -rf /tmp

RUN pip install awscli

ENV LANG C.UTF-8

COPY build/libs/gdrive2workdocs-all-*.jar /var/lib/gdrive2workdocs/gdrive2workdocs.jar

WORKDIR /gdrive2workdocs

CMD java -jar /var/lib/gdrive2workdocs/gdrive2workdocs.jar

# docker run -v ~/Development/gdrive2workdocs/build/data:/gdrive2workdocs gdrive2workdocs
