# Rust API server image. Build from the REPO ROOT (it needs core/ and the shared message bundles):
#   docker build -f core/kovil-server/Dockerfile -t kovil-server .
# Unlike the Java image, this builds inside Docker: a binary built on the CI runner's newer glibc may not run here.
FROM rust:1-slim-bookworm AS build
WORKDIR /src
COPY core/ core/
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/core/target \
    cd core && cargo build --release -p kovil-server --locked \
    && cp target/release/kovil-server /kovil-server

FROM debian:bookworm-slim
# Logs are UTC with a Z suffix (slim has no tzdata; the Java image logs KST). Convert when comparing the two.
ENV BIND_ADDR=0.0.0.0:8081 \
    MESSAGES_DIR=/app/messages \
    RUST_LOG=info
RUN groupadd --system kovil && useradd --system --gid kovil kovil
WORKDIR /app
COPY --from=build /kovil-server /app/kovil-server
COPY src/main/resources/messages/ /app/messages/
USER kovil
EXPOSE 8081
# DATABASE_URL, JWT_SECRET (same as the Java app) and REDIS_URL come from the deploy environment (.env);
# startup fails fast if any is missing or wrong.
ENTRYPOINT ["/app/kovil-server"]
