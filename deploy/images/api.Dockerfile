FROM eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
WORKDIR /opt/lifeapp
COPY --chown=10001:10001 server/ ./
USER 10001:10001
ENTRYPOINT ["/opt/lifeapp/bin/server"]
