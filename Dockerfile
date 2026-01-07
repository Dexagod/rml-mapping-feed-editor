# ---- Build stage ---- 

FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /build 

# Copy everything (scripts are in the default folder) 
COPY . . 

# Ensure scripts are executable, then build 
RUN mvn -q -DskipTests package

# Optional: fail fast if the jar wasn't produced 
RUN test -f /build/target/rml-post-1.0.0.jar





# ---- Run stage ---- 

FROM eclipse-temurin:17-jre
WORKDIR /app

# jar
COPY --from=build /build/target/rml-post-1.0.0.jar /app/target/rml-post-1.0.0.jar

# entrypoint script
COPY --from=build /build/run.sh /app/run.sh
RUN chmod +x /app/run.sh

ENTRYPOINT ["/app/run.sh"]
