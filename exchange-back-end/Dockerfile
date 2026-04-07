FROM openjdk:11-jre-slim

ENV APP_HOME=/opt/app
RUN apt-get update \
	&& apt-get install -y --no-install-recommends tini \
	&& rm -rf /var/lib/apt/lists/* \
	&& mkdir -p $APP_HOME

WORKDIR $APP_HOME

COPY exchange-back-end/target/exchange-back-end-1.0.0-SNAPSHOT-runner.jar app.jar

EXPOSE 8090

ENTRYPOINT ["tini", "--", "java", "-jar", "app.jar"]
