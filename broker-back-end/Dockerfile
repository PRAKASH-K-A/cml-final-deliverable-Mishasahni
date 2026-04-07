FROM openjdk:11-jre-slim

ENV APP_HOME=/opt/app
RUN apt-get update \
	&& apt-get install -y --no-install-recommends tini \
	&& rm -rf /var/lib/apt/lists/* \
	&& mkdir -p $APP_HOME

WORKDIR $APP_HOME

COPY broker-back-end/target/broker-back-end-1.0.0-SNAPSHOT-runner.jar app.jar

EXPOSE 8080

ENTRYPOINT ["tini", "--", "java", "-jar", "app.jar"]
