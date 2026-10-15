FROM node:22.22.0-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
COPY docs/prototypes/meterbill-console/public/ /workspace/docs/prototypes/meterbill-console/public/
RUN npm run build

FROM maven:3.9.12-eclipse-temurin-21-alpine AS backend-build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src/ src/
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21.0.10_7-jre-alpine AS app
ARG APP_VERSION=dev
LABEL org.opencontainers.image.title="MeterBill" \
      org.opencontainers.image.version="${APP_VERSION}"
RUN apk add --no-cache wget && addgroup -S meterbill && adduser -S -G meterbill -u 10001 meterbill
WORKDIR /app
COPY --from=backend-build --chown=meterbill:meterbill /workspace/target/meterbill-*.jar /app/meterbill.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-Djava.io.tmpdir=/tmp","-jar","/app/meterbill.jar"]

FROM nginx:1.28.0-alpine AS gateway
ARG APP_VERSION=dev
LABEL org.opencontainers.image.title="MeterBill Gateway" \
      org.opencontainers.image.version="${APP_VERSION}"
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY --from=frontend-build /workspace/frontend/dist/ /usr/share/nginx/html/
RUN mkdir -p /var/cache/nginx /var/run/nginx && \
    chown -R nginx:nginx /var/cache/nginx /var/run/nginx /usr/share/nginx/html
USER nginx
EXPOSE 8080
