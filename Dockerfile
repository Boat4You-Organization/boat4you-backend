FROM gradle:8-jdk21-alpine as build
WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Copy the source code
COPY src ./src

# Build the application
RUN gradle assemble --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y \
        libstdc++6 \
        libc6 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=build /app/build/libs/*.jar app.jar
COPY --from=build /app/src/main/resources/boat4you_selfsigned.p12 keystore.p12
# Expose the port the app runs on
EXPOSE 8443

# Set the command to run the application
ENTRYPOINT ["java", "-Dserver.ssl.key-store=file:/app/keystore.p12", "-jar", "app.jar"]