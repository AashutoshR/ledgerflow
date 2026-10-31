FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY services services
ARG SERVICE=workspace
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress -q -pl services/${SERVICE} -am -DskipTests -Dspotless.skip=true package && cp services/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar /app.jar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:ActiveProcessorCount=2 -XX:ReservedCodeCacheSize=32m -Xss512k"
USER 10001
EXPOSE 10000
ENTRYPOINT ["java","-jar","/app/app.jar"]
