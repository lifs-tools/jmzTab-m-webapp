FROM eclipse-temurin:25-jre
LABEL maintainer="Nils Hoffmann <nils.hoffmann@cebitec.uni-bielefeld.de>"

EXPOSE 8083
VOLUME /tmp
ARG JAR_FILE
ARG APP_NAME
ADD target/${JAR_FILE} app.jar
# the heap follows the container memory limit
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-XX:MaxRAMPercentage=75","-jar","/app.jar"]
