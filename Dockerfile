FROM eclipse-temurin:24-jre-slim
LABEL maintainer="Nils Hoffmann <nils.hoffmann@cebitec.uni-bielefeld.de>"

EXPOSE 8083
VOLUME /tmp
ARG JAR_FILE
ARG APP_NAME
ADD target/${JAR_FILE} app.jar
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar"]
