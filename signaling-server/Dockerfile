FROM gradle:8.9-jdk17 AS build
WORKDIR /home/gradle/project
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/linkbridge-signaling-server /app
EXPOSE 8080
ENV PORT=8080
CMD ["/app/bin/linkbridge-signaling-server"]
